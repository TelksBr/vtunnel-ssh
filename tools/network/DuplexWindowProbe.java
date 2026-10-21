package com.jcraft.jsch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Controlled loopback fixture, not a VPN throughput or jitter benchmark. */
public final class DuplexWindowProbe {
  public static void main(String[] args) throws Exception {
    if (args.length != 3 || !args[0].matches("before|after"))
      throw new IllegalArgumentException("LABEL PORT OUTPUT; requires loopback_ssh.py --duplex-window");
    String password = System.getenv("VTUNNEL_SSH_TEST_PASSWORD");
    if (password == null) throw new IllegalArgumentException("Fixture password environment missing");
    String cipher = System.getProperty("vtunnel.duplex.cipher", "aes256-ctr");
    if (!Arrays.asList("aes256-ctr", "aes128-gcm@openssh.com").contains(cipher))
      throw new IllegalArgumentException("Unsupported test cipher");
    Session session = new JSch().getSession("vtunnel-test", "127.0.0.1", Integer.parseInt(args[1]));
    session.setConfig("StrictHostKeyChecking", "no");
    session.setConfig("PreferredAuthentications", "password");
    session.setConfig("max_input_buffer_size", "1048576");
    session.setConfig("cipher.c2s", cipher);
    session.setConfig("cipher.s2c", cipher);
    session.setConfig("mac.c2s", "hmac-sha2-512");
    session.setConfig("mac.s2c", "hmac-sha2-512");
    if (Boolean.getBoolean("vtunnel.duplex.metrics")) session.setFlowMetrics(new SessionFlowMetrics());
    int readDelay = Integer.getInteger("vtunnel.duplex.readDelay", 0);
    if (readDelay < 0 || readDelay > 10) throw new IllegalArgumentException("Invalid test read delay");
    session.setPassword(password.getBytes(StandardCharsets.UTF_8));
    session.setTimeout(10000);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    ChannelDirectTCPIP channel = null;
    AtomicLong downloaded = new AtomicLong();
    boolean complete = false;
    boolean timeout = false;
    String failureClass = "";
    String receiverState = "UNKNOWN";
    String receiverFrame = "";
    long elapsed;
    try {
      session.connect(5000);
      channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
      channel.setHost("duplex.vtunnel.test");
      channel.setPort(7);
      channel.setLocalWindowSizeMax(4 * 1024 * 1024);
      channel.setLocalWindowSize(4 * 1024 * 1024);
      InputStream input = channel.getInputStream();
      OutputStream output = channel.getOutputStream();
      channel.connect(5000);
      byte[] payload = new byte[128 * 1024];
      new Random(804).nextBytes(payload);
      long started = System.nanoTime();
      Future<?> sending = workers.submit(() -> {
        output.write(payload);
        output.flush();
        return null;
      });
      boolean creditWait = false;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (System.nanoTime() < deadline) {
        synchronized (channel) { creditWait = channel.notifyme > 0; }
        if (creditWait) break;
        Thread.sleep(1);
      }
      if (!creditWait) throw new AssertionError("Upload did not exhaust peer credit");
      Future<?> receiving = workers.submit(() -> {
        DataInputStream data = new DataInputStream(input);
        byte[] expected = new byte[16384];
        for (int i=0;i<expected.length;i++) expected[i]=(byte)(i*31+7);
        byte[] block = new byte[expected.length];
        for (int n=0;n<192;n++) {
          data.readFully(block);
          downloaded.addAndGet(block.length);
          if (!Arrays.equals(expected,block)) throw new AssertionError("Download corruption");
          if (readDelay > 0) Thread.sleep(readDelay);
        }
        byte[] digest = new byte[32];
        data.readFully(digest);
        if (!Arrays.equals(MessageDigest.getInstance("SHA-256").digest(payload),digest))
          throw new AssertionError("Upload corruption");
        if (input.read()!=-1) throw new AssertionError("Missing EOF");
        return null;
      });
      try {
        receiving.get(5,TimeUnit.SECONDS);
        sending.get(1,TimeUnit.SECONDS);
        complete=true;
      } catch (ExecutionException failed) {
        failureClass=failed.getCause().getClass().getName();
      } catch (TimeoutException expected) {
        timeout=true;
        for (Map.Entry<Thread,StackTraceElement[]> entry:Thread.getAllStackTraces().entrySet()) {
          for (StackTraceElement frame:entry.getValue()) {
            if (frame.getClassName().equals("com.jcraft.jsch.Session") && frame.getMethodName().equals("run")) {
              receiverState=entry.getKey().getState().name();
              StackTraceElement top=entry.getValue()[0];
              receiverFrame=top.getClassName()+"."+top.getMethodName();
            }
          }
        }
      }
      elapsed=System.nanoTime()-started;
    } finally {
      if (channel!=null) channel.disconnect();
      session.disconnect();
      workers.shutdownNow();
      if (!workers.awaitTermination(2,TimeUnit.SECONDS)) throw new AssertionError("Worker leak");
    }
    String json=String.format(Locale.ROOT,
        "{\"label\":\"%s\",\"cipher\":\"%s\",\"complete\":%s,\"timeout\":%s,\"failure_class\":\"%s\",\"elapsed_ms\":%.6f,\"downloaded_bytes\":%d,\"expected_download_bytes\":3145728,\"upload_bytes\":131072,\"window_bytes\":4194304,\"pipe_max_bytes\":1048576,\"credit_wait_observed\":true,\"integrity_verified\":%s,\"receiver_state_at_timeout\":\"%s\",\"receiver_frame_at_timeout\":\"%s\"}%n",
        args[0],cipher,complete,timeout,failureClass,elapsed/1e6,downloaded.get(),complete,receiverState,receiverFrame);
    Files.write(Paths.get(args[2]),json.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
  }
}
