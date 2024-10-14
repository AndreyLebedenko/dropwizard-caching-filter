/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache;

import online.adinor.cachingserver.cache.storage.HttpResponse;
import online.adinor.cachingserver.cache.storage.StatefulCacheEntry;
import online.adinor.cachingserver.cache.config.Options;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.cache.Cache;

/**
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class CachingRequestFilter implements ContainerRequestFilter {
  private final Function<ContainerRequestContext, String> keyFactory;
  private final Cache<String, StatefulCacheEntry<HttpResponse>> cache;

  @Context private volatile ResourceInfo resourceInfo;

  public CachingRequestFilter(
      final Function<ContainerRequestContext, String> keyFactory,
      final Cache<String, StatefulCacheEntry<HttpResponse>> cache) {
    this.keyFactory = keyFactory;
    this.cache = cache;
  }

  @Override
  public void filter(final ContainerRequestContext requestContext) {
    final Optional<ResponseCachedByFilter> annotation =
        Optional.ofNullable(resourceInfo)
            .map(ResourceInfo::getResourceMethod)
            .map(rm -> rm.getDeclaredAnnotation(ResponseCachedByFilter.class));
    annotation.ifPresent(a -> processRequestViaCache(requestContext, a));
  }

  private void processRequestViaCache(
      ContainerRequestContext requestContextContainer, ResponseCachedByFilter cachedResponse) {
    try {
      final String key = keyFactory.apply(requestContextContainer);
      requestContextContainer.setProperty(Options.KEY, key);
      // In worst case scenario if cache implementation does not guarantee the per-key atomicity of
      // the `get` function.
      // such a cache hit will result in double processing with race condition on update, which is
      // unfortunate, but
      // not catastrophic.
      final StatefulCacheEntry<HttpResponse> element = cache.get(key, StatefulCacheEntry::new);
      synchronized (element) {
        if (element.isNew()) {
          final int ttl = cachedResponse.value().getTtl();
          requestContextContainer.setProperty(Options.TTL, ttl);
          requestContextContainer.setProperty(Role.OPTION_NAME, Role.Producer);
          requestContextContainer.setProperty(Options.CACHE_ENTRY, element);
        } else if (element.isPending()) {
          requestContextContainer.setProperty(Role.OPTION_NAME, Role.Consumer);
          element.wait(cachedResponse.value().getTtl() + 1);
          if (element.isReady()) // ready after concurrent request
          {
            requestContextContainer.abortWith(element.getData().asResponse());
          }
        } else // Ready
        {
          requestContextContainer.setProperty(Role.OPTION_NAME, Role.Consumer);
          requestContextContainer.abortWith(element.getData().asResponse());
        }
      }
    } catch (ExecutionException | InterruptedException ex) {
      Logger.getLogger(CachingRequestFilter.class.getName()).log(Level.SEVERE, null, ex);
    }
  }
}
