package dev.sixik.sdmal.api.ecs;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Bounded owner-thread command queues with optional Minecraft world access. Workers buffer commands privately until successful system
 * commit. Commands submitted while their phase's batch is draining wait for the next tick; required
 * ones are rejected.
 */
public final class EcsContext {
    /**
     * Shared bound for queued commands and all unpublished worker command buffers.
     */
    public static final int COMMAND_CAPACITY = 4096;

    /**
     * Owning manager; contains only a weak reference to the bound Minecraft world.
     */
    private final EcsObjectManager manager;

    /**
     * Serializes outside submissions, phase transitions, capacity reservations and close.
     */
    private final Object lock = new Object();

    /**
     * Queue heads in PRE/NORMAL/POST order; -1 denotes an empty intrusive slot chain.
     */
    private final int[] queueHeads = {-1, -1, -1};

    /**
     * Queue tails, with the same phase ordering and empty sentinel.
     */
    private final int[] queueTails = {-1, -1, -1};

    /**
     * Published slot count per phase; fixes each flush's maximum batch size.
     */
    private final int[] queueSizes = new int[3];

    /**
     * Lazily allocated action storage; no per-command record or per-batch ObjectArrayList is needed.
     */
    private Runnable[] actions;

    /**
     * Exact-attachment guards corresponding to action slots.
     */
    private BooleanSupplier[] guards;

    /**
     * Earliest manager tick for each allocated slot.
     */
    private long[] commandTicks;

    /**
     * Current-tick deadline bit for each allocated slot.
     */
    private boolean[] requiredCommands;

    /**
     * Target phase ordinal for each allocated slot.
     */
    private byte[] commandPhases;

    /**
     * Intrusive links shared by the free list, private batches and published phase queues.
     */
    private int[] nextSlots;

    /**
     * First free slot, or -1 when the bounded storage is full/not yet allocated.
     */
    private int freeHead = -1;

    /**
     * Per-computation buffer, also bound during prepare and apply to propagate required deadlines.
     */
    private final ThreadLocal<Batch> currentJob = new ThreadLocal<>();

    /**
     * Last begun manager tick, initially zero.
     */
    private volatile long tick;

    /**
     * Currently executing phase; null between ticks.
     */
    private volatile TickPhase phase;

    /**
     * Whether the manager has begun a tick whose POST phase is not complete.
     */
    private boolean active;

    /**
     * Whether the current phase accepts commands for this tick.
     */
    private boolean open;

    /**
     * Whether a fixed command batch is currently being drained.
     */
    private boolean flushing;

    /**
     * Ordinal of the last completed phase; -1 at tick start.
     */
    private int completed = -1;

    /**
     * Number of reserved command slots, including unpublished batches.
     */
    private int reserved;

    /**
     * Permanent closed state; all access is guarded by lock.
     */
    private boolean closed;

    /**
     * Deadline inherited by nested submissions from an owner-thread command callback.
     */
    private boolean commandRequired;

    /**
     * Attachment guard inherited by nested submissions from an owner-thread command callback.
     */
    private BooleanSupplier commandGuard;

    /**
     * Creates command queues for one owner-thread manager.
     *
     * @param manager owning manager
     */
    EcsContext(EcsObjectManager manager) {
        this.manager = manager;
    }

    /**
     * Exposes the live Level only to owner-thread preparation/application and legacy component
     * ticks.
     *
     * @return bound live world
     * @throws IllegalStateException on workers, during compute, after close, or for an unbound
     *                               standalone manager
     */
    public Level level() {
        manager.checkThread();
        Batch batch = currentJob.get();
        if (batch != null && batch.computing)
            throw new IllegalStateException("Live Level access is forbidden during compute");
        if (manager.isClosed()) throw new IllegalStateException("World context is closed");
        return manager.boundWorld();
    }

    /**
     * @return current manager tick sequence; jobs see the sequence captured for their input
     */
    public long tick() {
        Batch batch = currentJob.get();
        return batch == null ? tick : batch.tick;
    }

    /**
     * @return currently executing phase, or null between ticks
     */
    public TickPhase phase() {
        return phase;
    }

    /**
     * @return true when this thread is inside the snapshot-only computation part of a system
     */
    boolean isComputing() {
        Batch batch = currentJob.get();
        return batch != null && batch.computing;
    }

    /**
     * @return total occupied slots, including commands buffered by running jobs
     */
    public int pendingCommands() {
        synchronized (lock) {
            return reserved;
        }
    }

    /**
     * Defers a world mutation to the selected phase, inheriting the current system/component
     * deadline. Capture independently owned or immutable inputs; the callback runs later in the
     * world thread.
     *
     * @param target  destination phase
     * @param command world mutation receiving the live Level only during execution
     * @throws RejectedExecutionException if closed or the shared command capacity is exhausted
     * @throws IllegalStateException      if required work targets a passed phase
     */
    public void enqueue(TickPhase target, Consumer<Level> command) {
        Objects.requireNonNull(command, "command");
        enqueue(target, () -> command.accept(level()));
    }

    /**
     * Defers an owner-thread action without requiring a Minecraft world, including standalone
     * simulations.
     *
     * @param target  destination phase
     * @param command owner-thread operation
     * @throws RejectedExecutionException if closed or full
     * @throws IllegalStateException      if required work targets a passed phase
     */
    public void enqueue(TickPhase target, Runnable command) {
        enqueue(target, command, false);
    }

    /**
     * Defers an action guarded by the exact live object instance, rejecting stale results after
     * UUID reuse.
     *
     * @param target  destination phase
     * @param object  expected live object, not just its UUID
     * @param command operation performed only while the same object remains registered
     */
    public void enqueue(TickPhase target, EcsObject object, Runnable command) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(command, "command");
        enqueue(
                target,
                () -> {
                    if (!object.isDetached() && manager.get(object.id()).orElse(null) == object)
                        command.run();
                });
    }

    /**
     * Defers an explicitly required owner-thread action; between ticks it is required in the next
     * tick.
     *
     * @param target  destination phase
     * @param command required action
     * @throws IllegalStateException if the current tick has already passed the requested boundary
     */
    public void enqueueRequired(TickPhase target, Runnable command) {
        enqueue(target, command, true);
    }

    /**
     * Validates a phase deadline and reserves capacity before publishing or buffering a command.
     *
     * @param target   destination phase
     * @param action   operation to execute
     * @param required explicit current-tick requirement, combined with inherited restrictions
     */
    private void enqueue(TickPhase target, Runnable action, boolean required) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(action, "command");
        Batch batch = currentJob.get();
        required |= batch != null ? batch.required : manager.legacyMustComplete();
        required |= manager.isOwnerThread() && commandRequired;
        synchronized (lock) {
            if (closed) throw new RejectedExecutionException("World context is closed");
            boolean passed =
                    active
                            && (target.ordinal() <= completed
                            || (phase == target && (!open || flushing)));
            if (required && passed)
                throw new IllegalStateException("Required command targets passed phase " + target);
            if (reserved >= COMMAND_CAPACITY)
                throw new RejectedExecutionException("World command capacity exhausted");
            initializeStorage();
            int slot = freeHead;
            freeHead = nextSlots[slot];
            nextSlots[slot] = -1;
            actions[slot] = action;
            guards[slot] =
                    batch != null ? batch.guard : manager.isOwnerThread() ? commandGuard : null;
            commandTicks[slot] = active && !passed ? tick : tick + 1;
            requiredCommands[slot] = required;
            commandPhases[slot] = (byte) target.ordinal();
            reserved++;
            if (batch == null) {
                appendQueue(target.ordinal(), slot);
            } else {
                if (batch.last < 0) batch.first = slot;
                else nextSlots[batch.last] = slot;
                batch.last = slot;
            }
        }
    }

    /**
     * Allocates bounded storage once, on first valid submission under the context lock.
     */
    private void initializeStorage() {
        if (actions != null) return;
        actions = new Runnable[COMMAND_CAPACITY];
        guards = new BooleanSupplier[COMMAND_CAPACITY];
        commandTicks = new long[COMMAND_CAPACITY];
        requiredCommands = new boolean[COMMAND_CAPACITY];
        commandPhases = new byte[COMMAND_CAPACITY];
        nextSlots = new int[COMMAND_CAPACITY];
        for (int slot = 0; slot < COMMAND_CAPACITY - 1; slot++) nextSlots[slot] = slot + 1;
        nextSlots[COMMAND_CAPACITY - 1] = -1;
        freeHead = 0;
    }

    /**
     * Appends an allocated, unlinked slot to one phase FIFO under the context lock.
     *
     * @param phase target phase ordinal in [0,2]
     * @param slot  allocated slot whose next link is -1
     */
    private void appendQueue(int phase, int slot) {
        if (queueTails[phase] < 0) queueHeads[phase] = slot;
        else nextSlots[queueTails[phase]] = slot;
        queueTails[phase] = slot;
        queueSizes[phase]++;
    }

    /**
     * Removes one published slot under the context lock without releasing its capacity reservation.
     *
     * @param phase source phase ordinal
     * @return removed slot, or -1 when the queue is empty
     */
    private int pollQueue(int phase) {
        int slot = queueHeads[phase];
        if (slot < 0) return -1;
        queueHeads[phase] = nextSlots[slot];
        if (queueHeads[phase] < 0) queueTails[phase] = -1;
        nextSlots[slot] = -1;
        queueSizes[phase]--;
        return slot;
    }

    /**
     * Returns an unlinked slot to the free list and clears all user references under the context
     * lock.
     *
     * @param slot allocated slot that no queue/batch retains
     */
    private void releaseSlot(int slot) {
        actions[slot] = null;
        guards[slot] = null;
        nextSlots[slot] = freeHead;
        freeHead = slot;
        reserved--;
    }

    /**
     * Begins a tick before PRE; no jobs may outlive the preceding tick.
     *
     * @param sequence manager tick sequence
     */
    void begin(long sequence) {
        manager.checkThread();
        synchronized (lock) {
            if (closed || active)
                throw new IllegalStateException("World context is closed or already ticking");
            tick = sequence;
            active = true;
            completed = -1;
        }
    }

    /**
     * Opens the next phase in the fixed PRE/NORMAL/POST order.
     *
     * @param next expected next phase
     */
    void startPhase(TickPhase next) {
        synchronized (lock) {
            if (!active || open || next.ordinal() != completed + 1)
                throw new IllegalStateException("Invalid tick phase order: " + next);
            phase = next;
            open = true;
        }
    }

    /**
     * Drains a fixed command batch; errors are reported after the rest of the batch has been
     * attempted.
     */
    void flush() {
        manager.checkThread();
        int remaining;
        int flushingPhase;
        synchronized (lock) {
            if (closed) return;
            if (!open || flushing) throw new IllegalStateException("No open command phase");
            flushingPhase = phase.ordinal();
            remaining = queueSizes[flushingPhase];
            if (remaining == 0) return;
            flushing = true;
        }
        EcsObjectException errors = null;
        try {
            while (remaining-- > 0) {
                Runnable action;
                BooleanSupplier guard;
                boolean required;
                synchronized (lock) {
                    int slot = pollQueue(flushingPhase);
                    if (slot < 0) break;
                    if (commandTicks[slot] > tick) {
                        appendQueue(flushingPhase, slot);
                        continue;
                    }
                    action = actions[slot];
                    guard = guards[slot];
                    required = requiredCommands[slot];
                    releaseSlot(slot);
                }
                boolean previousRequired = commandRequired;
                BooleanSupplier previousGuard = commandGuard;
                commandRequired = required;
                commandGuard = guard;
                try {
                    if (guard == null || guard.getAsBoolean()) action.run();
                } catch (Throwable failure) {
                    EcsFailures.rethrowFatal(failure);
                    if (errors == null)
                        errors = new EcsObjectException("World commands failed in " + phase);
                    errors.addSuppressed(failure);
                } finally {
                    commandRequired = previousRequired;
                    commandGuard = previousGuard;
                }
            }
        } finally {
            synchronized (lock) {
                flushing = false;
            }
        }
        if (errors != null) throw errors;
    }

    /**
     * Closes the current phase, retaining commands explicitly deferred to a future tick.
     */
    void finishPhase() {
        synchronized (lock) {
            if (closed) return;
            completed = phase.ordinal();
            open = false;
        }
    }

    /**
     * Ends a completed tick, including after recoverable system/command failures.
     */
    void end() {
        synchronized (lock) {
            active = false;
            open = false;
            phase = null;
        }
    }

    /**
     * Binds one job's private command buffer during a callback.
     *
     * @param batch current job's reusable buffer
     */
    void enter(Batch batch) {
        if (currentJob.get() != null) throw new IllegalStateException("Nested ECS system callback");
        currentJob.set(batch);
    }

    /**
     * Removes the current callback binding without allocating a new ThreadLocal entry next time.
     */
    void exit() {
        currentJob.set(null);
    }

    /**
     * Binds an automatic component's private buffer inside its internal system callback.
     *
     * @param batch current component buffer
     * @return enclosing system buffer, possibly null, to restore in finally
     */
    Batch swapBatch(Batch batch) {
        Batch previous = currentJob.get();
        currentJob.set(batch);
        return previous;
    }

    /**
     * @return inherited system deadline while capturing an automatic component batch
     */
    boolean requiresCurrentTick() {
        Batch batch = currentJob.get();
        return batch != null && batch.required;
    }

    /**
     * Publishes a successful system's commands in deterministic owner-thread commit order.
     *
     * @param batch completed job's private commands
     */
    void publish(Batch batch) {
        if (batch.first < 0) return;
        synchronized (lock) {
            if (closed) {
                discard(batch);
                return;
            }
            int slot = batch.first;
            while (slot >= 0) {
                int next = nextSlots[slot];
                nextSlots[slot] = -1;
                appendQueue(commandPhases[slot], slot);
                slot = next;
            }
            batch.first = batch.last = -1;
        }
    }

    /**
     * Releases commands from an unsuccessful or invalidated job.
     *
     * @param batch buffer whose commands must never be applied
     */
    void discard(Batch batch) {
        if (batch.first < 0) return;
        synchronized (lock) {
            int slot = batch.first;
            while (slot >= 0) {
                int next = nextSlots[slot];
                releaseSlot(slot);
                slot = next;
            }
            batch.first = batch.last = -1;
            releaseClosedStorage();
        }
    }

    /**
     * Clears queued world references and rejects future submissions; unpublished buffers are
     * released by their jobs.
     */
    void close() {
        manager.checkThread();
        synchronized (lock) {
            closed = true;
            for (int phaseIndex = 0; phaseIndex < queueHeads.length; phaseIndex++) {
                int slot;
                while ((slot = pollQueue(phaseIndex)) >= 0) releaseSlot(slot);
            }
            releaseClosedStorage();
            end();
        }
    }

    /**
     * Frees backing arrays after the last published/private reservation of a closed world is released.
     * The context lock must be held; outstanding worker batches keep their slots until their barrier.
     */
    private void releaseClosedStorage() {
        if (!closed || reserved != 0) return;
        actions = null;
        guards = null;
        commandTicks = null;
        requiredCommands = null;
        commandPhases = null;
        nextSlots = null;
        freeHead = -1;
    }

    /**
     * Reusable per-system buffer; a phase barrier prevents simultaneous reuse by different ticks.
     */
    static final class Batch {
        /**
         * First private command slot; -1 means no unpublished commands.
         */
        int first = -1;

        /**
         * Last private command slot; -1 means no unpublished commands.
         */
        int last = -1;

        /**
         * Tick captured for this dispatch.
         */
        long tick;

        /**
         * Whether this dispatch and its commands must finish in this tick.
         */
        boolean required;

        /**
         * Whether a callback is computing, forbidding live-world access even during owner-thread
         * fallback.
         */
        boolean computing;

        /**
         * Optional exact-attachment predicate inherited by queued automatic component commands.
         */
        BooleanSupplier guard;
    }
}
