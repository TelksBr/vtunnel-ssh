package com.jcraft.jsch;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;

/** Single-copy, segmented receive storage. Never waits for its consumer in offer(). */
final class ChannelReceiveBuffer extends InputStream {
  static final int SEGMENT = 32768;
  private final Channel channel;
  private final ArrayDeque<byte[]> segments = new ArrayDeque<>();
  private int head, tail, size;
  private byte[] spare;
  private boolean ended, closed;
  private IOException failure;

  ChannelReceiveBuffer(Channel channel) {
    this.channel = channel;
  }

  synchronized void offer(byte[] bytes, int offset, int length) throws IOException {
    if (bytes == null || offset < 0 || length < 0 || length > bytes.length - offset)
      throw new IOException("Invalid SSH receive payload bounds");
    if (ended || closed)
      throw new IOException("SSH receive stream is closed");
    int free = segments.isEmpty() ? (spare == null ? 0 : SEGMENT) : SEGMENT - tail;
    int needed = (int) (Math.max(0L, (long) length - free + SEGMENT - 1) / SEGMENT);
    long allocation = (long) needed * SEGMENT;
    channel.reserveReceiveSegments(allocation);
    byte[][] allocated;
    try {
      allocated = needed == 0 ? null : new byte[needed][];
      for (int i = 0; i < needed; i++)
        allocated[i] = new byte[SEGMENT];
    } catch (OutOfMemoryError error) {
      channel.releaseReceiveSegments(allocation);
      throw new IOException("SSH receive allocation failed", error);
    }
    int next = 0;
    while (length > 0) {
      if (segments.isEmpty() || tail == SEGMENT) {
        segments.addLast(spare != null ? spare : allocated[next++]);
        spare = null;
        tail = 0;
      }
      int count = Math.min(length, SEGMENT - tail);
      System.arraycopy(bytes, offset, segments.getLast(), tail, count);
      tail += count;
      size += count;
      offset += count;
      length -= count;
    }
    notifyAll();
  }

  synchronized void end() {
    ended = true;
    if (spare != null)
      channel.releaseReceiveSegments(SEGMENT);
    spare = null;
    notifyAll();
  }

  synchronized long allocatedBytes() {
    return ((long) segments.size() + (spare == null ? 0 : 1)) * SEGMENT;
  }

  synchronized int fail(IOException cause) {
    failure = cause;
    ended = true;
    int discarded = size;
    clear();
    notifyAll();
    return discarded;
  }

  private void clear() {
    long allocated = allocatedBytes();
    size = head = tail = 0;
    segments.clear();
    spare = null;
    channel.releaseReceiveSegments(allocated);
  }

  @Override
  public int read() throws IOException {
    byte[] one = new byte[1];
    return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    if (bytes == null)
      throw new NullPointerException();
    if (offset < 0 || length < 0 || length > bytes.length - offset)
      throw new IndexOutOfBoundsException();
    if (length == 0)
      return 0;
    int count;
    synchronized (this) {
      while (size == 0 && !ended && !closed) {
        try {
          wait();
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException("SSH receive interrupted");
        }
      }
      if (failure != null)
        throw failure;
      if (closed)
        throw new IOException("SSH receive stream is closed");
      if (size == 0)
        return -1;
      count = Math.min(length, size);
      int remaining = count;
      while (remaining > 0) {
        int n = Math.min(remaining, (segments.size() == 1 ? tail : SEGMENT) - head);
        System.arraycopy(segments.getFirst(), head, bytes, offset, n);
        head += n;
        offset += n;
        size -= n;
        remaining -= n;
        if (head == SEGMENT || size == 0) {
          byte[] consumed = segments.removeFirst();
          head = 0;
          if (segments.isEmpty()) {
            tail = 0;
            if (!ended)
              spare = consumed;
            else
              channel.releaseReceiveSegments(SEGMENT);
          } else {
            channel.releaseReceiveSegments(SEGMENT);
          }
        }
      }
    }
    // Never hold the storage monitor while writing SSH control packets.
    channel.consumeReceiveBytes(count);
    return count;
  }

  @Override
  public synchronized int available() throws IOException {
    if (closed)
      throw new IOException("SSH receive stream is closed");
    return size;
  }

  @Override
  public void close() {
    int discarded;
    synchronized (this) {
      if (closed)
        return;
      closed = true;
      discarded = size;
      clear();
      notifyAll();
    }
    channel.discardReceiveBytes(discarded);
  }
}
