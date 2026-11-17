package dev.sixik.sdmal.api.ecs;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

/**
 * Internal per-class adapter: automatically captures components and dispatches disjoint instance
 * batches.
 */
final class ComponentTickSystem implements EcsSystem<ComponentTickSystem, ComponentTickSystem> {
    /**
     * Minimum number of instances per worker batch; tiny components do not allocate a task per
     * instance.
     */
    private static final int MIN_BATCH_SIZE = 64;

    /**
     * Bound on submitted batches per component type, independent of object count.
     */
    private static final int MAX_JOBS =
            Math.max(1, Math.min(16, Runtime.getRuntime().availableProcessors()));

    /**
     * World manager owning every live attachment in this adapter.
     */
    private final WorldObjectManager manager;

    /**
     * Command context shared with the ordinary scheduler.
     */
    private final WorldContext context;

    /**
     * Concrete type used to group same-class component instances.
     */
    final Class<?> componentClass;

    /**
     * Stable group id visible to explicit dependencies.
     */
    private final String id;

    /**
     * Per-type frozen execution phase.
     */
    private final TickPhase phase;

    /**
     * Per-type frozen access, always including writes to the actual component class.
     */
    private final EcsAccess access;

    /**
     * Per-type explicit permission to defer work before capture.
     */
    private final boolean deferrable;

    /**
     * Per-type frozen dependency ids.
     */
    private final List<String> after;

    /**
     * Identity lookup for reusing attachment buffers across ticks.
     */
    private final IdentityHashMap<WorldObjectComponent, Binding> byComponent =
            new IdentityHashMap<>();

    /**
     * Stable attachment order, changed only while synchronizing between ticks.
     */
    private final List<Binding> bindings = new ArrayList<>();

    /**
     * Current selected captures; reused after every barrier.
     */
    private final List<Binding> prepared = new ArrayList<>();

    /**
     * At most MAX_JOBS futures from the current phase; all joined before apply.
     */
    private final List<FutureTask<Void>> jobs = new ArrayList<>();

    /**
     * Whether at least one live awake instance has a pending dispatch.
     */
    private boolean due;

    /**
     * Freezes metadata from the first concrete instance seen in this manager.
     *
     * @param manager owner-thread manager
     * @param component first snapshot component instance of this type
     */
    @SuppressWarnings("unchecked")
    ComponentTickSystem(WorldObjectManager manager, SnapshotTickableComponent<?, ?> component) {
        this.manager = manager;
        context = manager.worldContext();
        componentClass = component.getClass();
        id = SnapshotTickableComponent.systemId(componentClass);
        phase = java.util.Objects.requireNonNull(component.tickPhase(), "component tick phase");
        access =
                java.util.Objects.requireNonNull(component.tickAccess(), "component tick access")
                        .write((Class<? extends WorldObjectComponent>) componentClass);
        deferrable = component.mayDeferTick();
        after = List.copyOf(component.tickAfter());
    }

    /**
     * Records an attachment in manager/object order without retaining replaced attachment
     * generations.
     *
     * @param object current object
     * @param entry exact attached component/sequence pair
     * @param epoch scheduler synchronization stamp
     */
    void observe(WorldObject object, WorldObject.ComponentEntry entry, long epoch) {
        Binding binding = byComponent.get(entry.component());
        if (binding == null || binding.object != object || binding.sequence != entry.sequence()) {
            binding = new Binding(object, entry);
            bindings.add(binding);
            byComponent.put(entry.component(), binding);
        }
        binding.seen = epoch;
    }

    /**
     * Removes detached/replaced bindings and determines whether this type has awake pending work.
     *
     * @param epoch current scheduler synchronization stamp
     */
    void sweep(long epoch) {
        due = false;
        for (int i = bindings.size() - 1; i >= 0; i--) {
            Binding binding = bindings.get(i);
            if (binding.seen != epoch) {
                if (byComponent.get(binding.component) == binding)
                    byComponent.remove(binding.component);
                bindings.remove(i);
                binding.clear();
            } else if (binding.pending && binding.live()) due = true;
        }
    }

    /**
     * Selects due attachments without rebuilding stable membership or capturing stale deferred
     * inputs.
     *
     * @param tick manager tick, or zero when only validating the plan
     */
    void select(long tick) {
        due = false;
        for (int i = 0; i < bindings.size(); i++) {
            Binding binding = bindings.get(i);
            if (tick > 0 && binding.object.isTickScheduled(manager, tick)) binding.pending = true;
            if (binding.pending && binding.live()) due = true;
        }
    }

    /**
     * @return stable concrete component group id
     */
    @Override
    public String id() {
        return id;
    }

    /**
     * @return frozen phase
     */
    @Override
    public TickPhase phase() {
        return phase;
    }

    /**
     * @return frozen conflict declaration
     */
    @Override
    public EcsAccess access() {
        return access;
    }

    /**
     * @return true because implementing SnapshotTickableComponent is the explicit parallel opt-in
     */
    @Override
    public boolean parallel() {
        return true;
    }

    /**
     * @return per-type budget-deferral permission
     */
    @Override
    public boolean mayDefer() {
        return deferrable;
    }

    /**
     * @return frozen explicit dependencies
     */
    @Override
    public List<String> after() {
        return after;
    }

    /**
     * @param tick current manager tick
     * @return whether live awake instances have pending work
     */
    @Override
    public boolean isDue(long tick) {
        return due;
    }

    /**
     * Captures all selected component inputs on the owner thread, isolating recoverable failures by
     * instance.
     *
     * @param context current world context
     * @param manager owner manager
     * @return this reusable internal batch; live bindings never become user snapshot data
     */
    @Override
    public ComponentTickSystem prepare(WorldContext context, WorldObjectManager manager) {
        boolean required = context.requiresCurrentTick();
        for (int i = 0; i < bindings.size() && !manager.isClosed(); i++) {
            Binding binding = bindings.get(i);
            if (!binding.pending || !binding.live()) continue;
            binding.pending = false;
            binding.batch.tick = context.tick();
            binding.batch.required = required;
            binding.batch.computing = false;
            prepared.add(binding);
            WorldContext.Batch previous = context.swapBatch(binding.batch);
            try {
                binding.input = binding.component.capture(context);
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                binding.failure = failure;
            } finally {
                context.swapBatch(previous);
            }
        }
        return this;
    }

    /**
     * Submits disjoint chunks from the owner thread, alongside other independent systems, without
     * nested executor waits. Rejected jobs remain claimable at the phase barrier. Every FutureTask
     * executes at most once.
     *
     * @param executor externally owned worker executor
     */
    void submit(Executor executor) {
        int count = Math.min(MAX_JOBS, (prepared.size() + MIN_BATCH_SIZE - 1) / MIN_BATCH_SIZE);
        if (count == 0) return;
        int chunk = (prepared.size() + count - 1) / count;
        for (int start = 0; start < prepared.size(); start += chunk) {
            int first = start;
            int last = Math.min(prepared.size(), start + chunk);
            FutureTask<Void> job =
                    new FutureTask<>(
                            () -> {
                                computeRange(first, last);
                                return null;
                            });
            jobs.add(job);
            try {
                executor.execute(job);
            } catch (RejectedExecutionException rejected) {
                /* Owner claims this task at the barrier. */
            } catch (Throwable failure) {
                // Join all other jobs before reporting even an unusual executor failure.
                prepared.get(first).failure = failure;
            }
        }
    }

    /**
     * Joins all submitted chunks or computes serially when component metadata requires the world
     * thread.
     *
     * @param input this reusable batch
     * @param context context bound by the outer scheduler
     * @return completed batch for stable owner-thread commit
     */
    @Override
    public ComponentTickSystem compute(ComponentTickSystem input, WorldContext context) {
        if (jobs.isEmpty()) computeRange(0, prepared.size());
        else {
            boolean interrupted = false;
            Throwable error = null;
            try {
                for (int i = 0; i < jobs.size(); i++) {
                    FutureTask<Void> job = jobs.get(i);
                    job.run();
                    boolean waiting = true;
                    while (waiting) {
                        try {
                            job.get();
                            waiting = false;
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        } catch (ExecutionException failure) {
                            error = failure.getCause();
                            waiting = false;
                        }
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (error != null) {
                EcsFailures.rethrowFatal(error);
                throw new WorldObjectException("Component worker batch failed", error);
            }
        }
        return this;
    }

    /**
     * Computes a disjoint range with one private command buffer per instance.
     *
     * @param first inclusive prepared index
     * @param last exclusive prepared index
     */
    private void computeRange(int first, int last) {
        for (int i = first; i < last; i++) {
            Binding binding = prepared.get(i);
            if (binding.failure != null) continue;
            binding.batch.computing = true;
            WorldContext.Batch previous = context.swapBatch(binding.batch);
            try {
                binding.result = binding.component.compute(binding.input, context);
            } catch (Throwable failure) {
                binding.failure = failure;
            } finally {
                context.swapBatch(previous);
                binding.batch.computing = false;
            }
        }
    }

    /**
     * Applies each successful live result and publishes only that instance's command buffer.
     *
     * @param result completed internal batch
     * @param context owner-thread context
     * @param manager owning manager
     * @throws WorldObjectException after every healthy result is attempted, if any instance failed
     */
    @Override
    public void apply(
            ComponentTickSystem result, WorldContext context, WorldObjectManager manager) {
        WorldObjectException errors = null;
        for (int i = 0; i < prepared.size(); i++) {
            Binding binding = prepared.get(i);
            if (binding.failure == null && binding.live()) {
                WorldContext.Batch previous = context.swapBatch(binding.batch);
                try {
                    binding.component.apply(binding.result, context);
                    if (binding.live()) context.publish(binding.batch);
                } catch (Throwable failure) {
                    binding.failure = failure;
                } finally {
                    context.swapBatch(previous);
                }
            }
            if (binding.failure != null) {
                EcsFailures.rethrowFatal(binding.failure);
                if (errors == null)
                    errors =
                            new WorldObjectException(
                                    "Snapshot components failed: " + componentClass.getName());
                errors.addSuppressed(binding.failure);
            }
            binding.clear();
        }
        if (errors != null) throw errors;
    }

    /**
     * Clears all per-dispatch references after the scheduler barrier, including failed
     * prepare/apply callbacks.
     */
    void release() {
        for (int i = 0; i < prepared.size(); i++) prepared.get(i).clear();
        prepared.clear();
        jobs.clear();
    }

    /**
     * One exact component attachment; buffers are reused while the attachment remains present.
     */
    private final class Binding {
        /**
         * Object identity used to reject results after UUID reuse or object replacement.
         */
        final WorldObject object;

        /**
         * Exact attachment sequence used to reject detach/reattach of the same component instance.
         */
        final long sequence;

        /**
         * Exact immutable entry identity, including this attachment's generation.
         */
        final WorldObject.ComponentEntry attachment;

        /**
         * Last component array in which this entry was verified present.
         */
        WorldObject.ComponentEntry[] verifiedEntries;

        /**
         * Type-erased callback target; only its own capture and apply may access live component
         * fields.
         */
        final SnapshotTickableComponent<Object, Object> component;

        /**
         * Reused private command buffer, not shared with other instances in a chunk.
         */
        final WorldContext.Batch batch = new WorldContext.Batch();

        /**
         * Scheduler synchronization epoch in which this attachment was observed.
         */
        long seen;

        /**
         * Due work waiting for budget admission, without retaining a stale data snapshot.
         */
        boolean pending;

        /**
         * Current independently owned input.
         */
        Object input;

        /**
         * Computation output awaiting application.
         */
        Object result;

        /**
         * Capture/compute/apply failure for this instance only.
         */
        Throwable failure;

        /**
         * Captures exact attachment identity once.
         *
         * @param object current object
         * @param entry exact component entry
         */
        @SuppressWarnings("unchecked")
        Binding(WorldObject object, WorldObject.ComponentEntry entry) {
            this.object = object;
            sequence = entry.sequence();
            attachment = entry;
            component = (SnapshotTickableComponent<Object, Object>) entry.component();
            batch.guard = this::live;
        }

        /**
         * @return true only while the same awake attachment is still registered in this manager
         */
        boolean live() {
            if (manager.isClosed() || !object.isTickActive(manager))
                return false;
            WorldObject.ComponentEntry[] current = object.entriesArray();
            if (current == verifiedEntries) return true;
            for (int i = 0; i < current.length; i++) {
                if (current[i] != attachment) continue;
                verifiedEntries = current;
                return true;
            }
            return false;
        }

        /**
         * Releases data and failed/stale commands without discarding an unstarted pending
         * invocation.
         */
        void clear() {
            context.discard(batch);
            input = null;
            result = null;
            failure = null;
            batch.computing = false;
        }
    }
}
