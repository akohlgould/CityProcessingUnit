package trip;

import driver.Driver;
import trip.stoppoints.StopPoint;

import java.util.Map;

/**
 * A tunnel: a road that every driver in the city shares. Similar to a critical section guarding a shared resource.
 * <p>
 * A tunnel holds only one type of vehicle at a time, and at most as many vehicles of that type as its capacity for
 * the type: for example, at most 3 cars or 1 truck. A vehicle that cannot enter waits, like at a red light: its driver
 * leaves it and drives something else, and the vehicle tries again after a vehicle has left the tunnel.
 * <p>
 * Several drivers may call {@code enter()} and {@code exit()} at the same moment, each from their own thread, so both
 * methods must be thread-safe: while one call checks or changes which vehicles are in the tunnel, no other call may.
 * This does not limit how many vehicles can be in the tunnel; only the capacities do. Record each tunnel event in the
 * logbook inside that same protected part, so the logbook shows the order in which vehicles really entered and left.
 */
public class Tunnel extends Road {

    private final Map<Vehicle.Type, Integer> capacities;

    // TODO: add the fields you need, such as the vehicles in the tunnel and the vehicles (with their drivers) waiting
    //  to enter

    /**
     * Creates a tunnel.
     *
     * @param name              the tunnel's name
     * @param length            the number of ticks it takes to drive through the tunnel
     * @param stopPoint         what stands at the end of the tunnel, or null if nothing does
     * @param vehicleCapacities the most vehicles of each type that can be in the tunnel at once; every type must have
     *                          a capacity of at least 1
     */
    public Tunnel(String name, int length, StopPoint stopPoint, Map<Vehicle.Type, Integer> vehicleCapacities) {
        super(name, length, stopPoint);
        this.capacities = Map.copyOf(vehicleCapacities);
    }

    public Tunnel(String name, int length, Map<Vehicle.Type, Integer> vehicleCapacities) {
        this(name, length, null, vehicleCapacities);
    }

    /**
     * Tries to drive the vehicle into the tunnel. It can enter if the tunnel is empty, or if every vehicle in the tunnel
     * is of the same type as this one and there are fewer of them than the tunnel's capacity for that type.
     * <p>
     * If it can, the vehicle enters, its {@code TUNNEL_ENTER} event is logged, and this returns true.
     * Otherwise, its {@code TUNNEL_ENTER_FAILED} event is logged, the tunnel remembers the vehicle and its driver as
     * waiting, a {@code RED_LIGHT} message for the vehicle is sent to the driver right away, and this returns false.
     *
     * @param driver  the driver driving the vehicle
     * @param vehicle the vehicle entering the tunnel
     * @return true if the vehicle entered the tunnel, otherwise false
     */
    @Override
    public boolean enter(Driver driver, Vehicle vehicle) {
        // TODO
        return false;
    }

    /**
     * Drives the vehicle out of the tunnel. Nothing happens if the vehicle is not in the tunnel.
     * <p>
     * Otherwise, the vehicle leaves, its {@code TUNNEL_EXIT} event is logged, and the tunnel's stop point, if it has
     * one, is passed through as at the end of any road ({@code stopPoint.goThrough()}). Then every waiting vehicle gets
     * one {@code GREEN_LIGHT} message, sent to its own driver at the start of the next tick by setting an alarm on the
     * clock, and the tunnel stops remembering it as waiting. If it cannot enter when it tries again, it waits again.
     *
     * @param driver  the driver driving the vehicle
     * @param vehicle the vehicle exiting the tunnel
     */
    @Override
    public void exit(Driver driver, Vehicle vehicle) {
        // TODO
    }

    public Map<Vehicle.Type, Integer> getCapacities() {
        return capacities;
    }

    @Override
    public String toString() {
        return String.format("TUNNEL %s", name);
    }
}
