package dev.sixik.sdmal.api.ecs;

import dev.sixik.sdmal.core.utils.ObjectArrayView;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

/**
 * Owner-thread scheduler with cached dependency groups and snapshot-only worker computation.
 * Started jobs always join within their phase. Budget deferral happens before input preparation,
 * coalesces into one pending invocation and uses a fresh snapshot on a later tick.
 */
public final class EcsScheduler {
    /**
     * Owning world manager.
     */
    private final EcsObjectManager manager;

    /**
     * Per-world bounded command queues.
     */
    private final EcsContext context;

    /**
     * Stable registration order, changed only between manager ticks.
     */
    private final List<Node> systems = new ObjectArrayList<>();

    /**
     * Topological execution groups, rebuilt only after system registration changes.
     */
    private Node[][][] phases = new Node[][][]{new Node[0][], new Node[0][], new Node[0][]};

    /**
     * All nodes in topological phase order, used for required predecessor propagation.
     */
    private Node[] ordered = new Node[0];

    /**
     * Worker executor; ownership stays with the caller when replaced.
     */
    private Executor executor = ForkJoinPool.commonPool();

    /**
     * Maximum optional systems started in each phase; mandatory dependencies do not consume this
     * budget.
     */
    private int optionalBudget = Integer.MAX_VALUE;

    /**
     * Whether the cached dependency groups need rebuilding.
     */
    private boolean dirty = true;

    /**
     * Permanent shutdown state.
     */
    private boolean closed;

    /**
     * Unique traversal stamp for allocation-free optional prerequisite admission.
     */
    private long admissionVisit;

    /**
     * Automatic component groups, retained per concrete type until this world's manager is closed.
     */
    private final Map<Class<?>, ComponentTickSystem> componentSystems = new Object2ObjectLinkedOpenHashMap<>();

    /**
     * Increasing stamp for attachment reconciliation, separate from manager tick sequences.
     */
    private long componentEpoch;

    /**
     * Composition revision last reconciled into automatic component bindings.
     */
    private long componentVersion = -1;

    /**
     * Composition revision last scanned for concrete-type execution restrictions.
     */
    private long policyVersion = -1;

    /**
     * Stable automatic group array, replaced only after structural reconciliation.
     */
    private ComponentTickSystem[] automaticGroups = new ComponentTickSystem[0];

    /**
     * Creates a scheduler and its command context for one manager.
     *
     * @param manager owner-thread world manager
     */
    EcsScheduler(EcsObjectManager manager) {
        this.manager = manager;
        context = new EcsContext(manager);
    }

    /**
     * @return this world's command queues and restricted world access
     */
    public EcsContext context() {
        return context;
    }

    /**
     * Registers a system, freezing its access/phase/parallelism metadata until unregistered. Call
     * validatePlan after registering dependent systems to detect missing ids/cycles before ticking.
     *
     * @param system snapshot system, non-null
     * @throws IllegalArgumentException if the id is blank or already used
     * @throws IllegalStateException    if called while ticking, closed, or from a worker
     */
    public void register(EcsSystem<?, ?> system) {
        checkMutable();
        Node node = new Node(Objects.requireNonNull(system, "system"));
        if (node.id.startsWith("sdmal:component/"))
            throw new IllegalArgumentException(
                    "Reserved automatic component system id: " + node.id);
        if (node.id.isBlank()) throw new IllegalArgumentException("System id must not be blank");
        for (Node existing : systems)
            if (existing.id.equals(node.id))
                throw new IllegalArgumentException("Duplicate system id: " + node.id);
        systems.add(node);
        dirty = true;
    }

    /**
     * Removes a system between ticks; references to it in other systems remain validation errors.
     *
     * @param id registered system id
     * @return true if a system was removed
     */
    public boolean unregister(String id) {
        checkMutable();
        if (id.startsWith("sdmal:component/"))
            throw new IllegalArgumentException(
                    "Automatic component groups follow their attachments");
        boolean removed = systems.removeIf(node -> node.id.equals(id));
        dirty |= removed;
        return removed;
    }

    /**
     * Configures an executor compatible with external job-executor implementations. Rejected
     * submissions safely fall back to the owner thread. The scheduler never shuts down this
     * executor.
     *
     * @param executor worker executor, non-null
     */
    public void setExecutor(Executor executor) {
        checkMutable();
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Bounds optional system starts per phase; required work and its required predecessors always
     * run.
     *
     * @param budget optional starts per phase, zero or greater
     */
    public void setOptionalBudget(int budget) {
        checkMutable();
        if (budget < 0) throw new IllegalArgumentException("Optional budget must be non-negative");
        optionalBudget = budget;
    }

    /**
     * Validates and caches stable dependency groups before gameplay.
     */
    public void validatePlan() {
        checkMutable();
        synchronizeComponents(0);
        rebuild();
    }

    /**
     * Rejects configuration changes from workers, callbacks, or after manager shutdown.
     */
    private void checkMutable() {
        manager.checkThread();
        if (closed || manager.isClosed() || manager.isTicking())
            throw new IllegalStateException("Configure systems between live manager ticks");
    }

    /**
     * Opens a tick and selects due work, with mandatory dependencies promoted across phase
     * boundaries.
     *
     * @param tick current manager sequence
     */
    void begin(long tick) {
        synchronizeComponents(tick);
        rebuild();
        context.begin(tick);
        EcsObjectException errors = null;
        for (Node node : ordered) {
            node.status = Node.WAITING;
            node.flags =
                    policyVersion == manager.structureVersion()
                            ? node.resolvedFlags
                            : node.access.flags();
            node.due = false;
            try {
                node.due = (node.automatic == null && node.pending) || node.system.isDue(tick);
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                node.status = Node.FAILED;
                errors = aggregate(errors, failure);
            }
            if (node.due && node.waitingSince == 0) node.waitingSince = tick;
            if (!node.due) node.waitingSince = 0;
            node.required =
                    node.due
                            && (!node.deferrable
                            || (node.flags & EcsExecutionPolicy.CURRENT_TICK) != 0);
        }
        refreshPolicies();
        promoteRequired();
        if (errors != null) throw errors;
    }

    /**
     * Discovers snapshot tick components directly from attachments; no user system registration is
     * needed. New attachments introduced during a tick are included starting with the next tick.
     *
     * @param tick selected manager tick, or zero when only validating configuration
     */
    private void synchronizeComponents(long tick) {
        long version = manager.structureVersion();
        if (componentVersion != version) {
            long epoch = ++componentEpoch;
            EcsObject[] objects = manager.dispatchSnapshot();
            for (int objectIndex = 0; objectIndex < objects.length; objectIndex++) {
                EcsObject object = objects[objectIndex];
                EcsObject.ComponentEntry[] entries = object.entriesArray();
                for (int i = 0; i < entries.length; i++) {
                    var entry = entries[i];
                    if (!(entry.component() instanceof SnapshotTickableComponent<?, ?> component))
                        continue;
                    ComponentTickSystem group = componentSystems.get(component.getClass());
                    if (group == null) {
                        group = new ComponentTickSystem(manager, component);
                        componentSystems.put(component.getClass(), group);
                        systems.add(new Node(group));
                        dirty = true;
                    }
                    group.observe(object, entry, epoch);
                }
            }
            automaticGroups = componentSystems.values().toArray(ComponentTickSystem[]::new);
            for (int i = 0; i < automaticGroups.length; i++) automaticGroups[i].sweep(epoch);
            componentVersion = version;
        }
        for (int i = 0; i < automaticGroups.length; i++) automaticGroups[i].select(tick);
    }

    /**
     * Scans actual attached classes so a base-type declaration cannot bypass inherited thread
     * restrictions.
     */
    private void refreshPolicies() {
        long version = manager.structureVersion();
        if (policyVersion == version) return;
        for (int i = 0; i < ordered.length; i++)
            ordered[i].resolvedFlags = ordered[i].access.flags();
        EcsObject[] objects = manager.dispatchSnapshot();
        for (int objectIndex = 0; objectIndex < objects.length; objectIndex++) {
            EcsObject.ComponentEntry[] entries = objects[objectIndex].entriesArray();
            for (int i = 0; i < entries.length; i++) {
                Class<?> type = entries[i].component().getClass();
                int flags = EcsExecutionPolicy.flags(type);
                if (flags == 0) continue;
                for (int nodeIndex = 0; nodeIndex < ordered.length; nodeIndex++) {
                    Node node = ordered[nodeIndex];
                    if (node.access.matches(type)) node.resolvedFlags |= flags;
                }
            }
        }
        for (int i = 0; i < ordered.length; i++) {
            Node node = ordered[i];
            node.flags |= node.resolvedFlags;
            if (node.due && (node.flags & EcsExecutionPolicy.CURRENT_TICK) != 0)
                node.required = true;
        }
        policyVersion = version;
    }

    /**
     * Propagates current-tick requirements through all due predecessors in reverse topological
     * order.
     */
    private void promoteRequired() {
        for (int i = ordered.length - 1; i >= 0; i--)
            if (ordered[i].required)
                for (Node parent : ordered[i].parents) if (parent.due) parent.required = true;
    }

    /**
     * Runs one phase's groups with stable commit order and barriers before any live state is
     * applied.
     *
     * @param phase next world-tick boundary
     * @throws EcsObjectException after independent work and command batches have been attempted
     */
    void runPhase(TickPhase phase) {
        context.startPhase(phase);
        EcsObjectException errors = null;
        boolean interrupted = false;
        try {
            refreshPolicies();
            promoteRequired();
            admitOptional(phase);
            try {
                context.flush();
            } catch (EcsObjectException failure) {
                errors = aggregate(errors, failure);
            }
            for (Node[] group : phases[phase.ordinal()]) {
                if (manager.isClosed()) break;
                // World commands and earlier commits may have attached a newly restricted concrete
                // subtype.
                refreshPolicies();
                promoteRequired();
                errors = prepareGroup(group, errors);
                submitWorkers(group);
                computeOwnerJobs(group);
                interrupted |= awaitWorkers(group);
                errors = applyGroup(group, errors);
                try {
                    context.flush();
                } catch (EcsObjectException failure) {
                    errors = aggregate(errors, failure);
                }
            }
        } finally {
            for (Node node : ordered)
                if (node.phase == phase) {
                    context.discard(node.batch);
                    node.clear();
                }
            context.finishPhase();
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (errors != null) throw errors;
    }

    /**
     * Selects eligible systems and captures owner-thread inputs before any worker submission.
     *
     * @param group  independent systems in stable commit order
     * @param errors existing recoverable failures, possibly null
     * @return accumulated failures
     */
    private EcsObjectException prepareGroup(Node[] group, EcsObjectException errors) {
        for (Node node : group) {
            if (manager.isClosed()) break;
            if (node.status == Node.FAILED) continue;
            if (!node.due) {
                node.status = Node.SKIPPED;
                continue;
            }
            boolean blocked = false;
            for (Node parent : node.parents) {
                if (parent.status == Node.FAILED
                        || (node.required && parent.status == Node.DEFERRED)) {
                    node.status = Node.FAILED;
                    errors =
                            aggregate(
                                    errors,
                                    new IllegalStateException(
                                            node.id + " blocked by " + parent.id));
                    blocked = true;
                    break;
                }
                if (parent.status == Node.DEFERRED) {
                    blocked = true;
                    node.status = Node.DEFERRED;
                    node.pending = true;
                }
            }
            if (blocked) continue;
            if (!node.admitted && !node.required) {
                node.status = Node.DEFERRED;
                node.pending = true;
                continue;
            }
            node.batch.tick = context.tick();
            node.batch.required = node.required;
            node.batch.computing = false;
            context.enter(node.batch);
            try {
                node.input = node.system.prepare(context, manager);
                node.status = Node.PREPARED;
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                node.status = Node.FAILED;
                errors = aggregate(errors, failure);
            } finally {
                context.exit();
            }
        }
        return errors;
    }

    /**
     * Submits independent worker jobs before running owner-thread-only computations.
     *
     * @param group independent systems in stable commit order
     */
    private void submitWorkers(Node[] group) {
        for (Node node : group)
            if (node.status == Node.PREPARED
                    && !manager.isClosed()
                    && node.parallel
                    && (node.flags & EcsExecutionPolicy.WORLD_THREAD) == 0) {
                if (node.automatic != null) {
                    node.automatic.submit(executor);
                    continue;
                }
                node.future = new FutureTask<>(node);
                try {
                    executor.execute(node.future);
                } catch (RejectedExecutionException rejected) {
                    node.future.run();
                } catch (Throwable failure) {
                    node.failure = failure;
                    node.future.run();
                }
            }
    }

    /**
     * Computes owner-only jobs and joins automatic component chunks without nested executor waits.
     *
     * @param group independent systems in stable commit order
     */
    private void computeOwnerJobs(Node[] group) {
        for (Node node : group)
            if (node.status == Node.PREPARED && node.future == null && !manager.isClosed()) {
                try {
                    node.result = node.call();
                } catch (Throwable failure) {
                    node.failure = failure;
                }
            }
    }

    /**
     * Claims unstarted tasks and joins all started workers before any live-state commit.
     *
     * @param group independent systems in stable commit order
     * @return whether interrupted waiting must be restored after the phase
     */
    private boolean awaitWorkers(Node[] group) {
        boolean interrupted = false;
        for (Node node : group)
            if (node.future != null) {
                node.future.run();
                boolean waiting = true;
                while (waiting) {
                    try {
                        node.result = node.future.get();
                        waiting = false;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    } catch (ExecutionException failure) {
                        if (node.failure == null) node.failure = failure.getCause();
                        else if (node.failure != failure.getCause())
                            node.failure.addSuppressed(failure.getCause());
                        waiting = false;
                    }
                }
            }
        return interrupted;
    }

    /**
     * Commits successful systems in stable order and discards failed or unloaded command buffers.
     *
     * @param group  independent systems in stable commit order
     * @param errors existing recoverable failures, possibly null
     * @return accumulated failures
     */
    private EcsObjectException applyGroup(Node[] group, EcsObjectException errors) {
        for (Node node : group) {
            if (node.status != Node.PREPARED) {
                context.discard(node.batch);
                continue;
            }
            if (manager.isClosed()) {
                context.discard(node.batch);
                node.clear();
                continue;
            }
            if (node.failure != null) {
                EcsFailures.rethrowFatal(node.failure);
                errors = aggregate(errors, node.failure);
                node.status = Node.FAILED;
            } else {
                context.enter(node.batch);
                try {
                    node.system.apply(node.result, context, manager);
                    context.publish(node.batch);
                    node.status = Node.DONE;
                    node.pending = false;
                    node.waitingSince = 0;
                } catch (Throwable failure) {
                    EcsFailures.rethrowFatal(failure);
                    node.status = Node.FAILED;
                    errors = aggregate(errors, failure);
                } finally {
                    context.exit();
                }
            }
            context.discard(node.batch);
            node.clear();
        }
        return errors;
    }

    /**
     * Admits oldest pending optional work with its due same-phase prerequisite closure. Mandatory
     * work costs no optional slots; a dependency chain must fit the configured optional budget.
     *
     * @param phase phase whose optional starts are being selected
     */
    private void admitOptional(TickPhase phase) {
        for (Node node : ordered)
            if (node.phase == phase)
                node.admitted = node.due && (node.required || optionalBudget == Integer.MAX_VALUE);
        if (optionalBudget == Integer.MAX_VALUE) return;
        int remaining = optionalBudget;
        while (remaining > 0) {
            Node oldest = null;
            int cost = 0;
            for (Node candidate : ordered) {
                if (candidate.phase != phase
                        || !candidate.due
                        || candidate.admitted
                        || candidate.status == Node.FAILED) continue;
                int candidateCost = admissionCost(candidate, phase, ++admissionVisit, remaining);
                if (candidateCost <= remaining
                        && (oldest == null || candidate.waitingSince < oldest.waitingSince)) {
                    oldest = candidate;
                    cost = candidateCost;
                }
            }
            if (oldest == null) break;
            admitParents(oldest, phase);
            remaining -= cost;
        }
    }

    /**
     * Counts each unadmitted due predecessor at most once for a candidate.
     *
     * @param node  candidate or prerequisite
     * @param phase current phase
     * @param visit unique traversal stamp
     * @param limit remaining optional slots
     * @return required slots, or Integer.MAX_VALUE for a blocked/oversized closure
     */
    private int admissionCost(Node node, TickPhase phase, long visit, int limit) {
        if (node.status == Node.FAILED || node.status == Node.DEFERRED) return Integer.MAX_VALUE;
        if (!node.due || node.admitted) return 0;
        if (node.phase != phase)
            return node.status == Node.DONE || node.status == Node.SKIPPED ? 0 : Integer.MAX_VALUE;
        if (node.visit == visit) return 0;
        node.visit = visit;
        int cost = node.required ? 0 : 1;
        for (Node parent : node.parents) {
            int previous = admissionCost(parent, phase, visit, limit - cost);
            if (previous > limit - cost) return Integer.MAX_VALUE;
            cost += previous;
        }
        return cost <= limit ? cost : Integer.MAX_VALUE;
    }

    /**
     * Marks an admitted optional system and all its current-phase due prerequisites.
     *
     * @param node  selected system
     * @param phase current phase
     */
    private void admitParents(Node node, TickPhase phase) {
        if (!node.due || node.phase != phase || node.admitted) return;
        node.admitted = true;
        for (Node parent : node.parents) admitParents(parent, phase);
    }

    /**
     * Adds a recoverable failure without allocating an aggregate on successful ticks.
     *
     * @param errors  existing aggregate or null
     * @param failure current failure
     * @return aggregate containing this failure
     */
    private static EcsObjectException aggregate(EcsObjectException errors, Throwable failure) {
        if (errors == null) errors = new EcsObjectException("ECS system phase failed");
        errors.addSuppressed(failure);
        return errors;
    }

    /**
     * Ends the phase context after POST, including ticks with reported recoverable failures.
     */
    void end() {
        context.end();
        if (closed) {
            systems.clear();
            componentSystems.clear();
            automaticGroups = new ComponentTickSystem[0];
            ordered = new Node[0];
            phases = new Node[][][]{new Node[0][], new Node[0][], new Node[0][]};
        }
    }

    /**
     * Builds stable topological order, orients hazards along that order, and caches dependency
     * waves.
     */
    private void rebuild() {
        if (!dirty) return;
        Map<String, Node> ids = new Object2ObjectLinkedOpenHashMap<>();
        for (Node node : systems) ids.put(node.id, node);
        List<List<Node[]>> nextPhases = new ObjectArrayList<>();
        List<Node> nextOrdered = new ObjectArrayList<>();
        for (TickPhase phase : TickPhase.values()) {
            List<Node> nodes = systems.stream().filter(node -> node.phase == phase).toList();
            boolean[][] edges = new boolean[nodes.size()][nodes.size()];
            for (Node node : nodes)
                for (String dependency : node.after) {
                    Node parent = ids.get(dependency);
                    if (parent == null)
                        throw new IllegalStateException(
                                "Unknown dependency " + dependency + " of " + node.id);
                    if (parent.phase.ordinal() > phase.ordinal())
                        throw new IllegalStateException(
                                "Dependency runs in a later phase: " + dependency);
                    if (parent.phase == phase)
                        edges[nodes.indexOf(parent)][nodes.indexOf(node)] = true;
                }
            int[] order = topological(nodes, edges);
            for (int i = 0; i < order.length; i++)
                for (int j = i + 1; j < order.length; j++)
                    if (nodes.get(order[i]).access.conflictsWith(nodes.get(order[j]).access))
                        edges[order[i]][order[j]] = true;
            List<List<Node>> groups = new ObjectArrayList<>();
            int[] waves = new int[order.length];
            for (int index : order) {
                Node node = nodes.get(index);
                int wave = 0;
                List<Node> parents = new ObjectArrayList<>();
                for (int previous = 0; previous < nodes.size(); previous++)
                    if (edges[previous][index]) {
                        parents.add(nodes.get(previous));
                        wave = Math.max(wave, waves[previous] + 1);
                    }
                for (String dependency : node.after) {
                    Node parent = ids.get(dependency);
                    if (parent.phase != phase) parents.add(parent);
                }
                node.parents = parents.toArray(Node[]::new);
                waves[index] = wave;
                while (groups.size() <= wave) groups.add(new ObjectArrayList<>());
                groups.get(wave).add(node);
                nextOrdered.add(node);
            }
            nextPhases.add(groups.stream().map(group -> group.toArray(Node[]::new)).toList());
        }
        ordered = nextOrdered.toArray(Node[]::new);
        phases = new Node[nextPhases.size()][][];
        for (int i = 0; i < phases.length; i++)
            phases[i] = nextPhases.get(i).toArray(Node[][]::new);
        policyVersion = -1;
        dirty = false;
    }

    /**
     * Finds a stable topological order of explicit dependencies, preferring registration order.
     *
     * @param nodes systems of one phase
     * @param edges explicit dependency adjacency
     * @return node indices in stable dependency order
     * @throws IllegalStateException if explicit dependencies contain a cycle
     */
    private static int[] topological(List<Node> nodes, boolean[][] edges) {
        int[] result = new int[nodes.size()];
        int[] incoming = new int[nodes.size()];
        boolean[] used = new boolean[nodes.size()];
        for (boolean[] row : edges) for (int i = 0; i < row.length; i++) if (row[i]) incoming[i]++;
        for (int position = 0; position < result.length; position++) {
            int found = -1;
            for (int i = 0; i < used.length; i++)
                if (!used[i] && incoming[i] == 0) {
                    found = i;
                    break;
                }
            if (found < 0)
                throw new IllegalStateException(
                        "Cyclic ECS system dependencies: "
                                + nodes.stream().map(node -> node.id).toList());
            used[found] = true;
            result[position] = found;
            for (int i = 0; i < used.length; i++) if (edges[found][i]) incoming[i]--;
        }
        return result;
    }

    /**
     * Releases commands and system references without taking ownership of external worker
     * executors.
     */
    void close() {
        manager.checkThread();
        closed = true;
        context.close();
        // Registered nodes are retained until the current callback/phase has returned and released
        // its buffers.
        if (!manager.isTicking()) {
            systems.clear();
            componentSystems.clear();
            automaticGroups = new ComponentTickSystem[0];
            ordered = new Node[0];
            phases = new Node[][][]{new Node[0][], new Node[0][], new Node[0][]};
        }
    }

    /**
     * One reusable registration record; only the FutureTask and user snapshots/results are
     * per-dispatch objects.
     */
    private final class Node implements Callable<Object> {
        /**
         * Scheduler execution states, distinct from FutureTask's internal exactly-once computation
         * state.
         */
        static final int WAITING = 0, SKIPPED = 1, PREPARED = 2, DONE = 3, FAILED = 4, DEFERRED = 5;

        /**
         * Type-erased internal adapter; public registration preserves snapshot/result generic
         * types.
         */
        final EcsSystem<Object, Object> system;

        /**
         * Internal component-batch dispatcher, or null for a user-defined aggregate system.
         */
        final ComponentTickSystem automatic;

        /**
         * Frozen registration metadata.
         */
        final String id;

        /**
         * Frozen phase.
         */
        final TickPhase phase;

        /**
         * Frozen access declaration.
         */
        final EcsAccess access;

        /**
         * Frozen explicit dependency ids.
         */
        final List<String> after;

        /**
         * Explicit worker permission.
         */
        final boolean parallel;

        /**
         * Explicit budget-deferral permission.
         */
        final boolean deferrable;

        /**
         * Predecessors populated when dependency groups are rebuilt.
         */
        Node[] parents = new Node[0];

        /**
         * Reused private command buffer.
         */
        final EcsContext.Batch batch = new EcsContext.Batch();

        /**
         * Current scheduling status.
         */
        int status;

        /**
         * Declared and actual-class restrictions for this tick.
         */
        int flags;

        /**
         * Exact cached restrictions for the most recently scanned composition.
         */
        int resolvedFlags;

        /**
         * Whether this tick selected the system or an earlier deferred invocation remains pending.
         */
        boolean due;

        /**
         * Transitive current-tick requirement.
         */
        boolean required;

        /**
         * Coalesced pending invocation, without retaining a stale prepared snapshot.
         */
        boolean pending;

        /**
         * Whether optional admission or a mandatory requirement selected this invocation.
         */
        boolean admitted;

        /**
         * First tick awaiting admission, zero after a successful commit.
         */
        long waitingSince;

        /**
         * Last optional-closure traversal stamp.
         */
        long visit;

        /**
         * Input owned by the current compute invocation.
         */
        Object input;

        /**
         * Completed result awaiting owner-thread apply.
         */
        Object result;

        /**
         * Compute failure collected after all workers have joined.
         */
        Throwable failure;

        /**
         * Exactly-once compute task; a stale queued invocation cannot execute against a later
         * tick's input.
         */
        FutureTask<Object> future;

        /**
         * Freezes system metadata once, outside hot loops.
         *
         * @param source typed registered system
         */
        @SuppressWarnings("unchecked")
        Node(EcsSystem<?, ?> source) {
            system = (EcsSystem<Object, Object>) source;
            automatic = source instanceof ComponentTickSystem components ? components : null;
            id = Objects.requireNonNull(source.id(), "system id");
            phase = Objects.requireNonNull(source.phase(), "system phase");
            access = Objects.requireNonNull(source.access(), "system access");
            after = ObjectArrayView.copyOf(source.after());
            parallel = source.parallel();
            deferrable = source.mayDefer();
            resolvedFlags = access.flags();
        }

        /**
         * Computes against private input while binding only the command buffer and denying
         * live-world access.
         *
         * @return independent result for owner-thread application
         */
        @Override
        public Object call() {
            batch.computing = true;
            context.enter(batch);
            try {
                return system.compute(input, context);
            } finally {
                context.exit();
                batch.computing = false;
            }
        }

        /**
         * Releases per-dispatch references after every success, failure or shutdown.
         */
        void clear() {
            if (automatic != null) automatic.release();
            input = null;
            result = null;
            failure = null;
            future = null;
        }
    }
}
