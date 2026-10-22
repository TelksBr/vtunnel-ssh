package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.*;

import com.jcraft.jsch.annotations.SuppressForbiddenApi;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Drive the real receive loop with framed packets and the real outgoing credit wait. */
@Timeout(12)
class ChannelWindowAdjustIsolationTest {
  private static final int WINDOW = 4 * 1024 * 1024;

  @org.junit.jupiter.api.Test
  void consumerWaitingToWriteCreditDoesNotHoldTheReaderOrReceiveStorage() throws Exception {
    RecordingSink sink = new RecordingSink();
    sink.blockedCommand = Session.SSH_MSG_CHANNEL_WINDOW_ADJUST;
    Capture session = new Capture(sink);
    ChannelDirectTCPIP download = channel(session), other = channel(session);
    download.connected = false;
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try (InputStream input = download.getInputStream()) {
      download.connected = true;
      byte[] block = new byte[16384];
      for (int n = 0; n < 32; n++)
        session.incoming.add(data(download, block, false));
      workers.submit(session::run);
      await(() -> {
        try {
          return input.available() == 524288;
        } catch (IOException failure) {
          throw new UncheckedIOException(failure);
        }
      }, "Receive data did not arrive");
      Future<Integer> reading = workers.submit(() -> input.read(new byte[262144]));
      assertTrue(sink.entered.await(1, TimeUnit.SECONDS));
      session.incoming.add(data(download, block, false));
      session.incoming.add(credit(other, 123));
      await(() -> other.rwsize == 123,
          "A consumer waiting on the transport must not stall incoming packets");
      assertEquals(262144 + 16384, input.available());
      sink.release.countDown();
      assertEquals(262144, reading.get(1, TimeUnit.SECONDS));
    } finally {
      sink.release.countDown();
      download.close = other.close = true;
      session.thread = null;
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
      session.disconnect();
    }
  }

  @org.junit.jupiter.api.Test
  void stalledConsumerDoesNotBlockOtherChannelOrIncomingCredit() throws Exception {
    Capture session = new Capture(new RecordingSink());
    session.setConfig("max_input_buffer_size", "1048576");
    ChannelDirectTCPIP stalled = channel(session);
    stalled.connected = false;
    ChannelDirectTCPIP echo = channel(session);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (InputStream input = stalled.getInputStream()) {
      stalled.connected = true;
      CountDownLatch delivered = new CountDownLatch(1);
      echo.setOutputStream(new ByteArrayOutputStream() {
        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
          super.write(bytes, offset, length);
          delivered.countDown();
        }
      });
      byte[] block = new byte[16384];
      for (int n = 0; n < WINDOW / block.length; n++)
        session.incoming.add(data(stalled, block, false));
      session.incoming.add(credit(echo, 8192));
      session.incoming.add(data(echo, new byte[] {42}, false));
      worker.submit(session::run);
      assertTrue(delivered.await(2, TimeUnit.SECONDS),
          "A consumer stalled beyond 1 MiB must not block the shared SSH reader");
      assertEquals(8192, echo.rwsize);
      assertEquals(WINDOW, input.available());
      assertEquals(0, stalled.lwsize, "Unread bytes must not earn new credit");
      byte[] received = new byte[WINDOW];
      new DataInputStream(input).readFully(received);
      assertArrayEquals(new byte[WINDOW], received);
      assertTrue(stalled.lwsize > 0, "Consumption must restore credit");
    } finally {
      stalled.close = echo.close = true;
      session.thread = null;
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
      session.disconnect();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void windowAdjustmentIsOrderedOrSuppressedWhenCloseRacesWithTransport(boolean alreadyWriting)
      throws Exception {
    RecordingSink sink = new RecordingSink();
    sink.blockedCommand =
        alreadyWriting ? Session.SSH_MSG_CHANNEL_WINDOW_ADJUST : Session.SSH_MSG_CHANNEL_DATA;
    Capture session = new Capture(sink);
    SessionFlowMetrics metrics = new SessionFlowMetrics();
    session.setFlowMetrics(metrics);
    ChannelDirectTCPIP download = channel(session);
    ChannelDirectTCPIP other = channel(session);
    download.setLocalWindowSize(WINDOW / 2 + 4);
    download.setOutputStream(new ByteArrayOutputStream());
    byte[] bytes = new byte[8];
    ExecutorService workers = Executors.newFixedThreadPool(3);
    try {
      Future<?> sending = null;
      if (!alreadyWriting) {
        other.setRemoteWindowSize(bytes.length);
        sending = workers.submit(() -> {
          OutputStream output = other.getOutputStream();
          output.write(bytes);
          output.flush();
          return null;
        });
        assertTrue(sink.entered.await(1, TimeUnit.SECONDS));
      }
      session.incoming.add(data(download, bytes, false));
      workers.submit(session::run);
      if (alreadyWriting)
        assertTrue(sink.entered.await(1, TimeUnit.SECONDS));
      else
        await(() -> metrics.snapshot().getTimings().get(SessionFlowMetrics.Phase.WRITE_LOCK_WAIT)
            .getInFlight() == 1, "Window update did not reach the transport lock");
      Future<?> closing = workers.submit(download::close);
      await(() -> download.close, "Close did not mark the channel closed");
      sink.release.countDown();
      closing.get(2, TimeUnit.SECONDS);
      if (sending != null)
        sending.get(2, TimeUnit.SECONDS);
      assertTrue(session.firstProcessed.await(2, TimeUnit.SECONDS),
          "Receive loop did not finish the window update");
      assertEquals(WINDOW, download.lwsize);
      assertEquals(
          alreadyWriting
              ? Arrays.asList(Session.SSH_MSG_CHANNEL_WINDOW_ADJUST, Session.SSH_MSG_CHANNEL_CLOSE)
              : Arrays.asList(Session.SSH_MSG_CHANNEL_DATA, Session.SSH_MSG_CHANNEL_CLOSE),
          sink.commands);
      assertEquals(
          alreadyWriting ? Collections.singletonList(WINDOW / 2 + 4) : Collections.emptyList(),
          sink.adjustments);
      assertEquals(0, metrics.snapshot().getTimings().get(SessionFlowMetrics.Phase.WRITE_LOCK_WAIT)
          .getInFlight());
    } finally {
      sink.release.countDown();
      download.close = other.close = true;
      session.thread = null;
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
      session.disconnect();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void receiveCreditDoesNotWaitForAnUploadWaitingForPeerCredit(boolean extended) throws Exception {
    RecordingSink sink = new RecordingSink();
    Capture session = new Capture(sink);
    ChannelDirectTCPIP upload = channel(session);
    ChannelDirectTCPIP echo = channel(session);
    upload.setLocalWindowSize(WINDOW / 2 + 4);
    ByteArrayOutputStream downloaded = new ByteArrayOutputStream();
    if (extended)
      upload.setExtOutputStream(downloaded);
    else
      upload.setOutputStream(downloaded);
    CountDownLatch echoDelivered = new CountDownLatch(1);
    ByteArrayOutputStream echoed = new ByteArrayOutputStream();
    echo.setOutputStream(new OutputStream() {
      @Override
      public void write(int value) {
        throw new AssertionError("bulk required");
      }

      @Override
      public void write(byte[] bytes, int offset, int length) {
        echoed.write(bytes, offset, length);
        echoDelivered.countDown();
      }
    });
    byte[] bytes = {0, 1, 2, 3, -1, 7, 8, 9};
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<?> sending = workers.submit(() -> {
        OutputStream output = upload.getOutputStream();
        output.write(bytes);
        output.flush();
        return null;
      });
      awaitCreditWait(upload);
      session.incoming.add(data(upload, bytes, extended));
      session.incoming.add(credit(upload, bytes.length));
      session.incoming.add(data(echo, bytes, false));
      workers.submit(session::run);
      assertTrue(echoDelivered.await(1, TimeUnit.SECONDS),
          "The reader must send receive credit and process subsequent credit/data packets");
      sending.get(1, TimeUnit.SECONDS);
      assertArrayEquals(bytes, downloaded.toByteArray());
      assertArrayEquals(bytes, echoed.toByteArray());
      assertArrayEquals(bytes, sink.data.toByteArray());
      assertEquals(WINDOW, upload.lwsize_max);
      assertEquals(WINDOW, upload.lwsize);
      assertEquals(0, upload.rwsize);
      assertEquals(Collections.singletonList(WINDOW / 2 + 4), sink.adjustments);
      assertEquals(
          Arrays.asList(Session.SSH_MSG_CHANNEL_WINDOW_ADJUST, Session.SSH_MSG_CHANNEL_DATA),
          sink.commands);
    } finally {
      upload.close = echo.close = true;
      upload.addRemoteWindowSize(0);
      session.thread = null;
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
      session.disconnect();
    }
  }

  private static void awaitCreditWait(Channel channel) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      synchronized (channel) {
        if (channel.notifyme > 0)
          return;
      }
      Thread.sleep(1);
    }
    fail("Upload did not reach the remote-credit wait");
  }

  private static void await(BooleanSupplier condition, String message) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean())
        return;
      Thread.sleep(1);
    }
    fail(message);
  }

  private static ChannelDirectTCPIP channel(Capture session) throws Exception {
    ChannelDirectTCPIP channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
    channel.connected = channel.open_confirmation = true;
    channel.setRecipient(100 + channel.id);
    channel.setRemotePacketSize(32768);
    channel.setRemoteWindowSize(0);
    channel.setLocalWindowSizeMax(WINDOW);
    channel.setLocalWindowSize(WINDOW);
    return channel;
  }

  private static Buffer header(Channel channel, int command) {
    Buffer buffer = new Buffer(128);
    buffer.putInt(0);
    buffer.putByte((byte) 0);
    buffer.putByte((byte) command);
    buffer.putInt(channel.id);
    return buffer;
  }

  private static Buffer data(Channel channel, byte[] bytes, boolean extended) {
    Buffer buffer = header(channel,
        extended ? Session.SSH_MSG_CHANNEL_EXTENDED_DATA : Session.SSH_MSG_CHANNEL_DATA);
    if (extended)
      buffer.putInt(1);
    buffer.checkFreeSize(bytes.length + 4);
    buffer.putString(bytes);
    return buffer;
  }

  private static Buffer credit(Channel channel, int bytes) {
    Buffer buffer = header(channel, Session.SSH_MSG_CHANNEL_WINDOW_ADJUST);
    buffer.putInt(bytes);
    return buffer;
  }

  private static class Capture extends Session {
    final BlockingQueue<Buffer> incoming = new LinkedBlockingQueue<>();
    final CountDownLatch firstProcessed = new CountDownLatch(1);
    int reads;

    @SuppressForbiddenApi("jdk-reflection")
    Capture(OutputStream sink) throws Exception {
      super(new JSch(), "fixture", "127.0.0.1", 22);
      IO output = new IO();
      output.setOutputStream(sink);
      Field field = Session.class.getDeclaredField("io");
      field.setAccessible(true);
      field.set(this, output);
      field = Session.class.getDeclaredField("isConnected");
      field.setAccessible(true);
      field.setBoolean(this, true);
    }

    @Override
    Buffer read(Buffer buffer) throws Exception {
      if (reads++ > 0)
        firstProcessed.countDown();
      Buffer next = incoming.take();
      buffer.reset();
      buffer.putByte(next.buffer, 0, next.index);
      return buffer;
    }

    @Override
    void encode(Packet packet) {}
  }

  private static class RecordingSink extends OutputStream {
    int blockedCommand = -1;
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    final List<Integer> commands = new CopyOnWriteArrayList<>();
    final List<Integer> adjustments = new CopyOnWriteArrayList<>();
    final ByteArrayOutputStream data = new ByteArrayOutputStream();

    @Override
    public void write(int value) {
      throw new AssertionError("bulk required");
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      Buffer buffer = new Buffer(bytes);
      buffer.setOffSet(offset + 5);
      int command = buffer.getByte();
      if (command == blockedCommand) {
        entered.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS))
            throw new IOException("Fixture transport timeout");
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IOException(error);
        }
      }
      buffer.getInt();
      if (command == Session.SSH_MSG_CHANNEL_WINDOW_ADJUST)
        adjustments.add(buffer.getInt());
      else if (command == Session.SSH_MSG_CHANNEL_DATA) {
        byte[] payload = buffer.getString();
        data.write(payload, 0, payload.length);
      }
      commands.add(command);
    }
  }
}
