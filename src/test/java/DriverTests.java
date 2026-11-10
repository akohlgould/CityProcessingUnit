import driver.Driver;
import driver.Message;
import driver.Clock;
import logbook.Event;
import logbook.EventType;
import logbook.Logbook;
import org.junit.jupiter.api.*;
import dispatcher.Dispatcher;
import dispatcher.policies.FirstInFirstOut;
import dispatcher.policies.RoundRobin;
import dispatcher.policies.ShortestNextStretchFirst;
import dispatcher.policies.VIPFirst;
import trip.Location;
import trip.Road;
import trip.Route;
import trip.Trip;
import trip.Vehicle;
import trip.stoppoints.Destination;
import trip.stoppoints.RedLight;
import trip.stoppoints.StopSign;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 1, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class DriverTests {

    private Clock clock;
    private Logbook logbook;
    private Driver driver;
    private Queue<Message> messages;
    private Dispatcher dispatcher;
    private List<Route> routes;

    private List<Road> createRoads(int[] lengths) {
        List<Road> roads = IntStream.range(0, lengths.length)
                .mapToObj((i) -> new Road(Integer.toString(i), lengths[i], new StopSign()))
                .collect(Collectors.toCollection(ArrayList::new));
        roads.getLast().setStopPoint(new Destination());
        return roads;
    }

    @BeforeEach
    public void setUp() {
        clock = new Clock();
        logbook = new Logbook();
        driver = new Driver(clock, logbook);
        messages = driver.getMessageQueue();

        routes = new ArrayList<>();
        routes.add(new Route("A (no red lights)", createRoads(new int[]{5, 10, 7, 5})));
        List<Road> roads = createRoads(new int[]{3, 2, 3});
        roads.getFirst().setStopPoint(new RedLight(2));
        routes.add(new Route("B (1 red light)", roads));

        dispatcher = new Dispatcher(driver, new FirstInFirstOut());
        clock.signalNewWaiter(); // The test thread takes part in the ticks, and the clock thread starts them
        clock.startClock();
    }

    @AfterEach
    public void stopClock() {
        clock.stopClock();
    }

    private void arriveAt(Trip trip, int tick) {
        clock.setAlarmAt(tick, () -> driver.getDispatcher().addNewTrip(trip));
    }

    private void checkCurrent(Trip trip) {
        assertSame(trip, dispatcher.getCurrent());
        assertSame(trip != null ? trip.getVehicle() : null, driver.getVehicle());
    }

    private Trip scheduleOneTrip() throws InterruptedException {
        checkCurrent(null);
        driver.runTick();
        assertEquals(1, clock.getTickNum());
        checkCurrent(null);
        Trip trip = new Trip(routes.getFirst(), new Vehicle(1));
        dispatcher.addNewTrip(trip);
        driver.runTick();
        assertEquals(2, clock.getTickNum());
        checkCurrent(trip);
        return trip;
    }

    @Test
    public void testScheduleTrip() throws InterruptedException {
        scheduleOneTrip();
    }

    @Test
    public void testFullTrip() throws InterruptedException {
        Trip trip = scheduleOneTrip();

        int eventNum = 1;
        assertEquals(new Event(trip.getVehicle(), EventType.TRIP_START, eventNum++), logbook.poll());
        assertNull(logbook.poll());

        for (Road road : trip.getRoute().getRoads()) {
            assertEquals(road.getStartLocation(), trip.getNextAddress());
            driver.runTick();
            assertEquals(new Event(trip.getVehicle(), road, EventType.ROAD_ENTER, eventNum++), logbook.poll());
            for (int i = 0; i < road.getLength() - 2; i++) {
                driver.runTick();
                assertTrue(messages.isEmpty());
                assertNull(logbook.poll());
                assertEquals(road.getStartLocation(), trip.getNextAddress());
            }
            driver.runTick();
            Message.Type expectedType = road != trip.getRoute().getRoads().getLast() ? Message.Type.STOP_SIGN : Message.Type.DESTINATION;
            assertTrue(messages.size() == 1 && messages.peek().equals(new Message(trip.getVehicle().id(), expectedType)));
            assertEquals(new Event(trip.getVehicle(), road, EventType.ROAD_EXIT, eventNum++), logbook.poll());
            assertNull(logbook.poll());

            driver.runTick(); // Scheduling tick
            assertTrue(messages.isEmpty());
        }
        assertEquals(new Event(trip.getVehicle(), EventType.TRIP_COMPLETE, eventNum), logbook.poll());
        assertNull(logbook.poll());
        assertSame(trip, dispatcher.getFinished().getFirst());
    }

    @Test
    public void testScheduleNextTrip() throws InterruptedException {
        Trip trip1 = scheduleOneTrip();
        Trip trip2 = new Trip(routes.get(1), new Vehicle(2));
        dispatcher.addNewTrip(trip2);
        for (int i = 0; i < trip1.getRoute().getRoad(0).getLength(); i++) {
            driver.runTick();
        }
        checkCurrent(trip1);
        assertEquals(0, trip1.getLastStretchLength());
        driver.runTick();
        checkCurrent(trip2);
        assertEquals(trip1.getRoute().getRoad(0).getLength(), trip1.getLastStretchLength());
        for (int i = 0; i < trip2.getRoute().getRoad(0).getLength(); i++) {
            driver.runTick();
        }
        checkCurrent(trip2);
        driver.runTick();
        checkCurrent(trip1);
    }

    @Test
    public void testRedAndGreenLights() throws InterruptedException {
        Trip tripA = new Trip(routes.get(0), new Vehicle(1));
        Trip tripB = new Trip(routes.get(1), new Vehicle(2));
        dispatcher.addNewTrip(tripB);
        dispatcher.addNewTrip(tripA);

        driver.runTick();
        checkCurrent(tripB);
        for (int i = 0; i < tripB.getRoute().getRoad(0).getLength(); i++) {
            assertTrue(messages.isEmpty());
            driver.runTick();
        }
        assertEquals(1, messages.size());
        assertEquals(new Message(tripB.getVehicle().id(), Message.Type.RED_LIGHT), messages.peek());
        int redLightTick = clock.getTickNum();

        driver.runTick(); // Scheduling tick: tripB waits, tripA is scheduled
        checkCurrent(tripA);
        assertEquals(1, dispatcher.getWaitingListSize());

        int waitTime = ((RedLight) tripB.getRoute().getRoad(0).getStopPoint()).getWaitTime();
        while (clock.getTickNum() < redLightTick + waitTime) {
            driver.runTick();
            assertTrue(messages.isEmpty());
            assertEquals(1, dispatcher.getWaitingListSize());
            checkCurrent(tripA);
        }
        driver.runTick(); // Scheduling tick: the light turns green, tripB is ready, tripA keeps driving
        assertTrue(messages.isEmpty());
        assertEquals(0, dispatcher.getWaitingListSize());
        assertEquals(1, dispatcher.getReadyLineSize());
        checkCurrent(tripA);

        int roadLength = tripA.getRoute().getRoad(0).getLength();
        for (int i = 0; i < roadLength && messages.isEmpty(); i++) {
            driver.runTick();
        }
        assertEquals(new Message(tripA.getVehicle().id(), Message.Type.STOP_SIGN), messages.peek());
        driver.runTick(); // Scheduling tick
        checkCurrent(tripB);
        assertEquals(roadLength, tripA.getLastStretchLength());
    }

    @Test
    public void testEarlyDestination() throws InterruptedException {
        Route route = new Route("Test Destination", List.of(
                new Road("1", 2, new Destination()),
                new Road("2", 2, new Destination())
        ));
        Vehicle vehicle = new Vehicle(1);
        Trip trip = new Trip(route, vehicle);
        dispatcher.addNewTrip(trip);

        driver.runTick();
        checkCurrent(trip);
        driver.runTick();
        assertTrue(messages.isEmpty());
        driver.runTick();
        assertTrue(messages.size() == 1 && messages.peek().equals(new Message(vehicle.id(), Message.Type.DESTINATION)));
        checkCurrent(trip);
        assertEquals(1, dispatcher.getNumTrips());
        driver.runTick();
        assertTrue(messages.isEmpty());
        checkCurrent(null);
        assertEquals(0, dispatcher.getNumTrips());
        assertSame(trip, dispatcher.getFinished().getFirst());

        assertEquals(new Event(vehicle, EventType.TRIP_START, 1), logbook.poll());
        assertEquals(new Event(vehicle, route.getRoad(0), EventType.ROAD_ENTER, 2), logbook.poll());
        assertEquals(new Event(vehicle, route.getRoad(0), EventType.ROAD_EXIT, 3), logbook.poll());
        assertEquals(new Event(vehicle, EventType.TRIP_COMPLETE, 4), logbook.poll());
    }

    @Test
    public void testArrivals() throws InterruptedException {
        Trip trip1 = new Trip(routes.get(0), new Vehicle(1));
        Trip trip2 = new Trip(routes.get(1), new Vehicle(2));
        arriveAt(trip1, 3);
        arriveAt(trip2, 5);

        driver.runTick();
        driver.runTick(); // Nothing has arrived yet
        checkCurrent(null);
        assertEquals(0, dispatcher.getNumTrips());
        assertNull(logbook.poll());

        driver.runTick(); // trip1 arrives and is picked in the same tick
        checkCurrent(trip1);
        assertEquals(new Event(trip1.getVehicle(), EventType.TRIP_START, 1), logbook.poll());

        driver.runTick();
        driver.runTick(); // trip2 arrives, and trip1 keeps driving
        checkCurrent(trip1);
        assertEquals(1, dispatcher.getReadyLineSize());
        assertFalse(clock.hasAlarms());
        assertEquals(new Event(trip1.getVehicle(), trip1.getRoute().getRoad(0), EventType.ROAD_ENTER, 2), logbook.poll());
        assertEquals(new Event(trip2.getVehicle(), EventType.TRIP_START, 3), logbook.poll());
    }

    @Test
    public void testAlarmMustBeAfterCurrentTick() throws InterruptedException {
        driver.runTick();
        assertThrows(IllegalArgumentException.class, () -> clock.setAlarmAt(1, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> clock.setAlarmAt(0, () -> {}));
        assertFalse(clock.hasAlarms());
    }

    @Test
    public void testDriveAllTripsWaitsForArrivals() throws InterruptedException {
        Trip trip = new Trip(new Route("Late", List.of(new Road("1", 2, new Destination()))), new Vehicle(1));
        arriveAt(trip, 10);
        driver.driveAllTrips(); // Waits until tick 10, then 2 ticks of driving and a tick at the dispatcher
        assertSame(trip, dispatcher.getFinished().getFirst());
        assertEquals(13, clock.getTickNum());
        assertFalse(clock.hasAlarms());
    }

    @Test
    public void testOneTickRoads() throws InterruptedException {
        Route route = new Route("Test One Tick Roads", List.of(
                new Road("1", 1, new StopSign()),
                new Road("2", 1, new Destination())
        ));
        Vehicle vehicle = new Vehicle(1);
        Trip trip = new Trip(route, vehicle);
        dispatcher.addNewTrip(trip);

        driver.runTick(); // Scheduling tick
        driver.runTick();
        assertEquals(new Message(vehicle.id(), Message.Type.STOP_SIGN), messages.peek());
        driver.runTick(); // Scheduling tick
        checkCurrent(trip);
        driver.runTick();
        assertEquals(new Message(vehicle.id(), Message.Type.DESTINATION), messages.peek());
        driver.runTick(); // Scheduling tick
        assertSame(trip, dispatcher.getFinished().getFirst());

        assertEquals(new Event(vehicle, EventType.TRIP_START, 1), logbook.poll());
        assertEquals(new Event(vehicle, route.getRoad(0), EventType.ROAD_ENTER, 2), logbook.poll());
        assertEquals(new Event(vehicle, route.getRoad(0), EventType.ROAD_EXIT, 3), logbook.poll());
        assertEquals(new Event(vehicle, route.getRoad(1), EventType.ROAD_ENTER, 4), logbook.poll());
        assertEquals(new Event(vehicle, route.getRoad(1), EventType.ROAD_EXIT, 5), logbook.poll());
        assertEquals(new Event(vehicle, EventType.TRIP_COMPLETE, 6), logbook.poll());
    }

    @Test
    public void testRoundRobin() throws InterruptedException {
        dispatcher = new Dispatcher(driver, new RoundRobin(2));
        Road roadA = new Road("A", 5, new Destination());
        Trip tripA = new Trip(new Route("A", List.of(roadA)), new Vehicle(1));
        Trip tripB = new Trip(new Route("B", List.of(new Road("B", 3, new Destination()))), new Vehicle(2));
        dispatcher.addNewTrip(tripA);
        dispatcher.addNewTrip(tripB);

        // Which trip is current at the end of each tick; index 0 is tick 1
        Trip[] expected = {tripA, tripA, tripA, tripB, tripB, tripB, tripA, tripA, tripA, tripB, tripB, tripA, tripA, null};
        for (int tick = 1; tick <= expected.length; tick++) {
            driver.runTick();
            assertSame(expected[tick - 1], dispatcher.getCurrent(), "at the end of tick " + tick);
            if (tick == 4) {
                // The timer stopped tripA in the middle of its road
                assertEquals(new Location(roadA, 2), tripA.getNextAddress());
                assertEquals(2, tripA.getLastStretchLength());
            }
        }
        assertEquals(List.of(tripB, tripA), dispatcher.getFinished());
    }

    @Test
    public void testGreenLightDoesNotUseTimeSlice() throws InterruptedException {
        dispatcher = new Dispatcher(driver, new RoundRobin(3));
        Trip tripX = new Trip(new Route("X", List.of(
                new Road("X1", 2, new RedLight(2)),
                new Road("X2", 2, new Destination())
        )), new Vehicle(1));
        Trip tripY = new Trip(new Route("Y", List.of(new Road("Y1", 10, new Destination()))), new Vehicle(2));
        dispatcher.addNewTrip(tripX);
        dispatcher.addNewTrip(tripY);

        for (int tick = 1; tick <= 5; tick++) {
            driver.runTick(); // tripX drives to its red light, then tripY is scheduled and drives one tick
        }
        checkCurrent(tripY);
        assertEquals(2, driver.getTimerTicksLeft());
        driver.runTick(); // Scheduling tick: the light turns green, and tripY keeps driving
        checkCurrent(tripY);
        assertEquals(0, dispatcher.getWaitingListSize());
        assertEquals(2, driver.getTimerTicksLeft());
        driver.runTick();
        driver.runTick(); // tripY has now been driven for 3 ticks, so the timer runs out
        assertEquals(new Message(tripY.getVehicle().id(), Message.Type.TIMER), messages.peek());
        driver.runTick(); // Scheduling tick
        checkCurrent(tripX);
        assertEquals(3, tripY.getLastStretchLength());
    }

    /**
     * The example from the assignment handout.
     */
    @Nested
    class HandoutExampleTests {

        private Trip tripA;
        private Trip tripB;
        private Trip tripC;

        @BeforeEach
        public void setUp() {
            tripA = new Trip(new Route("A", List.of(
                    new Road("A1", 3, new StopSign()),
                    new Road("A2", 2, new Destination())
            )), new Vehicle(1));
            tripB = new Trip(new Route("B", List.of(
                    new Road("B1", 2, new RedLight(2)),
                    new Road("B2", 2, new Destination())
            )), new Vehicle(2));
            tripC = new Trip(new Route("C", List.of(
                    new Road("C1", 2, new Destination())
            )), new Vehicle(3));
        }

        private void addArrivals() {
            arriveAt(tripA, 1);
            arriveAt(tripB, 1);
            arriveAt(tripC, 4);
        }

        @Test
        public void testFifoTickByTick() throws InterruptedException {
            // What the dispatcher holds at the end of each tick; index 0 is tick 1
            Trip[] driving = {tripA, tripA, tripA, tripA, tripB, tripB, tripB, tripC, tripC, tripC, tripC,
                    tripA, tripA, tripA, tripB, tripB, tripB, null};
            int[] numReady = {1, 1, 1, 2, 2, 2, 2, 1, 1, 2, 2, 1, 1, 1, 0, 0, 0, 0};
            int[] numWait = {0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0};

            addArrivals();
            for (int tick = 1; tick <= driving.length; tick++) {
                driver.runTick();
                String message = "at the end of tick " + tick;
                assertSame(driving[tick - 1], dispatcher.getCurrent(), message);
                assertEquals(numReady[tick - 1], dispatcher.getReadyLineSize(), message);
                assertEquals(numWait[tick - 1], dispatcher.getWaitingListSize(), message);
            }
            assertEquals(List.of(tripC, tripA, tripB), dispatcher.getFinished());
        }

        @Test
        public void testRoundRobinTickByTick() throws InterruptedException {
            dispatcher = new Dispatcher(driver, new RoundRobin(2));
            // Which trip is current at the end of each tick; index 0 is tick 1
            Trip[] driving = {tripA, tripA, tripA, tripB, tripB, tripB, tripC, tripC, tripC, tripC,
                    tripA, tripA, tripB, tripB, tripB, tripA, tripA, tripA, null};

            addArrivals();
            for (int tick = 1; tick <= driving.length; tick++) {
                driver.runTick();
                assertSame(driving[tick - 1], dispatcher.getCurrent(), "at the end of tick " + tick);
            }
            assertEquals(List.of(tripC, tripB, tripA), dispatcher.getFinished());
        }

        @Test
        public void testPriorityFinishOrder() throws InterruptedException {
            dispatcher = new Dispatcher(driver, new VIPFirst());
            tripA.setPriority(3);
            tripB.setPriority(1);
            tripC.setPriority(2);
            addArrivals();
            driver.driveAllTrips();
            assertEquals(List.of(tripC, tripB, tripA), dispatcher.getFinished());
            assertEquals(18, clock.getTickNum());
        }

        @Test
        public void testShortestNextStretchFirstFinishOrder() throws InterruptedException {
            dispatcher = new Dispatcher(driver, new ShortestNextStretchFirst(0.5));
            addArrivals();
            driver.runTick();
            driver.runTick();
            driver.runTick(); // C arrives at tick 4, so it is not in the dispatcher yet
            assertEquals(2, dispatcher.getNumTrips());
            driver.driveAllTrips();
            assertEquals(List.of(tripC, tripB, tripA), dispatcher.getFinished());
            assertEquals(18, clock.getTickNum());
        }

        @Test
        public void testDriveScheduledTrips() throws InterruptedException {
            addArrivals();
            driver.driveAllTrips();
            assertEquals(0, dispatcher.getNumTrips());
            assertEquals(List.of(tripC, tripA, tripB), dispatcher.getFinished());
            assertEquals(18, clock.getTickNum());
        }
    }
}
