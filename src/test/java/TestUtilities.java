import driver.Clock;

import java.util.concurrent.*;

/**
 * Helpers for checking whether a call that may block returns in time.
 */
public class TestUtilities {

    /**
     * Calls the given function on another thread and waits up to the given time for it to return.
     * If it does not return in time, its thread is interrupted.
     *
     * @return true if the function returned in time, otherwise false
     */
    public static boolean callWithTimeout(Callable<?> function, int time, TimeUnit unit) {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> future = executor.submit(() -> {
                try {
                    function.call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            try {
                future.get(time, unit);
                return true;
            } catch (TimeoutException e) {
                future.cancel(true);
                return false;
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static boolean startTickWithTimeout(Clock clock, int time, TimeUnit unit) {
        return callWithTimeout(() -> {
            clock.startNextTick();
            return null;
        }, time, unit);
    }

    public static boolean waitForTickWithTimeout(Clock clock, int time, TimeUnit unit) {
        return callWithTimeout(() -> {
            clock.waitForTick();
            return null;
        }, time, unit);
    }
}
