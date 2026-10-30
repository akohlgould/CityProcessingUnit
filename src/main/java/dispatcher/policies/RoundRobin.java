package dispatcher.policies;

/**
 * The Round Robin policy.
 * Vehicles go in first in, first out order, but each one can be driven for
 * at most {@code timeSlice} ticks at a time.
 * Each time a trip is picked, the dispatcher starts the driver's timer with the time slice.
 * If the timer runs out before the trip reaches the end of a road, the resulting {@code TIMER}
 * message moves the trip to the back of the ready line.
 */
public class RoundRobin extends FirstInFirstOut {

    private final int timeSlice;

    public RoundRobin(int timeSlice) {
        this.timeSlice = timeSlice;
    }

    @Override
    public int timeSlice() {
        return timeSlice;
    }
}
