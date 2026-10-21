package com.jcraft.jsch;

import java.io.IOException;
import java.io.OutputStream;

/** Probe-only byte offsets; never retains encrypted bytes, payloads or keys. */
final class CipherStreamTrace {
  private final Object lock = new Object();
  private final ThreadLocal<PacketRange> marked = new ThreadLocal<>();
  private long completed, pending, uncertainUpper;
  private boolean valid = true, closed;

  PacketRange mark() {
    PacketRange range = new PacketRange();
    marked.set(range);
    return range;
  }

  void unmark() { marked.remove(); }

  OutputStream wrap(OutputStream delegate) {
    return new OutputStream() {
      @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}); }
      @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        PacketRange range = marked.get();
        long start;
        synchronized (lock) {
          if (pending != 0) valid = false; // Session is expected to serialize transport writes.
          start = completed;
          pending += length;
          if (range != null) range.begin(start, length);
        }
        boolean success = false;
        try {
          delegate.write(bytes, offset, length);
          success = true;
        } finally {
          synchronized (lock) {
            if (success) completed += length;
            else {
              valid = false;
              uncertainUpper = Math.max(uncertainUpper, start + length);
            }
            pending -= length;
            if (range != null) range.finish(success);
          }
        }
      }
      @Override public void flush() throws IOException {
        try { delegate.flush(); }
        catch (IOException error) {
          PacketRange range = marked.get();
          if (range != null) range.fail();
          throw error;
        }
      }
      @Override public void close() throws IOException {
        synchronized (lock) { closed = true; }
        delegate.close();
      }
    };
  }

  Snapshot snapshot() {
    synchronized (lock) {
      return new Snapshot(completed, Math.max(completed + pending, uncertainUpper), valid, closed);
    }
  }

  static final class Snapshot {
    final long lower, upper;
    final boolean valid, closed;
    Snapshot(long lower, long upper, boolean valid, boolean closed) {
      this.lower=lower; this.upper=upper; this.valid=valid; this.closed=closed;
    }
    String json() {
      return "{\"accepted_lower\":"+lower+",\"accepted_upper\":"+upper
          +",\"valid\":"+valid+",\"closed\":"+closed+"}";
    }
  }

  static final class PacketRange {
    private Long start, end;
    private long writes, completedWrites;
    private boolean failed;
    synchronized void begin(long offset, int length) {
      if (start == null) start = offset;
      end = offset + length;
      writes++;
    }
    synchronized void finish(boolean success) { if (success) completedWrites++; else failed=true; }
    synchronized void fail() { failed=true; }
    synchronized RangeSnapshot snapshot() { return new RangeSnapshot(start,end,writes,completedWrites,failed); }
  }

  static final class RangeSnapshot {
    final Long start, end;
    final long writes, completedWrites;
    final boolean failed;
    RangeSnapshot(Long start, Long end, long writes, long completedWrites, boolean failed) {
      this.start=start; this.end=end; this.writes=writes; this.completedWrites=completedWrites; this.failed=failed;
    }
    String json() {
      return "{\"start\":"+start+",\"end\":"+end+",\"writes\":"+writes
          +",\"completed_writes\":"+completedWrites+",\"failed\":"+failed+"}";
    }
  }
}
