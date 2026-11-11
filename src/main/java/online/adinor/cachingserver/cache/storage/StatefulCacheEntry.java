/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A cache entry that also tracks the state of the request that is producing its data.
 *
 * <p>The life cycle of an entry:
 *
 * <pre>
 *   new ---&gt; pending ---&gt; ready     the producer succeeded, the data is cached
 *                |
 *                +------&gt; failed      the producer failed, there is nothing to cache
 * </pre>
 *
 * <p>A failed entry holds no data. It only records that the previous producer gave up, so that
 * the next request takes the entry over instead of waiting for a producer that is not there
 * anymore.
 *
 * <p>While an entry is pending, the producer that owns it holds a lease: the deadline until which
 * it is considered to be alive. A request that finds a pending entry waits for it, but takes the
 * entry over as soon as the lease is over - otherwise a producer that never came back would block
 * the key for the whole TTL.
 *
 * <p>The entry does not read the clock itself: the deadlines it stores are given by the caller,
 * which knows how to tell the time.
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class StatefulCacheEntry<T> {

  private static final int STATE_NEW = 0;
  private static final int STATE_PENDING = 1;
  private static final int STATE_READY = 2;
  private static final int STATE_FAILED = 3;

  private final AtomicInteger state = new AtomicInteger(STATE_NEW);
  private final AtomicReference<T> data = new AtomicReference<>();
  /** Bumped on every takeover: tells which producer currently owns the entry. */
  private final AtomicInteger generation = new AtomicInteger(0);
  /** The moment until which the current producer is considered alive. Null means "no producer". */
  private final AtomicReference<Instant> producerDeadline = new AtomicReference<>();

  public StatefulCacheEntry() {
  }

  public StatefulCacheEntry(final T data) {
    this.data.set(data);
  }

  public StatefulCacheEntry<T> setData(final T data) {
    this.data.set(data);
    return this;
  }

  public T getData() {
    return data.get();
  }

  public boolean isNew() {
    return state.get() == STATE_NEW;
  }

  public boolean isPending() {
    return state.get() == STATE_PENDING;
  }

  public boolean isReady() {
    return state.get() == STATE_READY;
  }

  /**
   * @return true if the producer of this entry failed and nothing was cached
   */
  public boolean isFailed() {
    return state.get() == STATE_FAILED;
  }

  /**
   * Puts the entry into the pending state, owned by the producer until the given deadline.
   *
   * @param deadline the moment until which the producer is considered to be alive, null if there is
   *     no producer at all
   * @return this entry
   */
  public synchronized StatefulCacheEntry<T> setPending(final Instant deadline) {
    state.set(STATE_PENDING);
    generation.incrementAndGet();
    producerDeadline.set(deadline);
    return this;
  }

  /**
   * @return the number of the current producer, to be passed back to {@link #resolve(int, Object)}
   */
  public int getGeneration() {
    return generation.get();
  }

  /**
   * @return the moment until which the current producer is considered to be alive, null if there is
   *     no producer
   */
  public Instant getProducerDeadline() {
    return producerDeadline.get();
  }

  /**
   * @param now the current time
   * @return true if a producer owns the entry and its lease is not over yet
   */
  public boolean hasLiveProducer(final Instant now) {
    final Instant deadline = producerDeadline.get();
    return deadline != null && deadline.isAfter(now);
  }

  /**
   * @param now the current time
   * @return how long the current producer is still expected to work
   */
  public Duration getRemainingLease(final Instant now) {
    final Instant deadline = producerDeadline.get();
    if (deadline == null) {
      return Duration.ZERO;
    }
    final Duration remaining = Duration.between(now, deadline);
    return remaining.isNegative() ? Duration.ZERO : remaining;
  }

  /**
   * Resolves the entry with the result of its producer and releases everybody waiting for it.
   *
   * <p>A result of a producer that has been taken over is ignored: a newer producer owns the entry
   * by then and its result is the one that counts.
   *
   * @param producerGeneration the generation the producer was given when it took the entry
   * @param data the produced data, or null if the producer failed
   * @return true if the result was applied to the entry
   */
  public synchronized boolean resolve(final int producerGeneration, final T data) {
    if (producerGeneration != generation.get()) {
      return false;
    }
    if (data == null) {
      state.set(STATE_FAILED);
    } else {
      this.data.set(data);
      state.set(STATE_READY);
    }
    notifyAll();
    return true;
  }

  /**
   * Waits until the entry is resolved by its producer or the timeout is over.
   *
   * <p>The wait itself is measured with a monotonic clock: it is a real wait, it must not depend on
   * the wall clock and it must always come to an end, even if the source of the current time is
   * frozen.
   *
   * @param timeout how long to wait at most
   * @return true if the entry was resolved while waiting
   */
  public synchronized boolean awaitResolution(final Duration timeout) throws InterruptedException {
    final long deadline = System.nanoTime() + Math.max(timeout.toNanos(), 0L);
    while (isPending()) {
      final long remaining = deadline - System.nanoTime();
      if (remaining <= 0L) {
        return false;
      }
      TimeUnit.NANOSECONDS.timedWait(this, remaining);
    }
    return true;
  }

  public void unblock() {
    synchronized (this) {
      notifyAll();
    }
  }

}
