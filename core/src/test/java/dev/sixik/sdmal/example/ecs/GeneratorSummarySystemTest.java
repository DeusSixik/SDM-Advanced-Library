package dev.sixik.sdmal.example.ecs;

import dev.sixik.sdmal.api.ecs.EcsObjectManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Ensures the copyable parallel example uses detached data and respects its own scheduling and activity filter. */
class GeneratorSummarySystemTest {
    /** NORMAL observes preceding state, while automatic generator batches commit in POST. */
    @Test void summary_readsOnlyAwakeGeneratorsAndPublishesInItsScheduledTick() {
        dev.sixik.sdmal.api.ecs.ComponentRegistry.instance().registerAnnotated(GeneratorComponent.class);
        try (var manager = new EcsObjectManager()) {
            var awake = manager.create();
            awake.attach(new GeneratorComponent());
            var asleep = manager.create();
            asleep.attach(new GeneratorComponent());
            asleep.sleep();
            var summary = new GeneratorSummarySystem();
            manager.scheduler().register(summary);
            manager.tickAll();
            assertEquals(0, summary.totalStored());
            for (int i = 0; i < 20; i++) manager.tickAll();
            assertEquals(20, summary.totalStored());
            assertEquals(0, asleep.getSingle(GeneratorComponent.class).orElseThrow().battery().stored());
            assertEquals(21, awake.getSingle(GeneratorComponent.class).orElseThrow().battery().stored());
        }
    }
}
