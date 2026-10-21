package com.jcraft.jsch;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class CipherStreamTraceTest {
  public static void main(String[] args) throws Exception {
    exactRangeAndThreadOwnership();
    blockedPartialFailureDoesNotBlockSnapshot();
    System.out.println("PASS: exact cipher offsets, thread ownership, blocked snapshot, partial failure bounds");
  }

  private static void exactRangeAndThreadOwnership() throws Exception {
    CipherStreamTrace trace = new CipherStreamTrace();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    OutputStream output = trace.wrap(bytes);
    output.write(new byte[] {1,2,3});
    CipherStreamTrace.PacketRange packet = trace.mark();
    Thread unrelated = new Thread(() -> {
      try { output.write(new byte[] {4,5}); } catch (IOException error) { throw new AssertionError(error); }
    });
    unrelated.start(); unrelated.join(1000);
    if (unrelated.isAlive()) throw new AssertionError("Leaked writer");
    byte[] payload = new byte[32]; Arrays.fill(payload, (byte) 7);
    output.write(payload);
    output.flush();
    trace.unmark();
    output.write(9);
    CipherStreamTrace.RangeSnapshot range = packet.snapshot();
    if (!Long.valueOf(5).equals(range.start) || !Long.valueOf(37).equals(range.end)
        || range.writes != 1 || range.completedWrites != 1 || range.failed)
      throw new AssertionError("Wrong packet range: " + range.json());
    if (trace.snapshot().lower != 38 || trace.snapshot().upper != 38 || bytes.size() != 38)
      throw new AssertionError("Lost stream bytes");
    byte[] expected = new byte[38];
    System.arraycopy(new byte[] {1,2,3,4,5},0,expected,0,5);
    Arrays.fill(expected,5,37,(byte)7); expected[37]=9;
    if (!Arrays.equals(expected,bytes.toByteArray())) throw new AssertionError("Wire bytes changed");
  }

  private static void blockedPartialFailureDoesNotBlockSnapshot() throws Exception {
    CipherStreamTrace trace = new CipherStreamTrace();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    IOException expected = new IOException("fixture may have accepted five bytes");
    OutputStream output = trace.wrap(new OutputStream() {
      @Override public void write(int value) { throw new AssertionError("bulk required"); }
      @Override public void write(byte[] data, int offset, int length) throws IOException {
        entered.countDown();
        try {
          if (!release.await(2,TimeUnit.SECONDS)) throw new AssertionError("Fixture timed out");
        } catch (InterruptedException error) { throw new IOException(error); }
        throw expected;
      }
    });
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<?> sender = workers.submit(() -> {
        trace.mark();
        try { output.write(new byte[32]); throw new AssertionError("Missing failure"); }
        catch (IOException error) { if (error != expected) throw new AssertionError("Exception changed"); }
        finally { trace.unmark(); }
      });
      if (!entered.await(1,TimeUnit.SECONDS)) throw new AssertionError("No blocked write");
      CipherStreamTrace.Snapshot pending = workers.submit(trace::snapshot).get(200,TimeUnit.MILLISECONDS);
      if (pending.lower != 0 || pending.upper != 32 || !pending.valid)
        throw new AssertionError("Partial write falsely reported precise acceptance");
      release.countDown(); sender.get(1,TimeUnit.SECONDS);
      CipherStreamTrace.Snapshot failed = trace.snapshot();
      if (failed.valid || failed.lower != 0 || failed.upper != 32)
        throw new AssertionError("Failure uncertainty was discarded");
    } finally {
      release.countDown(); workers.shutdownNow();
      if (!workers.awaitTermination(2,TimeUnit.SECONDS)) throw new AssertionError("Leaked worker");
    }
  }
}
