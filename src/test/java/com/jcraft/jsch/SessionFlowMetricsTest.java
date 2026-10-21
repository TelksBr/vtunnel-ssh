package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class SessionFlowMetricsTest {
  @Test
  void snapshotSeparatesPendingFromCompletedAndIsImmutable() {
    AtomicLong clock = new AtomicLong(100);
    SessionFlowMetrics metrics = new SessionFlowMetrics(clock::get);
    long start = metrics.begin(SessionFlowMetrics.Phase.ENCODE);
    SessionFlowMetrics.Snapshot pending = metrics.snapshot();
    assertEquals(1, pending.getTimings().get(SessionFlowMetrics.Phase.ENCODE).getInFlight());
    assertEquals(0, pending.getTimings().get(SessionFlowMetrics.Phase.ENCODE).getCount());
    clock.addAndGet(75);
    metrics.end(SessionFlowMetrics.Phase.ENCODE, start);
    SessionFlowMetrics.Timing complete =
        metrics.snapshot().getTimings().get(SessionFlowMetrics.Phase.ENCODE);
    assertEquals(75, complete.getTotalNanos());
    assertEquals(75, complete.getMaxNanos());
    assertEquals(1, complete.getCount());
    assertEquals(0, complete.getInFlight());
    assertEquals(1, pending.getTimings().get(SessionFlowMetrics.Phase.ENCODE).getInFlight());
    assertThrows(UnsupportedOperationException.class, () -> pending.getTimings().clear());
  }

  @Test
  void concurrentWritersDoNotLoseCounts() throws Exception {
    SessionFlowMetrics metrics = new SessionFlowMetrics();
    ExecutorService workers = Executors.newFixedThreadPool(6);
    try {
      Future<?>[] tasks = new Future<?>[6];
      for (int index = 0; index < tasks.length; index++)
        tasks[index] = workers.submit(() -> {
          for (int n = 0; n < 1000; n++) {
            long start = metrics.begin(SessionFlowMetrics.Phase.ENCODE);
            metrics.requested(32);
            metrics.fragmented();
            metrics.end(SessionFlowMetrics.Phase.ENCODE, start);
          }
        });
      for (Future<?> task : tasks)
        task.get(3, TimeUnit.SECONDS);
      SessionFlowMetrics.Snapshot snapshot = metrics.snapshot();
      SessionFlowMetrics.Timing encode = snapshot.getTimings().get(SessionFlowMetrics.Phase.ENCODE);
      assertEquals(6000, encode.getCount());
      assertEquals(0, encode.getInFlight());
      assertTrue(encode.getTotalNanos() >= encode.getMaxNanos());
      assertEquals(6000 * 32, snapshot.getRequestedDataBytes());
      assertEquals(6000, snapshot.getFragments());
    } finally {
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void blockedChannelDeliveryIsVisibleWithoutBlockingSnapshot(boolean extended) throws Exception {
    Session session = new JSch().getSession("fixture", "127.0.0.1", 22);
    SessionFlowMetrics metrics = new SessionFlowMetrics();
    session.setFlowMetrics(metrics);
    ChannelDirectTCPIP channel = new ChannelDirectTCPIP();
    channel.init();
    channel.setSession(session);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ByteArrayOutputStream received = new ByteArrayOutputStream();
    OutputStream sink = new OutputStream() {
      @Override
      public void write(int value) {
        throw new AssertionError("Expected bulk data");
      }

      @Override
      public void write(byte[] bytes, int offset, int length) throws IOException {
        entered.countDown();
        try {
          if (!release.await(3, TimeUnit.SECONDS))
            throw new IOException("Fixture drain timeout");
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IOException(error);
        }
        received.write(bytes, offset, length);
      }
    };
    if (extended)
      channel.setExtOutputStream(sink);
    else
      channel.setOutputStream(sink);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    byte[] data = {0, 1, 7, -1};
    try {
      Future<?> delivered = worker.submit(() -> {
        session.deliverChannelData(channel, data, 0, data.length, extended);
        return null;
      });
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      assertEquals(1, metrics.snapshot().getTimings().get(SessionFlowMetrics.Phase.CHANNEL_DELIVERY)
          .getInFlight());
      release.countDown();
      delivered.get(1, TimeUnit.SECONDS);
      assertArrayEquals(data, received.toByteArray());
      assertEquals(0, metrics.snapshot().getTimings().get(SessionFlowMetrics.Phase.CHANNEL_DELIVERY)
          .getInFlight());
      assertEquals(1, metrics.snapshot().getTimings().get(SessionFlowMetrics.Phase.CHANNEL_DELIVERY)
          .getCount());
    } finally {
      release.countDown();
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
      channel.disconnect();
    }
  }
}
