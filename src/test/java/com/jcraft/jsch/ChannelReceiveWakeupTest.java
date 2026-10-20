package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the actual channel pipe, without the Android app's notifying stream wrapper. */
@Timeout(10)
class ChannelReceiveWakeupTest {
  private static final int PACKET = 32 * 1024;
  private static final int CAPACITY = 1024 * 1024;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void consumingPacketPromptlyResumesSharedReceiver(boolean extended) throws Exception {
    ChannelDirectTCPIP channel = new ChannelDirectTCPIP();
    channel.init();
    Session session = new JSch().getSession("fixture", "127.0.0.1", 22);
    session.setConfig("max_input_buffer_size", Integer.toString(CAPACITY));
    channel.setSession(session);
    ChannelDirectTCPIP nextChannel = new ChannelDirectTCPIP();
    nextChannel.init();
    nextChannel.setSession(session);
    channel.setLocalWindowSizeMax(4 * CAPACITY);
    channel.setLocalWindowSize(4 * CAPACITY);
    AtomicReference<Thread> writer = new AtomicReference<>();
    ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "shared-ssh-receiver");
      writer.set(thread);
      return thread;
    });
    try (InputStream input = extended ? channel.getExtInputStream() : channel.getInputStream();
        InputStream nextInput = nextChannel.getInputStream()) {
      byte[] packet = new byte[PACKET];
      for (int i = 0; i < CAPACITY / PACKET; i++) {
        write(channel, extended, packet);
      }
      assertEquals(CAPACITY, input.available());
      Future<?> pending = worker.submit(() -> {
        write(channel, extended, packet);
        // The same session receiver must then service another channel, without polling delay.
        nextChannel.write(new byte[] {17}, 0, 1);
        return null;
      });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (writer.get().getState() != Thread.State.TIMED_WAITING
          && System.nanoTime() < deadline) {
        Thread.sleep(1);
      }
      synchronized (input) {
        assertEquals(Thread.State.TIMED_WAITING, writer.get().getState(),
            "Producer must be waiting for space in the full pipe");
        assertFalse(pending.isDone());
        assertEquals(PACKET, input.read(new byte[PACKET]));
      }
      pending.get(300, TimeUnit.MILLISECONDS);
      assertEquals(17, nextInput.read());
      assertEquals(CAPACITY, input.available());
      assertEquals(4 * CAPACITY, channel.lwsize_max);
      assertEquals(4 * CAPACITY, channel.lwsize);
    } finally {
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
      channel.disconnect();
      nextChannel.disconnect();
    }
  }

  private static void write(Channel channel, boolean extended, byte[] packet) throws Exception {
    if (extended) {
      channel.write_ext(packet, 0, packet.length);
    } else {
      channel.write(packet, 0, packet.length);
    }
  }
}
