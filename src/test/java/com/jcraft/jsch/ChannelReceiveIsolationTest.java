package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import java.util.Random;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
@SuppressWarnings("try")
class ChannelReceiveIsolationTest {
  private static final int WINDOW = 4 * 1024 * 1024;
  private static final long RESERVATION = WINDOW + 4L * ChannelReceiveBuffer.SEGMENT;

  private static class Capture extends Session {
    final List<Integer> adjustments = new CopyOnWriteArrayList<>();

    Capture() throws Exception {
      super(new JSch(), "fixture", "127.0.0.1", 22);
      setConfig("channel_receive_budget_policy", "reserve");
    }

    @Override
    void sendChannelReceiveCredit(Channel channel, int bytes) {
      adjustments.add(bytes);
    }
  }

  private static ChannelDirectTCPIP channel(Capture session) {
    ChannelDirectTCPIP channel = new ChannelDirectTCPIP();
    channel.init();
    channel.setSession(session);
    channel.setLocalWindowSizeMax(WINDOW);
    channel.setLocalWindowSize(WINDOW);
    return channel;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void fullWindowRetainsBytesWithoutGrantingCreditUntilConsumption(boolean extended)
      throws Exception {
    Capture session = new Capture();
    ChannelDirectTCPIP channel = channel(session);
    byte[] expected = new byte[WINDOW];
    new Random(905).nextBytes(expected);
    try (InputStream input = extended ? channel.getExtInputStream() : channel.getInputStream()) {
      for (int offset = 0; offset < WINDOW; offset += 16384)
        assertTrue(channel.receiveData(expected, offset, 16384, extended));
      assertEquals(WINDOW, input.available());
      assertEquals(0, channel.lwsize);
      assertTrue(session.adjustments.isEmpty());
      assertEquals(RESERVATION, session.reservedReceiveMemory());
      assertThrows(IOException.class, () -> channel.receiveData(new byte[] {1}, 0, 1, extended));
      byte[] actual = new byte[WINDOW];
      new DataInputStream(input).readFully(actual);
      assertArrayEquals(expected, actual);
      assertEquals(WINDOW, channel.lwsize);
      assertEquals(WINDOW, session.adjustments.stream().mapToInt(Integer::intValue).sum());
      channel.eof_remote();
      assertEquals(-1, input.read());
      assertEquals(0, session.reservedReceiveMemory());
    } finally {
      channel.disconnect();
    }
  }

  @Test
  void managedInputCannotBeSilentlyReplacedByACustomSink() throws Exception {
    Capture session = new Capture();
    ChannelDirectTCPIP channel = channel(session);
    try (InputStream normal = channel.getInputStream();
        InputStream extended = channel.getExtInputStream()) {
      assertThrows(IllegalStateException.class,
          () -> channel.setOutputStream(new ByteArrayOutputStream()));
      assertThrows(IllegalStateException.class,
          () -> channel.setExtOutputStream(new ByteArrayOutputStream()));
      assertThrows(IllegalStateException.class,
          () -> channel.setOutputStream(new ByteArrayOutputStream(), true));
      assertThrows(IllegalStateException.class,
          () -> channel.setExtOutputStream(new ByteArrayOutputStream(), true));
    }
  }

  @Test
  void storageIsLazyBoundedAndReleasedOnDrainAfterEof() throws Exception {
    Capture session = new Capture();
    ChannelDirectTCPIP channel = channel(session);
    try (ChannelReceiveBuffer normal = (ChannelReceiveBuffer) channel.getInputStream();
        ChannelReceiveBuffer extended = (ChannelReceiveBuffer) channel.getExtInputStream()) {
      channel.prepareReceive();
      assertEquals(0, normal.allocatedBytes() + extended.allocatedBytes());
      byte[] bytes = new byte[16384];
      for (int n = 0; n < WINDOW / bytes.length; n++) {
        channel.receiveData(bytes, 0, bytes.length, (n & 1) == 1);
        assertTrue(normal.allocatedBytes() + extended.allocatedBytes() <= RESERVATION);
      }
      assertEquals(WINDOW, normal.available() + extended.available());
      channel.eof_remote();
      byte[] actual = new byte[WINDOW / 2];
      new DataInputStream(normal).readFully(actual);
      new DataInputStream(extended).readFully(actual);
      assertEquals(0, normal.allocatedBytes() + extended.allocatedBytes());
      assertEquals(0, session.reservedReceiveMemory());
    }
  }

  @Test
  void singleByteSkipAndInterruptedReadPreserveStreamContracts() throws Exception {
    Capture session = new Capture();
    ChannelDirectTCPIP channel = channel(session);
    try (InputStream input = channel.getInputStream()) {
      channel.receiveData(new byte[] {0, 1, 2, 3}, 0, 4, false);
      assertEquals(0, input.read());
      assertEquals(2, input.skip(2));
      assertEquals(3, input.read());
      assertEquals(0, input.read(new byte[0]));
      assertThrows(IndexOutOfBoundsException.class, () -> input.read(new byte[1], 1, 1));
      Thread.currentThread().interrupt();
      try {
        assertThrows(InterruptedIOException.class, input::read);
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      channel.eof_remote();
      assertEquals(-1, input.read());
      assertEquals(0, session.reservedReceiveMemory());
    }
  }

  @Test
  void closingConsumerDuringReceiveDoesNotLeakReservation() throws Exception {
    Capture session = new Capture();
    ChannelDirectTCPIP channel = channel(session);
    InputStream input = channel.getInputStream();
    channel.prepareReceive();
    input.close();
    assertThrows(IOException.class, () -> channel.receiveData(new byte[] {1}, 0, 1, false));
    channel.disconnect();
    assertEquals(0, session.reservedReceiveMemory());
  }

  @Test
  void normalAndExtendedShareOneWindowAndDrainAfterRemoteClose() throws Exception {
    Capture session = new Capture();
    ChannelDirectTCPIP channel = channel(session);
    try (InputStream normal = channel.getInputStream();
        InputStream extended = channel.getExtInputStream()) {
      byte[] bytes = new byte[WINDOW / 2];
      new Random(906).nextBytes(bytes);
      channel.receiveData(bytes, 0, bytes.length, false);
      channel.receiveData(bytes, 0, bytes.length, true);
      assertEquals(0, channel.lwsize);
      assertEquals(RESERVATION, session.reservedReceiveMemory());
      channel.connected = true;
      channel.disconnect();
      byte[] actual = new byte[bytes.length];
      new DataInputStream(extended).readFully(actual);
      assertArrayEquals(bytes, actual);
      assertEquals(RESERVATION, session.reservedReceiveMemory());
      new DataInputStream(normal).readFully(actual);
      assertArrayEquals(bytes, actual);
      assertEquals(-1, normal.read());
      assertEquals(-1, extended.read());
      assertTrue(session.adjustments.isEmpty(), "No credit after EOF/CLOSE");
      assertEquals(0, session.reservedReceiveMemory());
    }
  }

  @Test
  void invalidPayloadCannotLeakAllocatedBudget() throws Exception {
    Capture session = new Capture();
    session.setConfig("channel_receive_budget_policy", "allocated");
    ChannelDirectTCPIP channel = channel(session);
    try (InputStream input = channel.getInputStream()) {
      assertThrows(IOException.class, () -> channel.receiveData(new byte[1], 1, 32768, false));
      assertThrows(IOException.class, input::read);
      assertEquals(0, session.reservedReceiveMemory());
    } finally {
      channel.disconnect();
    }
  }

  @Test
  void allocatedBudgetAllows128SmallChannelsWithoutReserving512MiB() throws Exception {
    Capture session = new Capture();
    session.setConfig("channel_receive_budget_policy", "allocated");
    List<ChannelDirectTCPIP> channels = new ArrayList<>();
    List<InputStream> inputs = new ArrayList<>();
    try {
      for (int i = 0; i < 128; i++) {
        ChannelDirectTCPIP channel = channel(session);
        channels.add(channel);
        inputs.add(channel.getInputStream());
        channel.prepareReceive();
      }
      assertEquals(0, session.reservedReceiveMemory(), "Idle channels must not preallocate credit");
      for (int i = 0; i < 128; i++) {
        channels.get(i).receiveData(new byte[] {(byte) i}, 0, 1, false);
        assertEquals(i, inputs.get(i).read());
        assertEquals(WINDOW - 1, channels.get(i).lwsize);
      }
      assertEquals(128L * ChannelReceiveBuffer.SEGMENT, session.reservedReceiveMemory());
    } finally {
      for (InputStream input : inputs)
        input.close();
      for (ChannelDirectTCPIP channel : channels)
        channel.disconnect();
    }
    assertEquals(0, session.reservedReceiveMemory());
  }

  @Test
  void allocatedPressureFailsOnlyOffendingChannelAndDoesNotPretendTruncatedDataIsEof()
      throws Exception {
    Capture session = new Capture();
    session.setConfig("channel_receive_budget_policy", "allocated");
    session.setConfig("channel_receive_memory_limit", "65536");
    ChannelDirectTCPIP slow = channel(session), other = channel(session);
    try (InputStream a = slow.getInputStream(); InputStream b = other.getInputStream()) {
      byte[] block = new byte[32768];
      new Random(910).nextBytes(block);
      slow.receiveData(block, 0, block.length, false);
      other.receiveData(block, 0, block.length, false);
      assertEquals(65536, session.reservedReceiveMemory());
      assertThrows(IOException.class, () -> slow.receiveData(new byte[] {1}, 0, 1, false));
      assertThrows(IOException.class, a::read, "Resource exhaustion must not look like normal EOF");
      assertEquals(32768, session.reservedReceiveMemory(), "Failed channel must release storage");
      byte[] actual = new byte[block.length];
      new DataInputStream(b).readFully(actual);
      assertArrayEquals(block, actual);
      other.eof_remote();
      assertEquals(-1, b.read());
      assertEquals(0, session.reservedReceiveMemory());
    } finally {
      slow.disconnect();
      other.disconnect();
    }
  }

  @Test
  void reservedPolicyAdmitsFifteenFourMiBWindowsAndReclaimsEveryReservation() throws Exception {
    Capture session = new Capture();
    List<ChannelDirectTCPIP> channels = new ArrayList<>();
    List<InputStream> inputs = new ArrayList<>();
    try {
      for (int i = 0; i < 16; i++) {
        ChannelDirectTCPIP channel = channel(session);
        channels.add(channel);
        inputs.add(channel.getInputStream());
        if (i < 15)
          channel.prepareReceive();
        else
          assertThrows(IOException.class, channel::prepareReceive);
      }
      assertEquals(15 * RESERVATION, session.reservedReceiveMemory());
      assertTrue(session.reservedReceiveMemory() <= 64L * 1024 * 1024);
    } finally {
      for (InputStream input : inputs)
        input.close();
      for (ChannelDirectTCPIP channel : channels)
        channel.disconnect();
    }
    assertEquals(0, session.reservedReceiveMemory());
  }

  @Test
  void budgetRejectsAdmissionBeforeAnyReceiveWindowIsAdvertised() throws Exception {
    Capture session = new Capture();
    session.setConfig("channel_receive_memory_limit", Long.toString(RESERVATION));
    ChannelDirectTCPIP first = channel(session), second = channel(session);
    try (InputStream a = first.getInputStream(); InputStream b = second.getInputStream()) {
      first.prepareReceive();
      assertThrows(IOException.class, second::prepareReceive);
      assertEquals(RESERVATION, session.reservedReceiveMemory());
      first.disconnect();
      assertEquals(0, session.reservedReceiveMemory());
      second.prepareReceive();
      assertEquals(RESERVATION, session.reservedReceiveMemory());
      second.disconnect();
      assertEquals(0, session.reservedReceiveMemory());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
  void invalidBudgetFailsClosedWithoutReservation(String limit) throws Exception {
    Capture session = new Capture();
    session.setConfig("channel_receive_memory_limit", limit);
    ChannelDirectTCPIP channel = channel(session);
    try (InputStream input = channel.getInputStream()) {
      assertThrows(IOException.class, channel::prepareReceive);
      assertEquals(0, session.reservedReceiveMemory());
    } finally {
      channel.disconnect();
    }
  }

  @Test
  void windowConfiguredAfterAttachingStreamIsUsedForReservation() throws Exception {
    Capture session = new Capture();
    ChannelDirectTCPIP channel = new ChannelDirectTCPIP();
    channel.init();
    channel.setSession(session);
    try (InputStream input = channel.getInputStream()) {
      channel.setLocalWindowSizeMax(WINDOW);
      channel.setLocalWindowSize(WINDOW);
      channel.prepareReceive();
      assertEquals(RESERVATION, session.reservedReceiveMemory());
      assertEquals(WINDOW, channel.lwsize);
    } finally {
      channel.disconnect();
    }
    assertEquals(0, session.reservedReceiveMemory());
  }

  @Test
  void closeAndEofWakeReaderAndReleaseReservations() throws Exception {
    Capture session = new Capture();
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      for (boolean eof : new boolean[] {false, true}) {
        ChannelDirectTCPIP channel = channel(session);
        InputStream input = channel.getInputStream();
        channel.prepareReceive();
        Future<Integer> reading = worker.submit(() -> input.read());
        if (eof) {
          channel.eof_remote();
          assertEquals(-1, reading.get(1, TimeUnit.SECONDS));
        } else {
          input.close();
          assertThrows(ExecutionException.class, () -> reading.get(1, TimeUnit.SECONDS));
        }
        channel.disconnect();
        input.close();
        assertEquals(0, session.reservedReceiveMemory());
      }
    } finally {
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"reserve", "allocated"})
  void randomBurstsAndConcurrentConsumersPreserveBytesAndCredit(String policy) throws Exception {
    Capture session = new Capture();
    session.setConfig("channel_receive_budget_policy", policy);
    ChannelDirectTCPIP channel = channel(session);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    byte[][] expected = {new byte[6 * 1024 * 1024 + 13], new byte[6 * 1024 * 1024 + 71]};
    new Random(907).nextBytes(expected[0]);
    new Random(908).nextBytes(expected[1]);
    try (InputStream normal = channel.getInputStream();
        InputStream extended = channel.getExtInputStream()) {
      channel.prepareReceive();
      List<Future<byte[]>> results = new ArrayList<>();
      for (InputStream input : new InputStream[] {normal, extended})
        results.add(workers.submit(() -> {
          ByteArrayOutputStream result = new ByteArrayOutputStream();
          byte[] block = new byte[49153];
          int count;
          while ((count = input.read(block)) >= 0) {
            result.write(block, 0, count);
            Thread.sleep(1);
          }
          return result.toByteArray();
        }));
      int[] positions = {0, 0};
      Random random = new Random(909);
      while (positions[0] < expected[0].length || positions[1] < expected[1].length) {
        for (int stream = 0; stream < 2; stream++) {
          int count =
              Math.min(1 + random.nextInt(16384), expected[stream].length - positions[stream]);
          if (count > 0 && channel.lwsize >= count) {
            channel.receiveData(expected[stream], positions[stream], count, stream == 1);
            positions[stream] += count;
          }
        }
        if (channel.lwsize < 16384)
          Thread.sleep(1);
      }
      channel.eof_remote();
      for (int i = 0; i < 2; i++)
        assertArrayEquals(expected[i], results.get(i).get(5, TimeUnit.SECONDS));
      assertTrue(channel.lwsize >= 0 && channel.lwsize <= WINDOW);
      assertEquals(0, session.reservedReceiveMemory());
    } finally {
      channel.disconnect();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
    }
  }
}
