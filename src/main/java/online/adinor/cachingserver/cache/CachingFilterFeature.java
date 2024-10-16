/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache;

import com.google.common.cache.Cache;
import java.util.List;
import java.util.function.Function;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Feature;
import javax.ws.rs.core.FeatureContext;
import online.adinor.cachingserver.cache.storage.HttpResponse;
import online.adinor.cachingserver.cache.storage.StatefulCacheEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;

import com.google.common.cache.Cache;

/** @author Andrey Lebedenko (andrey.lebedenko@gmail.com) */
public class CachingFilterFeature implements Feature {

  private static final Logger logger = LoggerFactory.getLogger(CachingFilterFeature.class);
  private final List<String> headersToIncludeInKey;
  private final List<String> queryParametersToIncludeInKey;
  private final Cache<String, StatefulCacheEntry<HttpResponse>> cache;

  public CachingFilterFeature(
      List<String> headersToIncludeInKey,
      List<String> queryParametersToIncludeInKey,
      Cache<String, StatefulCacheEntry<HttpResponse>> cache) {
    this.headersToIncludeInKey = headersToIncludeInKey;
    this.queryParametersToIncludeInKey = queryParametersToIncludeInKey;
    this.cache = cache;
  }

  @Override
  public boolean configure(final FeatureContext context) {
    final Function<ContainerRequestContext, String> keyGen = x -> computeKey(x);
    context
        .register(new CachingRequestFilter(keyGen, cache))
        .register(new CachingResponseFilter(cache));
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
