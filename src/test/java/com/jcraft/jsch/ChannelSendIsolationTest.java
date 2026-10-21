package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.*;

import com.jcraft.jsch.annotations.SuppressForbiddenApi;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real public stream/pump paths: transport backpressure must not own incoming credit's monitor. */
@Timeout(12)
class ChannelSendIsolationTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void incomingCreditAndAnotherChannelDeliveryProgressWhileTransportIsBlocked(boolean pump)
      throws Exception {
    Sink sink = new Sink();
    Capture session = new Capture(sink);
    ChannelDirectTCPIP upload = channel(session, 128);
    session.opening = upload;
    byte[] payload = new byte[32];
    Arrays.fill(payload, (byte) 7);
    ByteArrayOutputStream echoBytes = new ByteArrayOutputStream();
    ChannelDirectTCPIP echo = channel(session, 128);
    echo.setOutputStream(echoBytes);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<?> sending = workers.submit(() -> {
        if (pump) {
          upload.setInputStream(new ByteArrayInputStream(payload));
          upload.thread = Thread.currentThread();
          upload.run();
        } else {
          OutputStream output = upload.getOutputStream();
          output.write(payload);
          output.flush();
        }
        return null;
      });
      assertTrue(sink.entered.await(1, TimeUnit.SECONDS));
      Future<?> receiver = workers.submit(() -> {
        upload.addRemoteWindowSize(32);
        session.deliverChannelData(echo, payload, 0, payload.length, false);
        return null;
      });
      assertDoesNotThrow(() -> receiver.get(300, TimeUnit.MILLISECONDS),
          "WINDOW_ADJUST and the next received channel must not wait for a blocked socket write");
      assertFalse(sending.isDone());
      assertArrayEquals(payload, echoBytes.toByteArray());
      sink.release.countDown();
      sending.get(2, TimeUnit.SECONDS);
      assertArrayEquals(payload, sink.data.toByteArray());
      assertEquals(128, upload.rwsize);
      assertEquals(4 * 1024 * 1024, upload.lwsize_max);
    } finally {
      sink.release.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void eofAndCloseRemainOrderedAfterTheDataAlreadyBeingWritten(boolean close) throws Exception {
    Sink sink = new Sink();
    Capture session = new Capture(sink);
    ChannelDirectTCPIP channel = channel(session, 128);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<?> sender = workers.submit(() -> {
        OutputStream output = channel.getOutputStream();
        output.write(new byte[32]);
        output.flush();
        return null;
      });
      assertTrue(sink.entered.await(1, TimeUnit.SECONDS));
      CountDownLatch started = new CountDownLatch(1);
      Future<?> control = workers.submit(() -> {
        started.countDown();
        if (close)
          channel.close();
        else
          channel.eof();
        return null;
      });
      assertTrue(started.await(1, TimeUnit.SECONDS));
      assertFalse(control.isDone());
      sink.release.countDown();
      sender.get(2, TimeUnit.SECONDS);
      control.get(2, TimeUnit.SECONDS);
      assertEquals(Arrays.asList(Session.SSH_MSG_CHANNEL_DATA,
          close ? Session.SSH_MSG_CHANNEL_CLOSE : Session.SSH_MSG_CHANNEL_EOF), sink.commands);
    } finally {
      sink.release.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void disconnectCanMarkAChannelClosedWhileWriterWaitsForCredit() throws Exception {
    Sink sink = new Sink();
    sink.release.countDown();
    Capture session = new Capture(sink);
    ChannelDirectTCPIP channel = channel(session, 0);
    AtomicReference<Thread> writer = new AtomicReference<>();
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<?> sending = workers.submit(() -> {
        writer.set(Thread.currentThread());
        OutputStream output = channel.getOutputStream();
        output.write(new byte[32]);
        assertThrows(IOException.class, output::flush);
        return null;
      });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while ((writer.get() == null || writer.get().getState() != Thread.State.TIMED_WAITING)
          && System.nanoTime() < deadline)
        Thread.sleep(1);
      assertNotNull(writer.get());
      assertEquals(Thread.State.TIMED_WAITING, writer.get().getState());
      Future<?> closing = workers.submit(channel::disconnect);
      sending.get(1, TimeUnit.SECONDS);
      closing.get(1, TimeUnit.SECONDS);
      assertTrue(channel.close);
      assertFalse(channel.connected);
      assertEquals(0, sink.data.size());
      assertTrue(sink.commands.contains(Session.SSH_MSG_CHANNEL_CLOSE));
    } finally {
      channel.close = true;
      sink.release.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  private static ChannelDirectTCPIP channel(Capture session, int credit) throws Exception {
    ChannelDirectTCPIP channel = new ChannelDirectTCPIP();
    channel.init();
    channel.setSession(session);
    channel.setHost("echo.fixture");
    channel.setPort(7);
    channel.setOrgIPAddress("127.0.0.1");
    channel.setOrgPort(12345);
    channel.connected = true;
    channel.open_confirmation = true;
    channel.setRecipient(17);
    channel.setRemotePacketSize(32768);
    channel.setRemoteWindowSize(credit);
    channel.setLocalWindowSizeMax(4 * 1024 * 1024);
    channel.setLocalWindowSize(4 * 1024 * 1024);
    return channel;
  }

  private static class Capture extends Session {
    ChannelDirectTCPIP opening;

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
    void encode(Packet packet) {}

    @Override
    void write(Packet packet) throws Exception {
      if (packet.buffer.getCommand() == SSH_MSG_CHANNEL_OPEN) {
        opening.open_confirmation = true;
        opening.setRecipient(17);
        return;
      }
      super.write(packet);
    }
  }

  private static class Sink extends OutputStream {
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    final List<Integer> commands = new ArrayList<>();
    final ByteArrayOutputStream data = new ByteArrayOutputStream();

    @Override
    public void write(int value) {
      throw new AssertionError("bulk required");
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      Buffer packet = new Buffer(bytes);
      packet.setOffSet(offset + 5);
      int command = packet.getByte();
      if (command == Session.SSH_MSG_CHANNEL_DATA) {
        entered.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS))
            throw new IOException("fixture timed out");
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IOException(error);
        }
        packet.getInt();
        byte[] payload = packet.getString();
        data.write(payload, 0, payload.length);
      }
      commands.add(command);
    }
  }
}
