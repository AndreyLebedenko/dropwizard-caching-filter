/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache.resource;

import org.joda.time.Instant;

import java.util.function.Supplier;

/**
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class DAO implements Supplier<Object> {

  @Override
  public Object get() {
    return new Object() {
      public String processed = Instant.now().toDateTime().toString();
    };
  }

}

