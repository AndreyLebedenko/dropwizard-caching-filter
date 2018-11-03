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
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class CachingRequestFilter implements ContainerRequestFilter {
  private final Function<ContainerRequestContext, String> keyFactory;
  private final Cache<String, StatefulCacheEntry<HttpResponse>> cache;

  @Context
  private ResourceInfo resourceInfo;

  public CachingRequestFilter(
      final Function<ContainerRequestContext, String> keyFactory,
      final Cache<String, StatefulCacheEntry<HttpResponse>> cache) {
    this.keyFactory = keyFactory;
    this.cache = cache;
  }

  @Override
  public void filter(final ContainerRequestContext requestContext) throws IOException {
    final Method resourceMethod = resourceInfo.getResourceMethod();
    final Optional<ResponseCachedByFilter> annotation = Optional.ofNullable(resourceMethod.getDeclaredAnnotation(ResponseCachedByFilter.class));
    annotation.ifPresent(a -> {
      try {
        final String key = keyFactory.apply(requestContext);
        requestContext.setProperty(Options.KEY, key);
        final StatefulCacheEntry<HttpResponse> element = cache.get(key, StatefulCacheEntry::new);
        synchronized (element) {
          if (element.isNew()) {
            requestContext.setProperty(Options.TTL, a.value());
            requestContext.setProperty(Role.OPTION_NAME, Role.Producer);
            requestContext.setProperty(Options.CACHE_ENTRY, element);
            return;
          } else if (element.isPending()) {
            requestContext.setProperty(Role.OPTION_NAME, Role.Consumer);
            element.wait(a.value());
            if (element.isReady()) // ready after concurrent request
            {
              requestContext.abortWith(element.getData().asResponse());
            }
          } else // Ready
          {
            requestContext.setProperty(Role.OPTION_NAME, Role.Consumer);
            requestContext.abortWith(element.getData().asResponse());
          }
        }
      } catch (ExecutionException | InterruptedException ex) {
        Logger.getLogger(CachingRequestFilter.class.getName()).log(Level.SEVERE, null, ex);
      }
    });
  }

}

