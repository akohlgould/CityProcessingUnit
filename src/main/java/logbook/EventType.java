package logbook;

public enum EventType {
    ROAD_ENTER("entered road"),
    ROAD_EXIT("exited road"),
    TUNNEL_ENTER("entered tunnel"),
    TUNNEL_ENTER_FAILED("failed to enter tunnel"),
    TUNNEL_EXIT("exited tunnel"),
    TRIP_START("started trip"),
    TRIP_COMPLETE("completed trip");

    private final String name;

    EventType(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return this.name;
    }
}
