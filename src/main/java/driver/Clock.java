package driver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The city clock, which counts ticks. Just like a CPU/hardware clock.
 * <p>
 * In part B several drivers drive at the same time, each on its own thread, and the clock keeps them in step: no
 * driver starts a tick until every driver has finished the one before. A thread that takes part in the ticks first
 * signs up with {@code signalNewWaiter()}, then calls {@code waitForTick()} at the start of each of its ticks. The
 * clock's own thread, started by {@code startClock()}, calls {@code startNextTick()} in a loop: it waits until every
 * waiter is waiting, starts the tick, runs the tick's alarms, and only then lets the waiters continue.
 * <p>
 * The clock may run actions at the start of a given tick, before any driver does anything in that tick.
 * Red lights use this to signal green after a fixed number of ticks, and trips use it to arrive: for example,
 * {@code clock.setAlarmAt(4, () -> dispatcher.addNewTrip(trip))} makes a trip arrive at tick 4.
 * <p>
 * Every method may be called by several threads at the same time, so the clock must be thread-safe.
 * Use the locks and condition variables in {@code java.util.concurrent.locks}; do not use any other class from
 * {@code java.util.concurrent}.
 */
public class Clock {

    private final Map<Integer, List<Runnable>> alarms = new HashMap<>();
    private int tickNum;

    // TODO: add the fields you need, such as a lock, its conditions, the number of waiters, and the clock thread

    /**
     * Starts a separate thread that calls {@code startNextTick()} in a loop until {@code stopClock()} is called.
     * Does nothing if a clock thread is already running due to a previous call.
     */
    public void startClock() {
        // TODO
    }

    /**
     * Stops the clock thread created by a call to {@code startClock()}, and returns once it has stopped.
     * Does nothing if no clock thread is running.
     */
    public void stopClock() {
        // TODO
    }

    /**
     * Starts the next tick after waiting until there is at least one waiter and every waiter is waiting in
     * {@code waitForTick()}. Starting the tick adds 1 to the tick number, then runs the tick's alarms by calling
     * {@code runAlarms()}, then lets every waiter return from {@code waitForTick()}.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void startNextTick() throws InterruptedException {
        // TODO
    }

    /**
     * Waits until {@code startNextTick()} has started the next tick and run its alarms. The next tick can only start
     * once every waiter is waiting here, so a thread must be a waiter (see {@code signalNewWaiter()}) to call this.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void waitForTick() throws InterruptedException {
        // TODO
    }

    /**
     * Starts the given number of ticks by calling {@code startNextTick()} in a loop.
     *
     * @param numTicks the number of ticks to start
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void startTicks(int numTicks) throws InterruptedException {
        for (int i = 0; i < numTicks; i++) {
            startNextTick();
        }
    }

    /**
     * Calls {@code waitForTick()} the given number of times.
     *
     * @param numTicks the number of ticks to wait for
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void waitForTicks(int numTicks) throws InterruptedException {
        for (int i = 0; i < numTicks; i++) {
            waitForTick();
        }
    }

    /**
     * Adds one waiter: from now on, {@code startNextTick()} also waits for one more thread to call
     * {@code waitForTick()} before starting a tick.
     */
    public void signalNewWaiter() {
        // TODO
    }

    /**
     * Removes one waiter: from now on, {@code startNextTick()} waits for one less thread. If every remaining waiter
     * is already waiting, the next tick can now start. Does nothing if there are no waiters.
     */
    public void signalRemoveWaiter() {
        // TODO
    }

    /**
     * Runs every alarm set for the current tick, in the order the alarms were set, and forgets them.
     * {@code startNextTick()} calls this right after adding 1 to the tick number.
     */
    private void runAlarms() {
        List<Runnable> due = alarms.remove(tickNum);
        if (due != null) {
            due.forEach(Runnable::run);
        }
    }

    /**
     * Sets an alarm that runs the given action at the start of the given tick,
     * before any driver does anything in that tick.
     * Alarms set for the same tick run in the order they were set.
     * TODO: make this method thread-safe; drivers set alarms from their own threads.
     *
     * @param tick   the tick to run the action at, which must be after the current tick
     * @param action the action to run
     */
    public void setAlarmAt(int tick, Runnable action) {
        if (tick <= tickNum) {
            throw new IllegalArgumentException("tick " + tick + " is not after the current tick " + tickNum);
        }
        alarms.computeIfAbsent(tick, t -> new ArrayList<>()).add(action);
    }

    /**
     * Sets an alarm that runs the given action at the start of the tick that is {@code numTicks} ticks from now.
     * TODO: make this method thread-safe.
     *
     * @param numTicks the number of ticks from now, at least 1
     * @param action   the action to run
     */
    public void setAlarm(int numTicks, Runnable action) {
        if (numTicks < 1) {
            throw new IllegalArgumentException("numTicks must be at least 1");
        }
        setAlarmAt(tickNum + numTicks, action);
    }

    /**
     * Returns whether any alarms have not run yet, such as trips that have not arrived.
     * TODO: make this method thread-safe.
     *
     * @return true if an alarm is set for a later tick, otherwise false
     */
    public boolean hasAlarms() {
        return !alarms.isEmpty();
    }

    /**
     * Returns the current tick number.
     * TODO: make this method thread-safe.
     *
     * @return the number of ticks that have been started; 0 before the first tick
     */
    public int getTickNum() {
        return tickNum;
    }
}
