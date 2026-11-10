package trip;

/**
 * A vehicle, identified by its ID.
 * Just like the process ID of a process.
 * <p>
 * A vehicle is either a car or a truck. The type only matters in a {@code Tunnel}, which holds one type of vehicle
 * at a time.
 */
public record Vehicle(int id, Type type) {

    public enum Type {
        CAR,
        TRUCK
    }

    /**
     * Creates a car with the given ID, as in part A.
     *
     * @param id the vehicle ID
     */
    public Vehicle(int id) {
        this(id, Type.CAR);
    }
}
