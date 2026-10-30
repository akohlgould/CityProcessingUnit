package dispatcher.policies;

import trip.Trip;

import java.util.List;

/**
 * The VIP First policy.
 * The trip with the lowest priority number goes first, since it is the most important.
 * Trips with the same priority number go in the order they joined the ready line.
 */
public class VIPFirst implements Policy {

    @Override
    public Trip pickNext(List<Trip> readyLine) {
        Trip best = readyLine.getFirst();
        for (int i = 1; i < readyLine.size(); i++) {
            Trip trip = readyLine.get(i);
            if (trip.getPriority() < best.getPriority()) {
                best = trip;
            }
        }
        return best;
    }
}
