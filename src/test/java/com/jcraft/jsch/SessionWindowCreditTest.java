package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.*;

import com.jcraft.jsch.annotations.SuppressForbiddenApi;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real packet splitting and credit accounting; encode is replaced by an in-memory sink. */
@Timeout(10)
class SessionWindowCreditTest {
  @Test
  void partialCreditSendsBeforeWaitingForTheRemainder() throws Exception {
    Capture session = new Capture();
    Channel channel = channel(session, 1024);
    byte[] payload = new byte[32768];
    new Random(526).nextBytes(payload);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread sender = sender(session, channel, payload, failure);
    try {
      assertTrue(session.first.await(75, TimeUnit.MILLISECONDS),
          "Available credit must be sent without the 100 ms polling wait");
      synchronized (channel) {
        assertEquals(0, channel.rwsize);
        assertEquals(1024, session.bytes.size());
      }
      // Small, irregular WINDOW_ADJUST bursts must not lose, duplicate or oversend bytes.
      Random grants = new Random(527);
      int remaining = payload.length - 1024;
      while (remaining > 0) {
        int grant = Math.min(remaining, 1 + grants.nextInt(4096));
        channel.addRemoteWindowSize(grant);
        remaining -= grant;
        Thread.sleep(1);
      }
      sender.join(2000);
      assertFalse(sender.isAlive());
      assertNull(failure.get());
      assertArrayEquals(payload, session.bytes.toByteArray());
      assertEquals(0, channel.rwsize);
      assertEquals(4 * 1024 * 1024, channel.lwsize_max);
    } finally {
      finish(channel, sender);
    }
  }

  @Test
  void zeroCreditDoesNotSendUntilWindowAdjust() throws Exception {
    Capture session = new Capture();
    Channel channel = channel(session, 0);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread sender = sender(session, channel, new byte[] {0, 7, -1}, failure);
    try {
      awaitWaiting(sender);
      assertEquals(0, session.bytes.size());
      channel.addRemoteWindowSize(3);
      sender.join(1000);
      assertFalse(sender.isAlive());
      assertNull(failure.get());
      assertArrayEquals(new byte[] {0, 7, -1}, session.bytes.toByteArray());
    } finally {
      finish(channel, sender);
    }
  }

  @Test
  void interruptedCreditWaitTerminatesAndPreservesInterrupt() throws Exception {
    Capture session = new Capture();
    Channel channel = channel(session, 0);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread sender = sender(session, channel, new byte[32], failure);
    try {
      awaitWaiting(sender);
      sender.interrupt();
      sender.join(300);
      assertFalse(sender.isAlive(), "Cancellation must not be swallowed by the credit wait");
      assertInstanceOf(InterruptedIOException.class, failure.get());
      assertTrue(sender.isInterrupted());
      assertEquals(0, session.bytes.size());
    } finally {
      finish(channel, sender);
    }
  }

  @Test
  // Fixture-only access to this library's private rekey state; no JDK internals.
  @SuppressForbiddenApi("jdk-reflection")
  void interruptedRekeyWaitTerminatesWithoutSending() throws Exception {
    Capture session = new Capture();
    Field rekey = Session.class.getDeclaredField("in_kex");
    rekey.setAccessible(true);
    rekey.setBoolean(session, true);
    Channel channel = channel(session, 100);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread sender = sender(session, channel, new byte[32], failure);
    try {
      awaitWaiting(sender);
      sender.interrupt();
      sender.join(300);
      assertFalse(sender.isAlive(), "Cancellation must not wait for rekey completion");
      assertInstanceOf(InterruptedIOException.class, failure.get());
      assertTrue(sender.isInterrupted());
      assertEquals(0, session.bytes.size());
    } finally {
      rekey.setBoolean(session, false);
      finish(channel, sender);
    }
  }

  private static Thread sender(Capture session, Channel channel, byte[] payload,
      AtomicReference<Throwable> failure) {
    Thread thread = new Thread(() -> {
      try {
        Buffer buffer = new Buffer(payload.length + 512);
        Packet packet = new Packet(buffer);
        packet.reset();
        buffer.putByte((byte) Session.SSH_MSG_CHANNEL_DATA);
        buffer.putInt(channel.getRecipient());
        buffer.putString(payload);
        session.write(packet, channel, payload.length);
      } catch (Throwable error) {
        failure.set(error);
      }
    }, "credit-test-sender");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private static Channel channel(Capture session, int credit) {
    ChannelDirectTCPIP channel = new ChannelDirectTCPIP();
    channel.setSession(session);
    channel.connected = true;
    channel.setRecipient(17);
    channel.setRemoteWindowSize(credit);
    channel.setLocalWindowSizeMax(4 * 1024 * 1024);
    channel.setLocalWindowSize(4 * 1024 * 1024);
    return channel;
  }

  private static void awaitWaiting(Thread thread) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertEquals(Thread.State.TIMED_WAITING, thread.getState());
  }

  private static void finish(Channel channel, Thread sender) throws Exception {
    channel.close = true;
    synchronized (channel) {
      channel.notifyAll();
    }
    sender.interrupt();
    sender.join(1000);
    assertFalse(sender.isAlive(), "Test must not leak a sender");
  }

  private static class Capture extends Session {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final CountDownLatch first = new CountDownLatch(1);

    Capture() throws JSchException {
      super(new JSch(), "fixture", "127.0.0.1", 22);
    }

    @Override
    public boolean isConnected() {
      return true;
    }

    @Override
    void encode(Packet packet) {
      int length = packet.buffer.index - 14;
      bytes.write(packet.buffer.buffer, 14, length);
      first.countDown();
    }
  }
}
