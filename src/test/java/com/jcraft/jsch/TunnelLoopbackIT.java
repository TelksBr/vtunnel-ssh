package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Runs against tools/loopback_ssh.py, independently of Docker and external networks. */
@EnabledIfEnvironmentVariable(named = "VTUNNEL_SSH_TEST_PORT", matches = "[0-9]+")
@Timeout(30)
class TunnelLoopbackIT {
  @Test
  @EnabledIfEnvironmentVariable(named = "VTUNNEL_SSH_TEST_DUPLEX", matches = "true")
  void duplexDownloadReplenishesReceiveWindowWhileUploadWaitsForPeerCredit() throws Exception {
    Session session = connect(false);
    SessionFlowMetrics metrics = new SessionFlowMetrics();
    session.setFlowMetrics(metrics);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    ChannelDirectTCPIP channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
    channel.setHost("duplex.vtunnel.test");
    channel.setPort(7);
    channel.setLocalWindowSizeMax(4 * 1024 * 1024);
    channel.setLocalWindowSize(4 * 1024 * 1024);
    byte[] upload = new byte[128 * 1024];
    new Random(804).nextBytes(upload);
    InputStream input = channel.getInputStream();
    OutputStream output = channel.getOutputStream();
    AtomicLong delivered = new AtomicLong();
    AtomicReference<IOException> deliveryFailure = new AtomicReference<>();
    channel.setOutputStream(new FilterOutputStream(channel.io.out) {
      @Override
      public void write(byte[] bytes, int offset, int length) throws IOException {
        try {
          out.write(bytes, offset, length);
          delivered.addAndGet(length);
        } catch (IOException error) {
          deliveryFailure.set(error);
          throw error;
        }
      }
    });
    try {
      channel.connect(5000);
      Future<?> sending = workers.submit(() -> {
        output.write(upload);
        output.flush();
        return null;
      });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (metrics.snapshot().getTimings().get(SessionFlowMetrics.Phase.CREDIT_WAIT)
          .getCount() == 0
          && metrics.snapshot().getTimings().get(SessionFlowMetrics.Phase.CREDIT_WAIT)
              .getInFlight() == 0
          && System.nanoTime() < deadline)
        Thread.sleep(1);
      assertTrue(
          metrics.snapshot().getTimings().get(SessionFlowMetrics.Phase.CREDIT_WAIT).getCount() > 0
              || metrics.snapshot().getTimings().get(SessionFlowMetrics.Phase.CREDIT_WAIT)
                  .getInFlight() > 0,
          "The upload must encounter the peer's exhausted window");
      Future<?> receiving = workers.submit(() -> {
        DataInputStream data = new DataInputStream(input);
        byte[] block = new byte[16384];
        for (int n = 0; n < 192; n++) {
          try {
            data.readFully(block);
          } catch (java.io.EOFException truncated) {
            AssertionError failure = new AssertionError("Download truncated after " + n
                + " complete 16 KiB blocks; delivered to pipe=" + delivered.get(), truncated);
            if (deliveryFailure.get() != null)
              failure.addSuppressed(deliveryFailure.get());
            throw failure;
          }
          for (int i = 0; i < block.length; i++)
            assertEquals((byte) (i * 31 + 7), block[i]);
        }
        byte[] digest = new byte[32];
        data.readFully(digest);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(upload), digest);
        assertEquals(-1, input.read());
        return null;
      });
      receiving.get(5, TimeUnit.SECONDS);
      sending.get(1, TimeUnit.SECONDS);
      assertEquals(4 * 1024 * 1024, channel.lwsize_max);
      session.sendKeepAliveMsg();
      echo(session, 805);
    } finally {
      channel.disconnect();
      session.disconnect();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  private Session connect(boolean publicKey) throws Exception {
    JSch jsch = new JSch();
    if (publicKey) {
      jsch.addIdentity(ResourceUtil.getResourceFile(getClass(), "docker/id_rsa"));
    }
    Session session = jsch.getSession("vtunnel-test", "127.0.0.1",
        Integer.parseInt(System.getenv("VTUNNEL_SSH_TEST_PORT")));
    session.setConfig("StrictHostKeyChecking", "no");
    session.setConfig("PreferredAuthentications", publicKey ? "publickey" : "password");
    // Match the VPN's receive-buffer budget. This fixture writes its entire 256 KiB
    // request before reading the echo, unlike the app's simultaneous stream pumps.
    session.setConfig("max_input_buffer_size", "1048576");
    session.setPassword(System.getenv("VTUNNEL_SSH_TEST_PASSWORD")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    session.setTimeout(5000);
    session.connect(5000);
    return session;
  }

  private void echo(Session session, int seed) throws Exception {
    echo(session, seed, null, null);
  }

  private void echo(Session session, int seed, CountDownLatch partial, CountDownLatch resume)
      throws Exception {
    ChannelDirectTCPIP channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
    channel.setHost("echo.vtunnel.test");
    channel.setPort(7);
    channel.setLocalWindowSizeMax(4 * 1024 * 1024);
    channel.setLocalWindowSize(4 * 1024 * 1024);
    byte[] expected = new byte[256 * 1024];
    new Random(seed).nextBytes(expected);
    try (InputStream input = channel.getInputStream();
        OutputStream output = channel.getOutputStream()) {
      channel.connect(5000);
      if (partial != null) {
        output.write(expected, 0, 32768);
        output.flush();
        partial.countDown();
        assertTrue(resume.await(5, TimeUnit.SECONDS));
        output.write(expected, 32768, expected.length - 32768);
      } else {
        output.write(expected);
      }
      output.flush();
      byte[] actual = new byte[expected.length];
      int offset = 0;
      while (offset < actual.length) {
        int read = input.read(actual, offset, actual.length - offset);
        if (read < 0) {
          throw new AssertionError("Truncated SSH tunnel payload");
        }
        offset += read;
      }
      assertArrayEquals(expected, actual);
      channel.eof();
      assertEquals(-1, input.read(), "Remote EOF must propagate");
    } finally {
      channel.disconnect();
    }
  }

  @Test
  void passwordTunnelAndEof() throws Exception {
    Session session = connect(false);
    SessionFlowMetrics metrics = new SessionFlowMetrics();
    session.setFlowMetrics(metrics);
    try {
      echo(session, 526);
      assertTrue(
          metrics.snapshot().getTimings().get(SessionFlowMetrics.Phase.ENCODE).getCount() > 0);
      assertTrue(metrics.snapshot().getTimings().get(SessionFlowMetrics.Phase.CHANNEL_DELIVERY)
          .getCount() > 0);
      assertTrue(metrics.snapshot().getRequestedDataBytes() >= 256 * 1024);
    } finally {
      session.disconnect();
    }
  }

  @Test
  void publicKeyTunnelAndEof() throws Exception {
    Session session = connect(true);
    try {
      echo(session, 552);
    } finally {
      session.disconnect();
    }
  }

  @Test
  void concurrentTunnelsRemainIndependent() throws Exception {
    Session session = connect(false);
    ExecutorService workers = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> results = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        final int seed = i;
        results.add(workers.submit(() -> {
          echo(session, seed);
          return null;
        }));
      }
      for (Future<?> result : results) {
        result.get(15, TimeUnit.SECONDS);
      }
    } finally {
      session.disconnect();
      workers.shutdownNow();
    }
  }

  @Test
  void keepaliveAndRekeyPreserveFourPartiallyTransferredChannels() throws Exception {
    Session session = connect(false);
    ExecutorService workers = Executors.newFixedThreadPool(4);
    CountDownLatch partial = new CountDownLatch(4);
    CountDownLatch resume = new CountDownLatch(1);
    try {
      List<Future<?>> results = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        final int seed = 700 + i;
        results.add(workers.submit(() -> {
          echo(session, seed, partial, resume);
          return null;
        }));
      }
      assertTrue(partial.await(5, TimeUnit.SECONDS),
          "Every channel must have transferred its prefix");
      session.sendKeepAliveMsg();
      session.rekey();
      resume.countDown();
      for (Future<?> result : results)
        result.get(15, TimeUnit.SECONDS);
      session.sendKeepAliveMsg();
      echo(session, 710);
    } finally {
      resume.countDown();
      session.disconnect();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void rejectedDestinationDoesNotBreakSession() throws Exception {
    Session session = connect(false);
    ChannelDirectTCPIP rejected = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
    try {
      rejected.setHost("forbidden.invalid");
      rejected.setPort(1);
      assertThrows(JSchException.class, () -> rejected.connect(5000));
      echo(session, 4);
    } finally {
      rejected.disconnect();
      session.disconnect();
    }
  }
}
