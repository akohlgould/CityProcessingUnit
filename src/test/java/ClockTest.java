import driver.Clock;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 1, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class ClockTest {

    private Clock clock;

    @BeforeEach
    public void setUp() {
        clock = new Clock();
    }

    @AfterEach
    public void tearDown() {
        clock.stopClock();
    }

    @Nested
    class BasicTests {

        @Test
        public void testZeroWaiters() {
            assertFalse(TestUtilities.startTickWithTimeout(clock, 10, TimeUnit.MILLISECONDS),
                    "A tick should not start while there are no waiters");
            assertEquals(0, clock.getTickNum());
        }

        @Test
        public void testBasicFunctionality() throws InterruptedException {
            CountDownLatch[] tickStarts = {new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};
            CountDownLatch[] tickEnds = {new CountDownLatch(1), new CountDownLatch(2), new CountDownLatch(1)};
            CountDownLatch waiterGate = new CountDownLatch(1);

            ExecutorService executor = Executors.newCachedThreadPool();
            try (executor) {
                CompletableFuture.runAsync(() -> {
                    clock.signalNewWaiter();
                    try {
                        for (int i = 0; i < tickStarts.length; i++) {
                            tickStarts[i].await();
                            clock.waitForTick();
                            tickEnds[i].countDown();
                        }
                        clock.signalRemoveWaiter();
                    } catch (InterruptedException ignored) {
                    }
                }, executor);
                CompletableFuture.runAsync(() -> {
                    try {
                        tickEnds[0].await();
                        clock.signalNewWaiter();
                        waiterGate.await();
                        clock.waitForTick();
                        clock.signalRemoveWaiter();
                        tickEnds[1].countDown();
                    } catch (InterruptedException ignored) {
                    }
                }, executor);
                try {
                    assertEquals(0, clock.getTickNum());
                    tickStarts[0].countDown();
                    assertTrue(TestUtilities.startTickWithTimeout(clock, 50, TimeUnit.MILLISECONDS));
                    assertTrue(tickEnds[0].await(50, TimeUnit.MILLISECONDS), "Thread timed out before tick 1 finished");
                    assertEquals(1, clock.getTickNum());
                    tickStarts[1].countDown();
                    assertFalse(TestUtilities.startTickWithTimeout(clock, 10, TimeUnit.MILLISECONDS),
                            "Tick 2 should not start when 1 of 2 waiters are waiting");
                    assertEquals(1, clock.getTickNum());
                    waiterGate.countDown();
                    assertTrue(TestUtilities.startTickWithTimeout(clock, 50, TimeUnit.MILLISECONDS),
                            "Tick 2 should start when 2 of 2 waiters are waiting");
                    assertTrue(tickEnds[1].await(50, TimeUnit.MILLISECONDS), "Thread timed out before tick 2 finished");
                    assertEquals(2, clock.getTickNum());
                    tickStarts[2].countDown();
                    assertTrue(TestUtilities.startTickWithTimeout(clock, 50, TimeUnit.MILLISECONDS),
                            "Tick 3 should start when 1 of 1 waiters are waiting");
                    assertTrue(tickEnds[2].await(50, TimeUnit.MILLISECONDS), "Thread timed out before tick 3 finished");
                    assertEquals(3, clock.getTickNum());
                } finally {
                    executor.shutdownNow();
                }
            }
        }

        @Test
        public void testRemoveWaiter() {
            clock.signalNewWaiter();
            clock.signalNewWaiter();
            CompletableFuture.runAsync(() -> {
                try {
                    clock.waitForTick();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            assertFalse(TestUtilities.startTickWithTimeout(clock, 10, TimeUnit.MILLISECONDS),
                    "Tick should not start when 1 of 2 waiters are waiting");
            clock.signalRemoveWaiter();
            assertTrue(TestUtilities.startTickWithTimeout(clock, 50, TimeUnit.MILLISECONDS),
                    "Tick should start when 1 of 1 waiters are waiting");
        }

        @Test
        public void testRemovingAWaiterLetsTheTickStart() throws InterruptedException {
            clock.signalNewWaiter();
            clock.signalNewWaiter();
            CompletableFuture.runAsync(() -> {
                try {
                    clock.waitForTick();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            CountDownLatch tickStarted = new CountDownLatch(1);
            CompletableFuture.runAsync(() -> {
                try {
                    clock.startNextTick();
                    tickStarted.countDown();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            assertFalse(tickStarted.await(20, TimeUnit.MILLISECONDS), "Tick should not start when 1 of 2 waiters are waiting");
            clock.signalRemoveWaiter();
            assertTrue(tickStarted.await(100, TimeUnit.MILLISECONDS),
                    "A tick that is waiting should start as soon as removing a waiter leaves every waiter waiting");
        }

        @Test
        public void testStartAndStopClock() {
            clock.startClock();
            clock.signalNewWaiter();
            for (int i = 0; i < 10; i++) {
                assertTrue(TestUtilities.waitForTickWithTimeout(clock, 50, TimeUnit.MILLISECONDS),
                        "Clock is not running in a separate thread");
            }
            assertEquals(10, clock.getTickNum());
            clock.startClock(); // Should do nothing
            clock.stopClock();
            assertFalse(TestUtilities.waitForTickWithTimeout(clock, 10, TimeUnit.MILLISECONDS),
                    "Clock should no longer be running in a separate thread");
            assertEquals(10, clock.getTickNum());
        }

        @Test
        public void testInterruptStartNextTick() {
            Thread thread = Thread.currentThread();
            CompletableFuture.runAsync(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                thread.interrupt();
            });
            assertThrows(InterruptedException.class, clock::startNextTick);
        }

        @Test
        public void testInterruptWaitForTick() {
            Thread thread = Thread.currentThread();
            CompletableFuture.runAsync(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                thread.interrupt();
            });
            clock.signalNewWaiter();
            assertThrows(InterruptedException.class, clock::waitForTick);
        }
    }

    @Nested
    class AlarmTests {

        @Test
        public void testAlarmsRunAtTheStartOfTheirTick() throws InterruptedException {
            List<Integer> ran = Collections.synchronizedList(new ArrayList<>());
            clock.setAlarmAt(1, () -> ran.add(clock.getTickNum()));
            clock.setAlarmAt(1, () -> ran.add(-1));
            clock.setAlarmAt(3, () -> ran.add(clock.getTickNum()));
            assertTrue(clock.hasAlarms());

            clock.signalNewWaiter();
            clock.startClock();
            clock.waitForTick();
            assertEquals(List.of(1, -1), ran, "Alarms for tick 1 should run, in the order they were set, "
                    + "before waitForTick() returns");
            assertTrue(clock.hasAlarms());
            clock.waitForTick();
            assertEquals(List.of(1, -1), ran);
            clock.waitForTick();
            assertEquals(List.of(1, -1, 3), ran);
            assertFalse(clock.hasAlarms());
        }

        @Test
        public void testAlarmsRunBeforeAnyWaiterContinues() throws InterruptedException {
            int numWaiters = 5;
            AtomicInteger continued = new AtomicInteger();
            AtomicInteger continuedWhenAlarmRan = new AtomicInteger(-1);
            clock.setAlarmAt(1, () -> continuedWhenAlarmRan.set(continued.get()));
            CountDownLatch done = new CountDownLatch(numWaiters);
            for (int i = 0; i < numWaiters; i++) {
                clock.signalNewWaiter();
                CompletableFuture.runAsync(() -> {
                    try {
                        clock.waitForTick();
                        continued.incrementAndGet();
                        clock.signalRemoveWaiter();
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            clock.startClock();
            assertTrue(done.await(500, TimeUnit.MILLISECONDS), "Waiters timed out before tick 1 started");
            assertEquals(0, continuedWhenAlarmRan.get(), "The tick's alarms should run before any waiter continues");
            assertEquals(numWaiters, continued.get());
        }

        @Test
        public void testSetAlarmCountsFromTheCurrentTick() throws InterruptedException {
            AtomicBoolean ran = new AtomicBoolean();
            clock.signalNewWaiter();
            clock.startClock();
            clock.waitForTicks(3);
            clock.setAlarm(2, () -> ran.set(clock.getTickNum() == 5));
            clock.waitForTick();
            assertFalse(ran.get());
            clock.waitForTick();
            assertTrue(ran.get(), "An alarm set at tick 3 for 2 ticks from now should run at tick 5");
        }

        @Test
        public void testAlarmMustBeInTheFuture() throws InterruptedException {
            clock.signalNewWaiter();
            clock.startClock();
            clock.waitForTick();
            assertThrows(IllegalArgumentException.class, () -> clock.setAlarmAt(1, () -> {}));
            assertThrows(IllegalArgumentException.class, () -> clock.setAlarmAt(0, () -> {}));
            assertThrows(IllegalArgumentException.class, () -> clock.setAlarm(0, () -> {}));
            assertFalse(clock.hasAlarms());
        }
    }

    @Nested
    class RaceConditionTests {
        private static final int numberOfThreads = 100;
        private static final int numberOfTicks = 100;
        private ExecutorService executor;

        @BeforeEach
        public void setUp() {
            executor = Executors.newFixedThreadPool(numberOfThreads);
        }

        @AfterEach
        public void tearDown() {
            executor.shutdownNow();
        }

        @RepeatedTest(3)
        public void testRunManyTicks() throws InterruptedException {
            ConcurrentHashMap<Integer, AtomicInteger> tickCounts = new ConcurrentHashMap<>();
            ConcurrentHashMap<Integer, AtomicInteger> errorCounts = new ConcurrentHashMap<>();
            for (int i = 0; i < numberOfTicks; i++) {
                tickCounts.put(i, new AtomicInteger(0));
                errorCounts.put(i, new AtomicInteger(0));
            }

            CountDownLatch startingGate = new CountDownLatch(1);
            CountDownLatch finishingGate = new CountDownLatch(numberOfThreads);

            for (int i = 0; i < numberOfThreads; i++) {
                clock.signalNewWaiter();
                CompletableFuture.runAsync(() -> {
                    try {
                        startingGate.await();
                        for (int tick = 0; tick < numberOfTicks; tick++) {
                            clock.waitForTick();
                            if (tick > 0 && tickCounts.get(tick - 1).get() != numberOfThreads) {
                                errorCounts.get(tick - 1).incrementAndGet();
                            }
                            tickCounts.get(tick).incrementAndGet();
                        }
                        clock.signalRemoveWaiter();
                    } catch (InterruptedException ignored) {
                    } finally {
                        finishingGate.countDown();
                    }
                }, executor);
            }

            startingGate.countDown();
            for (int i = 0; i < numberOfTicks; i++) {
                assertTrue(TestUtilities.startTickWithTimeout(clock, 50, TimeUnit.MILLISECONDS), "Tick did not start");
            }
            assertTrue(finishingGate.await(500, TimeUnit.MILLISECONDS), "Threads timed out before completing work.");

            assertTrue(tickCounts.values().stream().allMatch((count) -> count.get() == numberOfThreads),
                    "Race condition: not all threads reached every tick");
            assertTrue(errorCounts.values().stream().allMatch((count) -> count.get() == 0),
                    "Race condition: not all threads finished tick before the next tick started");
        }

        @RepeatedTest(10)
        public void testSetAlarmsFromManyThreads() throws InterruptedException {
            int alarmsPerThread = 20;
            AtomicInteger ran = new AtomicInteger();
            CountDownLatch startingGate = new CountDownLatch(1);
            CountDownLatch finishingGate = new CountDownLatch(numberOfThreads);
            for (int i = 0; i < numberOfThreads; i++) {
                int tick = 1 + i % 3;
                CompletableFuture.runAsync(() -> {
                    try {
                        startingGate.await();
                        for (int j = 0; j < alarmsPerThread; j++) {
                            clock.setAlarmAt(tick, ran::incrementAndGet);
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        finishingGate.countDown();
                    }
                }, executor);
            }
            startingGate.countDown();
            assertTrue(finishingGate.await(500, TimeUnit.MILLISECONDS), "Threads timed out before setting alarms.");

            clock.signalNewWaiter();
            clock.startClock();
            clock.waitForTicks(3);
            assertEquals(numberOfThreads * alarmsPerThread, ran.get(),
                    "Race condition: alarms set at the same time were lost or run twice");
            assertFalse(clock.hasAlarms());
        }
    }
}
