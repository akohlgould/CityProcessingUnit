import logbook.Event;
import logbook.EventType;
import logbook.Logbook;
import org.junit.jupiter.api.*;
import trip.Road;
import trip.Vehicle;

import java.lang.reflect.Field;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 1, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class LogbookTest {

    private Logbook logbook;

    @BeforeEach
    void setUp() {
        logbook = new Logbook();
    }

    @Nested
    class BasicTests {

        @Test
        @SuppressWarnings("unchecked")
        public void testUsedLinkedList() throws ReflectiveOperationException {
            Field field = Logbook.class.getDeclaredField("queue");
            field.setAccessible(true);
            Queue<Event> queue = (Queue<Event>) field.get(logbook);
            assertInstanceOf(LinkedList.class, queue, "The logbook must keep its events in a LinkedList");
            logbook.record(EventType.TRIP_START);
            Event event = queue.poll();
            assertNull(queue.poll());
            queue.add(event);
            assertSame(event, logbook.poll());
        }

        @Test
        public void testAddToAndPollLog() {
            Vehicle vehicle = new Vehicle(1);
            Road road = new Road("Maple St", 10);
            Event event1 = new Event(vehicle, EventType.TRIP_START, 1);
            Event event2 = new Event(vehicle, road, EventType.ROAD_ENTER, 2);
            Event event3 = new Event(vehicle, road, EventType.ROAD_EXIT, 3);
            Event event4 = new Event(vehicle, EventType.TRIP_COMPLETE, 4);

            logbook.record(event1.getVehicle(), event1.getEventType());
            logbook.record(event2.getVehicle(), event2.getRoad(), event2.getEventType());
            assertEquals(event1, logbook.poll());
            logbook.record(event3.getVehicle(), event3.getRoad(), event3.getEventType());
            assertEquals(event2, logbook.poll());
            assertEquals(event3, logbook.poll());
            logbook.record(event4.getVehicle(), event4.getEventType());
            assertEquals(event4, logbook.poll());
            assertNull(logbook.poll());
        }

        @Test
        public void testClearLog() {
            assertEquals(0, logbook.size());
            assertEquals(0, logbook.getCurrentEventId());
            logbook.record(EventType.TRIP_START);
            logbook.record(EventType.ROAD_ENTER);
            assertEquals(2, logbook.size());
            assertEquals(2, logbook.getCurrentEventId());
            logbook.clear();
            assertEquals(0, logbook.size());
            assertEquals(0, logbook.getCurrentEventId());
            logbook.record(EventType.ROAD_EXIT);
            assertEquals(1, logbook.size());
            assertEquals(1, logbook.getCurrentEventId());
            assertEquals(new Event(EventType.ROAD_EXIT, 1), logbook.poll());
        }

        @Test
        public void testTakeFromLog() throws InterruptedException {
            LinkedBlockingQueue<Event> taken = new LinkedBlockingQueue<>();
            CountDownLatch latch = new CountDownLatch(1);
            for (int i = 0; i < 2; i++) {
                CompletableFuture.runAsync(() -> {
                    try {
                        taken.add(logbook.take());
                        latch.countDown();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            TimeUnit.MILLISECONDS.sleep(10);
            logbook.record(EventType.TRIP_START);
            assertTrue(latch.await(10, TimeUnit.MILLISECONDS), "Thread timed out before take() returned");
            TimeUnit.MILLISECONDS.sleep(10);
            assertEquals(1, taken.size());
            assertEquals(new Event(EventType.TRIP_START, 1), taken.poll());
        }

        @Test
        public void testInterruptTake() {
            Thread thread = Thread.currentThread();
            CompletableFuture.runAsync(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                thread.interrupt();
            });
            assertThrows(InterruptedException.class, logbook::take);
        }
    }

    @Nested
    class RaceConditionTests {

        private static final int numberOfThreads = 100;
        private ExecutorService executor;

        @BeforeEach
        public void setUp() {
            executor = Executors.newFixedThreadPool(numberOfThreads);
        }

        @AfterEach
        public void tearDown() {
            executor.shutdownNow();
        }

        @RepeatedTest(10)
        public void testAddToLogRace() throws Exception {
            CountDownLatch startingGate = new CountDownLatch(1);
            CountDownLatch finishingGate = new CountDownLatch(numberOfThreads);

            for (int i = 0; i < numberOfThreads; i++) {
                CompletableFuture.runAsync(() -> {
                    try {
                        startingGate.await();
                        logbook.record(EventType.TRIP_START);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finishingGate.countDown();
                    }
                }, executor);
            }

            startingGate.countDown();

            boolean completed = finishingGate.await(500, TimeUnit.MILLISECONDS);
            assertTrue(completed, "Threads timed out before completing work.");

            assertEquals(numberOfThreads, logbook.size(), "Missing or additional events in logbook.");
            assertEquals(numberOfThreads, logbook.getCurrentEventId());
            for (int i = 1; i <= numberOfThreads; i++) {
                Event event = logbook.poll();
                assertEquals(i, event.getId(), String.format("Race condition: Event with ID %d missing or in wrong place.", i));
            }
        }

        @RepeatedTest(10)
        public void testTakeFromLogRace() throws InterruptedException {
            Set<Integer> observedIds = new ConcurrentSkipListSet<>();
            CountDownLatch startingGate = new CountDownLatch(1);
            CountDownLatch finishingGate = new CountDownLatch(numberOfThreads);

            // Spin up and start worker threads
            for (int i = 0; i < numberOfThreads; i++) {
                logbook.record(EventType.TRIP_START);
                CompletableFuture.runAsync(() -> {
                    try {
                        startingGate.await();
                        Event event = logbook.take();
                        observedIds.add(event.getId());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finishingGate.countDown();
                    }
                }, executor);
            }

            startingGate.countDown();
            assertTrue(finishingGate.await(500, TimeUnit.MILLISECONDS), "Threads timed out before completing work.");

            assertEquals(numberOfThreads, observedIds.size(), "Missing or additional observed ids.");
            for (int i = 1; i <= numberOfThreads; i++) {
                assertTrue(observedIds.contains(i), String.format("Race condition: Event with ID %d missing.", i));
            }
        }

        @RepeatedTest(10)
        public void testPollFromLogRace() throws InterruptedException {
            Set<Integer> observedIds = new ConcurrentSkipListSet<>();
            AtomicInteger nullCount = new AtomicInteger(0);
            CountDownLatch startingGate = new CountDownLatch(1);
            CountDownLatch finishingGate = new CountDownLatch(numberOfThreads);

            for (int i = 0; i < numberOfThreads; i++) {
                logbook.record(EventType.TRIP_START);
                CompletableFuture.runAsync(() -> {
                    try {
                        startingGate.await();
                        Event event = logbook.poll();
                        if (event != null) {
                            observedIds.add(event.getId());
                        } else {
                            nullCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finishingGate.countDown(); // Signal this thread is done
                    }
                }, executor);
            }

            startingGate.countDown();
            assertTrue(finishingGate.await(500, TimeUnit.MILLISECONDS), "Threads timed out before completing work.");

            assertEquals(numberOfThreads, observedIds.size(), "Missing or additional observed ids.");
            assertEquals(0, nullCount.get());
            for (int i = 1; i <= numberOfThreads; i++) {
                assertTrue(observedIds.contains(i), String.format("Race condition: Event with ID %d missing.", i));
            }
        }
    }
}
