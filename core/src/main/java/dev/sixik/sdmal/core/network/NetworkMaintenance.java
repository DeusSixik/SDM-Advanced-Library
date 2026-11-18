package dev.sixik.sdmal.core.network;

import dev.sixik.sdmal.SDMAdvancedLibrary;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * One daemon for request deadlines and abandoned transfer cleanup.
 */
final class NetworkMaintenance {
    /**
     * Remove-on-cancel avoids retaining completed requests until their original deadline.
     */
    static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, runnable -> {
        var thread = new Thread(runnable, "sdmal-network-maintenance");
        thread.setDaemon(true);
        return thread;
    });

    static {
        TIMER.setRemoveOnCancelPolicy(true);
    }

    /**
     * Prevents utility construction.
     */
    private NetworkMaintenance() {
    }

    /**
     * @param work bounded cleanup callback; failures do not cancel subsequent maintenance runs
     */
    static void schedule(Runnable work) {
        TIMER.scheduleWithFixedDelay(() -> {
            try {
                work.run();
            } catch (RuntimeException failure) {
                SDMAdvancedLibrary.LOGGER.error("Network maintenance failed", failure);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }
}
