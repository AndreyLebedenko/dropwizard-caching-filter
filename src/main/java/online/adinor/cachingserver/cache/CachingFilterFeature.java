/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache;

import com.google.common.cache.Cache;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Feature;
import javax.ws.rs.core.FeatureContext;
import online.adinor.cachingserver.cache.storage.HttpResponse;
import online.adinor.cachingserver.cache.storage.StatefulCacheEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the caching filters in the Jersey environment.
 *
 * <p>The cache key is built from the method, the path, the listed request headers and the listed
 * query parameters. Only the responses of the methods annotated with
 * {@link ResponseCachedByFilter} are cached, and only if their status is below 400: error
 * responses are never cached, see {@link ResponseCachedByFilter}.
 *
 * <p>The TTL of an entry and the lease of its producer are two different things: the TTL says how
 * long a cached value stays valid, the lease says how long a producer is allowed to work on it.
 * The lease is a property of the filter, the TTL is a property of the annotated method.
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class CachingFilterFeature implements Feature {

  /**
   * The default lease of a producer: how long a request may wait for another request to produce
   * the value it needs.
   * Must exceed the worst-case response time of the resource; a shorter lease effectively disables
   * caching under heavy load.
   */
  public static final Duration DEFAULT_PRODUCER_LEASE = Duration.ofSeconds(10);

  private static final Logger logger = LoggerFactory.getLogger(CachingFilterFeature.class);
  private final List<String> headersToIncludeInKey;
  private final List<String> queryParametersToIncludeInKey;
  private final Cache<String, StatefulCacheEntry<HttpResponse>> cache;
  private final Duration producerLease;

  public CachingFilterFeature(
      List<String> headersToIncludeInKey,
      List<String> queryParametersToIncludeInKey,
      Cache<String, StatefulCacheEntry<HttpResponse>> cache) {
    this(
        headersToIncludeInKey,
        queryParametersToIncludeInKey,
        cache,
        DEFAULT_PRODUCER_LEASE);
  }

  public CachingFilterFeature(
      List<String> headersToIncludeInKey,
      List<String> queryParametersToIncludeInKey,
      Cache<String, StatefulCacheEntry<HttpResponse>> cache,
      Duration producerLease) {
    this.headersToIncludeInKey = headersToIncludeInKey;
    this.queryParametersToIncludeInKey = queryParametersToIncludeInKey;
    this.cache = cache;
    this.producerLease = producerLease;
  }

  @Override
  public boolean configure(final FeatureContext context) {
    final Function<ContainerRequestContext, String> keyGen = this::computeKey;
    context
        .register(
            new CachingRequestFilter(keyGen, cache, Clock.systemUTC(), producerLease))
        .register(new CachingResponseFilter(cache, producerLease));
    return true;
  }

  // This will be called on every request to produce a key for the cache.
  private String computeKey(ContainerRequestContext context) {
    String res = "";
    for (final String h : headersToIncludeInKey) {
      res = res + h + ":" + context.getHeaderString(h);
    }
    for (final String q : queryParametersToIncludeInKey) {
      res = res + q + ":" + context.getUriInfo().getQueryParameters().getFirst(q);
    }
    final String key = context.getMethod() + ":" + context.getUriInfo().getPath() + ":" + res;
    logger.debug("Key produced: {}", key);
    return key;
  }
}
