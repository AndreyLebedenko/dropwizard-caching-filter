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

  @Override
  public Object apply(Integer i, String s) {
    return new Object() {
      public String processed = "i=" + i + ",s=" + s + "," + Instant.now().toDateTime();
    };
  }
}
