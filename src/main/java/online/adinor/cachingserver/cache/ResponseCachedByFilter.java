/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Instructs the caching filter to cache the response of the annotated method.
 *
 * <p><b>Only successful responses are cached.</b> A response with a status of 400 or above is
 * never stored in the cache: the requests waiting for it are released and one of them produces the
 * value again. This is a deliberate decision, as an error is usually a temporary condition and
 * caching it would spread that condition over the whole TTL. The price is that every request
 * during such an outage reaches the resource.
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
@Inherited
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ResponseCachedByFilter {

  TtlMode value() default TtlMode.FIXED;

  // optional TTL in milliseconds for fixed option
  int ttl() default TtlProvider.DEFAULT_TTL;
}

