/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache.resource;

import java.util.function.BiFunction;
import org.joda.time.Instant;

import java.util.function.Supplier;

/**
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class DAO implements BiFunction<Integer, String, Object> {

  public static final java.util.concurrent.atomic.AtomicInteger CALLS = new java.util.concurrent.atomic.AtomicInteger();

  @Override
  public Object apply(Integer i, String s) {
    CALLS.incrementAndGet();
    try { Thread.sleep(200); } catch (InterruptedException e) { }
    return new Object() {
      public String processed = "i=" + i + ",s=" + s + "," + Instant.now().toDateTime();
    };
  }
}
