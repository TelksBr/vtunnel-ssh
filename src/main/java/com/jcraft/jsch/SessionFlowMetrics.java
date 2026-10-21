package com.jcraft.jsch;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongSupplier;

/**
 * Opt-in, bounded per-session timings. Never retains packets, destinations or credentials.
 * Durations describe completed intervals; a blocked operation is visible through in-flight counts,
 * not through the completed maximum. Credit and rekey counts include each polling interval. ENCODE
 * includes packet construction, MAC and encryption; TRANSPORT_WRITE includes IO.put/flush.
 */
public final class SessionFlowMetrics {
  public enum Phase {
    CREDIT_WAIT, REKEY_WAIT, WRITE_LOCK_WAIT, ENCODE, TRANSPORT_WRITE, CHANNEL_DELIVERY
  }

  private final LongSupplier clock;
  private final AtomicLongArray counts = new AtomicLongArray(Phase.values().length);
  private final AtomicLongArray totals = new AtomicLongArray(Phase.values().length);
  private final AtomicLongArray maxima = new AtomicLongArray(Phase.values().length);
  private final AtomicLongArray active = new AtomicLongArray(Phase.values().length);
  private final AtomicLong requestedDataBytes = new AtomicLong();
  private final AtomicLong fragments = new AtomicLong();

  public SessionFlowMetrics() {
    this(System::nanoTime);
  }

  SessionFlowMetrics(LongSupplier clock) {
    this.clock = clock;
  }

  long begin(Phase phase) {
    active.incrementAndGet(phase.ordinal());
    return clock.getAsLong();
  }

  void end(Phase phase, long started) {
    int index = phase.ordinal();
    long elapsed = Math.max(0, clock.getAsLong() - started);
    totals.addAndGet(index, elapsed);
    long previous;
    do {
      previous = maxima.get(index);
    } while (elapsed > previous && !maxima.compareAndSet(index, previous, elapsed));
    counts.incrementAndGet(index);
    active.decrementAndGet(index);
  }

  void requested(int bytes) {
    requestedDataBytes.addAndGet(bytes);
  }

  void fragmented() {
    fragments.incrementAndGet();
  }

  /** Nonblocking observation; fields are eventually consistent while operations are active. */
  public Snapshot snapshot() {
    Map<Phase, Timing> timings = new EnumMap<>(Phase.class);
    for (Phase phase : Phase.values()) {
      int index = phase.ordinal();
      timings.put(phase,
          new Timing(counts.get(index), totals.get(index), maxima.get(index), active.get(index)));
    }
    return new Snapshot(timings, requestedDataBytes.get(), fragments.get());
  }

  public static final class Snapshot {
    private final Map<Phase, Timing> timings;
    private final long requestedDataBytes;
    private final long fragments;

    private Snapshot(Map<Phase, Timing> timings, long requestedDataBytes, long fragments) {
      this.timings = Collections.unmodifiableMap(timings);
      this.requestedDataBytes = requestedDataBytes;
      this.fragments = fragments;
    }

    public Map<Phase, Timing> getTimings() {
      return timings;
    }

    /** Bytes requested by channel writers, not confirmed delivery to the remote destination. */
    public long getRequestedDataBytes() {
      return requestedDataBytes;
    }

    public long getFragments() {
      return fragments;
    }
  }

  public static final class Timing {
    private final long count;
    private final long totalNanos;
    private final long maxNanos;
    private final long inFlight;

    private Timing(long count, long totalNanos, long maxNanos, long inFlight) {
      this.count = count;
      this.totalNanos = totalNanos;
      this.maxNanos = maxNanos;
      this.inFlight = inFlight;
    }

    public long getCount() {
      return count;
    }

    public long getTotalNanos() {
      return totalNanos;
    }

    public long getMaxNanos() {
      return maxNanos;
    }

    public long getInFlight() {
      return inFlight;
    }
  }
}
