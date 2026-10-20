package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/** Exercises the VPN data path instead of SFTP/SCP in the upstream algorithm matrix. */
final class TunnelTestSupport {
  private static final AtomicInteger ports = new AtomicInteger(20000);

  private TunnelTestSupport() {}

  static void roundTrip(Session session, GenericContainer<?> sshd, Path source, Path destination,
      int timeout) throws Exception {
    int port = ports.getAndIncrement();
    ExecResult started = sshd.execInContainer("sh", "-c", "busybox nc -l -p " + port
        + " -e cat </dev/null >/tmp/vtunnel-echo-" + port + ".log 2>&1 & sleep 0.1");
    assertEquals(0, started.getExitCode(), started.getStderr());
    ChannelDirectTCPIP channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
    channel.setHost("127.0.0.1");
    channel.setPort(port);
    try (InputStream input = channel.getInputStream();
        OutputStream output = channel.getOutputStream()) {
      channel.connect(timeout);
      byte[] expected = Files.readAllBytes(source);
      // Interleave writes/reads so this helper also works with deliberately tiny packet/window
      // tests.
      try (OutputStream received = Files.newOutputStream(destination)) {
        byte[] buffer = new byte[4096];
        for (int offset = 0; offset < expected.length;) {
          int count = Math.min(buffer.length, expected.length - offset);
          output.write(expected, offset, count);
          output.flush();
          int remaining = count;
          while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            assertTrue(read > 0, "Unexpected EOF in tunnel echo");
            received.write(buffer, 0, read);
            remaining -= read;
          }
          offset += count;
        }
      }
    } finally {
      channel.disconnect();
    }
  }

  static void checkSshBanner(Session session, int timeout) throws Exception {
    ChannelDirectTCPIP channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
    channel.setHost("127.0.0.1");
    channel.setPort(22);
    try (InputStream input = channel.getInputStream()) {
      channel.connect(timeout);
      byte[] prefix = new byte[4];
      int offset = 0;
      while (offset < prefix.length) {
        int read = input.read(prefix, offset, prefix.length - offset);
        assertTrue(read > 0, "Missing SSH banner through direct-tcpip");
        offset += read;
      }
      assertEquals("SSH-", Util.byte2str(prefix));
    } finally {
      channel.disconnect();
    }
  }
}
