package com.jcraft.jsch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicBoolean;

/** Encrypted local SSH, controlled consumer stall; not a Speedtest/radio measurement. */
public final class ReceiveIsolationProbe {
  private static final int WINDOW = 4 * 1024 * 1024, FRAMES = 80;
  private static final boolean OPENSSH = Boolean.getBoolean("vtunnel.receive.openssh");

  private static ChannelDirectTCPIP open(Session session, String destination) throws Exception {
    ChannelDirectTCPIP channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
    channel.setHost(OPENSSH ? "127.0.0.1" : destination);
    channel.setPort(OPENSSH ? (destination.startsWith("burst") ? 17004 :
        destination.startsWith("upload") ? 17005 : 17001) : 7);
    channel.setLocalWindowSizeMax(WINDOW);
    channel.setLocalWindowSize(WINDOW);
    return channel;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 4 || !args[0].matches("before|after"))
      throw new IllegalArgumentException("LABEL PORT CIPHER OUTPUT");
    String cipher = args[2];
    JSch jsch = new JSch();
    if (OPENSSH) jsch.addIdentity(System.getenv("VTUNNEL_SSH_TEST_KEY"));
    Session session = jsch.getSession(OPENSSH ? "root" : "vtunnel-test",
        System.getProperty("vtunnel.receive.host", OPENSSH ? "10.205.33.2" : "127.0.0.1"), Integer.parseInt(args[1]));
    session.setLogger(new Logger() {
      public boolean isEnabled(int level) { return level >= Logger.INFO; }
      public void log(int level, String message) {
        if (message.contains("exception") || level >= Logger.WARN)
          System.err.println(message);
      }
    });
    session.setConfig("StrictHostKeyChecking", "no");
    session.setConfig("PreferredAuthentications", OPENSSH ? "publickey" : "password");
    session.setConfig("max_input_buffer_size", "1048576");
    session.setConfig("cipher.c2s", cipher);
    session.setConfig("cipher.s2c", cipher);
    if (!OPENSSH) session.setPassword(System.getenv("VTUNNEL_SSH_TEST_PASSWORD").getBytes(StandardCharsets.UTF_8));
    session.setTimeout(10000);
    ExecutorService workers = Executors.newFixedThreadPool(4);
    List<Double> rtts = new ArrayList<>();
    List<Double> scheduled = new ArrayList<>();
    List<Long> arrivals = new ArrayList<>();
    AtomicLongArray sent = new AtomicLongArray(FRAMES);
    boolean complete = false;
    String failure = "";
    long started = 0, resumed = 0, finished = 0;
    int beforeResume = 0, peakBuffered = 0;
    AtomicBoolean diagnosticDone = new AtomicBoolean();
    try {
      session.connect(5000);
      ChannelDirectTCPIP stalled = open(session, "burst.vtunnel.test");
      ChannelDirectTCPIP echo = open(session, "echo.vtunnel.test");
      ChannelDirectTCPIP upload = open(session, OPENSSH ? "upload.vtunnel.test" : "echo.vtunnel.test");
      try (InputStream stalledIn = stalled.getInputStream();
          InputStream echoIn = echo.getInputStream(); OutputStream echoOut = echo.getOutputStream();
          InputStream uploadIn = upload.getInputStream(); OutputStream uploadOut = upload.getOutputStream()) {
        // Open the interactive/upload channels before intentionally stalling the reader.
        echo.connect(5000);
        upload.connect(5000);
        stalled.connect(5000);
        long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (stalledIn.available() < 1024 * 1024 && System.nanoTime() < readyDeadline)
          Thread.sleep(1);
        if (stalledIn.available() < 1024 * 1024)
          throw new IOException("Stall was not established");
        started = System.nanoTime();
        if (Boolean.getBoolean("vtunnel.receive.diagnostics")) {
          Thread diagnostic = new Thread(() -> {
            try {
              Thread.sleep(3000);
              if (diagnosticDone.get()) return;
              System.err.println("DIAGNOSTIC windows stalled=" + stalled.lwsize + "/" + stalled.rwsize
                  + " echo=" + echo.lwsize + "/" + echo.rwsize
                  + " upload=" + upload.lwsize + "/" + upload.rwsize);
              for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                System.err.println(entry.getKey().getName() + " " + entry.getKey().getState());
                for (StackTraceElement frame : entry.getValue()) System.err.println("  " + frame);
              }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
          }, "receive-diagnostic");
          diagnostic.setDaemon(true);
          diagnostic.start();
        }
        final long phaseStart = started;
        Future<?> receiveEcho = workers.submit(() -> {
          DataInputStream data = new DataInputStream(echoIn);
          for (int i = 0; i < FRAMES; i++) {
            if (data.readInt() != i)
              throw new IOException("Echo sequence mismatch");
            byte[] payload = new byte[28];
            data.readFully(payload);
            for (int j = 0; j < payload.length; j++)
              if (payload[j] != (byte) (i + j))
                throw new IOException("Echo payload mismatch");
            long received = System.nanoTime();
            rtts.add((received - sent.get(i)) / 1e6);
            scheduled.add((received - phaseStart - TimeUnit.MILLISECONDS.toNanos(i * 20L)) / 1e6);
            arrivals.add(received);
          }
          return null;
        });
        Future<?> sendEcho = workers.submit(() -> {
          DataOutputStream data = new DataOutputStream(echoOut);
          for (int i = 0; i < FRAMES; i++) {
            long target = phaseStart + TimeUnit.MILLISECONDS.toNanos(i * 20L);
            while (System.nanoTime() < target)
              TimeUnit.NANOSECONDS.sleep(target - System.nanoTime());
            sent.set(i, System.nanoTime());
            data.writeInt(i);
            byte[] payload = new byte[28];
            for (int j = 0; j < payload.length; j++) payload[j] = (byte) (i + j);
            data.write(payload);
            data.flush();
          }
          return null;
        });
        byte[] bulk = new byte[2 * 1024 * 1024];
        new java.util.Random(905).nextBytes(bulk);
        Future<?> sendUpload = workers.submit(() -> {
          uploadOut.write(bulk);
          uploadOut.flush();
          upload.eof();
          return null;
        });
        Future<?> receiveUpload = workers.submit(() -> {
          byte[] actual = new byte[bulk.length];
          new DataInputStream(uploadIn).readFully(actual);
          if (!Arrays.equals(bulk, actual) || uploadIn.read() != -1)
            throw new IOException("Upload echo integrity/EOF failure");
          return null;
        });
        long resumeDeadline = started + TimeUnit.MILLISECONDS.toNanos(1500);
        while (System.nanoTime() < resumeDeadline) {
          peakBuffered = Math.max(peakBuffered, stalledIn.available());
          Thread.sleep(1);
        }
        resumed = System.nanoTime();
        DataInputStream data = new DataInputStream(stalledIn);
        byte[] block = new byte[16384];
        for (int n = 0; n < 512; n++) {
          data.readFully(block);
          for (int i = 0; i < block.length; i++)
            if (block[i] != (byte) (i * 31 + 7))
              throw new IOException("Download integrity failure");
          Thread.sleep(2);
        }
        if (stalledIn.read() != -1) throw new IOException("Download EOF missing");
        receiveEcho.get(10, TimeUnit.SECONDS);
        sendEcho.get(1, TimeUnit.SECONDS);
        sendUpload.get(10, TimeUnit.SECONDS);
        receiveUpload.get(10, TimeUnit.SECONDS);
        finished = System.nanoTime();
        for (long arrival : arrivals) if (arrival < resumed) beforeResume++;
        complete = true;
      }
    } catch (Exception error) {
      failure = error.getClass().getSimpleName();
      error.printStackTrace(System.err);
    } finally {
      diagnosticDone.set(true);
      session.disconnect();
      workers.shutdownNow();
      if (!workers.awaitTermination(3, TimeUnit.SECONDS))
        throw new IllegalStateException("Probe workers did not terminate");
    }
    if (finished == 0) finished = System.nanoTime();
    beforeResume = 0;
    for (long arrival : arrivals) if (resumed > 0 && arrival < resumed) beforeResume++;
    List<Double> sorted = new ArrayList<>(rtts);
    List<Double> sortedScheduled = new ArrayList<>(scheduled);
    Collections.sort(sorted);
    Collections.sort(sortedScheduled);
    String row = String.format(Locale.ROOT,
        "{\"label\":\"%s\",\"cipher\":\"%s\",\"complete\":%s,\"integrity_verified\":%s,"
        + "\"failure\":\"%s\",\"echo_frames\":%d,\"echo_before_resume\":%d,"
        + "\"peak_buffered_bytes\":%d,\"stall_ms\":%.3f,\"total_ms\":%.3f,"
        + "\"rtt_p50_ms\":%s,\"rtt_p95_ms\":%s,\"rtt_max_ms\":%s,\"rtt_ms\":%s,"
        + "\"scheduled_p50_ms\":%s,\"scheduled_p95_ms\":%s,\"scheduled_latency_ms\":%s}",
        args[0], cipher, complete, complete, failure, rtts.size(), beforeResume, peakBuffered,
        resumed == 0 ? 0 : (resumed - started) / 1e6, started == 0 ? 0 : (finished - started) / 1e6,
        percentile(sorted, .5), percentile(sorted, .95), percentile(sorted, 1), rtts.toString(),
        percentile(sortedScheduled, .5), percentile(sortedScheduled, .95), scheduled.toString());
    Files.write(Paths.get(args[3]), (row + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    if (!complete) throw new IOException("Incomplete isolation phase; raw result preserved");
  }

  private static String percentile(List<Double> sorted, double percentile) {
    return sorted.isEmpty() ? "null" : Double.toString(sorted.get((int) Math.ceil(sorted.size() * percentile) - 1));
  }
}
