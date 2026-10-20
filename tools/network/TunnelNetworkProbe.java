package com.jcraft.jsch;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Standalone library benchmark for app_process; no TUN, app adapter or production credentials. */
public final class TunnelNetworkProbe {
  private static final int WINDOW = 4 * 1024 * 1024;
  private static final int BUFFER = 1024 * 1024;

  public static void main(String[] args) throws Exception {
    String row;
    if ("wakeup".equals(args[0])) {
      row = wakeup(args[1]);
    } else {
      // network LABEL PROFILE SEED DIRECTION SECONDS HOST SSH_PORT KEY OUTPUT
      row = measure(args[1], args[2], Integer.parseInt(args[3]), args[4],
          Integer.parseInt(args[5]), args[6], Integer.parseInt(args[7]), args[8]);
    }
    try (OutputStream file = new FileOutputStream(args[args.length - 1], true)) {
      file.write((row + "\n").getBytes(StandardCharsets.UTF_8));
    }
  }

  private static ExecutorService workers(int count) {
    return Executors.newFixedThreadPool(count, runnable -> {
      Thread thread = new Thread(runnable, "ssh-network-probe");
      thread.setDaemon(true);
      return thread;
    });
  }

  private static String wakeup(String label) throws Exception {
    List<Double> delays = new ArrayList<>();
    for (int repetition = 0; repetition < 12; repetition++) {
      ChannelDirectTCPIP channel = new ChannelDirectTCPIP();
      channel.init();
      Session session = new JSch().getSession("fixture", "127.0.0.1", 22);
      session.setConfig("max_input_buffer_size", Integer.toString(BUFFER));
      channel.setSession(session);
      channel.setLocalWindowSizeMax(WINDOW);
      channel.setLocalWindowSize(WINDOW);
      AtomicReference<Thread> producer = new AtomicReference<>();
      ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        producer.set(thread);
        return thread;
      });
      try (InputStream input = channel.getInputStream()) {
        byte[] packet = new byte[32768];
        for (int i = 0; i < 32; i++) channel.write(packet, 0, packet.length);
        Future<?> pending = worker.submit(() -> {
          channel.write(packet, 0, packet.length);
          return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (producer.get().getState() != Thread.State.TIMED_WAITING
            && System.nanoTime() < deadline) Thread.sleep(1);
        if (producer.get().getState() != Thread.State.TIMED_WAITING || pending.isDone()) {
          throw new AssertionError("Producer did not wait for pipe space");
        }
        long start = System.nanoTime();
        if (input.read(packet) != packet.length) throw new AssertionError("Short pipe read");
        pending.get(2, TimeUnit.SECONDS);
        delays.add((System.nanoTime() - start) / 1e6);
      } finally {
        worker.shutdownNow();
        worker.awaitTermination(2, TimeUnit.SECONDS);
        channel.disconnect();
      }
    }
    return "{\"label\":\"" + label + "\",\"mode\":\"wakeup\",\"window\":" + WINDOW
        + ",\"delays_ms\":" + delays + "}";
  }

  private static final class Tunnel implements AutoCloseable {
    final ChannelDirectTCPIP channel;
    final InputStream input;
    final OutputStream output;

    Tunnel(Session session, int port) throws Exception {
      channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
      channel.setHost("127.0.0.1");
      channel.setPort(port);
      channel.setLocalWindowSizeMax(WINDOW);
      channel.setLocalWindowSize(WINDOW);
      input = channel.getInputStream();
      output = channel.getOutputStream();
      channel.connect(15000);
    }

    @Override public void close() { channel.disconnect(); }
  }

  private static Session connect(String host, int port, String key) throws Exception {
    JSch jsch = new JSch();
    jsch.addIdentity(key);
    Session session = jsch.getSession("root", host, port);
    session.setConfig("StrictHostKeyChecking", "no");
    session.setConfig("PreferredAuthentications", "publickey");
    session.setConfig("max_input_buffer_size", Integer.toString(BUFFER));
    session.setConfig("cipher.c2s", "aes256-ctr");
    session.setConfig("cipher.s2c", "aes256-ctr");
    session.setConfig("mac.c2s", "hmac-sha2-512");
    session.setConfig("mac.s2c", "hmac-sha2-512");
    session.setSocketFactory(new SocketFactory() {
      @Override public Socket createSocket(String hostname, int targetPort) throws IOException {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(hostname, targetPort), 15000);
        return socket;
      }
      @Override public InputStream getInputStream(Socket socket) throws IOException {
        return socket.getInputStream();
      }
      @Override public OutputStream getOutputStream(Socket socket) throws IOException {
        return socket.getOutputStream();
      }
    });
    session.setDaemonThread(true);
    session.connect(20000);
    return session;
  }

  private static String control(String host, String command) throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, 19084), 3000);
      socket.setSoTimeout(10000);
      socket.getOutputStream().write((command + "\n").getBytes(StandardCharsets.US_ASCII));
      String reply = new BufferedReader(new InputStreamReader(socket.getInputStream(),
          StandardCharsets.UTF_8)).readLine();
      if (reply == null) throw new IOException("No lab-control reply");
      return reply;
    }
  }

  private static long uploaded(String host) throws IOException {
    Matcher count = Pattern.compile("\"upload_received\"\\s*:\\s*(\\d+)")
        .matcher(control(host, "STATS"));
    if (!count.find()) throw new IOException("Missing remote upload counter");
    return Long.parseLong(count.group(1));
  }

  private static long cpuMs() {
    try {
      return ((Long) Class.forName("android.os.Process").getMethod("getElapsedCpuTime")
          .invoke(null)).longValue();
    } catch (ReflectiveOperationException unavailable) {
      return -1;
    }
  }

  private static String percentile(List<Double> sorted, double fraction) {
    return sorted.isEmpty() ? "null"
        : Double.toString(sorted.get((int) ((sorted.size() - 1) * fraction)));
  }

  private static String measure(String label, String profile, int seed, String direction,
      int seconds, String host, int sshPort, String key) throws Exception {
    if (!Arrays.asList("unshaped", "mobile-jitter", "shallow", "deep").contains(profile)
        || !Arrays.asList("idle", "download", "upload").contains(direction)) {
      throw new IllegalArgumentException("Unknown network profile or direction");
    }
    if (!"OK".equals(control(host, "RESET " + profile + " " + seed))) {
      throw new IOException("Unable to reset private network lab");
    }
    Session session = connect(host, sshPort, key);
    ExecutorService workers = workers(5);
    AtomicBoolean active = new AtomicBoolean(true);
    AtomicLong bytes = new AtomicLong();
    AtomicLong loadErrors = new AtomicLong();
    List<Tunnel> tunnels = new ArrayList<>();
    List<Double> rtts = new ArrayList<>();
    int timeouts = 0;
    try (Tunnel echo = new Tunnel(session, 17001)) {
      if (!"idle".equals(direction)) {
        for (int i = 0; i < 4; i++) {
          tunnels.add(new Tunnel(session, "download".equals(direction) ? 17002 : 17003));
        }
        for (Tunnel tunnel : tunnels) workers.submit(() -> {
          byte[] block = new byte[32768];
          try {
            if ("download".equals(direction)) {
              tunnel.output.write(1);
              tunnel.output.flush();
              while (active.get()) {
                int count = tunnel.input.read(block);
                if (count < 0) throw new IOException("Load stream ended early");
                bytes.addAndGet(count);
              }
            } else {
              while (active.get()) {
                tunnel.output.write(block);
                bytes.addAndGet(block.length);
              }
            }
          } catch (IOException failure) {
            if (active.get()) loadErrors.incrementAndGet();
          }
        });
      }
      Thread.sleep("idle".equals(direction) ? 300 : 1500);
      long remoteBefore = uploaded(host);
      long before = bytes.get();
      long cpuBefore = cpuMs();
      long start = System.nanoTime();
      long end = start + TimeUnit.SECONDS.toNanos(seconds);
      byte[] payload = new byte[32];
      Arrays.fill(payload, (byte) 17);
      while (System.nanoTime() < end) {
        Future<Double> ping = workers.submit(() -> {
          byte[] reply = new byte[payload.length];
          long sent = System.nanoTime();
          echo.output.write(payload);
          echo.output.flush();
          new DataInputStream(echo.input).readFully(reply);
          if (!Arrays.equals(payload, reply)) throw new IOException("Echo data mismatch");
          return (System.nanoTime() - sent) / 1e6;
        });
        try {
          rtts.add(ping.get(12, TimeUnit.SECONDS));
        } catch (TimeoutException timeout) {
          timeouts++;
          ping.cancel(true);
          break;
        }
        Thread.sleep(20);
      }
      double elapsed = (System.nanoTime() - start) / 1e9;
      long cpuAfter = cpuMs();
      long cpu = cpuBefore < 0 || cpuAfter < 0 ? -1 : cpuAfter - cpuBefore;
      double rate = (bytes.get() - before) * 8 / elapsed / 1e6;
      long remoteAfter = uploaded(host);
      if (remoteAfter < remoteBefore) throw new IOException("Remote counter regressed; invalid run");
      double remoteRate = (remoteAfter - remoteBefore) * 8 / elapsed / 1e6;
      double variation = 0;
      for (int i = 1; i < rtts.size(); i++) variation += Math.abs(rtts.get(i) - rtts.get(i - 1));
      List<Double> sorted = new ArrayList<>(rtts);
      Collections.sort(sorted);
      return String.format(Locale.ROOT,
          "{\"label\":\"%s\",\"profile\":\"%s\",\"seed\":%d,\"direction\":\"%s\","
          + "\"window\":%d,\"buffer_max\":%d,\"seconds\":%.6f,\"mbps\":%.6f,"
          + "\"remote_upload_mbps\":%.6f,\"upload_before\":%d,\"upload_after\":%d,"
          + "\"cpu_ms\":%d,\"samples\":%d,\"timeouts\":%d,"
          + "\"load_errors\":%d,\"rtt_p50_ms\":%s,\"rtt_p95_ms\":%s,"
          + "\"adjacent_rtt_jitter_ms\":%s,\"rtts_ms\":%s}",
          label, profile, seed, direction, WINDOW, BUFFER, elapsed, rate, remoteRate,
          remoteBefore, remoteAfter, cpu, rtts.size(), timeouts, loadErrors.get(),
          percentile(sorted, .5), percentile(sorted, .95),
          rtts.size() < 2 ? "null" : Double.toString(variation / (rtts.size() - 1)), rtts);
    } finally {
      active.set(false);
      for (Tunnel tunnel : tunnels) tunnel.close();
      session.disconnect();
      workers.shutdownNow();
      workers.awaitTermination(2, TimeUnit.SECONDS);
    }
  }
}
