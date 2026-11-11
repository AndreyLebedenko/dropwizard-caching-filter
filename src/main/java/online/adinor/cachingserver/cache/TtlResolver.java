/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache;

/**
 * Single source of truth for the effective TTL of a {@link ResponseCachedByFilter} annotation.
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
final class TtlResolver {

  private TtlResolver() {}

  /**
   * @param annotation the annotation found on the resource method
   * @return the TTL in milliseconds: the declared one for {@link TtlMode#FIXED}, the value
   *     currently provided by the {@link TtlProvider} for {@link TtlMode#DYNAMIC}
   */
  static int resolve(final ResponseCachedByFilter annotation) {
    return annotation.value() == TtlMode.FIXED ? annotation.ttl() : annotation.value().getTtl();
  }
}
