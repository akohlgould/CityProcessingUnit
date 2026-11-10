# City Processing Unit: Part B

In part A, one driver (one CPU core) drove many vehicles (processes), and the **dispatcher** you wrote decided which
vehicle the driver drove next. In part B the company hires more drivers. Each driver has its own dispatcher and drives
on its own **thread**, and all the drivers drive at the same time. They share three things: the city **clock**, the
**logbook**, and **tunnels**, roads that every driver can use. Shared things are only safe if they are synchronized, and
that is what you write in part B.

| In the city | In an operating system |
|---|---|
| driver | CPU core |
| (new) several drivers on their own threads | several cores running at the same time |
| vehicle and its ID | process and its process ID |
| trip | process control block (PCB) |
| route, made of roads | program, made of CPU bursts |
| stop sign / red light / destination | yield / wait for I/O / exit |
| message to the dispatcher | interrupt |
| dispatcher / policy | scheduler / scheduling algorithm |
| ready line / waiting list | ready queue / wait queue |
| timer and time slice | hardware timer and quantum |
| (new) clock that waits for every driver before the next tick | barrier |
| (new) logbook written by every driver | shared buffer, read with a blocking `take()` |
| (new) tunnel: up to 3 cars, or 1 truck | critical section guarding a shared resource |
| (new) vehicle refused at a tunnel, then a green light | process blocked on a lock, then woken up |

The handout explains part B in full. **Read it before you start:**
[City Processing Unit handout, part B](https://moodle.brandeis.edu/mod/resource/view.php?id=398692)

## Getting Part B

Part B is a branch of the same repository. Commit your part A work first, then merge part B into it:

```
git add -A
git commit -m "PA-2A"
git fetch origin
git merge origin/pa2b
```

Part B does not change the five files you wrote in part A, so the merge keeps your work. If you changed any other
file in part A, git may report a conflict in that file: take part B's version of it.

## Structural Overview

```
src/main/java
├── dispatcher/
│   ├── Dispatcher.java                     yours, from part A
│   └── policies/                           yours, from part A (Policy.java is given)
├── driver/
│   ├── Driver.java                         drives one step per tick, or goes to the dispatcher; can drive on its own thread
│   ├── Clock.java                          Task 1: keeps every driver in step, and runs alarms
│   └── Message.java                        STOP_SIGN, RED_LIGHT, GREEN_LIGHT, DESTINATION, or TIMER for a vehicle
├── trip/
│   ├── Trip.java                           where a vehicle was left, its stretch lengths, and its priority
│   ├── Route.java                          the roads a trip drives, in order
│   ├── Road.java                           a road; it takes as many ticks to drive as its length
│   ├── Tunnel.java                         Task 3: a road every driver shares
│   ├── Location.java                       one spot on a road
│   ├── Vehicle.java                        a vehicle ID and its type, CAR or TRUCK
│   └── stoppoints/                         StopPoint, StopSign, RedLight, Destination
└── logbook/
    ├── Logbook.java                        Task 2: a record of what happened, written by every driver
    └── Event.java, EventType.java
src/test/java
├── ClockTest.java                          
├── LogbookTest.java                        
├── TunnelTests.java                        
├── CityTests.java                          several drivers at once: everything together
├── DispatcherTests.java, PolicyTests.java, DriverTests.java    your part A work, now on the shared clock
└── TestUtilities.java
```

**The tunnel.** A tunnel holds one type of vehicle at a time: at most 3 cars, or 1 truck. A vehicle that cannot enter
is treated like a vehicle at a red light: its driver gets a `RED_LIGHT` message right away and drives something else.
When a vehicle leaves the tunnel, every vehicle waiting for it gets a `GREEN_LIGHT` message at the start of the next
tick, and tries again when its dispatcher picks it. Your dispatcher from part A already handles both messages.

## Tasks for You

Write the three classes below. The Javadoc comment above each class and method says exactly what it must do, so read
those comments along with the handout.

| Task | File | What you write |
|---|---|---|
| 1 | `driver/Clock.java` | `startClock()`, `stopClock()`, `startNextTick()`, `waitForTick()`, `signalNewWaiter()`, `signalRemoveWaiter()`, and make the alarm methods and `getTickNum()` thread-safe |
| 2 | `logbook/Logbook.java` | make every method thread-safe, and write `take()` |
| 3 | `trip/Tunnel.java` | `enter()`, `exit()`, and the fields you need |

- **Do Task 1 first.** Every driver waits for the clock, so the tests of your part A work and most other tests only pass
  once the clock works.
- **Do not change any other file**, including the tests. You may still fix your part A files if a test shows a bug.
- **Synchronize with the basic tools:** `synchronized`, `wait()` and `notify()`/`notifyAll()`, or the locks and
  condition variables in `java.util.concurrent.locks`. Do not use thread-safe collections or any other class from
  `java.util.concurrent`. The Javadoc of each class says which tool it expects.

## How to Test

**Requirements:** Java 21 or newer (`java -version`) and Maven (`mvn -v`).

From project root folder:

```
mvn test                                          # run all the tests
mvn test -Dtest=ClockTest                         # run one test class
mvn test -Dtest='TunnelTests$RaceConditionTests'  # run one nested test class (keep the quotes)
mvn test -Dtest='ClockTest$BasicTests#testRemoveWaiter'   # run one test
```

A race condition does not show up on every run. The race-condition tests repeat themselves, but run the whole suite
several times before you submit. Each test fails if it takes longer than one or two seconds, so a deadlock shows up as
a failed test.
