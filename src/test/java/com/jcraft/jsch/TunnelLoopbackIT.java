package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Runs against tools/loopback_ssh.py, independently of Docker and external networks. */
@EnabledIfEnvironmentVariable(named = "VTUNNEL_SSH_TEST_PORT", matches = "[0-9]+")
@Timeout(30)
class TunnelLoopbackIT {
  private Session connect(boolean publicKey) throws Exception {
    JSch jsch = new JSch();
    if (publicKey) {
      jsch.addIdentity(ResourceUtil.getResourceFile(getClass(), "docker/id_rsa"));
    }
    Session session = jsch.getSession("vtunnel-test", "127.0.0.1",
        Integer.parseInt(System.getenv("VTUNNEL_SSH_TEST_PORT")));
    session.setConfig("StrictHostKeyChecking", "no");
    session.setConfig("PreferredAuthentications", publicKey ? "publickey" : "password");
    session.setPassword(System.getenv("VTUNNEL_SSH_TEST_PASSWORD")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    session.setTimeout(5000);
    session.connect(5000);
    return session;
  }

  private void echo(Session session, int seed) throws Exception {
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
      output.write(expected);
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
    try {
      echo(session, 526);
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
