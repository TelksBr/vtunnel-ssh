package com.jcraft.jsch;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Measures the real Session.write partial-credit path, not network RTT or Ookla jitter. */
public final class WindowCreditProbe {
  public static void main(String[] args) throws Exception {
    List<Double> partial = new ArrayList<>();
    List<Double> full = new ArrayList<>();
    for (int i = 0; i < 36; i++) {
      double a = run(1 + new Random(526 + i).nextInt(8192), 526 + i);
      double b = run(32768, 526 + i);
      if (i >= 6) {
        partial.add(a);
        full.add(b);
      }
    }
    String row = "{\"label\":\"" + args[0] + "\",\"window_bytes\":4194304,"
        + "\"partial_ms\":" + partial + ",\"full_ms\":" + full + "}";
    try (FileOutputStream output = new FileOutputStream(args[1], true)) {
      output.write((row + "\n").getBytes(StandardCharsets.UTF_8));
    }
    Collections.sort(partial);
    System.out.println(args[0] + " partial median(ms)=" + partial.get(15));
  }

  private static double run(int credit, int seed) throws Exception {
    Capture session = new Capture();
    ChannelDirectTCPIP channel = new ChannelDirectTCPIP();
    channel.setSession(session);
    channel.connected = true;
    channel.setRecipient(7);
    channel.setRemoteWindowSize(credit);
    channel.setLocalWindowSizeMax(4 * 1024 * 1024);
    channel.setLocalWindowSize(4 * 1024 * 1024);
    byte[] data = new byte[32768];
    new Random(seed).nextBytes(data);
    AtomicReference<Throwable> error = new AtomicReference<>();
    Thread sender = new Thread(() -> {
      try {
        Buffer buffer = new Buffer(data.length + 512);
        Packet packet = new Packet(buffer);
        packet.reset();
        buffer.putByte((byte) Session.SSH_MSG_CHANNEL_DATA);
        buffer.putInt(7);
        buffer.putString(data);
        session.started = System.nanoTime();
        session.write(packet, channel, data.length);
      } catch (Throwable failure) {
        error.set(failure);
        session.first.countDown();
      }
    }, "window-credit-probe");
    sender.setDaemon(true);
    sender.start();
    try {
      if (!session.first.await(3, TimeUnit.SECONDS)) throw new AssertionError("No first packet");
      if (credit < data.length) channel.addRemoteWindowSize(data.length - credit);
      sender.join(3000);
      if (sender.isAlive() || error.get() != null) throw new AssertionError("Send failed", error.get());
      if (!Arrays.equals(data, session.bytes.toByteArray()) || channel.rwsize != 0)
        throw new AssertionError("Corrupted bytes or credit accounting");
      return (session.firstSent - session.started) / 1_000_000.0;
    } finally {
      channel.close = true;
      synchronized (channel) { channel.notifyAll(); }
      sender.interrupt();
      sender.join(1000);
    }
  }

  private static final class Capture extends Session {
    final CountDownLatch first = new CountDownLatch(1);
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    long started;
    long firstSent;

    Capture() throws JSchException { super(new JSch(), "fixture", "127.0.0.1", 22); }
    @Override public boolean isConnected() { return true; }
    @Override void encode(Packet packet) {
      bytes.write(packet.buffer.buffer, 14, packet.buffer.index - 14);
      if (firstSent == 0) { firstSent = System.nanoTime(); first.countDown(); }
    }
  }
}
