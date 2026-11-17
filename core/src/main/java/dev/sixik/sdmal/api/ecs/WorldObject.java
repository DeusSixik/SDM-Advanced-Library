package dev.sixik.sdmal.api.ecs;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * Snapshot-based container of {@link WorldObjectComponent}s. Managed objects mutate and tick only
 * on their manager's owning thread. Standalone objects support concurrent structural access, but
 * callers must coordinate lifecycle callbacks.
 *
 * <p>Threading model (read-heavy workload): reads ({@link #entries}, {@link #get}, {@link #tick})
 * never take locks. Every mutation publishes a new immutable snapshot through a {@code volatile}
 * reference, so readers iterate a consistent view without blocking. Mutations serialize on an
 * internal lock; they are rare by design.
 *
 * <p>Ordering: components tick and serialize in ascending {@link WorldObjectComponent#priority}
 * order, stable by attach sequence. There is no manual index juggling; if a component's priority
 * changes at runtime, call {@link #resort}.
 *
 * <p>Lifecycle of an attached component: bound to this owner, published to readers, then {@link
 * WorldObjectComponent#onAttached} runs. Detach hides it from new readers first, then {@link
 * WorldObjectComponent#onDetached} runs. Callbacks execute outside the owner's write lock on the
 * mutating thread, so synchronous structural changes are safe &mdash; use {@link #defer} when work
 * must run on the ticking thread or after the current mutation settles.
 *
 * <p>Only {@link AbstractWorldObjectComponent} subclasses can be attached; direct {@link
 * WorldObjectComponent} implementations are rejected with {@link IllegalArgumentException}. This
 * guarantees uniform ownership bookkeeping.
 */
public final class WorldObject {

    /**
     * One attached component plus the type it was attached with ({@code null} = untyped).
     */
    public record ComponentEntry(
            WorldObjectComponent component, WorldObjectComponentType<?> type, long sequence) {}

    /**
     * Aggregates per-component tick failures; individual causes are {@code suppressed}.
     */
    public static final class TickException extends WorldObjectException {
        /**
         * Creates a TickException with the supplied configuration.
         *
         * @param message diagnostic context of the failure
         */
        public TickException(String message) {
            super(message);
        }
    }

    /**
     * Orders entries by ascending priority and then attach sequence.
     */
    private static final Comparator<ComponentEntry> ORDER =
            Comparator.comparingInt((ComponentEntry entry) -> entry.component().priority())
                    .thenComparingLong(ComponentEntry::sequence);

    /**
     * Stable object UUID or component registry identifier, never null.
     */
    private final UUID id;

    /**
     * Serializes structural mutations before publishing a new snapshot.
     */
    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Observers in registration order; iteration tolerates concurrent subscription changes.
     */
    private final CopyOnWriteArrayList<WorldObjectListener> listeners =
            new CopyOnWriteArrayList<>();

    /**
     * Bounded queue (1024 tasks) drained in batches by tick/drainDeferred; cleared on detach.
     */
    private final LinkedBlockingQueue<Runnable> deferred = new LinkedBlockingQueue<>(1024);

    /**
     * Maximum deferred tasks executed by the no-argument drain in one tick.
     */
    public static final int DEFAULT_DEFERRED_BUDGET = 256;

    /**
     * Exclusive manager; null for standalone or permanently detached objects.
     */
    private volatile WorldObjectManager manager;

    /**
     * Whether component ticking is suspended; deferred work also waits for wake.
     */
    private volatile boolean sleeping;

    /**
     * Number of manager ticks between dispatches, always positive.
     */
    private volatile int tickInterval = 1;

    /**
     * Earliest manager sequence eligible for the next dispatch.
     */
    private long nextTick = 1;

    /**
     * Optional owner-thread eligibility check, for example a hosted object's chunk activity.
     */
    private BooleanSupplier tickCondition;

    /**
     * Whether an external host owns persistence, forbidding duplicate inclusion in the world
     * archive.
     */
    private boolean hosted;

    /**
     * Assigns sole persistence ownership to an external host before manager publication.
     */
    final void markHosted() {
        checkMutationThread();
        requireLive();
        hosted = true;
        persistent = false;
    }

    /**
     * Manager sequence at which eligibility was last sampled; invalidated by condition replacement.
     */
    private long conditionTick = Long.MIN_VALUE;

    /**
     * Eligibility sampled before this object's first dispatch decision in the current tick.
     */
    private boolean conditionAllowsTick;

    /**
     * Adds an owner-thread activation condition without changing explicit sleep or tick intervals.
     * The condition is sampled at most once per manager tick and must not mutate ECS structure.
     *
     * @param condition eligibility callback, or null for unconditional activation
     */
    public void setTickCondition(BooleanSupplier condition) {
        checkMutationThread();
        requireLive();
        tickCondition = condition;
        conditionTick = Long.MIN_VALUE;
    }

    /**
     * Samples an external activation condition without invoking it from worker computation.
     *
     * @param tick manager sequence
     * @return whether external ownership currently permits simulation
     */
    private boolean conditionAllowsTick(long tick) {
        if (tickCondition == null) return true;
        if (conditionTick != tick) {
            conditionAllowsTick = tickCondition.getAsBoolean();
            conditionTick = tick;
        }
        return conditionAllowsTick;
    }

    /**
     * Whether this object belongs in world archives; false marks an explicitly transient object.
     */
    private volatile boolean persistent = true;

    /**
     * Next attach sequence, initially zero; used to break equal-priority ties.
     */
    private final AtomicLong sequencer = new AtomicLong();

    /**
     * Current immutable component snapshot, published through a volatile reference.
     */
    private volatile View view = View.EMPTY;

    /**
     * Creates a WorldObject with the supplied configuration.
     */
    public WorldObject() {
        this(UUID.randomUUID());
    }

    /**
     * Creates a WorldObject with the supplied configuration.
     *
     * @param id stable object UUID or registry identifier, non-null
     */
    public WorldObject(UUID id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    /**
     * Stable identity of this object.
     *
     * @return stable object UUID, never null
     */
    public UUID id() {
        return id;
    }

    /**
     * Returns this object's exclusive manager.
     *
     * @return manager, or empty for standalone/detached objects
     */
    public Optional<WorldObjectManager> manager() {
        return Optional.ofNullable(manager);
    }

    /**
     * Claims exclusive manager ownership before registration becomes visible.
     *
     * @param candidate manager claiming this live object, non-null
     * @throws IllegalStateException if another manager already owns this object
     */
    final void claimManager(WorldObjectManager candidate) {
        writeLock.lock();
        try {
            requireLive();
            if (manager != null)
                throw new IllegalStateException("WorldObject already belongs to a manager");
            manager = candidate;
            nextTick = candidate.lastTick() + 1;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Detaches this object's contents while the owner removes its membership.
     *
     * @param owner manager removing this object, non-null
     * @throws IllegalStateException if ownership does not match
     */
    final void detachFromManager(WorldObjectManager owner) {
        if (manager != owner) throw new IllegalStateException("Wrong WorldObject manager");
        owner.checkThread();
        try {
            detachContents();
        } finally {
            manager = null;
        }
    }

    /**
     * Checks the structural thread contract for managed objects.
     *
     * @throws IllegalStateException if called outside the owning manager thread
     */
    private void checkMutationThread() {
        WorldObjectManager current = manager;
        if (current != null) current.checkThread();
    }

    /**
     * Returns whether component dispatch is suspended.
     *
     * @return true while sleeping
     */
    public boolean isSleeping() {
        return sleeping;
    }

    /**
     * Suspends component ticks and deferred work, without removing the object from queries.
     *
     * @throws IllegalStateException if detached or called outside the manager thread
     */
    public void sleep() {
        setSleeping(true);
    }

    /**
     * Resumes dispatch on the next manager tick.
     *
     * @throws IllegalStateException if detached or called outside the manager thread
     */
    public void wake() {
        setSleeping(false);
    }

    /**
     * Changes activity; a wake never inserts a second dispatch into an ongoing iteration.
     *
     * @param value true to sleep, false to wake
     * @throws IllegalStateException if detached or called outside the manager thread
     */
    private void setSleeping(boolean value) {
        checkMutationThread();
        requireLive();
        if (sleeping == value) return;
        sleeping = value;
        WorldObjectManager current = manager;
        if (current != null) {
            nextTick = current.lastTick() + 1;
            current.refreshActiveObjects();
        }
    }

    /**
     * Returns the configured component tick interval.
     *
     * @return interval in manager ticks, at least one
     */
    public int tickInterval() {
        return tickInterval;
    }

    /**
     * @return true if this object is included in world archives; initially true
     */
    public boolean isPersistent() {
        return persistent;
    }

    /**
     * Marks an object as durable or explicitly transient.
     *
     * @param value whether world saves include the object
     * @throws IllegalStateException if detached or called outside the manager thread
     */
    public void setPersistent(boolean value) {
        if (hosted && value)
            throw new IllegalStateException("Hosted object persistence belongs to its host");
        checkMutationThread();
        requireLive();
        persistent = value;
    }

    /**
     * Captures the next dispatch offset without persisting queued tasks.
     *
     * @param tick manager sequence at snapshot time
     * @return positive delay in manager ticks
     */
    final int remainingTickDelay(long tick) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, nextTick - tick));
    }

    /**
     * Restores scheduling after manager ownership is claimed.
     *
     * @param tick restored manager sequence
     * @param interval positive dispatch interval
     * @param delay positive remaining delay
     * @param asleep whether this object is sleeping
     */
    final void restoreSchedule(long tick, int interval, int delay, boolean asleep) {
        tickInterval = interval;
        nextTick = tick + delay;
        sleeping = asleep;
    }

    /**
     * Installs a validated archive component set without invoking lifecycle callbacks.
     *
     * @param components entries with unique sequences and detached component instances
     * @throws IllegalStateException if this object is not empty or a component is already attached
     */
    final void prepareRestoredComponents(List<ComponentEntry> components) {
        if (!view.entries.isEmpty() || manager != null)
            throw new IllegalStateException("Restore requires a fresh object");
        List<ComponentEntry> sorted = new ArrayList<>(components);
        sorted.sort(ORDER);
        View prepared = View.of(sorted);
        long sequence = 0;
        for (ComponentEntry entry : sorted) {
            if (asAbstract(entry.component()).isAttached())
                throw new IllegalStateException("Factory returned attached component");
            sequence = Math.max(sequence, entry.sequence() + 1);
        }
        for (ComponentEntry entry : sorted) asAbstract(entry.component()).bind(this);
        sequencer.set(sequence);
        publishView(prepared);
    }

    /**
     * Publishes lifecycle callbacks after every restored object has manager membership.
     *
     * @throws WorldObjectException after all component callbacks if any failed
     */
    final void finishRestoredComponents() {
        List<Throwable> failures = new ArrayList<>();
        for (ComponentEntry entry : view.entries) {
            if (asAbstract(entry.component()).owner().orElse(null) != this) continue;
            try {
                entry.component().onAttached();
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                failures.add(failure);
            }
            fireAdded(entry.component(), failures);
        }
        fireOrderChanged(failures);
        throwIfFailed(failures, "Restored component lifecycle failed for " + id);
    }

    /**
     * Sets the dispatch interval; the next dispatch is eligible on the next manager tick.
     *
     * @param ticks positive number of manager ticks between component dispatches
     * @throws IllegalArgumentException if ticks is less than one
     * @throws IllegalStateException if detached or called outside the manager thread
     */
    public void setTickInterval(int ticks) {
        checkMutationThread();
        requireLive();
        if (ticks < 1) throw new IllegalArgumentException("Tick interval must be positive");
        tickInterval = ticks;
        WorldObjectManager current = manager;
        if (current != null) nextTick = current.lastTick() + 1;
    }

    /**
     * Dispatches a due active object from the owning manager's stable snapshot.
     *
     * @param owner expected manager, non-null
     * @param tick current manager sequence
     */
    final void tickScheduled(WorldObjectManager owner, long tick) {
        if (!isTickScheduled(owner, tick)) return;
        nextTick = tick + tickInterval;
        tick(tick);
    }

    /**
     * Tests scheduling without consuming the object's legacy/deferred dispatch slot. Snapshot
     * component groups capture this decision before any of the three phases runs.
     *
     * @param owner expected owner manager
     * @param tick selected manager sequence
     * @return true for a live, awake object whose interval permits this tick
     */
    final boolean isTickScheduled(WorldObjectManager owner, long tick) {
        return manager == owner
                && !sleeping
                && !view.detached
                && tick >= nextTick
                && conditionAllowsTick(tick);
    }

    /**
     * Checks activation for pending snapshot work without consuming interval scheduling.
     *
     * @param owner expected world manager
     * @return whether external ownership permits simulation during the current manager tick
     */
    final boolean isTickActive(WorldObjectManager owner) {
        return manager == owner
                && !sleeping
                && !view.detached
                && conditionAllowsTick(owner.lastTick());
    }

    /**
     * Current lifecycle: a live object accepts mutations until {@link #detach}.
     *
     * @return true once permanent closure or detachment has started
     */
    public boolean isDetached() {
        return view.detached;
    }

    /**
     * Detaches every component, clears deferred work and rejects future mutations. Idempotent;
     * returns {@code true} on the first call. Readers holding an older snapshot keep iterating it
     * safely.
     *
     * @return true on the first detach, false if already detached
     */
    public boolean detach() {
        checkMutationThread();
        WorldObjectManager current = manager;
        return current == null ? detachContents() : current.remove(id);
    }

    /**
     * Performs permanent component cleanup after ownership/thread checks.
     *
     * @return true on the first detach, false if already detached
     */
    private boolean detachContents() {
        List<ComponentEntry> removed;
        writeLock.lock();
        try {
            if (view.detached) {
                return false;
            }
            removed = view.entries;
            publishView(View.DETACHED);
            deferred.clear();
            for (ComponentEntry entry : removed) {
                asAbstract(entry.component()).bind(null);
            }
        } finally {
            writeLock.unlock();
        }
        List<Throwable> failures = new ObjectArrayList<>();
        for (ComponentEntry entry : removed) {
            try {
                entry.component().onDetached();
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                failures.add(failure);
            }
            fireRemoved(entry.component(), failures);
        }
        throwIfFailed(failures, "WorldObject.detach callbacks failed for " + id);
        return true;
    }

    // Attach.

    /**
     * Attaches an untyped component (always allowed, duplicates permitted).
     *
     * @param <C> component type
     * @param component component instance, non-null
     * @return supplied component after attachment, never null
     */
    public <C extends WorldObjectComponent> C attach(C component) {
        return attach(component, null);
    }

    /**
     * Attaches {@code component} under {@code type}, applying its duplicate policy. The component
     * becomes visible to readers before {@code onAttached} runs, so the callback can query siblings
     * (including itself). A throwing {@code onAttached} still leaves the component attached;
     * listeners are notified regardless.
     *
     * @param <C> component type
     * @param component component instance, non-null
     * @param type component descriptor used for typed membership and duplicate policies
     * @return supplied component after attachment, never null
     */
    public <C extends WorldObjectComponent> C attach(
            C component, WorldObjectComponentType<C> type) {
        checkMutationThread();
        Objects.requireNonNull(component, "component");
        if (component instanceof SnapshotTickableComponent<?, ?>
                && component instanceof TickableComponent)
            throw new IllegalArgumentException(
                    "Choose SnapshotTickableComponent or TickableComponent, not both");
        if (type != null && !type.componentClass().isInstance(component)) {
            throw new IllegalArgumentException("Component does not match type " + type.id());
        }
        AbstractWorldObjectComponent handle = asAbstract(component);
        List<ComponentEntry> replaced;
        writeLock.lock();
        try {
            requireLive();
            if (handle.isAttached()) {
                throw new IllegalStateException("Component already attached: " + component);
            }
            replaced = List.of();
            if (type != null) {
                List<ComponentEntry> clashing = view.byType.getOrDefault(type, List.of());
                if (!clashing.isEmpty()) {
                    switch (type.duplicatePolicy()) {
                        case REJECT ->
                                throw new DuplicateComponentException(
                                        "Duplicate component of type '" + type.id() + "' on " + id);
                        case REPLACE -> replaced = List.copyOf(clashing);
                        case ALLOW_MULTIPLE -> {}
                    }
                }
            }
            List<ComponentEntry> next = new ArrayList<>(view.entries);
            next.removeAll(replaced);
            next.add(new ComponentEntry(component, type, sequencer.getAndIncrement()));
            next.sort(ORDER);
            View prepared = View.of(next);
            handle.bind(this);
            for (ComponentEntry entry : replaced) {
                asAbstract(entry.component()).bind(null);
            }
            publishView(prepared);
        } finally {
            writeLock.unlock();
        }
        List<Throwable> failures = new ArrayList<>();
        for (ComponentEntry entry : replaced) {
            try {
                entry.component().onDetached();
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                failures.add(failure);
            }
            fireRemoved(entry.component(), failures);
        }
        try {
            component.onAttached();
        } catch (Throwable failure) {
            EcsFailures.rethrowFatal(failure);
            failures.add(failure);
        } finally {
            fireAdded(component, failures);
            fireOrderChanged(failures);
        }
        throwIfFailed(failures, "WorldObject.attach callbacks failed for " + id);
        return component;
    }

    // Detach by handle, class or type.

    /**
     * Detaches this exact instance. Returns {@code true} if it was present.
     *
     * @param component component instance, non-null
     * @return true if this component belonged to the object and was removed
     */
    public boolean detachComponent(WorldObjectComponent component) {
        checkMutationThread();
        Objects.requireNonNull(component, "component");
        ComponentEntry target;
        writeLock.lock();
        try {
            if (view.detached) {
                return false;
            }
            target = null;
            for (ComponentEntry entry : view.entries) {
                if (entry.component() == component) {
                    target = entry;
                    break;
                }
            }
            if (target == null) {
                return false;
            }
            List<ComponentEntry> next = new ArrayList<>(view.entries);
            next.remove(target);
            publishView(View.of(next));
            asAbstract(component).bind(null);
        } finally {
            writeLock.unlock();
        }
        List<Throwable> failures = new ArrayList<>();
        try {
            target.component().onDetached();
        } catch (Throwable failure) {
            EcsFailures.rethrowFatal(failure);
            failures.add(failure);
        } finally {
            fireRemoved(target.component(), failures);
        }
        throwIfFailed(failures, "WorldObject.detach callbacks failed for " + id);
        return true;
    }

    /**
     * Detaches every component assignable to {@code componentClass}.
     *
     * @param <C> component type
     * @param componentClass expected component class, non-null
     * @return immutable list of removed components in their previous tick order
     */
    public <C extends WorldObjectComponent> List<C> detachAll(Class<C> componentClass) {
        Objects.requireNonNull(componentClass, "componentClass");
        return detachMatching(
                entry -> componentClass.isInstance(entry.component()), componentClass::cast);
    }

    /**
     * Detaches every component attached under {@code type}.
     *
     * @param <C> component type
     * @param type component descriptor used for typed membership and duplicate policies
     * @return immutable list of removed components in their previous tick order
     */
    public <C extends WorldObjectComponent> List<C> detachAll(WorldObjectComponentType<C> type) {
        Objects.requireNonNull(type, "type");
        return detachMatching(
                entry -> matches(entry, type), entry -> type.componentClass().cast(entry));
    }

    /**
     * Removes matching components atomically and invokes all required detach callbacks.
     *
     * @param <C> component type
     * @param filter predicate selecting component entries to remove
     * @param cast conversion from a matching component to its declared type
     * @return immutable list of removed components in their previous tick order
     */
    private <C extends WorldObjectComponent> List<C> detachMatching(
            java.util.function.Predicate<ComponentEntry> filter,
            java.util.function.Function<WorldObjectComponent, C> cast) {
        checkMutationThread();
        List<ComponentEntry> removed;
        writeLock.lock();
        try {
            if (view.detached) {
                return List.of();
            }
            removed = new ArrayList<>();
            List<ComponentEntry> next = new ArrayList<>();
            for (ComponentEntry entry : view.entries) {
                if (filter.test(entry)) {
                    removed.add(entry);
                } else {
                    next.add(entry);
                }
            }
            if (removed.isEmpty()) {
                return List.of();
            }
            publishView(View.of(next));
            for (ComponentEntry entry : removed) {
                asAbstract(entry.component()).bind(null);
            }
        } finally {
            writeLock.unlock();
        }
        List<C> result = new ArrayList<>(removed.size());
        List<Throwable> failures = new ArrayList<>();
        for (ComponentEntry entry : removed) {
            try {
                entry.component().onDetached();
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                failures.add(failure);
            } finally {
                fireRemoved(entry.component(), failures);
            }
            result.add(cast.apply(entry.component()));
        }
        throwIfFailed(failures, "WorldObject.detach callbacks failed for " + id);
        return List.copyOf(result);
    }

    // Lock-free reads. Lookups by type/class resolve through the per-snapshot
    // index; polymorphic class queries still scan the entries linearly, which is
    // a single cache line for the expected handful of components per object.

    /**
     * Publishes an immutable component snapshot and invalidates manager caches before lifecycle
     * callbacks.
     *
     * @param next independently constructed state, never mutated after publication
     */
    private void publishView(View next) {
        view = next;
        WorldObjectManager current = manager;
        if (current != null) current.structureChanged();
    }

    /**
     * Returns an internal ordered array; callers must not modify it or expose it to mod code.
     *
     * @return immutable attachment snapshot, replaced only by structural mutations
     */
    ComponentEntry[] entriesArray() {
        return view.ordered;
    }

    /**
     * Checks exact manager ownership without allocating Optional wrappers or hashing a UUID.
     *
     * @param expected manager whose binding is being checked
     * @return whether this live object is still owned by the expected manager
     */
    boolean isLiveIn(WorldObjectManager expected) {
        return manager == expected && !view.detached;
    }

    /**
     * Immutable snapshot of entries (component plus its attach type). Never blocks.
     *
     * @return immutable component-entry snapshot in tick order
     */
    public List<ComponentEntry> entries() {
        return view.entries;
    }

    /**
     * Immutable snapshot of components in tick order. Never blocks.
     *
     * @return immutable component snapshot in tick order
     */
    public List<WorldObjectComponent> components() {
        return view.components;
    }

    /**
     * All components assignable to {@code componentClass}, in tick order.
     *
     * @param <C> component type
     * @param componentClass expected component class, non-null
     * @return immutable list of matching components in tick order
     */
    public <C extends WorldObjectComponent> List<C> get(Class<C> componentClass) {
        Objects.requireNonNull(componentClass, "componentClass");
        List<ComponentEntry> entries = view.entries;
        List<C> result = new ArrayList<>();
        for (ComponentEntry entry : entries) {
            if (componentClass.isInstance(entry.component())) {
                result.add(componentClass.cast(entry.component()));
            }
        }
        return List.copyOf(result);
    }

    /**
     * First component assignable to {@code componentClass}, if any.
     *
     * @param <C> component type
     * @param componentClass expected component class, non-null
     * @return matching value, or Optional.empty() when absent
     */
    public <C extends WorldObjectComponent> Optional<C> getSingle(Class<C> componentClass) {
        Objects.requireNonNull(componentClass, "componentClass");
        for (ComponentEntry entry : view.entries) {
            if (componentClass.isInstance(entry.component())) {
                return Optional.of(componentClass.cast(entry.component()));
            }
        }
        return Optional.empty();
    }

    /**
     * All components attached under {@code type}, in tick order. O(1) index lookup.
     *
     * @param <C> component type
     * @param type component descriptor used for typed membership and duplicate policies
     * @return immutable list of matching components in tick order
     */
    public <C extends WorldObjectComponent> List<C> get(WorldObjectComponentType<C> type) {
        Objects.requireNonNull(type, "type");
        List<ComponentEntry> entries = view.byType.getOrDefault(type, List.of());
        List<C> result = new ArrayList<>(entries.size());
        for (ComponentEntry entry : entries) {
            result.add(type.componentClass().cast(entry.component()));
        }
        return List.copyOf(result);
    }

    /**
     * First component attached under {@code type}, if any. O(1) index lookup.
     *
     * @param <C> component type
     * @param type component descriptor used for typed membership and duplicate policies
     * @return matching value, or Optional.empty() when absent
     */
    public <C extends WorldObjectComponent> Optional<C> getSingle(
            WorldObjectComponentType<C> type) {
        Objects.requireNonNull(type, "type");
        List<ComponentEntry> entries = view.byType.getOrDefault(type, List.of());
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(type.componentClass().cast(entries.get(0).component()));
    }

    /**
     * Returns {@code true} if any component is assignable to {@code componentClass}.
     *
     * @param componentClass expected component class, non-null
     * @return true if at least one matching component is attached
     */
    public boolean has(Class<? extends WorldObjectComponent> componentClass) {
        return getSingle(componentClass).isPresent();
    }

    /**
     * Returns {@code true} if any component is attached under {@code type}. O(1).
     *
     * @param type component descriptor used for typed membership and duplicate policies
     * @return true if at least one matching component is attached
     */
    public boolean has(WorldObjectComponentType<?> type) {
        Objects.requireNonNull(type, "type");
        return view.byType.containsKey(type);
    }

    /**
     * Number of attached components.
     *
     * @return current membership count, greater than or equal to zero
     */
    public int size() {
        return view.entries.size();
    }

    /**
     * Re-sorts by current {@code priority()} values. Returns {@code true} on change.
     *
     * @return true if component identity order changed
     */
    public boolean resort() {
        checkMutationThread();
        boolean changed;
        writeLock.lock();
        try {
            if (view.detached) {
                return false;
            }
            List<ComponentEntry> next = new ArrayList<>(view.entries);
            next.sort(ORDER);
            changed = !orderEquals(next, view.entries);
            if (changed) {
                publishView(View.of(next));
            }
        } finally {
            writeLock.unlock();
        }
        if (changed) {
            List<Throwable> failures = new ArrayList<>();
            fireOrderChanged(failures);
            throwIfFailed(failures, "WorldObject.resort callbacks failed for " + id);
        }
        return changed;
    }

    // Tick and deferred work.

    /**
     * Ticks every {@link TickableComponent} in order over a lock-free snapshot. Concurrent
     * attach/detach never breaks the iteration; components attached mid-tick tick on the next call.
     * Per-component failures are reported to listeners and aggregated into {@link TickException}.
     * Deferred tasks run after.
     *
     * @param tick manager-wide tick sequence, not wall-clock time
     */
    public void tick(long tick) {
        checkMutationThread();
        if (view.detached) {
            return;
        }
        List<Throwable> failures = null;
        ComponentEntry[] ticking = view.tickables;
        for (int index = 0; index < ticking.length; index++) {
            ComponentEntry entry = ticking[index];
            TickableComponent tickable = (TickableComponent) entry.component();
            WorldObjectManager tickManager = manager;
            WorldObjectComponent previous =
                    tickManager == null ? null : tickManager.beginComponentTick(entry.component());
            try {
                tickable.onTick(tick);
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                if (failures == null) {
                    failures = new ArrayList<>();
                }
                failures.add(failure);
                fireError(entry.component(), failure);
            } finally {
                if (tickManager != null) tickManager.restoreComponentTick(previous);
            }
        }
        try {
            drainDeferred();
        } catch (WorldObjectException failure) {
            if (failures == null) {
                failures = new ArrayList<>();
            }
            failures.add(failure);
        }
        if (failures != null) {
            TickException aggregated =
                    new TickException(
                            "Tick failed for " + failures.size() + " component(s) on " + id);
            for (Throwable failure : failures) {
                aggregated.addSuppressed(failure);
            }
            throw aggregated;
        }
    }

    /**
     * Queues work to run on the ticking thread. A task queued from a tick runs after the remaining
     * components of that tick; a task queued from a mutation callback runs on the next {@link
     * #tick}/{@link #drainDeferred} call. Best-effort across {@link #detach}: tasks still queued at
     * detach time are discarded.
     *
     * @param task non-null work item executed on the thread draining deferred work
     * @throws RejectedExecutionException if 1024 tasks are already waiting
     */
    public void defer(Runnable task) {
        Objects.requireNonNull(task, "task");
        writeLock.lock();
        try {
            requireLive();
            if (!deferred.offer(task))
                throw new RejectedExecutionException("WorldObject deferred queue is full");
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Runs up to DEFAULT_DEFERRED_BUDGET queued tasks on the owning thread. Work queued while
     * draining remains for a later drain.
     */
    public void drainDeferred() {
        drainDeferred(DEFAULT_DEFERRED_BUDGET);
    }

    /**
     * Runs a bounded batch captured at entry, so self-rescheduling work cannot monopolize a tick.
     *
     * @param budget maximum tasks to execute, greater than zero
     * @return number of attempted tasks, including failures
     * @throws IllegalArgumentException if budget is not positive
     * @throws WorldObjectException after the batch if any task failed
     * @throws IllegalStateException if called outside the owning manager thread
     */
    public int drainDeferred(int budget) {
        checkMutationThread();
        if (budget < 1) throw new IllegalArgumentException("Deferred budget must be positive");
        List<Throwable> failures = null;
        int remaining = Math.min(budget, deferred.size());
        int attempted = 0;
        Runnable task;
        while (remaining-- > 0 && (task = deferred.poll()) != null) {
            attempted++;
            try {
                task.run();
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                if (failures == null) {
                    failures = new ArrayList<>();
                }
                failures.add(failure);
            }
        }
        if (failures != null) {
            throwIfFailed(failures, "Deferred tasks failed for " + id);
        }
        return attempted;
    }

    /**
     * Returns {@code true} if deferred tasks are queued.
     *
     * @return true if deferred work is currently queued
     */
    public boolean hasPendingDeferred() {
        return !deferred.isEmpty();
    }

    // Listeners.

    /**
     * Subscribes a listener. Safe to call at any time; never blocks readers.
     *
     * @param listener structural event observer
     */
    public void addListener(WorldObjectListener listener) {
        checkMutationThread();
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Unsubscribes a listener.
     *
     * @param listener structural event observer
     */
    public void removeListener(WorldObjectListener listener) {
        checkMutationThread();
        listeners.remove(listener);
    }

    /**
     * Notifies all relevant structural observers and collects their failures.
     *
     * @param component component instance, non-null
     * @param failures collected callback failures; populated or reported by this operation
     */
    private void fireAdded(WorldObjectComponent component, List<Throwable> failures) {
        for (WorldObjectListener listener : listeners) {
            try {
                listener.onComponentAdded(this, component);
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                failures.add(failure);
            }
        }
    }

    /**
     * Notifies all relevant structural observers and collects their failures.
     *
     * @param component component instance, non-null
     * @param failures collected callback failures; populated or reported by this operation
     */
    private void fireRemoved(WorldObjectComponent component, List<Throwable> failures) {
        for (WorldObjectListener listener : listeners) {
            try {
                listener.onComponentRemoved(this, component);
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                failures.add(failure);
            }
        }
    }

    /**
     * Notifies all relevant structural observers and collects their failures.
     *
     * @param failures collected callback failures; populated or reported by this operation
     */
    private void fireOrderChanged(List<Throwable> failures) {
        for (WorldObjectListener listener : listeners) {
            try {
                listener.onOrderChanged(this);
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                failures.add(failure);
            }
        }
    }

    /**
     * Reports a component failure without allowing diagnostic listeners to interrupt remaining
     * ticks.
     *
     * @param component component instance, non-null
     * @param cause original failure to preserve in the exception
     */
    private void fireError(WorldObjectComponent component, Throwable cause) {
        for (WorldObjectListener listener : listeners) {
            try {
                listener.onComponentError(this, component, cause);
            } catch (Throwable ignored) {
                EcsFailures.rethrowFatal(ignored);
                // Error reporting must never break the tick of remaining components.
            }
        }
    }

    // Debug view.

    /**
     * Debug view: one type id per attached component ({@code "untyped:"} plus class name for
     * untyped ones). For real persistence see {@code api.ecs.sync.SyncedWorldObjectComponent}.
     *
     * @return immutable component identifiers in tick order, including untyped class names
     */
    public List<String> describe() {
        List<String> result = new ArrayList<>();
        for (ComponentEntry entry : view.entries) {
            if (entry.type() != null) {
                result.add(entry.type().id());
            } else {
                result.add("untyped:" + entry.component().getClass().getName());
            }
        }
        return List.copyOf(result);
    }

    // Internals.

    /**
     * Rejects structural work after permanent detachment.
     */
    private void requireLive() {
        if (view.detached) {
            throw new DetachedException("WorldObject " + id + " is detached");
        }
    }

    /**
     * Validates that a component supports the shared ownership protocol.
     *
     * @param component component instance, non-null
     * @return same component as an ownership-capable base instance, never null
     */
    private static AbstractWorldObjectComponent asAbstract(WorldObjectComponent component) {
        if (!(component instanceof AbstractWorldObjectComponent handle)) {
            throw new IllegalArgumentException(
                    "Component must extend AbstractWorldObjectComponent: "
                            + component.getClass().getName());
        }
        return handle;
    }

    /**
     * Checks an attached entry against a component type, including compatible untyped instances.
     *
     * @param entry component snapshot entry being inspected
     * @param type component descriptor used for typed membership and duplicate policies
     * @return true if the entry has this type or is an untyped instance of its class
     */
    private static boolean matches(ComponentEntry entry, WorldObjectComponentType<?> type) {
        if (type.equals(entry.type())) {
            return true;
        }
        return entry.type() == null && type.componentClass().isInstance(entry.component());
    }

    /**
     * Compares component identity order between two snapshots.
     *
     * @param first first value to compare, possibly null
     * @param second second value to compare, possibly null
     * @return true if both lists contain the same component identities in the same order
     */
    private static boolean orderEquals(List<ComponentEntry> first, List<ComponentEntry> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int i = 0; i < first.size(); i++) {
            if (first.get(i).component() != second.get(i).component()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports collected callback failures after mandatory work has completed.
     *
     * @param failures collected callback failures; populated or reported by this operation
     * @param message diagnostic context of the failure
     */
    private static void throwIfFailed(List<Throwable> failures, String message) {
        if (failures == null || failures.isEmpty()) {
            return;
        }
        WorldObjectException aggregated =
                new WorldObjectException(message + " (" + failures.size() + " failure(s))");
        for (Throwable failure : failures) {
            aggregated.addSuppressed(failure);
        }
        throw aggregated;
    }

    /**
     * {@inheritDoc}
     *
     * @param other value to compare against, possibly null
     * @return true if the other object has the same descriptor or object identifier
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorldObject that)) {
            return false;
        }
        return id.equals(that.id);
    }

    /**
     * {@inheritDoc}
     *
     * @return identifier hash; any signed 32-bit value
     */
    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /**
     * {@inheritDoc}
     *
     * @return diagnostic or stable identifier text, never null
     */
    @Override
    public String toString() {
        View current = view;
        return "WorldObject{id="
                + id
                + ", detached="
                + current.detached
                + ", size="
                + current.entries.size()
                + "}";
    }

    /**
     * Immutable per-snapshot state: ordered entries plus a type index. Built once per mutation
     * under the write lock, then shared lock-free. The index maps exact attach types to their
     * entries in tick order; untyped components live only in {@link #entries}. Memory cost is one
     * map plus one list per distinct type — negligible against the copy-on-write snapshot itself.
     */
    private static final class View {
        /**
         * Shared empty, live component snapshot.
         */
        static final View EMPTY = new View(List.of(), Map.of(), false);

        /**
         * Permanently detached empty snapshot, shared across objects.
         */
        static final View DETACHED = new View(List.of(), Map.of(), true);

        /**
         * Immutable ordered component entries for one snapshot.
         */
        final List<ComponentEntry> entries;

        /**
         * Raw ordered entries for internal snapshot traversal.
         */
        final ComponentEntry[] ordered;

        /**
         * Legacy tickable entries only, in the same priority/attachment order.
         */
        final ComponentEntry[] tickables;

        /**
         * Public immutable component projection, built once per structural mutation.
         */
        final List<WorldObjectComponent> components;

        /**
         * Exact component-type index pointing to immutable ordered entry lists.
         */
        final Map<WorldObjectComponentType<?>, List<ComponentEntry>> byType;

        /**
         * Whether the owner has permanently detached all components.
         */
        final boolean detached;

        /**
         * Creates a View with the supplied configuration.
         *
         * @param entries component entries in their current ordering
         * @param byType immutable index from exact component type to ordered entries
         * @param detached whether this snapshot represents a permanently detached object
         */
        private View(
                List<ComponentEntry> entries,
                Map<WorldObjectComponentType<?>, List<ComponentEntry>> byType,
                boolean detached) {
            this.entries = entries;
            ordered = entries.toArray(ComponentEntry[]::new);
            WorldObjectComponent[] projected = new WorldObjectComponent[ordered.length];
            int tickableCount = 0;
            for (int i = 0; i < ordered.length; i++) {
                projected[i] = ordered[i].component();
                if (projected[i] instanceof TickableComponent) tickableCount++;
            }
            components = List.of(projected);
            if (tickableCount == ordered.length) {
                tickables = ordered;
            } else {
                tickables = new ComponentEntry[tickableCount];
                int position = 0;
                for (int i = 0; i < ordered.length; i++) {
                    if (ordered[i].component() instanceof TickableComponent)
                        tickables[position++] = ordered[i];
                }
            }
            this.byType = byType;
            this.detached = detached;
        }

        /**
         * Builds an immutable ordered snapshot and exact-type lookup index.
         *
         * @param entries component entries in their current ordering
         * @return new immutable live snapshot and exact-type lookup index
         */
        static View of(List<ComponentEntry> entries) {
            List<ComponentEntry> frozen = List.copyOf(entries);
            Map<WorldObjectComponentType<?>, List<ComponentEntry>> index = new HashMap<>();
            for (ComponentEntry entry : frozen) {
                if (entry.type() != null) {
                    index.computeIfAbsent(entry.type(), key -> new ArrayList<>()).add(entry);
                }
            }
            index.replaceAll((key, list) -> List.copyOf(list));
            return new View(frozen, Map.copyOf(index), false);
        }
    }
}
