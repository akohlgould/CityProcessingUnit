import dispatcher.Dispatcher;
import dispatcher.policies.FirstInFirstOut;
import driver.Clock;
import driver.Driver;
import driver.Message;
import logbook.Event;
import logbook.EventType;
import logbook.Logbook;
import org.junit.jupiter.api.*;
import trip.Road;
import trip.Route;
import trip.Trip;
import trip.Tunnel;
import trip.Vehicle;
import trip.stoppoints.Destination;
import trip.stoppoints.StopSign;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 1, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class TunnelTests {

    private static final Map<Vehicle.Type, Integer> capacities = Map.of(Vehicle.Type.CAR, 3, Vehicle.Type.TRUCK, 1);
    private static final Vehicle[] cars = new Vehicle[capacities.get(Vehicle.Type.CAR) + 1];
    private static final Vehicle[] trucks = new Vehicle[capacities.get(Vehicle.Type.TRUCK) + 1];

    private Clock clock;
    private Logbook logbook;
    private Driver driver;
    private Queue<Message> messages;
    private Tunnel tunnel;

    @BeforeAll
    public static void initVehicles() {
        for (int i = 0; i < cars.length; i++) {
            cars[i] = new Vehicle(i + 1, Vehicle.Type.CAR);
        }
        for (int i = 0; i < trucks.length; i++) {
            trucks[i] = new Vehicle(i + 1 + cars.length, Vehicle.Type.TRUCK);
        }
    }

    @BeforeEach
    public void setUp() {
        clock = new Clock();
        logbook = new Logbook();
        driver = new Driver(clock, logbook);
        messages = driver.getMessageQueue();
        tunnel = new Tunnel("East Tunnel", 10, capacities);
    }

    @AfterEach
    public void tearDown() {
        clock.stopClock();
    }

    /**
     * Makes the test thread take part in the ticks, so it can wait for the next tick with {@code clock.waitForTick()}.
     */
    private void startClock() {
        clock.signalNewWaiter();
        clock.startClock();
    }

    private void assertLogged(Vehicle vehicle, EventType type) {
        Event event = logbook.poll();
        assertNotNull(event, "Expected " + vehicle + " " + type + " in the logbook, but it is empty");
        assertTrue(new Event(vehicle, tunnel, type, 0).weakEquals(event),
                "Expected " + vehicle + " " + type + ", but the logbook has " + event);
    }

    @Nested
    class SingleDriverTests {

        @Test
        public void testCapacityAndTypes() {
            for (int i = 0; i < cars.length; i++) {
                boolean shouldEnter = i < capacities.get(Vehicle.Type.CAR);
                assertEquals(shouldEnter, tunnel.enter(driver, cars[i]), "The tunnel holds at most 3 cars");
                assertLogged(cars[i], shouldEnter ? EventType.TUNNEL_ENTER : EventType.TUNNEL_ENTER_FAILED);
            }
            for (Vehicle truck : trucks) {
                assertFalse(tunnel.enter(driver, truck), "A truck cannot enter while there are cars in the tunnel");
                assertLogged(truck, EventType.TUNNEL_ENTER_FAILED);
            }
            for (int i = cars.length - 1; i > 0; i--) {
                tunnel.exit(driver, cars[i]); // Exit all except cars[0]
                if (i < capacities.get(Vehicle.Type.CAR)) {
                    assertLogged(cars[i], EventType.TUNNEL_EXIT);
                } else {
                    assertNull(logbook.poll(), "A vehicle that is not in the tunnel cannot exit it");
                }
            }
            assertFalse(tunnel.enter(driver, trucks[0]), "A truck cannot enter while there is a car in the tunnel");
            assertLogged(trucks[0], EventType.TUNNEL_ENTER_FAILED);
            tunnel.exit(driver, cars[1]); // Nothing should happen if a vehicle tries to exit a tunnel it is not in
            assertNull(logbook.poll());
            tunnel.exit(driver, cars[0]);
            assertLogged(cars[0], EventType.TUNNEL_EXIT);
            for (int i = 0; i < trucks.length; i++) {
                boolean shouldEnter = i < capacities.get(Vehicle.Type.TRUCK);
                assertEquals(shouldEnter, tunnel.enter(driver, trucks[i]), "An empty tunnel holds at most 1 truck");
                assertLogged(trucks[i], shouldEnter ? EventType.TUNNEL_ENTER : EventType.TUNNEL_ENTER_FAILED);
            }
            assertFalse(tunnel.enter(driver, cars[0]), "A car cannot enter while there is a truck in the tunnel");
        }

        @Test
        public void testRedLightWhenRefused() {
            for (int i = 0; i < capacities.get(Vehicle.Type.CAR); i++) {
                assertTrue(tunnel.enter(driver, cars[i]));
                assertTrue(messages.isEmpty(), "A vehicle that enters gets no message");
            }
            tunnel.enter(driver, cars[cars.length - 1]);
            assertEquals(new Message(cars[cars.length - 1].id(), Message.Type.RED_LIGHT), messages.poll(),
                    "A refused vehicle's driver gets a RED_LIGHT message right away");
            tunnel.enter(driver, trucks[0]);
            assertEquals(new Message(trucks[0].id(), Message.Type.RED_LIGHT), messages.poll());
            assertTrue(messages.isEmpty());
        }

        @Test
        public void testGreenLightAtTheNextTick() throws InterruptedException {
            startClock();
            for (int i = 0; i < capacities.get(Vehicle.Type.CAR); i++) {
                tunnel.enter(driver, cars[i]);
            }
            tunnel.enter(driver, cars[cars.length - 1]);
            tunnel.enter(driver, trucks[0]);
            messages.clear();

            tunnel.exit(driver, cars[cars.length - 1]); // Not in the tunnel, so nothing happens
            clock.waitForTick();
            assertTrue(messages.isEmpty(), "No green light until a vehicle leaves the tunnel");

            tunnel.exit(driver, cars[0]);
            assertTrue(messages.isEmpty(), "The green lights are sent at the start of the next tick, not right away");
            assertTrue(clock.hasAlarms(), "The tunnel should set alarms on the clock for the green lights");
            clock.waitForTick();
            assertEquals(new Message(cars[cars.length - 1].id(), Message.Type.GREEN_LIGHT), messages.poll());
            assertEquals(new Message(trucks[0].id(), Message.Type.GREEN_LIGHT), messages.poll());
            assertTrue(messages.isEmpty());

            tunnel.exit(driver, cars[1]);
            clock.waitForTick();
            assertTrue(messages.isEmpty(), "Each refused vehicle gets only one green light");
        }

        @Test
        public void testGreenLightGoesToTheWaitingVehiclesDriver() throws InterruptedException {
            startClock();
            Driver otherDriver = new Driver(clock, logbook);
            tunnel.enter(driver, trucks[0]);
            tunnel.enter(otherDriver, cars[0]);
            assertTrue(messages.isEmpty());
            assertEquals(new Message(cars[0].id(), Message.Type.RED_LIGHT), otherDriver.getMessageQueue().poll());

            tunnel.exit(driver, trucks[0]);
            clock.waitForTick();
            assertTrue(messages.isEmpty(), "The green light goes to the waiting vehicle's driver");
            assertEquals(new Message(cars[0].id(), Message.Type.GREEN_LIGHT), otherDriver.getMessageQueue().poll());
        }

        @Test
        public void testStopPointOnExit() {
            Tunnel withStopSign = new Tunnel("North Tunnel", 2, new StopSign(), capacities);
            withStopSign.exit(driver, cars[0]);
            assertTrue(messages.isEmpty());
            withStopSign.enter(driver, cars[0]);
            assertTrue(messages.isEmpty());
            withStopSign.exit(driver, cars[0]);
            assertEquals(new Message(cars[0].id(), Message.Type.STOP_SIGN), messages.poll(),
                    "Leaving the tunnel passes through its stop point, like the end of any road");
            assertTrue(messages.isEmpty());
        }

        @Test
        public void testDrivingThroughTheTunnel() throws InterruptedException {
            startClock();
            Tunnel shortTunnel = new Tunnel("Short Tunnel", 2, new Destination(), capacities);
            Route route = new Route("Through the tunnel", List.of(new Road("1", 1), shortTunnel));
            Trip trip = new Trip(route, trucks[0]);
            Dispatcher dispatcher = new Dispatcher(driver, new FirstInFirstOut());
            dispatcher.addNewTrip(trip);
            driver.driveAllTrips();

            assertSame(trip, dispatcher.getFinished().getFirst());
            assertEquals(new Event(trucks[0], EventType.TRIP_START, 1), logbook.poll());
            assertEquals(new Event(trucks[0], route.getRoad(0), EventType.ROAD_ENTER, 2), logbook.poll());
            assertEquals(new Event(trucks[0], route.getRoad(0), EventType.ROAD_EXIT, 3), logbook.poll());
            assertEquals(new Event(trucks[0], shortTunnel, EventType.TUNNEL_ENTER, 4), logbook.poll());
            assertEquals(new Event(trucks[0], shortTunnel, EventType.TUNNEL_EXIT, 5), logbook.poll());
            assertEquals(new Event(trucks[0], EventType.TRIP_COMPLETE, 6), logbook.poll());
            assertNull(logbook.poll());
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
        public void testEnterRace() throws InterruptedException {
            CountDownLatch startingGate = new CountDownLatch(1);
            CountDownLatch finishingGate = new CountDownLatch(numberOfThreads);
            AtomicInteger entered = new AtomicInteger(0);
            for (int i = 0; i < numberOfThreads; i++) {
                Vehicle car = new Vehicle(i + 1, Vehicle.Type.CAR);
                CompletableFuture.runAsync(() -> {
                    try {
                        startingGate.await();
                        if (tunnel.enter(driver, car)) {
                            entered.incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        finishingGate.countDown();
                    }
                }, executor);
            }
            startingGate.countDown();
            assertTrue(finishingGate.await(500, TimeUnit.MILLISECONDS), "Threads timed out before finishing");
            assertEquals(capacities.get(Vehicle.Type.CAR), entered.get(),
                    "Race condition: the wrong number of cars entered the tunnel");
            assertEquals(numberOfThreads - entered.get(), messages.size(),
                    "Every refused car should get exactly one RED_LIGHT message");
        }

        @RepeatedTest(10)
        public void testMixedTypesRace() throws InterruptedException {
            CountDownLatch startingGate = new CountDownLatch(1);
            CountDownLatch finishingGate = new CountDownLatch(numberOfThreads);
            AtomicInteger carsEntered = new AtomicInteger(0);
            AtomicInteger trucksEntered = new AtomicInteger(0);
            for (int i = 0; i < numberOfThreads; i++) {
                Vehicle vehicle = new Vehicle(i + 1, i % 2 == 0 ? Vehicle.Type.CAR : Vehicle.Type.TRUCK);
                CompletableFuture.runAsync(() -> {
                    try {
                        startingGate.await();
                        if (tunnel.enter(driver, vehicle)) {
                            (vehicle.type() == Vehicle.Type.CAR ? carsEntered : trucksEntered).incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        finishingGate.countDown();
                    }
                }, executor);
            }
            startingGate.countDown();
            assertTrue(finishingGate.await(500, TimeUnit.MILLISECONDS), "Threads timed out before finishing");
            boolean onlyCars = carsEntered.get() == capacities.get(Vehicle.Type.CAR) && trucksEntered.get() == 0;
            boolean onlyTruck = trucksEntered.get() == capacities.get(Vehicle.Type.TRUCK) && carsEntered.get() == 0;
            assertTrue(onlyCars || onlyTruck, String.format(
                    "Race condition: %d cars and %d trucks entered the tunnel", carsEntered.get(), trucksEntered.get()));
        }

        @RepeatedTest(10)
        public void testExitRace() throws InterruptedException {
            startClock();
            tunnel.enter(driver, trucks[0]);
            tunnel.enter(driver, trucks[1]);
            messages.clear();
            logbook.clear();

            CountDownLatch startingGate = new CountDownLatch(1);
            CountDownLatch finishingGate = new CountDownLatch(numberOfThreads);
            for (int i = 0; i < numberOfThreads; i++) {
                CompletableFuture.runAsync(() -> {
                    try {
                        startingGate.await();
                        tunnel.exit(driver, trucks[0]);
                    } catch (InterruptedException ignored) {
                    } finally {
                        finishingGate.countDown();
                    }
                }, executor);
            }
            startingGate.countDown();
            assertTrue(finishingGate.await(500, TimeUnit.MILLISECONDS), "Threads timed out before finishing");
            assertEquals(1, logbook.size(), "Race condition: the truck should leave the tunnel exactly once");
            assertLogged(trucks[0], EventType.TUNNEL_EXIT);

            clock.waitForTick();
            assertEquals(new Message(trucks[1].id(), Message.Type.GREEN_LIGHT), messages.poll());
            assertTrue(messages.isEmpty(), "Race condition: the waiting truck should get exactly one green light");
        }
    }
}
