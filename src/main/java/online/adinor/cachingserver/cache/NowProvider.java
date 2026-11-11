/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * The source of the current time.
 *
 * <p>Every time related decision of the cache is taken through this provider, so that it can be
 * controlled in the tests: the lease of a producer, for instance, is a deadline that has to be
 * compared with the current time by several threads.
 *
 * <p>The default instance reads the system clock. Tests may replace the source of the current time
 * of the default instance with {@link #set(Supplier)}, or use their own instance of this class.
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class NowProvider implements Supplier<Instant> {

  private static final Supplier<Instant> DEFAULT_INSTANT_SUPPLIER = Instant::now;
  private static final NowProvider INSTANCE = new NowProvider();

  private volatile Supplier<Instant> clock = DEFAULT_INSTANT_SUPPLIER;

  public NowProvider() {}

  public static NowProvider getInstance() {
    return INSTANCE;
  }

  @Override
  public Instant get() {
    return clock.get();
  }

  /**
   * Replaces the source of the current time.
   *
   * @param newClock the new source of the current time
   */
  public void set(final Supplier<Instant> newClock) {
    this.clock = newClock;
  }

  /** Restores the system clock as the source of the current time. */
  public void reset() {
    this.clock = DEFAULT_INSTANT_SUPPLIER;
  }
}
