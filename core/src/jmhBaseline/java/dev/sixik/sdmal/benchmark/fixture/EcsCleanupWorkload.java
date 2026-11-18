package dev.sixik.sdmal.benchmark.fixture;

import dev.sixik.sdmal.api.ecs.*;
import dev.sixik.sdmal.api.ecs.annotations.*;
import dev.sixik.sdmal.api.ecs.sync.*;
import dev.sixik.sdmal.benchmark.EcsCleanupBenchmark.Workload;

import io.netty.buffer.Unpooled;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Identical fixture bytecode for the frozen and current ECS. Setup allocations are outside JMH
 * measurement.
 */
public final class EcsCleanupWorkload implements Workload {
    /**
     * Requested benchmark operation.
     */
    private final String kind;

    /**
     * Number of attached components or serialized values.
     */
    private final int count;

    /**
     * Arithmetic rounds in each automatic component.
     */
    private final int rounds;

    /**
     * Trial-owned world manager.
     */
    private final WorldObjectManager manager = new WorldObjectManager();

    /**
     * Optional fixed two-worker executor, never shared between trials.
     */
    private ExecutorService executor;

    /**
     * First observed automatic component.
     */
    private Counter first;

    /**
     * First observed legacy component.
     */
    private Legacy legacy;

    /**
     * Stable objects used to measure typed component queries.
     */
    private WorldObject[] queryObjects;

    /**
     * Counter changed only by phase command callbacks.
     */
    private long commands;

    /**
     * Reused callback, excluding user closure allocation from queue measurements.
     */
    private final Runnable command = () -> commands++;

    /**
     * Binary/NBT component used by serialization workloads.
     */
    private Payload payload;

    /**
     * Independent target used by staged reads.
     */
    private Payload target;

    /**
     * Revisioned fields used by delta measurements.
     */
    private Delta delta;

    /**
     * Sender revisions retained across packets.
     */
    private SyncedWorldObjectComponent.DeltaTracker tracker;

    /** Independent receiver for repeated full changed-field decode measurements. */
    private Delta receiving;

    /** Receiver revisions reset to the previous frame without allocating a new tracker. */
    private SyncedWorldObjectComponent.DeltaTracker received;

    /**
     * Reused packet buffer; allocation/growth happens during setup.
     */
    private FriendlyByteBuf buffer;

    /**
     * Immutable NBT input retained across reads.
     */
    private CompoundTag saved;

    /** Last encoded NBT graph, retained so the encoder's field writes remain observable. */
    private CompoundTag lastOutput;

    /**
     * Prepares one deterministic workload with no live Minecraft world dependency.
     *
     * @param specification kind:count:arithmeticRounds
     */
    public EcsCleanupWorkload(String specification) {
        String[] parts = specification.split(":");
        kind = parts[0];
        count = Integer.parseInt(parts[1]);
        rounds = Integer.parseInt(parts[2]);
        switch (kind) {
            case "owner", "worker" -> setupAutomatic();
            case "legacy" -> {
                for (int i = 0; i < count; i++) {
                    Legacy component = manager.create().attach(new Legacy());
                    if (i == 0) legacy = component;
                }
            }
            case "queries" -> {
                queryObjects = new WorldObject[count];
                for (int i = 0; i < count; i++) {
                    WorldObject object = manager.create();
                    queryObjects[i] = object;
                    for (int j = 0; j < 8; j++) object.attach(new Legacy());
                }
            }
            case "commands" -> {}
            case "delta", "deltaEmpty", "deltaSparse", "deltaRead" -> {
                delta = new Delta(count);
                tracker = new SyncedWorldObjectComponent.DeltaTracker();
                buffer = new FriendlyByteBuf(Unpooled.buffer(8192));
                if (kind.equals("deltaRead")) {
                    for (int i = 0; i < delta.fields.length; i++) delta.fields[i].set(1);
                    receiving = new Delta(count);
                    received = new SyncedWorldObjectComponent.DeltaTracker(count);
                }
                delta.writeDelta(buffer, tracker);
            }
            default -> {
                payload = new Payload();
                target = new Payload();
                for (int i = 0; i < count; i++) payload.values.add(1000 + i);
                buffer = new FriendlyByteBuf(Unpooled.buffer(65536));
                payload.writeNetwork(buffer);
                saved = new CompoundTag();
                payload.writeNbt(saved);
                target.readNetwork(buffer);
                buffer.readerIndex(0);
            }
        }
    }

    /**
     * Creates automatic components and freezes their first scheduler plan.
     */
    private void setupAutomatic() {
        boolean workers = kind.equals("worker");
        if (workers) {
            executor = Executors.newFixedThreadPool(2);
            manager.scheduler().setExecutor(executor);
        }
        for (int i = 0; i < count; i++) {
            Counter counter = workers ? new Counter(rounds) : new OwnerCounter(rounds);
            manager.create().attach(counter);
            if (i == 0) first = counter;
        }
        manager.scheduler().validatePlan();
    }

    /**
     * Executes one operation and observes resulting state.
     *
     * @return deterministic result shared by both implementations
     */
    @Override
    public long run() {
        return switch (kind) {
            case "owner", "worker" -> {
                manager.tickAll();
                yield first.value;
            }
            case "legacy" -> {
                manager.tickAll();
                yield legacy.value;
            }
            case "queries" -> {
                long matches = 0;
                for (int i = 0; i < queryObjects.length; i++)
                    matches += queryObjects[i].components().size();
                yield matches;
            }
            case "commands" -> {
                WorldContext context = manager.worldContext();
                for (int i = 0; i < count; i++) context.enqueue(TickPhase.POST, command);
                manager.tickAll();
                yield commands;
            }
            case "wireWrite" -> {
                buffer.clear();
                payload.writeNetwork(buffer);
                yield buffer.writerIndex();
            }
            case "wireRead" -> {
                buffer.readerIndex(0);
                target.readNetwork(buffer);
                yield target.values.get(count - 1);
            }
            case "nbtWrite" -> {
                CompoundTag output = new CompoundTag();
                payload.writeNbt(output);
                lastOutput = output;
                yield output.size();
            }
            case "nbtRead" -> {
                target.readNbt(saved);
                yield target.values.get(count - 1);
            }
            case "delta" -> {
                for (int i = 0; i < delta.fields.length; i++)
                    delta.fields[i].set((int) ((manager.lastTick() + commands) & 1));
                commands++;
                buffer.clear();
                delta.writeDelta(buffer, tracker);
                yield buffer.writerIndex();
            }
            case "deltaEmpty", "deltaSparse" -> {
                if (kind.equals("deltaSparse")) delta.fields[0].set((int) (++commands & 1));
                buffer.clear();
                delta.writeDelta(buffer, tracker);
                yield buffer.writerIndex();
            }
            case "deltaRead" -> {
                for (int i = 0; i < count; i++) received.mark(i, 0);
                buffer.readerIndex(0);
                receiving.readDelta(buffer, received);
                yield receiving.fields[count - 1].get();
            }
            default -> throw new IllegalArgumentException(kind);
        };
    }

    /**
     * Hashes every committed component and the full serialized content outside measured operations.
     * @return deterministic content hash independent of world/object identities
     */
    @Override
    public long verificationHash() {
        long result = 1;
        for (WorldObject object : manager.snapshot()) {
            for (WorldObjectComponent component : object.components()) {
                if (component instanceof Counter counter) result = 31 * result + counter.value;
                if (component instanceof Legacy serial) result = 31 * result + serial.value;
            }
        }
        if (payload != null) {
            CompoundTag encoded = new CompoundTag();
            payload.writeNbt(encoded);
            result = 31 * result + encoded.hashCode();
            result = 31 * result + target.values.hashCode();
            if (lastOutput != null) result = 31 * result + lastOutput.hashCode();
        }
        if (buffer != null) {
            for (int index = 0; index < buffer.writerIndex(); index++)
                result = 31 * result + buffer.getByte(index);
        }
        return 31 * result + commands;
    }

    /**
     * Releases owned resources, including external executors.
     */
    @Override
    public void close() {
        manager.close();
        if (executor != null) executor.shutdownNow();
        if (buffer != null) buffer.release();
    }

    /**
     * Reusable primitive snapshot/result owned by one component.
     */
    private static final class Data {
        /**
         * Owner-thread input.
         */
        int input;

        /**
         * Worker-computed output.
         */
        int output;
    }

    /**
     * Automatic component with immutable configuration and independent input/result storage.
     */
    private static class Counter extends AbstractWorldObjectComponent
            implements SnapshotTickableComponent<Data, Data> {
        /**
         * Committed owner-thread value.
         */
        int value;

        /**
         * Immutable amount of work per dispatch.
         */
        private final int rounds;

        /**
         * Independently owned snapshot/result reused after the phase barrier.
         */
        private final Data data = new Data();

        /**
         * @param rounds arithmetic rounds per dispatch
         */
        Counter(int rounds) {
            this.rounds = rounds;
        }

        /**
         * @param context owner context
         * @return copied primitive state
         */
        @Override
        public Data capture(WorldContext context) {
            data.input = value;
            return data;
        }

        /**
         * @param input independent state
         * @param context restricted context
         * @return calculated state
         */
        @Override
        public Data compute(Data input, WorldContext context) {
            int next = input.input + 1;
            for (int i = 0; i < rounds; i++)
                next = Integer.rotateLeft(next * 1664525 + 1013904223, 7);
            input.output = next;
            return input;
        }

        /**
         * @param result completed state
         * @param context owner context
         */
        @Override
        public void apply(Data result, WorldContext context) {
            value = result.output;
        }
    }

    /**
     * Equivalent computation restricted to the owner thread.
     */
    @EcsNoThreadSafe
    private static final class OwnerCounter extends Counter {
        /**
         * @param rounds arithmetic rounds per dispatch
         */
        OwnerCounter(int rounds) {
            super(rounds);
        }
    }

    /**
     * Legacy callback with no per-tick state allocation.
     */
    private static final class Legacy extends AbstractWorldObjectComponent
            implements TickableComponent {
        /**
         * Committed serial counter.
         */
        int value;

        /**
         * @param tick current manager tick
         */
        @Override
        public void onTick(long tick) {
            value++;
        }
    }

    /**
     * Component with scalar values and a list whose decoded ownership remains defensive.
     */
    private static final class Payload extends AnnotatedWorldObjectComponent {
        /**
         * Mutable consumer-owned values, never mutated by serialization.
         */
        @EcsPersist("values")
        @EcsSync("values")
        private List<Integer> values = new ArrayList<>();

        /**
         * Uncached boxed scalar exercises primitive field access.
         */
        @EcsPersist("scalar")
        @EcsSync("scalar")
        private int scalar = 1357;
    }

    /**
     * Revisioned fields exercising changed-field collection and packet encoding.
     */
    private static final class Delta extends SyncedWorldObjectComponent {
        /**
         * Fixed field handles, created outside measured operations.
         */
        final TrackedField<Integer>[] fields;

        /**
         * @param count number of fields with cached boxed values
         */
        @SuppressWarnings("unchecked")
        Delta(int count) {
            fields = new TrackedField[count];
            for (int i = 0; i < fields.length; i++)
                fields[i] = tracked("f" + i, FieldCodecs.intValue(), 0);
        }
    }
}
