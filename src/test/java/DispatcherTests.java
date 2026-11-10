import driver.Driver;
import driver.Message;
import driver.Clock;
import logbook.Event;
import logbook.EventType;
import logbook.Logbook;
import org.junit.jupiter.api.*;
import trip.Road;
import trip.Route;
import trip.stoppoints.RedLight;
import dispatcher.Dispatcher;
import dispatcher.policies.Policy;
import trip.Trip;
import trip.Vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;


@Timeout(value = 1, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class DispatcherTests {

    private final static List<Route> routes = new ArrayList<>();
    private List<Trip> trips;
    private Dispatcher dispatcher;

    private Logbook logbook;
    private Driver driver;
    private Clock clock;
    private Queue<Message> messages;

    /**
     * A first in, first out policy, or a newest-first one, that remembers every ready line it was shown,
     * so these tests do not depend on the policies you write.
     */
    private static class TestPolicy implements Policy {
        private final int timeSlice;
        private final boolean newestFirst;
        private final List<List<Trip>> readyLinesSeen = new ArrayList<>();

        TestPolicy(int timeSlice) {
            this(timeSlice, false);
        }

        TestPolicy(int timeSlice, boolean newestFirst) {
            this.timeSlice = timeSlice;
            this.newestFirst = newestFirst;
        }

        @Override
        public Trip pickNext(List<Trip> readyLine) {
            assertFalse(readyLine.isEmpty(), "pickNext() must not be called when the ready line is empty");
            assertThrows(UnsupportedOperationException.class, readyLine::clear,
                    "the policy must not be able to change the ready line");
            readyLinesSeen.add(List.copyOf(readyLine));
            return newestFirst ? readyLine.getLast() : readyLine.getFirst();
        }

        @Override
        public int timeSlice() {
            return timeSlice;
        }
    }

    private static Route createNewRoute(String name, int[] lengths) {
        List<Road> roads = IntStream.range(0, lengths.length)
                .mapToObj((i) -> new Road(Integer.toString(i), lengths[i]))
                .toList();
        roads.getLast().setStopPoint(new RedLight(0));
        return new Route(name, roads);
    }

    @BeforeAll
    public static void initRoutes() {
        routes.add(createNewRoute("A", new int[]{5, 10, 7, 5}));
        routes.add(createNewRoute("B", new int[]{3, 6, 3, 6}));
        routes.add(createNewRoute("C", new int[]{4}));
        routes.add(createNewRoute("D", new int[]{1, 2, 3}));
        routes.add(createNewRoute("E", new int[]{3, 2, 3}));
    }

    @BeforeEach
    public void initTrips() {
        trips = IntStream.range(0, routes.size())
                .mapToObj((i) -> new Trip(routes.get(i), new Vehicle(i + 1)))
                .collect(Collectors.toCollection(ArrayList::new));

        logbook = new Logbook();
        clock = new Clock();
        driver = new Driver(clock, logbook);
        messages = driver.getMessageQueue();
        clock.signalNewWaiter(); // The test thread takes part in the ticks, and the clock thread starts them
        clock.startClock();
    }

    @AfterEach
    public void stopClock() {
        clock.stopClock();
    }

    private void checkCurrent(Trip trip) {
        assertSame(trip, dispatcher.getCurrent());
        assertSame(trip != null ? trip.getVehicle() : null, driver.getVehicle());
    }

    private void sendAndDispatch(Message.Type type) throws InterruptedException {
        messages.add(new Message(driver.getVehicle().id(), type));
        runDispatcherTick();
    }

    private void runDispatcherTick() throws InterruptedException {
        clock.waitForTick();
        dispatcher.dispatch();
    }


    private void addOneTrip() {
        dispatcher.addNewTrip(trips.get(0));
        checkCurrent(null);
        assertEquals(1, dispatcher.getReadyLineSize());
        dispatcher.dispatch();
        checkCurrent(trips.get(0));
        assertEquals(0, dispatcher.getReadyLineSize());
        assertEquals(1, dispatcher.getNumTrips());
    }

    private void addTwoTrips() throws InterruptedException {
        dispatcher.addNewTrip(trips.get(0));
        dispatcher.addNewTrip(trips.get(1));
        checkCurrent(null);
        assertEquals(2, dispatcher.getReadyLineSize());
        assertEquals(2, dispatcher.getNumTrips());
        runDispatcherTick();
        checkCurrent(trips.get(0));
        assertEquals(1, dispatcher.getReadyLineSize());
        assertEquals(2, dispatcher.getNumTrips());
        runDispatcherTick();
        checkCurrent(trips.get(0));
        assertEquals(1, dispatcher.getReadyLineSize());
        assertEquals(2, dispatcher.getNumTrips());
    }

    @Nested
    class BasicDispatcherTests {

        @BeforeEach
        public void setUp() {
            dispatcher = new Dispatcher(driver, new TestPolicy(0));
        }

        @Test
        public void testLogTripStartAndEnd() {
            addOneTrip();
            assertEquals(new Event(trips.getFirst().getVehicle(), EventType.TRIP_START, 1), logbook.poll());
            assertNull(logbook.poll());
            messages.add(new Message(trips.get(0).getVehicle().id(), Message.Type.DESTINATION));
            dispatcher.dispatch();
            assertEquals(new Event(trips.getFirst().getVehicle(), EventType.TRIP_COMPLETE, 2), logbook.poll());
            assertNull(logbook.poll());
        }

        @Test
        public void testNoTripsOrInterrupts() {
            dispatcher.dispatch();
            checkCurrent(null);
        }

        @Test
        public void testNoInterruptsWithTwoTrips() throws InterruptedException {
            addTwoTrips();
        }

        @Test
        public void testStopSignWithOneTrip() {
            addOneTrip();
            messages.add(new Message(trips.get(0).getVehicle().id(), Message.Type.STOP_SIGN));
            dispatcher.dispatch();
            assertTrue(messages.isEmpty());
            checkCurrent(trips.get(0));
            assertEquals(0, dispatcher.getReadyLineSize());
        }

        @Test
        public void testStopSignWithTwoTrips() throws InterruptedException {
            addTwoTrips();
            messages.add(new Message(trips.get(0).getVehicle().id(), Message.Type.STOP_SIGN));
            dispatcher.dispatch();
            assertTrue(messages.isEmpty());
            checkCurrent(trips.get(1));
            assertEquals(1, dispatcher.getReadyLineSize());
        }

        @Test
        public void testRedLightGreenLightWithOneTrip() {
            addOneTrip();
            messages.add(new Message(trips.get(0).getVehicle().id(), Message.Type.RED_LIGHT));
            dispatcher.dispatch();
            assertTrue(messages.isEmpty());
            checkCurrent(null);
            assertEquals(0, dispatcher.getReadyLineSize());
            assertEquals(1, dispatcher.getWaitingListSize());
            dispatcher.dispatch();
            checkCurrent(null);
            assertEquals(0, dispatcher.getReadyLineSize());
            assertEquals(1, dispatcher.getWaitingListSize());
            messages.add(new Message(trips.get(0).getVehicle().id(), Message.Type.GREEN_LIGHT));
            dispatcher.dispatch();
            assertTrue(messages.isEmpty());
            checkCurrent(trips.get(0));
            assertEquals(0, dispatcher.getReadyLineSize());
            assertEquals(0, dispatcher.getWaitingListSize());
        }

        @Test
        public void testRedLightGreenLightWithMultipleTrips() throws InterruptedException {
            addTwoTrips();
            dispatcher.addNewTrip(trips.get(2));
            sendAndDispatch(Message.Type.RED_LIGHT);
            sendAndDispatch(Message.Type.RED_LIGHT);
            sendAndDispatch(Message.Type.RED_LIGHT);
            assertEquals(3, dispatcher.getWaitingListSize());
            assertEquals(0, dispatcher.getReadyLineSize());
            messages.add(new Message(trips.get(1).getVehicle().id(), Message.Type.GREEN_LIGHT));
            dispatcher.dispatch();
            checkCurrent(trips.get(1));
            assertEquals(2, dispatcher.getWaitingListSize());
            assertEquals(0, dispatcher.getReadyLineSize());
        }

        @Test
        public void testDestinationWithOneTrip() {
            addOneTrip();
            messages.add(new Message(trips.get(0).getVehicle().id(), Message.Type.DESTINATION));
            dispatcher.dispatch();
            assertTrue(messages.isEmpty());
            checkCurrent(null);
            assertEquals(0, dispatcher.getNumTrips());
            assertEquals(1, dispatcher.getFinished().size());
            assertSame(trips.get(0), dispatcher.getFinished().getFirst());
            dispatcher.dispatch();
            checkCurrent(null);
        }

        @Test
        public void testMultipleMessages() throws InterruptedException {
            addTwoTrips();
            dispatcher.addNewTrip(trips.get(2));
            sendAndDispatch(Message.Type.RED_LIGHT);
            checkCurrent(trips.get(1));
            assertEquals(1, dispatcher.getWaitingListSize());
            messages.add(new Message(trips.get(0).getVehicle().id(), Message.Type.GREEN_LIGHT));
            messages.add(new Message(trips.get(1).getVehicle().id(), Message.Type.STOP_SIGN));
            dispatcher.dispatch();
            assertEquals(0, dispatcher.getWaitingListSize());
            checkCurrent(trips.get(2));
            sendAndDispatch(Message.Type.STOP_SIGN);
            checkCurrent(trips.get(0));
            sendAndDispatch(Message.Type.STOP_SIGN);
            checkCurrent(trips.get(1));
        }

        @Test
        public void testStretchLength() throws InterruptedException {
            addTwoTrips();

            int roadOneLength = trips.get(0).getRoute().getRoad(0).getLength();
            assertEquals(0, trips.get(0).getLastStretchLength());
            clock.waitForTicks(roadOneLength);
            sendAndDispatch(Message.Type.STOP_SIGN);
            assertEquals(roadOneLength, trips.get(0).getLastStretchLength());

            int roadTwoLength = trips.get(1).getRoute().getRoad(0).getLength();
            assertEquals(0, trips.get(1).getLastStretchLength());
            clock.waitForTicks(roadTwoLength);
            messages.add(new Message(trips.get(1).getVehicle().id(), Message.Type.RED_LIGHT));
            runDispatcherTick();
            assertEquals(roadTwoLength, trips.get(1).getLastStretchLength());

            int roadThreeLength = trips.get(0).getRoute().getRoad(0).getLength();
            assertEquals(roadOneLength, trips.get(0).getLastStretchLength());
            clock.waitForTicks(roadThreeLength);
            // Travel time should not stop at or include dispatcher ticks that only handle green light
            messages.add(new Message(trips.get(1).getVehicle().id(), Message.Type.GREEN_LIGHT));
            runDispatcherTick();
            clock.waitForTick();
            clock.waitForTick();

            sendAndDispatch(Message.Type.STOP_SIGN);
            assertEquals(roadThreeLength + 2, trips.get(0).getLastStretchLength());
        }

        @Test
        public void testTimer() throws InterruptedException {
            addTwoTrips();
            messages.add(new Message(trips.get(0).getVehicle().id(), Message.Type.TIMER));
            dispatcher.dispatch();
            assertTrue(messages.isEmpty());
            checkCurrent(trips.get(1));
            assertEquals(1, dispatcher.getReadyLineSize());
            sendAndDispatch(Message.Type.TIMER);
            checkCurrent(trips.get(0));
            assertEquals(1, dispatcher.getReadyLineSize());
        }

        @Test
        public void testTimerAtEndOfRoad() throws InterruptedException {
            // The timer can run out on the same tick a road ends, so the trip has already left when TIMER is handled
            addTwoTrips();
            dispatcher.addNewTrip(trips.get(2));
            messages.add(new Message(trips.get(0).getVehicle().id(), Message.Type.STOP_SIGN));
            messages.add(new Message(trips.get(0).getVehicle().id(), Message.Type.TIMER));
            dispatcher.dispatch();
            checkCurrent(trips.get(1));
            assertEquals(2, dispatcher.getReadyLineSize());
            assertEquals(3, dispatcher.getNumTrips());

            messages.add(new Message(trips.get(1).getVehicle().id(), Message.Type.RED_LIGHT));
            messages.add(new Message(trips.get(1).getVehicle().id(), Message.Type.TIMER));
            dispatcher.dispatch();
            checkCurrent(trips.get(2));
            assertEquals(1, dispatcher.getReadyLineSize());
            assertEquals(1, dispatcher.getWaitingListSize());

            messages.add(new Message(trips.get(2).getVehicle().id(), Message.Type.DESTINATION));
            messages.add(new Message(trips.get(2).getVehicle().id(), Message.Type.TIMER));
            dispatcher.dispatch();
            checkCurrent(trips.get(0));
            assertEquals(0, dispatcher.getReadyLineSize());
            assertEquals(2, dispatcher.getNumTrips());
        }

        @Test
        public void testProgressSavedWhenTripLeaves() throws InterruptedException {
            addTwoTrips();
            dispatcher.addNewTrip(trips.get(2));
            clock.waitForTicks(4);
            sendAndDispatch(Message.Type.STOP_SIGN);
            clock.waitForTicks(2);
            sendAndDispatch(Message.Type.RED_LIGHT);
            clock.waitForTicks(3);
            sendAndDispatch(Message.Type.TIMER);
            assertEquals(List.of(4), trips.get(0).getStretchLengths());
            assertEquals(List.of(2), trips.get(1).getStretchLengths());
            assertEquals(List.of(3), trips.get(2).getStretchLengths());

            // A tick that only handles GREEN_LIGHT does not end the current trip's stretch
            messages.add(new Message(trips.get(1).getVehicle().id(), Message.Type.GREEN_LIGHT));
            runDispatcherTick();
            assertEquals(List.of(4), trips.get(0).getStretchLengths());
            clock.waitForTicks(2);
            sendAndDispatch(Message.Type.DESTINATION);
            assertEquals(List.of(4, 2), trips.get(0).getStretchLengths());
        }

        @Test
        public void testPolicyPicksFromReadyLine() throws InterruptedException {
            TestPolicy newestFirst = new TestPolicy(0, true);
            dispatcher = new Dispatcher(driver, newestFirst);
            for (int i = 0; i < 3; i++) {
                dispatcher.addNewTrip(trips.get(i));
            }
            runDispatcherTick();
            checkCurrent(trips.get(2));
            assertEquals(2, dispatcher.getReadyLineSize());
            sendAndDispatch(Message.Type.RED_LIGHT);
            checkCurrent(trips.get(1));
            sendAndDispatch(Message.Type.STOP_SIGN); // Back of the ready line, so the newest again
            checkCurrent(trips.get(1));
            messages.add(new Message(trips.get(1).getVehicle().id(), Message.Type.STOP_SIGN));
            messages.add(new Message(trips.get(2).getVehicle().id(), Message.Type.GREEN_LIGHT));
            runDispatcherTick();
            checkCurrent(trips.get(2));
            assertEquals(2, dispatcher.getReadyLineSize());

            // The ready line is always in the order the trips joined it, without the current trip
            assertEquals(List.of(
                    List.of(trips.get(0), trips.get(1), trips.get(2)),
                    List.of(trips.get(0), trips.get(1)),
                    List.of(trips.get(0), trips.get(1)),
                    List.of(trips.get(0), trips.get(1), trips.get(2))
            ), newestFirst.readyLinesSeen);
        }

        @Test
        public void testNoTimerWithoutTimeSlice() throws InterruptedException {
            addTwoTrips();
            sendAndDispatch(Message.Type.STOP_SIGN);
            checkCurrent(trips.get(1));
            assertEquals(0, driver.getTimerTicksLeft());
        }
    }

    @Nested
    class TimeSliceTests {

        private static final int TIME_SLICE = 3;

        @BeforeEach
        public void setUp() {
            dispatcher = new Dispatcher(driver, new TestPolicy(TIME_SLICE));
        }

        @Test
        public void testTimerOrder() throws InterruptedException {
            for (int i = 0; i < 3; i++) {
                dispatcher.addNewTrip(trips.get(i));
            }
            dispatcher.dispatch();
            checkCurrent(trips.get(0));
            assertEquals(TIME_SLICE, driver.getTimerTicksLeft());
            for (int i : new int[]{1, 2, 0, 1}) {
                driver.startTimer(1); // As if the trip had been driven for all but the last tick of its time slice
                sendAndDispatch(Message.Type.TIMER);
                checkCurrent(trips.get(i));
                assertEquals(2, dispatcher.getReadyLineSize());
                assertEquals(TIME_SLICE, driver.getTimerTicksLeft());
            }
        }

        @Test
        public void testTimerStartedForEveryScheduledTrip() throws InterruptedException {
            dispatcher.dispatch();
            checkCurrent(null);
            assertEquals(0, driver.getTimerTicksLeft());
            dispatcher.addNewTrip(trips.get(0));
            runDispatcherTick();
            checkCurrent(trips.get(0));
            assertEquals(TIME_SLICE, driver.getTimerTicksLeft());

            driver.startTimer(1);
            sendAndDispatch(Message.Type.STOP_SIGN);
            checkCurrent(trips.get(0));
            assertEquals(TIME_SLICE, driver.getTimerTicksLeft());
        }

        @Test
        public void testTimerNotRestartedForCurrentTrip() throws InterruptedException {
            for (int i = 0; i < 3; i++) {
                dispatcher.addNewTrip(trips.get(i));
            }
            dispatcher.dispatch();
            sendAndDispatch(Message.Type.RED_LIGHT);
            checkCurrent(trips.get(1));

            driver.startTimer(1);
            runDispatcherTick();
            checkCurrent(trips.get(1));
            assertEquals(1, driver.getTimerTicksLeft());

            messages.add(new Message(trips.get(0).getVehicle().id(), Message.Type.GREEN_LIGHT));
            runDispatcherTick();
            checkCurrent(trips.get(1));
            assertEquals(1, driver.getTimerTicksLeft());
            assertEquals(2, dispatcher.getReadyLineSize());
        }
    }
}
