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
import java.util.Map;
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
          Integer.parseInt(args[5]), args[6], Integer.parseInt(args[7]), args[8],
          "network-diag".equals(args[0]),
          "queue".equals(args[0]) ? Integer.parseInt(args[9]) : 4,
          "queue".equals(args[0]) ? Integer.parseInt(args[10]) : 0, "queue".equals(args[0]));
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

  private static boolean remoteEchoSent(String snapshot, long sequence) {
    Matcher value = Pattern.compile("\"sent_sequence\"\\s*:\\s*(\\d+)").matcher(snapshot);
    return value.find() && Long.parseLong(value.group(1)) == sequence;
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

  /** Reflection keeps the same probe usable against the published baseline JAR. */
  private static Object attachMetrics(Session session, boolean enabled) throws Exception {
    if (!enabled) return null;
    Class<?> type = Class.forName("com.jcraft.jsch.SessionFlowMetrics");
    Object metrics = type.getConstructor().newInstance();
    Session.class.getMethod("setFlowMetrics", type).invoke(session, metrics);
    return metrics;
  }

  private static String flowSnapshot(Object metrics) throws Exception {
    if (metrics == null) return "null";
    Object snapshot = metrics.getClass().getMethod("snapshot").invoke(metrics);
    StringBuilder json = new StringBuilder("{");
    for (String field : new String[] {"RequestedDataBytes", "Fragments"}) {
      json.append('"').append(field).append("\":")
          .append(snapshot.getClass().getMethod("get" + field).invoke(snapshot)).append(',');
    }
    json.append("\"timings\":{");
    Map<?, ?> timings = (Map<?, ?>) snapshot.getClass().getMethod("getTimings").invoke(snapshot);
    boolean first = true;
    for (Map.Entry<?, ?> entry : timings.entrySet()) {
      if (!first) json.append(',');
      first = false;
      json.append('"').append(entry.getKey()).append("\":{");
      Object timing = entry.getValue();
      boolean firstField = true;
      for (String field : new String[] {"Count", "TotalNanos", "MaxNanos", "InFlight"}) {
        if (!firstField) json.append(',');
        firstField = false;
        json.append('"').append(field).append("\":")
            .append(timing.getClass().getMethod("get" + field).invoke(timing));
      }
      json.append('}');
    }
    return json.append("}}").toString();
  }

  /** Only tiny state transitions hold this monitor; never hold it across I/O. */
  private static final class EchoProgress {
    final long queued = System.nanoTime();
    long writing, flushing, reading, complete;
    String stage = "QUEUED";
    Thread worker;

    synchronized void advance(String next) {
      long now = System.nanoTime();
      stage = next;
      if ("WRITING".equals(next)) { writing = now; worker = Thread.currentThread(); }
      if ("FLUSHING".equals(next)) flushing = now;
      if ("READING".equals(next)) reading = now;
      if ("COMPLETE".equals(next)) complete = now;
    }

    private static String duration(long from, long to) {
      return from == 0 || to == 0 ? "null" : Double.toString((to - from) / 1e6);
    }

    String snapshot(boolean timeout) {
      Thread observed;
      String fields;
      synchronized (this) {
        observed = worker;
        fields = "{\"stage\":\"" + stage + "\",\"timeout\":" + timeout
            + ",\"queue_ms\":" + duration(queued, writing)
            + ",\"write_ms\":" + duration(writing, flushing)
            + ",\"flush_ms\":" + duration(flushing, reading)
            + ",\"read_ms\":" + duration(reading, complete)
            + ",\"elapsed_ms\":" + (System.nanoTime() - queued) / 1e6;
      }
      String state = observed == null ? "UNSTARTED" : observed.getState().toString();
      StringBuilder stack = new StringBuilder("[");
      if (timeout && observed != null) {
        StackTraceElement[] trace = observed.getStackTrace();
        for (int index = 0; index < Math.min(trace.length, 16); index++) {
          if (index > 0) stack.append(',');
          stack.append('"').append(trace[index].getClassName()).append('.')
              .append(trace[index].getMethodName()).append('"');
        }
      }
      return fields + ",\"worker_state\":\"" + state
          + "\",\"timeout_stack\":" + stack.append(']') + "}";
    }
  }

  /** Aggregate offered load for experiments only; never changes the SSH receive window. */
  static final class UploadPacer {
    private final int kbps;
    private final AtomicLong next = new AtomicLong();

    UploadPacer(int kbps) {
      if (kbps < 0 || kbps > 1000000) throw new IllegalArgumentException("Invalid rate");
      this.kbps = kbps;
    }

    long reserveDelayNanos(int bytes, long now) {
      if (kbps == 0) return 0;
      long previous, slot;
      do {
        previous = next.get();
        slot = Math.max(now, previous);
      } while (!next.compareAndSet(previous, slot + bytes * 8000000L / kbps));
      return slot - now;
    }

    void await(int bytes) throws IOException {
      long delay = reserveDelayNanos(bytes, System.nanoTime());
      try {
        TimeUnit.NANOSECONDS.sleep(delay);
      } catch (InterruptedException cancelled) {
        Thread.currentThread().interrupt();
        throw new java.io.InterruptedIOException("Offered-load pacing interrupted");
      }
    }
  }

  private static String measure(String label, String profile, int seed, String direction,
      int seconds, String host, int sshPort, String key, boolean diagnostics, int loadChannels, int uploadKbps, boolean observeRemote) throws Exception {
    if (loadChannels < 1 || loadChannels > 4) throw new IllegalArgumentException("Invalid channel count");
    UploadPacer pacer = new UploadPacer(uploadKbps);
    if (!Arrays.asList("unshaped", "mobile-jitter", "shallow", "deep").contains(profile)
        || !Arrays.asList("idle", "download", "upload").contains(direction)) {
      throw new IllegalArgumentException("Unknown network profile or direction");
    }
    if (!"OK".equals(control(host, "RESET " + profile + " " + seed))) {
      throw new IOException("Unable to reset private network lab");
    }
    Session session = connect(host, sshPort, key);
    Object metrics = attachMetrics(session, diagnostics);
    List<String> echoEvents = new ArrayList<>();
    List<String> remoteEvents = new ArrayList<>();
    long sequence = 0;
    String flowAtTimeout = "null";
    ExecutorService workers = workers(5);
    AtomicBoolean active = new AtomicBoolean(true);
    AtomicLong bytes = new AtomicLong();
    AtomicLong loadErrors = new AtomicLong();
    List<Tunnel> tunnels = new ArrayList<>();
    List<Double> rtts = new ArrayList<>();
    int timeouts = 0;
    try (Tunnel echo = new Tunnel(session, 17001)) {
      if (!"idle".equals(direction)) {
        for (int i = 0; i < loadChannels; i++) {
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
                pacer.await(block.length);
                if (!active.get()) break;
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
      String flowAtStart = flowSnapshot(metrics);
      long start = System.nanoTime();
      long end = start + TimeUnit.SECONDS.toNanos(seconds);
      byte[] payload = new byte[32];
      Arrays.fill(payload, (byte) 17);
      while (System.nanoTime() < end) {
        long currentSequence = ++sequence;
        if (observeRemote) java.nio.ByteBuffer.wrap(payload).putLong(currentSequence);
        long markerStart = System.nanoTime();
        String remoteBeforeEcho = observeRemote ? control(host, "ECHO") : "null";
        double markerRoundtripMs = (System.nanoTime() - markerStart) / 1e6;
        EchoProgress progress = new EchoProgress();
        Future<Double> ping = workers.submit(() -> {
          byte[] reply = new byte[payload.length];
          long sent = System.nanoTime();
          progress.advance("WRITING");
          echo.output.write(payload);
          progress.advance("FLUSHING");
          echo.output.flush();
          progress.advance("READING");
          new DataInputStream(echo.input).readFully(reply);
          if (!Arrays.equals(payload, reply)) throw new IOException("Echo data mismatch");
          progress.advance("COMPLETE");
          return (System.nanoTime() - sent) / 1e6;
        });
        try {
          rtts.add(ping.get(12, TimeUnit.SECONDS));
          echoEvents.add(progress.snapshot(false));
          if (observeRemote) remoteEvents.add("{\"sequence\":" + currentSequence
              + ",\"marker_roundtrip_ms\":" + markerRoundtripMs + ",\"before\":" + remoteBeforeEcho
              + ",\"after\":" + control(host, "ECHO") + "}");
        } catch (TimeoutException timeout) {
          timeouts++;
          echoEvents.add(progress.snapshot(true));
          flowAtTimeout = flowSnapshot(metrics);
          if (observeRemote) remoteEvents.add("{\"sequence\":" + currentSequence
              + ",\"marker_roundtrip_ms\":" + markerRoundtripMs + ",\"before\":" + remoteBeforeEcho
              + ",\"after\":" + control(host, "ECHO") + "}");
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
      String tailObservation = "null";
      if (observeRemote && timeouts > 0) {
        active.set(false);
        long stopped = System.nanoTime();
        String tail;
        do {
          tail = control(host, "ECHO");
          if (remoteEchoSent(tail, sequence)) break;
          Thread.sleep(500);
        } while (System.nanoTime() - stopped < TimeUnit.SECONDS.toNanos(15));
        tailObservation = "{\"after_load_stop_ms\":" + (System.nanoTime() - stopped) / 1e6
            + ",\"remote\":" + tail + "}";
      }
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
          + "\"adjacent_rtt_jitter_ms\":%s,\"rtts_ms\":%s,\"diagnostics\":%s,\"echo_events\":%s,\"flow_at_start\":%s,\"flow_at_timeout\":%s,\"flow_at_end\":%s,\"load_channels\":%d,\"offered_upload_kbps\":%d,\"echo_remote_events\":%s,\"tail_observation\":%s}",
          label, profile, seed, direction, WINDOW, BUFFER, elapsed, rate, remoteRate,
          remoteBefore, remoteAfter, cpu, rtts.size(), timeouts, loadErrors.get(),
          percentile(sorted, .5), percentile(sorted, .95),
          rtts.size() < 2 ? "null" : Double.toString(variation / (rtts.size() - 1)), rtts,
          diagnostics, echoEvents, flowAtStart, flowAtTimeout, flowSnapshot(metrics), loadChannels, uploadKbps, remoteEvents, tailObservation);
    } finally {
      active.set(false);
      for (Tunnel tunnel : tunnels) tunnel.close();
      session.disconnect();
      workers.shutdownNow();
      workers.awaitTermination(2, TimeUnit.SECONDS);
    }
  }
}
