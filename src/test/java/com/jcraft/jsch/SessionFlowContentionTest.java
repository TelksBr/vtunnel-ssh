package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.*;

import com.jcraft.jsch.annotations.SuppressForbiddenApi;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Controlled transport backpressure, not a claim of monitor starvation after drain. */
@Timeout(12)
class SessionFlowContentionTest {
  @Test
  void fourUploadsSmallFlowAndKeepaliveExposeSharedWriteWait() throws Exception {
    SessionFlowMetrics metrics = new SessionFlowMetrics();
    Sink sink = new Sink();
    Capture session = new Capture(sink);
    session.setFlowMetrics(metrics);
    ExecutorService workers = Executors.newFixedThreadPool(6);
    try {
      Future<?> holder = workers.submit(() -> {
        send(session, 1, 32768);
        return null;
      });
      assertTrue(sink.entered.await(2, TimeUnit.SECONDS));
      Future<?>[] uploads = new Future<?>[3];
      for (int index = 0; index < uploads.length; index++) {
        final int recipient = index + 2;
        uploads[index] = workers.submit(() -> {
          send(session, recipient, 32768);
          return null;
        });
      }
      Future<?> small = workers.submit(() -> {
        send(session, 5, 32);
        return null;
      });
      Future<?> keepalive = workers.submit(() -> {
        session.sendKeepAliveMsg();
        return null;
      });
      awaitActive(metrics, SessionFlowMetrics.Phase.WRITE_LOCK_WAIT, 5);
      assertEquals(1, metrics.snapshot().getTimings().get(SessionFlowMetrics.Phase.TRANSPORT_WRITE)
          .getInFlight());
      assertFalse(small.isDone(), "The small flow shares the blocked transport");
      assertFalse(keepalive.isDone(), "Control cannot bypass an ongoing socket write");
      sink.release.countDown();
      holder.get(2, TimeUnit.SECONDS);
      for (Future<?> upload : uploads)
        upload.get(2, TimeUnit.SECONDS);
      small.get(2, TimeUnit.SECONDS);
      keepalive.get(2, TimeUnit.SECONDS);
      SessionFlowMetrics.Snapshot snapshot = metrics.snapshot();
      for (SessionFlowMetrics.Phase phase : new SessionFlowMetrics.Phase[] {
          SessionFlowMetrics.Phase.WRITE_LOCK_WAIT, SessionFlowMetrics.Phase.ENCODE,
          SessionFlowMetrics.Phase.TRANSPORT_WRITE}) {
        assertEquals(6, snapshot.getTimings().get(phase).getCount());
        assertEquals(0, snapshot.getTimings().get(phase).getInFlight());
        assertTrue(snapshot.getTimings().get(phase).getMaxNanos() > 0);
      }
      assertEquals(4 * 32768 + 32, snapshot.getRequestedDataBytes());
      assertEquals(0, snapshot.getFragments());
      assertEquals(1, sink.control);
      for (int recipient = 1; recipient <= 5; recipient++) {
        byte[] data = sink.received.get(recipient).toByteArray();
        assertEquals(recipient == 5 ? 32 : 32768, data.length);
        for (byte value : data)
          assertEquals((byte) recipient, value);
      }
    } finally {
      sink.release.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void transportFailureLeavesNoActiveTimingAndKeepsTheException() throws Exception {
    IOException expected = new IOException("fixture transport failure");
    Capture session = new Capture(new OutputStream() {
      @Override
      public void write(int value) throws IOException {
        throw expected;
      }

      @Override
      public void write(byte[] bytes, int offset, int length) throws IOException {
        throw expected;
      }
    });
    SessionFlowMetrics metrics = new SessionFlowMetrics();
    session.setFlowMetrics(metrics);
    assertSame(expected, assertThrows(IOException.class, () -> send(session, 1, 32)));
    assertEquals(1,
        metrics.snapshot().getTimings().get(SessionFlowMetrics.Phase.TRANSPORT_WRITE).getCount());
    for (SessionFlowMetrics.Timing timing : metrics.snapshot().getTimings().values())
      assertEquals(0, timing.getInFlight());
  }

  @Test
  void disablingDiagnosticsStopsCollectionWithoutChangingPacketDelivery() throws Exception {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    Capture session = new Capture(sink);
    send(session, 1, 32);
    int packetLength = sink.size();
    SessionFlowMetrics metrics = new SessionFlowMetrics();
    session.setFlowMetrics(metrics);
    send(session, 1, 32);
    session.setFlowMetrics(null);
    send(session, 1, 32);
    assertEquals(32, metrics.snapshot().getRequestedDataBytes());
    assertEquals(1,
        metrics.snapshot().getTimings().get(SessionFlowMetrics.Phase.ENCODE).getCount());
    assertEquals(3 * packetLength, sink.size());
    byte[] bytes = sink.toByteArray();
    assertArrayEquals(java.util.Arrays.copyOfRange(bytes, 0, packetLength),
        java.util.Arrays.copyOfRange(bytes, packetLength, 2 * packetLength));
    assertArrayEquals(java.util.Arrays.copyOfRange(bytes, 0, packetLength),
        java.util.Arrays.copyOfRange(bytes, 2 * packetLength, 3 * packetLength));
  }

  private static void awaitActive(SessionFlowMetrics metrics, SessionFlowMetrics.Phase phase,
      long expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (metrics.snapshot().getTimings().get(phase).getInFlight() != expected
        && System.nanoTime() < deadline)
      Thread.sleep(1);
    assertEquals(expected, metrics.snapshot().getTimings().get(phase).getInFlight());
  }

  private static void send(Capture session, int recipient, int length) throws Exception {
    ChannelDirectTCPIP channel = new ChannelDirectTCPIP();
    channel.setSession(session);
    channel.connected = true;
    channel.setRecipient(recipient);
    channel.setRemoteWindowSize(length);
    channel.setLocalWindowSizeMax(4 * 1024 * 1024);
    channel.setLocalWindowSize(4 * 1024 * 1024);
    Buffer buffer = new Buffer(length + 512);
    Packet packet = new Packet(buffer);
    packet.reset();
    buffer.putByte((byte) Session.SSH_MSG_CHANNEL_DATA);
    buffer.putInt(recipient);
    byte[] data = new byte[length];
    java.util.Arrays.fill(data, (byte) recipient);
    buffer.putString(data);
    try {
      session.write(packet, channel, length);
      assertEquals(0, channel.rwsize);
      assertEquals(4 * 1024 * 1024, channel.lwsize);
    } finally {
      channel.close = true;
      channel.disconnect();
    }
  }

  private static class Capture extends Session {
    @SuppressForbiddenApi("jdk-reflection")
    Capture(OutputStream sink) throws Exception {
      super(new JSch(), "fixture", "127.0.0.1", 22);
      IO output = new IO();
      output.setOutputStream(sink);
      Field field = Session.class.getDeclaredField("io");
      field.setAccessible(true);
      field.set(this, output);
    }

    @Override
    public boolean isConnected() {
      return true;
    }

    @Override
    void encode(Packet packet) { /* The real transport serialization is exercised. */ }
  }

  private static class Sink extends OutputStream {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final Map<Integer, ByteArrayOutputStream> received = new HashMap<>();
    int control;

    @Override
    public void write(int value) {
      throw new AssertionError("Expected bulk write");
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      if (entered.getCount() != 0) {
        entered.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS))
            throw new IOException("Fixture drain timed out");
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IOException(error);
        }
      }
      Buffer packet = new Buffer(bytes);
      packet.setOffSet(offset + 5);
      int command = packet.getByte();
      if (command == Session.SSH_MSG_GLOBAL_REQUEST) {
        control++;
      } else {
        int recipient = packet.getInt();
        byte[] payload = packet.getString();
        received.computeIfAbsent(recipient, key -> new ByteArrayOutputStream()).write(payload, 0,
            payload.length);
      }
    }
  }
}
