package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class ChannelReceiveLifecycleTest {
  @ParameterizedTest
  @CsvSource({"false,0", "false,1", "false,16384", "false,32767", "true,0", "true,1", "true,16384",
      "true,32767"})
  void growingWrappedFullPipePreservesUnreadPrefix(boolean extended, int consumed)
      throws Exception {
    ChannelDirectTCPIP channel = channel(1024 * 1024);
    channel.connected = true;
    byte[] expected = new byte[49152 + consumed];
    new Random(804).nextBytes(expected);
    try (InputStream input = extended ? channel.getExtInputStream() : channel.getInputStream()) {
      write(channel, extended, expected, 0, 32768);
      byte[] prefix = new byte[consumed];
      assertEquals(prefix.length, input.read(prefix));
      assertArrayEquals(Arrays.copyOfRange(expected, 0, prefix.length), prefix);
      write(channel, extended, expected, 32768, consumed);
      assertEquals(32768, input.available(), "The original ring must be full after wrapping");
      write(channel, extended, expected, 32768 + consumed, 16384);
      assertEquals(49152, input.available(),
          "Growing a full ring must preserve its wrapped prefix");
      channel.eof_remote();
      channel.disconnect();
      byte[] remaining = new byte[49152];
      int offset = 0;
      while (offset < remaining.length) {
        int count = input.read(remaining, offset, remaining.length - offset);
        assertTrue(count > 0, "Resize truncated unread data");
        offset += count;
      }
      assertArrayEquals(Arrays.copyOfRange(expected, consumed, expected.length), remaining);
      assertEquals(-1, input.read());
    } finally {
      channel.disconnect();
    }
  }

  private static void write(Channel channel, boolean extended, byte[] bytes, int offset, int length)
      throws IOException {
    if (extended)
      channel.write_ext(bytes, offset, length);
    else
      channel.write(bytes, offset, length);
  }

  @ParameterizedTest
  @ValueSource(ints = {32768, 1048576})
  void remoteEofAndClosePreserveQueuedDataForSlowConsumer(int capacity) throws Exception {
    ChannelDirectTCPIP channel = channel(capacity);
    channel.connected = true;
    byte[] expected = new byte[3 * 1024 * 1024];
    new Random(804).nextBytes(expected);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (InputStream input = channel.getInputStream()) {
      Future<?> produced = worker.submit(() -> {
        for (int offset = 0; offset < expected.length; offset += 16384)
          channel.write(expected, offset, 16384);
        channel.eof_remote();
        channel.disconnect();
        return null;
      });
      byte[] actual = new byte[expected.length];
      int offset = 0;
      while (offset < actual.length) {
        int count = input.read(actual, offset, Math.min(16384, actual.length - offset));
        assertTrue(count > 0, "Remote close truncated the pipe at " + offset);
        offset += count;
        Thread.sleep(2);
      }
      produced.get(2, TimeUnit.SECONDS);
      assertArrayEquals(expected, actual);
      assertEquals(-1, input.read());
    } finally {
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
      channel.disconnect();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {32768, 1048576})
  void variableBurstsAndSlowConsumerPreserveBytesAndEof(int capacity) throws Exception {
    ChannelDirectTCPIP channel = channel(capacity);
    byte[] expected = new byte[4 * 1024 * 1024 + 37];
    new Random(526).nextBytes(expected);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (InputStream input = channel.getInputStream()) {
      Future<?> produced = worker.submit(() -> {
        Random sizes = new Random(527);
        int offset = 0;
        while (offset < expected.length) {
          int count = Math.min(1 + sizes.nextInt(32768), expected.length - offset);
          channel.write(expected, offset, count);
          offset += count;
        }
        channel.eof_remote();
        return null;
      });
      byte[] actual = new byte[expected.length];
      Random sizes = new Random(528);
      int offset = 0;
      int reads = 0;
      while (offset < actual.length) {
        int count =
            input.read(actual, offset, Math.min(1 + sizes.nextInt(65536), actual.length - offset));
        assertTrue(count > 0, "EOF must not discard queued data");
        offset += count;
        assertTrue(input.available() <= capacity);
        // Seeded consumption stalls exercise wrap/backpressure, not a mobile-network model.
        if (++reads % 19 == 0) {
          Thread.sleep(1 + sizes.nextInt(4));
        }
      }
      produced.get(5, TimeUnit.SECONDS);
      assertEquals(-1, input.read());
      assertEquals(-1, input.read(new byte[7]));
      assertEquals(0, input.read(new byte[0]));
      assertArrayEquals(expected, actual);
    } finally {
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
      channel.disconnect();
    }
  }

  @Test
  void readingZeroByteAndSkippingWakeBlockedProducer() throws Exception {
    ChannelDirectTCPIP channel = channel(32768);
    AtomicReference<Thread> writer = new AtomicReference<>();
    ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "pipe-single-byte-producer");
      writer.set(thread);
      return thread;
    });
    try (InputStream input = channel.getInputStream()) {
      for (boolean skip : new boolean[] {false, true}) {
        // Restore a completely full pipe before each operation.
        byte[] block = new byte[32768 - input.available()];
        channel.write(block, 0, block.length);
        Future<?> pending = worker.submit(() -> {
          channel.write(new byte[] {7}, 0, 1);
          return null;
        });
        awaitWriter(writer.get());
        if (skip) {
          assertEquals(1, input.skip(1));
        } else {
          assertEquals(0, input.read(), "Zero is a consumed byte, not EOF");
        }
        pending.get(300, TimeUnit.MILLISECONDS);
      }
    } finally {
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
      channel.disconnect();
    }
  }

  @Test
  void interruptingBlockedProducerAndClosingReaderReleaseResources() throws Exception {
    ChannelDirectTCPIP channel = channel(32768);
    AtomicReference<Thread> writer = new AtomicReference<>();
    ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "pipe-cancelled-producer");
      writer.set(thread);
      return thread;
    });
    InputStream input = channel.getInputStream();
    try {
      channel.write(new byte[32768], 0, 32768);
      Future<IOException> pending = worker.submit(() -> {
        try {
          channel.write(new byte[] {1}, 0, 1);
          return null;
        } catch (IOException failure) {
          return failure;
        }
      });
      awaitWriter(writer.get());
      writer.get().interrupt();
      assertInstanceOf(InterruptedIOException.class, pending.get(300, TimeUnit.MILLISECONDS));
      input.close();
      assertThrows(IOException.class, () -> channel.write(new byte[] {1}, 0, 1));
      assertThrows(IOException.class, input::read);
    } finally {
      input.close();
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
      channel.disconnect();
    }
  }

  private static ChannelDirectTCPIP channel(int capacity) throws Exception {
    ChannelDirectTCPIP channel = new ChannelDirectTCPIP();
    channel.init();
    Session session = new JSch().getSession("fixture", "127.0.0.1", 22);
    session.setConfig("max_input_buffer_size", Integer.toString(capacity));
    channel.setSession(session);
    return channel;
  }

  private static void awaitWriter(Thread writer) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (writer.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertEquals(Thread.State.TIMED_WAITING, writer.getState());
  }
}
