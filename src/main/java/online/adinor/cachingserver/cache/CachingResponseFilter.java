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
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;

import com.google.common.cache.Cache;

/**
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class CachingResponseFilter implements ContainerResponseFilter {

  private static final Logger logger = LoggerFactory.getLogger(CachingResponseFilter.class);

  private final Timer timer = new Timer();
  private final Cache<String, StatefulCacheEntry<HttpResponse>> cache;

  @Context private ResourceInfo resourceInfo;

  public CachingResponseFilter(final Cache<String, StatefulCacheEntry<HttpResponse>> cache) {
    this.cache = cache;
  }

  @Override
  public void filter(
      ContainerRequestContext requestContext, ContainerResponseContext responseContext)
      throws IOException {
    final Optional<ResponseCachedByFilter> annotation =
        Optional.ofNullable(
            resourceInfo.getResourceMethod().getDeclaredAnnotation(ResponseCachedByFilter.class));
    annotation.ifPresent(
        a -> {
          final Optional<Role> myRole =
              Optional.ofNullable((Role) requestContext.getProperty(Role.OPTION_NAME));
          myRole.ifPresent(
              role -> {
                if (role.equals(Role.Producer)) {
                  final String key = (String) requestContext.getProperty(Options.KEY);
                  final StatefulCacheEntry<HttpResponse> entry =
                      (StatefulCacheEntry) requestContext.getProperty(Options.CACHE_ENTRY);
                  logger.debug("Response entry: {}", entry);
                  entry.setData(HttpResponse.from(responseContext));
                  entry.setReady();
                  entry.unblock();

                  // enforce per-entry TTL
                  // workaround for ignored feature request
                  // https://github.com/google/guava/issues/1203
                  timer.schedule(
                      new TimerTask() {
                        @Override
                        public void run() {
                          cache.invalidate(key);
                        }
                      },
                      a.value().getTtl());
                }
              });
        });
  }
}

