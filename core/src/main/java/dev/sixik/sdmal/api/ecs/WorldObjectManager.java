package dev.sixik.sdmal.api.ecs;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Owns objects on its creating thread and ticks them in registration order. Readers may inspect
 * immutable snapshots on other threads. Cross-thread structural work must use {@link #execute};
 * component values still follow their own codec/lock contract.
 */
public final class WorldObjectManager implements AutoCloseable {
    /**
     * Default maximum queued world commands executed per manager tick.
     */
    public static final int DEFAULT_COMMAND_BUDGET = 256;

    /**
     * Version of the world archive envelope written by this release.
     */
    public static final int ARCHIVE_FORMAT = WorldObjectArchive.FORMAT;

    /**
     * Objects indexed by UUID for concurrent reads.
     */
    private final ConcurrentHashMap<UUID, WorldObject> objects = new ConcurrentHashMap<>();

    /**
     * Registration order, mutated only on ownerThread.
     */
    private final List<WorldObject> ordered = new ArrayList<>();

    /**
     * Owner-thread revision of membership and component composition, independent of the tick
     * sequence.
     */
    private long structureVersion;

    /**
     * Invalidates component/policy caches before any structural lifecycle callbacks execute.
     */
    void structureChanged() {
        structureVersion++;
    }

    /**
     * @return owner-thread composition revision, including attachment replacement and ordering
     *     changes
     */
    long structureVersion() {
        return structureVersion;
    }

    /**
     * Immutable membership snapshot, including sleeping objects.
     */
    private volatile WorldObject[] membership = new WorldObject[0];

    /**
     * Immutable dispatch snapshot excluding sleeping objects.
     */
    private volatile WorldObject[] active = new WorldObject[0];

    /**
     * Structural observers in subscription order.
     */
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Bounded FIFO shared by submitting threads and the world tick thread.
     */
    private final ArrayBlockingQueue<Runnable> commands = new ArrayBlockingQueue<>(4096);

    /**
     * Serializes command submission with close so commands cannot survive shutdown.
     */
    private final Object commandLock = new Object();

    /**
     * Thread authorized to tick, mutate and close this manager.
     */
    private final Thread ownerThread = Thread.currentThread();

    /**
     * Last issued tick sequence, initially zero.
     */
    private volatile long tickCounter;

    /**
     * Consecutive failed manager ticks; zero after success.
     */
    private volatile long consecutiveTickFailures;

    /**
     * Maximum commands attempted per tick, positive.
     */
    private int commandBudget = DEFAULT_COMMAND_BUDGET;

    /**
     * Prevents recursive manager ticks. Accessed only on ownerThread.
     */
    private boolean ticking;

    /**
     * Permanent closed state, visible to submitters and readers.
     */
    private volatile boolean closed;

    /**
     * Archive templates and reserved UUIDs of unresolved objects; accessed only on ownerThread.
     */
    private WorldObjectArchive archive = WorldObjectArchive.empty();

    /**
     * Prevents replacing an already loaded archive, including one containing only unresolved
     * records.
     */
    private boolean archiveLoaded;

    /**
     * Prevents ticking or capturing partially completed restoration callbacks on the owner thread.
     */
    private boolean restoring;

    /**
     * Per-world phase scheduler and bounded command context, allocated once per manager.
     */
    private final EcsScheduler scheduler = new EcsScheduler(this);

    /**
     * Weak world binding; the manager registry must never keep a weak world key alive through its
     * value.
     */
    private WeakReference<Level> world;

    /**
     * Recoverable failures accumulated across the three phases and legacy object ticks.
     */
    private List<Throwable> tickFailures;

    /**
     * Whether NORMAL has already been attempted in the current tick.
     */
    private boolean normalComplete;

    /**
     * Guards public phase boundaries while a system/command callback is being dispatched.
     */
    private boolean phaseDispatching;

    /**
     * Prevents an ordinary legacy callback from recursively completing the current tick.
     */
    private boolean endingTick;

    /**
     * Legacy component currently executing on the owner thread, or null outside its callback.
     */
    private WorldObjectComponent tickingComponent;

    /**
     * @return true only on the manager's owner thread, without granting compute callbacks
     *     live-state access
     */
    boolean isOwnerThread() {
        return Thread.currentThread() == ownerThread;
    }

    /**
     * @return owner-thread phase scheduler; configure systems between ticks
     */
    public EcsScheduler scheduler() {
        return scheduler;
    }

    /**
     * @return thread-safe deferred command API and restricted live-world access
     */
    public WorldContext worldContext() {
        return scheduler.context();
    }

    /**
     * Binds this manager to exactly one Minecraft world without retaining it strongly.
     *
     * @param level world belonging to this manager
     * @throws IllegalStateException if already bound to another world or called from a worker
     */
    public void bindWorld(Level level) {
        checkThread();
        Objects.requireNonNull(level, "level");
        if (closed || ticking || (world != null && world.get() != level))
            throw new IllegalStateException("Manager already bound, ticking or closed");
        world = new WeakReference<>(level);
    }

    /**
     * Resolves the weak world binding on the owner thread.
     *
     * @return live bound world
     * @throws IllegalStateException for standalone/unloaded worlds
     */
    Level boundWorld() {
        Level level = world == null ? null : world.get();
        if (level == null)
            throw new IllegalStateException("Manager has no live Minecraft world binding");
        return level;
    }

    /**
     * @return immutable membership array for internal, allocation-free policy inspection
     */
    WorldObject[] dispatchSnapshot() {
        return membership;
    }

    /**
     * @return true between beginTick and endTick, including callbacks
     */
    public boolean isTicking() {
        return ticking;
    }

    /**
     * @return whether the currently executing legacy component imposes a current-tick command
     *     deadline
     */
    boolean legacyMustComplete() {
        return Thread.currentThread() == ownerThread
                && tickingComponent != null
                && (EcsExecutionPolicy.flags(tickingComponent.getClass())
                                & EcsExecutionPolicy.CURRENT_TICK)
                        != 0;
    }

    /**
     * Binds a legacy callback's inherited scheduling restrictions.
     *
     * @param component callback owner
     * @return prior callback binding, for nested callback restoration
     */
    WorldObjectComponent beginComponentTick(WorldObjectComponent component) {
        WorldObjectComponent previous = tickingComponent;
        tickingComponent = component;
        return previous;
    }

    /**
     * Restores a legacy callback binding after any success/failure/detach.
     *
     * @param previous preceding callback, possibly null
     */
    void restoreComponentTick(WorldObjectComponent previous) {
        tickingComponent = previous;
    }

    /**
     * Observes registration and removal after membership changes become visible.
     */
    public interface Listener {
        /**
         * Receives a newly registered object.
         *
         * @param manager owning manager, non-null
         * @param object newly registered object, non-null
         */
        default void onObjectAdded(WorldObjectManager manager, WorldObject object) {}

        /**
         * Receives a removed, permanently detached object.
         *
         * @param manager former owning manager, non-null
         * @param object removed object, non-null
         */
        default void onObjectRemoved(WorldObjectManager manager, WorldObject object) {}
    }

    /**
     * Verifies access from the manager's creating thread.
     *
     * @throws IllegalStateException if the current thread is not the owner
     */
    public void checkThread() {
        if (Thread.currentThread() != ownerThread)
            throw new IllegalStateException(
                    "Use WorldObjectManager.execute from outside " + ownerThread.getName());
        if (scheduler != null && scheduler.context().isComputing())
            throw new IllegalStateException("Live manager mutation is forbidden during compute");
    }

    /**
     * Queues a command for a future world tick; does not execute inline on any thread.
     *
     * @param command operation to execute on the owner thread, non-null
     * @throws RejectedExecutionException if the queue is full or the manager is closed
     */
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        synchronized (commandLock) {
            if (closed || !commands.offer(command))
                throw new RejectedExecutionException("Manager closed or command queue full");
        }
    }

    /**
     * Returns queued world commands, excluding an already executing command.
     *
     * @return pending count, between zero and 4096
     */
    public int pendingCommands() {
        return commands.size();
    }

    /**
     * Configures the command budget; work submitted during a batch waits for the next tick.
     *
     * @param budget maximum commands per tick, greater than zero
     * @throws IllegalArgumentException if budget is not positive
     * @throws IllegalStateException if called outside the owner thread
     */
    public void setCommandBudget(int budget) {
        checkThread();
        if (budget < 1) throw new IllegalArgumentException("Command budget must be positive");
        commandBudget = budget;
    }

    /**
     * Creates and registers an object on the owner thread.
     *
     * @return new live object
     * @throws IllegalStateException if called outside the owner thread or after close
     */
    public WorldObject create() {
        checkThread();
        WorldObject object = new WorldObject();
        add(object);
        return object;
    }

    /**
     * Registers an exclusively owned object at the end of the stable iteration order.
     *
     * @param object unowned live object, non-null
     * @return false for an already registered UUID, leaving the argument untouched
     * @throws IllegalStateException if closed, detached, already owned or on the wrong thread
     * @throws WorldObjectException after registration if a listener fails
     */
    public boolean add(WorldObject object) {
        checkThread();
        Objects.requireNonNull(object, "object");
        if (closed) throw new IllegalStateException("WorldObjectManager is closed");
        if (object.isDetached())
            throw new IllegalStateException("Cannot register a detached object");
        if (objects.containsKey(object.id()) || archive.unresolved().contains(object.id()))
            return false;
        object.claimManager(this);
        objects.put(object.id(), object);
        ordered.add(object);
        refreshMembership();
        fireMembership(object, true);
        return true;
    }

    /**
     * Gets a registered object from any thread.
     *
     * @param id UUID, non-null
     * @return object or empty
     */
    public Optional<WorldObject> get(UUID id) {
        return Optional.ofNullable(objects.get(id));
    }

    /**
     * Captures a versioned world archive on the owner thread. Queued work and transient objects are
     * excluded.
     *
     * @return independent NBT compound, including unresolved objects and unknown fields
     * @throws IllegalStateException if closed, restoring or called outside the owner thread
     * @throws WorldObjectPersistenceException if a durable object contains an unregistered
     *     component
     */
    public CompoundTag writeNbt() {
        checkThread();
        if (closed) throw new IllegalStateException("Cannot save a closed manager");
        if (restoring) throw new IllegalStateException("Cannot save during archive restoration");
        return archive.encode(snapshot(), tickCounter);
    }

    /**
     * Restores an archive into a fresh manager. All decoded objects are registered before
     * attachment callbacks. Unknown/failing component types reserve their entire object's UUID
     * without activating a partial object.
     *
     * @param nbt caller-owned archive, non-null and never mutated
     * @return immutable diagnostics for unresolved objects and non-fatal lifecycle failures
     * @throws IllegalStateException unless the manager is fresh, empty, and on the owner thread
     * @throws WorldObjectPersistenceException if the root format or object identities are invalid
     */
    public List<String> readNbt(CompoundTag nbt) {
        checkThread();
        if (closed
                || restoring
                || archiveLoaded
                || !objects.isEmpty()
                || tickCounter != 0
                || !commands.isEmpty())
            throw new IllegalStateException(
                    "Load requires a fresh empty manager with no pending commands");
        restoring = true;
        try {
            var decoded = WorldObjectArchive.decode(Objects.requireNonNull(nbt, "nbt"));
            archive = decoded.archive();
            archiveLoaded = true;
            tickCounter = decoded.tick();
            for (var restored : decoded.objects()) {
                WorldObject object = restored.object();
                object.claimManager(this);
                object.restoreSchedule(
                        tickCounter, restored.interval(), restored.delay(), restored.sleeping());
                objects.put(object.id(), object);
                ordered.add(object);
            }
            refreshMembership();
            List<String> diagnostics = new ArrayList<>(decoded.warnings());
            for (var restored : decoded.objects()) {
                WorldObject object = restored.object();
                try {
                    if (!object.isDetached()) fireMembership(object, true);
                } catch (Exception failure) {
                    diagnostics.add("Object listener " + object.id() + ": " + failure);
                }
            }
            for (var restored : decoded.objects()) {
                WorldObject object = restored.object();
                try {
                    if (!object.isDetached()) object.finishRestoredComponents();
                } catch (Exception failure) {
                    diagnostics.add("Attachment lifecycle " + object.id() + ": " + failure);
                }
            }
            return List.copyOf(diagnostics);
        } finally {
            restoring = false;
        }
    }

    /**
     * Returns UUIDs reserved by preserved, inactive object records.
     *
     * @return immutable unresolved UUIDs
     * @throws IllegalStateException if called outside the owner thread
     */
    public Set<UUID> unresolvedObjectIds() {
        checkThread();
        return archive.unresolved();
    }

    /**
     * Removes, detaches and releases ownership even when callbacks fail.
     *
     * @param id registered UUID, non-null
     * @return true if an object was removed
     * @throws IllegalStateException if called outside the owner thread
     * @throws WorldObjectException after cleanup if callbacks fail
     */
    public boolean remove(UUID id) {
        checkThread();
        WorldObject removed = objects.remove(id);
        if (removed == null) return false;
        ordered.remove(removed);
        refreshMembership();
        List<Throwable> failures = new ArrayList<>();
        try {
            removed.detachFromManager(this);
        } catch (Throwable failure) {
            EcsFailures.rethrowFatal(failure);
            failures.add(failure);
        }
        try {
            fireMembership(removed, false);
        } catch (Throwable failure) {
            EcsFailures.rethrowFatal(failure);
            failures.add(failure);
        }
        throwFailures(failures, "WorldObject removal failed");
        return true;
    }

    /**
     * Returns a live, unmodifiable membership view; ordering is unspecified.
     *
     * @return weakly consistent membership view
     */
    public Collection<WorldObject> all() {
        return Collections.unmodifiableCollection(objects.values());
    }

    /**
     * Copies one immutable membership snapshot in registration order.
     *
     * @return independent mutable list, including sleeping objects
     */
    public List<WorldObject> snapshot() {
        return new ArrayList<>(List.of(membership));
    }

    /**
     * Visits one stable membership snapshot; visitor exceptions propagate.
     *
     * @param action visitor, non-null
     */
    public void forEach(Consumer<WorldObject> action) {
        Objects.requireNonNull(action, "action");
        for (WorldObject object : membership) action.accept(object);
    }

    /**
     * Selects objects containing every requested component class, including subtypes. Membership is
     * captured once; each object's component snapshot is observed independently.
     *
     * @param required classes that must all be present; empty matches every live object
     * @return immutable matching objects in registration order, including sleeping objects
     */
    @SafeVarargs
    public final List<WorldObject> query(Class<? extends WorldObjectComponent>... required) {
        Objects.requireNonNull(required, "required");
        for (var type : required) Objects.requireNonNull(type, "component class");
        List<WorldObject> result = new ArrayList<>();
        for (WorldObject object : membership) {
            if (object.isDetached()) continue;
            boolean matches = true;
            for (var type : required)
                if (!object.has(type)) {
                    matches = false;
                    break;
                }
            if (matches) result.add(object);
        }
        return List.copyOf(result);
    }

    /**
     * Selects objects attached under every exact descriptor.
     *
     * @param required component descriptors that must all be present, non-null
     * @return immutable matching objects in registration order, including sleeping objects
     */
    public List<WorldObject> queryTypes(WorldObjectComponentType<?>... required) {
        Objects.requireNonNull(required, "required");
        for (var type : required) Objects.requireNonNull(type, "component type");
        List<WorldObject> result = new ArrayList<>();
        for (WorldObject object : membership) {
            if (object.isDetached()) continue;
            boolean matches = true;
            for (var type : required)
                if (!object.has(type)) {
                    matches = false;
                    break;
                }
            if (matches) result.add(object);
        }
        return List.copyOf(result);
    }

    /**
     * @return number of registered objects, including sleeping objects
     */
    public int size() {
        return objects.size();
    }

    /**
     * @return active snapshot size, excluding sleeping objects
     */
    public int activeSize() {
        return active.length;
    }

    /**
     * @return last manager sequence, initially zero
     */
    public long lastTick() {
        return tickCounter;
    }

    /**
     * @return successive failed dispatches since the last successful tick, initially zero
     */
    public long consecutiveTickFailures() {
        return consecutiveTickFailures;
    }

    /**
     * Executes a bounded command batch, then due active objects in registration order. New objects
     * added by a component wait until the next manager tick. Removed or sleeping objects are
     * skipped even if an older dispatch snapshot references them.
     *
     * @throws IllegalStateException if called recursively or outside the owner thread
     * @throws WorldObjectException after all work if non-fatal commands/components failed
     */
    public void tickAll() {
        beginTick();
        if (!ticking) return;
        try {
            tickNormal();
        } finally {
            endTick();
        }
    }

    /**
     * Begins a manager tick, runs its bounded legacy command batch, then PRE systems/commands.
     * Minecraft calls this before entity simulation; standalone callers normally use tickAll.
     *
     * @throws IllegalStateException on recursion, restoration or a worker thread
     */
    public void beginTick() {
        checkThread();
        if (restoring) throw new IllegalStateException("Cannot tick during archive restoration");
        if (closed) return;
        if (ticking) throw new IllegalStateException("Recursive manager tick");
        ticking = true;
        ++tickCounter;
        normalComplete = false;
        tickFailures = null;
        try {
            int remaining = Math.min(commandBudget, commands.size());
            while (!closed && remaining-- > 0) {
                Runnable command = commands.poll();
                if (command == null) break;
                try {
                    command.run();
                } catch (Throwable failure) {
                    EcsFailures.rethrowFatal(failure);
                    recordTickFailure(failure);
                }
            }
            if (!closed) {
                try {
                    scheduler.begin(tickCounter);
                } catch (Throwable failure) {
                    EcsFailures.rethrowFatal(failure);
                    recordTickFailure(failure);
                }
                runSystemPhase(TickPhase.PRE);
            }
        } catch (Throwable fatal) {
            ticking = false;
            scheduler.end();
            throw fatal;
        }
    }

    /**
     * Runs NORMAL once, after vanilla entity processing and before block-entity ticks.
     *
     * @throws IllegalStateException if no tick is open or NORMAL was already attempted
     */
    public void tickNormal() {
        checkThread();
        if (phaseDispatching || endingTick)
            throw new IllegalStateException("Cannot change phases inside an ECS callback");
        if (closed) return;
        if (!ticking || normalComplete)
            throw new IllegalStateException("NORMAL requires one open manager tick");
        normalComplete = true;
        runSystemPhase(TickPhase.NORMAL);
    }

    /**
     * Completes legacy object ticks and POST, then reports recoverable failures from the whole
     * tick. A missing NORMAL callback is completed before POST, preserving exactly-once
     * standalone/fallback behavior.
     *
     * @throws WorldObjectException after all independent work has been attempted
     */
    public void endTick() {
        checkThread();
        if (phaseDispatching || endingTick)
            throw new IllegalStateException("Recursive manager tick completion");
        if (!ticking) return;
        try {
            if (!normalComplete && !closed) tickNormal();
            endingTick = true;
            WorldObject[] snapshot = active;
            for (WorldObject object : snapshot) {
                if (closed) break;
                try {
                    object.tickScheduled(this, tickCounter);
                } catch (Throwable failure) {
                    EcsFailures.rethrowFatal(failure);
                    recordTickFailure(failure);
                }
            }
            if (!closed) runSystemPhase(TickPhase.POST);
            consecutiveTickFailures = tickFailures == null ? 0 : consecutiveTickFailures + 1;
            if (tickFailures != null)
                throwFailures(tickFailures, "tickAll #" + tickCounter + " failed");
        } finally {
            scheduler.end();
            tickFailures = null;
            tickingComponent = null;
            endingTick = false;
            ticking = false;
        }
    }

    /**
     * Attempts a phase without preventing later independent work after a recoverable error.
     *
     * @param phase boundary being attempted
     */
    private void runSystemPhase(TickPhase phase) {
        phaseDispatching = true;
        try {
            scheduler.runPhase(phase);
        } catch (Throwable failure) {
            EcsFailures.rethrowFatal(failure);
            recordTickFailure(failure);
        } finally {
            phaseDispatching = false;
        }
    }

    /**
     * Lazily records a recoverable failure, avoiding a collection allocation on successful ticks.
     *
     * @param failure non-fatal callback/command/system failure
     */
    private void recordTickFailure(Throwable failure) {
        if (tickFailures == null) tickFailures = new ArrayList<>();
        tickFailures.add(failure);
    }

    /**
     * Rebuilds stable membership and active arrays after an owner-thread mutation.
     */
    private void refreshMembership() {
        membership = ordered.toArray(WorldObject[]::new);
        structureChanged();
        refreshActiveObjects();
    }

    /**
     * Rebuilds the active dispatch snapshot after sleep/wake or membership changes.
     *
     * @throws IllegalStateException if called outside the owner thread
     */
    final void refreshActiveObjects() {
        checkThread();
        List<WorldObject> awake = new ArrayList<>();
        for (WorldObject object : ordered) if (!object.isSleeping()) awake.add(object);
        active = awake.toArray(WorldObject[]::new);
    }

    /**
     * Returns component debug information from a stable membership snapshot.
     *
     * @return immutable map from UUID strings to component identifier lists
     */
    public Map<String, List<String>> describeAll() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (WorldObject object : membership) result.put(object.id().toString(), object.describe());
        return Collections.unmodifiableMap(result);
    }

    /**
     * Subscribes a structural observer.
     *
     * @param listener observer, non-null
     */
    public void addListener(Listener listener) {
        checkThread();
        listeners.add(Objects.requireNonNull(listener));
    }

    /**
     * Unsubscribes an observer.
     *
     * @param listener observer to remove
     */
    public void removeListener(Listener listener) {
        checkThread();
        listeners.remove(listener);
    }

    /**
     * Calls every membership observer, preserving cleanup despite non-fatal failures.
     *
     * @param object object whose membership changed
     * @param added true for registration, false for removal
     * @throws WorldObjectException after all observers if any failed
     */
    private void fireMembership(WorldObject object, boolean added) {
        List<Throwable> failures = new ArrayList<>();
        for (Listener listener : listeners) {
            try {
                if (added) listener.onObjectAdded(this, object);
                else listener.onObjectRemoved(this, object);
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                failures.add(failure);
            }
        }
        throwFailures(failures, "WorldObjectManager callbacks failed");
    }

    /**
     * Stops command submission, detaches all objects and releases their ownership. Repeated calls
     * are harmless on the owner thread.
     *
     * @throws IllegalStateException if called outside the owner thread
     * @throws WorldObjectException after cleanup if any lifecycle callback failed
     */
    @Override
    public void close() {
        checkThread();
        synchronized (commandLock) {
            if (closed) return;
            closed = true;
            commands.clear();
        }
        scheduler.close();
        world = null;
        WorldObject[] remaining = membership;
        objects.clear();
        ordered.clear();
        refreshMembership();
        List<Throwable> failures = new ArrayList<>();
        for (WorldObject object : remaining) {
            try {
                object.detachFromManager(this);
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                failures.add(failure);
            }
            try {
                fireMembership(object, false);
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                failures.add(failure);
            }
        }
        listeners.clear();
        throwFailures(failures, "WorldObjectManager close failed");
    }

    /**
     * @return true once permanent closure has started
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Throws one aggregate after mandatory work has completed.
     *
     * @param failures collected non-fatal failures, non-null
     * @param message diagnostic context
     * @throws WorldObjectException if failures is non-empty
     */
    private static void throwFailures(List<Throwable> failures, String message) {
        if (failures.isEmpty()) return;
        WorldObjectException error = new WorldObjectException(message);
        failures.forEach(error::addSuppressed);
        throw error;
    }
}
