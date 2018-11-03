/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache;

/**
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public enum Role {
  Producer, Consumer;

  public static final String OPTION_NAME = "caching_filter_my_role";
}

