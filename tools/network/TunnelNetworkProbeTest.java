package com.jcraft.jsch;

import java.util.*;
import java.util.concurrent.*;

/** Standalone assertions for benchmark pacing, independent of Android and remote hosts. */
public final class TunnelNetworkProbeTest {
  public static void main(String[] args) throws Exception {
    TunnelNetworkProbe.UploadPacer disabled = new TunnelNetworkProbe.UploadPacer(0);
    if (disabled.reserveDelayNanos(32768, 1000) != 0) throw new AssertionError("Disabled pacer waits");
    if (new TunnelNetworkProbe.UploadPacer(1024).reserveDelayNanos(32768, -1000) != 0)
      throw new AssertionError("Negative clock origin caused an artificial delay");
    TunnelNetworkProbe.UploadPacer shared = new TunnelNetworkProbe.UploadPacer(1024);
    ExecutorService workers = Executors.newFixedThreadPool(4);
    try {
      List<Future<Long>> tasks = new ArrayList<>();
      for (int index = 0; index < 8; index++) {
        tasks.add(workers.submit(() -> shared.reserveDelayNanos(32768, 1000)));
      }
      Set<Long> slots = new TreeSet<>();
      for (Future<Long> task : tasks) slots.add(task.get(2, TimeUnit.SECONDS));
      if (slots.size() != 8) throw new AssertionError("Writers did not share the aggregate budget");
      long expected = 0;
      for (long slot : slots) {
        if (slot != expected) throw new AssertionError("Offered aggregate rate exceeds 1024 kbit/s");
        expected += 256000000;
      }
      if (shared.reserveDelayNanos(32768, 3000000000L) != 0)
        throw new AssertionError("Idle time was not discarded");
      if (shared.reserveDelayNanos(32768, 3000000000L) != 256000000)
        throw new AssertionError("Idle time incorrectly accumulated burst credit");
    } finally {
      workers.shutdownNow();
      if (!workers.awaitTermination(2, TimeUnit.SECONDS)) throw new AssertionError("Leaked worker");
    }
    System.out.println("PASS: disabled, aggregate concurrent budget, no idle burst credit");
  }
}
