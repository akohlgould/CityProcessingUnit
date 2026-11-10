import dispatcher.Dispatcher;
import dispatcher.policies.FirstInFirstOut;
import dispatcher.policies.Policy;
import dispatcher.policies.RoundRobin;
import dispatcher.policies.ShortestNextStretchFirst;
import dispatcher.policies.VIPFirst;
import driver.Clock;
import driver.Driver;
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
import trip.stoppoints.RedLight;
import trip.stoppoints.StopSign;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Several drivers driving at the same time, each on its own thread with its own dispatcher, sharing one clock, one
 * logbook, and tunnels. These tests use everything: your dispatcher and policies from part A, and your clock, logbook,
 * and tunnel from part B.
 */
@Timeout(value = 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class CityTests {

    private static final Map<Vehicle.Type, Integer> capacities = Map.of(Vehicle.Type.CAR, 3, Vehicle.Type.TRUCK, 1);

    private Clock clock;
    private Logbook logbook;

    @BeforeEach
    public void setUp() {
        clock = new Clock();
        logbook = new Logbook();
    }

    @AfterEach
    public void tearDown() {
        clock.stopClock();
    }

    /**
     * Starts every driver on its own thread, starts the clock, and waits for all of them to finish.
     */
    private void driveAll(Driver... drivers) throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        for (Driver driver : drivers) {
            threads.add(driver.startDriving());
        }
        clock.startClock();
        for (Thread thread : threads) {
            thread.join(1500);
            assertFalse(thread.isAlive(), "A driver did not finish its trips");
        }
    }

    private List<Event> drainLogbook() {
        List<Event> events = new ArrayList<>();
        Event event;
        while ((event = logbook.poll()) != null) {
            events.add(event);
        }
        return events;
    }

    /**
     * The events of the given vehicles, in the order they were logged.
     */
    private static List<Event> eventsOf(List<Event> events, Vehicle... vehicles) {
        List<Vehicle> wanted = List.of(vehicles);
        return events.stream().filter(e -> wanted.stream().anyMatch(v -> v == e.getVehicle())).toList();
    }

    private static void assertEvents(List<Event> expected, List<Event> actual) {
        assertEquals(expected.size(), actual.size(), "Wrong number of events: " + actual);
        for (int i = 0; i < expected.size(); i++) {
            assertTrue(expected.get(i).weakEquals(actual.get(i)),
                    "Event " + (i + 1) + " should be " + expected.get(i) + " but is " + actual.get(i));
        }
    }

    private static void assertIdsInOrder(List<Event> events) {
        for (int i = 0; i < events.size(); i++) {
            assertEquals(i + 1, events.get(i).getId(), "The logbook's event IDs should be 1, 2, 3, ... in order");
        }
    }

    /**
     * The handout's example day: A and B arrive at tick 1 and C at tick 4.
     */
    private static class ExampleDay {
        final Road a1 = new Road("A1", 3, new StopSign());
        final Road a2 = new Road("A2", 2, new Destination());
        final Road b1 = new Road("B1", 2, new RedLight(2));
        final Road b2 = new Road("B2", 2, new Destination());
        final Road c1 = new Road("C1", 2, new Destination());
        final Trip a = new Trip(new Route("A", List.of(a1, a2)), new Vehicle(1));
        final Trip b = new Trip(new Route("B", List.of(b1, b2)), new Vehicle(2));
        final Trip c = new Trip(new Route("C", List.of(c1)), new Vehicle(3));
        final Dispatcher dispatcher;
        final Driver driver;

        ExampleDay(Clock clock, Logbook logbook) {
            driver = new Driver(clock, logbook);
            dispatcher = new Dispatcher(driver, new FirstInFirstOut());
            clock.setAlarmAt(1, () -> dispatcher.addNewTrip(a));
            clock.setAlarmAt(1, () -> dispatcher.addNewTrip(b));
            clock.setAlarmAt(4, () -> dispatcher.addNewTrip(c));
        }

        /**
         * The events of this day in the order a single driver logs them under First In, First Out.
         */
        List<Event> expectedEvents() {
            Vehicle va = a.getVehicle(), vb = b.getVehicle(), vc = c.getVehicle();
            return List.of(
                    new Event(va, EventType.TRIP_START, 0), new Event(vb, EventType.TRIP_START, 0),
                    new Event(va, a1, EventType.ROAD_ENTER, 0), new Event(vc, EventType.TRIP_START, 0),
                    new Event(va, a1, EventType.ROAD_EXIT, 0),
                    new Event(vb, b1, EventType.ROAD_ENTER, 0), new Event(vb, b1, EventType.ROAD_EXIT, 0),
                    new Event(vc, c1, EventType.ROAD_ENTER, 0), new Event(vc, c1, EventType.ROAD_EXIT, 0),
                    new Event(vc, EventType.TRIP_COMPLETE, 0),
                    new Event(va, a2, EventType.ROAD_ENTER, 0), new Event(va, a2, EventType.ROAD_EXIT, 0),
                    new Event(va, EventType.TRIP_COMPLETE, 0),
                    new Event(vb, b2, EventType.ROAD_ENTER, 0), new Event(vb, b2, EventType.ROAD_EXIT, 0),
                    new Event(vb, EventType.TRIP_COMPLETE, 0));
        }
    }

    @RepeatedTest(5)
    public void testDriversShareTheClock() throws InterruptedException {
        ExampleDay[] days = {new ExampleDay(clock, logbook), new ExampleDay(clock, logbook), new ExampleDay(clock, logbook)};
        driveAll(Arrays.stream(days).map(day -> day.driver).toArray(Driver[]::new));

        assertEquals(18, clock.getTickNum(), "Each driver's day takes 18 ticks, and the drivers drive at the same time");
        List<Event> events = drainLogbook();
        assertEquals(16 * days.length, events.size(), "Events were lost or logged twice");
        assertIdsInOrder(events);
        for (ExampleDay day : days) {
            assertEquals(List.of(day.c, day.a, day.b), day.dispatcher.getFinished());
            assertEvents(day.expectedEvents(),
                    eventsOf(events, day.a.getVehicle(), day.b.getVehicle(), day.c.getVehicle()));
        }
    }

    @RepeatedTest(5)
    public void testWaitingForTheTunnel() throws InterruptedException {
        // The truck enters the tunnel at tick 2 and leaves at tick 4. The car reaches the tunnel at tick 3, is refused,
        // and waits; its green light arrives at tick 5, it is picked again, and drives through the tunnel at ticks 6-8.
        Tunnel tunnel = new Tunnel("Tunnel", 3, new Destination(), capacities);
        Road road = new Road("Road", 1);
        Trip truckTrip = new Trip(new Route("Truck", List.of(tunnel)), new Vehicle(1, Vehicle.Type.TRUCK));
        Trip carTrip = new Trip(new Route("Car", List.of(road, tunnel)), new Vehicle(2, Vehicle.Type.CAR));
        Driver truckDriver = new Driver(clock, logbook);
        Driver carDriver = new Driver(clock, logbook);
        Dispatcher truckDispatcher = new Dispatcher(truckDriver, new FirstInFirstOut());
        Dispatcher carDispatcher = new Dispatcher(carDriver, new FirstInFirstOut());
        truckDispatcher.addNewTrip(truckTrip);
        carDispatcher.addNewTrip(carTrip);

        // Alarms run at the start of a tick, while every driver waits, so they see the state at the end of the last tick
        int lastTick = 10;
        List<String> carStates = new ArrayList<>();
        List<Integer> trucksFinished = new ArrayList<>();
        for (int tick = 2; tick <= lastTick; tick++) {
            clock.setAlarmAt(tick, () -> {
                Trip current = carDispatcher.getCurrent();
                carStates.add((current == carTrip ? "car" : "-") + "/" + carDispatcher.getWaitingListSize());
                trucksFinished.add(truckDispatcher.getFinished().size());
            });
        }
        driveAll(truckDriver, carDriver);

        // State at the end of ticks 1 to 9
        assertEquals(List.of("car/0", "car/0", "car/0", "-/1", "car/0", "car/0", "car/0", "car/0", "-/0"), carStates,
                "The car's dispatcher at the end of ticks 1 to 9 (current trip / waiting list size)");
        assertEquals(List.of(0, 0, 0, 0, 1, 1, 1, 1, 1), trucksFinished,
                "The truck's trip should be finished at tick 5");
        assertEquals(List.of(carTrip), carDispatcher.getFinished());

        List<Event> events = drainLogbook();
        assertIdsInOrder(events);
        Vehicle truck = truckTrip.getVehicle(), car = carTrip.getVehicle();
        assertEvents(List.of(
                new Event(truck, EventType.TRIP_START, 0),
                new Event(truck, tunnel, EventType.TUNNEL_ENTER, 0),
                new Event(truck, tunnel, EventType.TUNNEL_EXIT, 0),
                new Event(truck, EventType.TRIP_COMPLETE, 0)), eventsOf(events, truck));
        assertEvents(List.of(
                new Event(car, EventType.TRIP_START, 0),
                new Event(car, road, EventType.ROAD_ENTER, 0),
                new Event(car, road, EventType.ROAD_EXIT, 0),
                new Event(car, tunnel, EventType.TUNNEL_ENTER_FAILED, 0),
                new Event(car, tunnel, EventType.TUNNEL_ENTER, 0),
                new Event(car, tunnel, EventType.TUNNEL_EXIT, 0),
                new Event(car, EventType.TRIP_COMPLETE, 0)), eventsOf(events, car));
    }

    @RepeatedTest(10)
    public void testTunnelIsNeverOverfull() throws InterruptedException {
        // Four drivers with different policies send cars and trucks through one tunnel. Round robin with a time slice
        // of 1 leaves vehicles parked inside the tunnel, so the tunnel is often busy.
        Tunnel tunnel = new Tunnel("Shared Tunnel", 2, capacities);
        Policy[] policies = {new FirstInFirstOut(), new RoundRobin(1), new VIPFirst(), new ShortestNextStretchFirst(0.5)};
        List<Driver> drivers = new ArrayList<>();
        List<Dispatcher> dispatchers = new ArrayList<>();
        List<Trip> trips = new ArrayList<>();
        int vehicleId = 1;
        for (int d = 0; d < policies.length; d++) {
            Driver driver = new Driver(clock, logbook);
            Dispatcher dispatcher = new Dispatcher(driver, policies[d]);
            for (int t = 0; t < 4; t++) {
                Vehicle.Type type = (d + t) % 3 == 0 ? Vehicle.Type.TRUCK : Vehicle.Type.CAR;
                Route route = new Route("D" + d + "T" + t, List.of(
                        new Road("before", 1 + (d + t) % 3, new StopSign()),
                        tunnel,
                        new Road("after", 1 + t % 2, new Destination())));
                Trip trip = new Trip(route, new Vehicle(vehicleId++, type), t);
                trips.add(trip);
                dispatcher.addNewTrip(trip);
            }
            drivers.add(driver);
            dispatchers.add(dispatcher);
        }
        driveAll(drivers.toArray(Driver[]::new));

        assertEquals(trips.size(), dispatchers.stream().mapToInt(d -> d.getFinished().size()).sum(),
                "Every trip should finish");
        List<Event> events = drainLogbook();
        assertIdsInOrder(events);

        List<Vehicle> inside = new ArrayList<>();
        Set<Vehicle> refused = new HashSet<>();
        for (Event event : events) {
            Vehicle vehicle = event.getVehicle();
            switch (event.getEventType()) {
                case TUNNEL_ENTER -> {
                    if (!inside.isEmpty()) {
                        Vehicle.Type type = inside.getFirst().type();
                        assertEquals(type, vehicle.type(), "A " + vehicle.type() + " entered the tunnel with a " + type);
                        assertTrue(inside.size() < capacities.get(type), "Too many " + type + "s entered the tunnel");
                    }
                    inside.add(vehicle);
                    refused.remove(vehicle);
                }
                case TUNNEL_EXIT -> assertTrue(inside.remove(vehicle), vehicle + " left the tunnel without entering");
                case TUNNEL_ENTER_FAILED -> refused.add(vehicle);
                default -> {
                }
            }
        }
        assertTrue(inside.isEmpty(), "Vehicles were left in the tunnel: " + inside);
        assertTrue(refused.isEmpty(), "Refused vehicles never entered the tunnel: " + refused);
    }
}
