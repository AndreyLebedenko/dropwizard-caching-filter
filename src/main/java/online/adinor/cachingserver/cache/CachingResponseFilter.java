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

import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;

import com.google.common.cache.Cache;

/**
 * Stores the response of the producer in the cache entry and releases the requests waiting for it.
 *
 * <p><b>Error responses are never cached.</b> A response with a status of 400 or above is not
 * stored in the entry: the entry is marked as failed instead, which releases the waiting requests
 * and lets one of them produce the value again. This is a deliberate decision: an error is usually
 * a temporary condition (an overloaded downstream, a timeout, a broken dependency), and serving it
 * from the cache would spread that condition over the whole TTL, turning a short outage into a
 * long one. The price is that every request during such an outage hits the resource. Deployments
 * that would rather trade the load for the predictability may cache the errors themselves, in the
 * resource, and let this filter deal with the successful responses only.
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class CachingResponseFilter implements ContainerResponseFilter {

  private static final Logger logger = LoggerFactory.getLogger(CachingResponseFilter.class);

  private final Timer timer = new Timer();
  private final Cache<String, StatefulCacheEntry<HttpResponse>> cache;

  @Context private volatile ResourceInfo resourceInfo;

  public CachingResponseFilter(final Cache<String, StatefulCacheEntry<HttpResponse>> cache) {
    this.cache = cache;
  }

  @Override
  public void filter(
      ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
    final Optional<ResponseCachedByFilter> annotation =
        Optional.ofNullable(resourceInfo)
            .map(ResourceInfo::getResourceMethod)
            .map(rm -> rm.getDeclaredAnnotation(ResponseCachedByFilter.class));
    annotation.ifPresent(
        a -> {
          final Optional<Role> myRole =
              Optional.ofNullable((Role) requestContext.getProperty(Role.OPTION_NAME));
          myRole.ifPresent(
              role -> {
                if (role.equals(Role.Producer)) {
                  final String key = (String) requestContext.getProperty(Options.KEY);
                  final StatefulCacheEntry<HttpResponse> entry =
                      (StatefulCacheEntry<HttpResponse>)
                          requestContext.getProperty(Options.CACHE_ENTRY);
                  final int generation = (Integer) requestContext.getProperty(Options.GENERATION);
                  logger.debug("Response entry: {}", entry);
                  if (responseContext.getStatus() >= 400) {
                    // The error is not cached. The entry is marked as failed, so that the
                    // requests waiting for it are released and one of them retries.
                    logger.debug("Error response is not cached: {}", responseContext.getStatus());
                    entry.resolve(generation, null);
                    return;
                  }
                  if (!entry.resolve(generation, HttpResponse.from(responseContext))) {
                    // The entry has been taken over by another producer while this one was
                    // working: its result is the one that counts, and its TTL must not be cut
                    // short by ours.
                    logger.debug("The entry has been taken over: the result is ignored");
                    return;
                  }

                  // enforce per-entry TTL
                  // workaround for ignored feature request
                  // https://github.com/google/guava/issues/1203
                  final int ttl = (Integer) requestContext.getProperty(Options.TTL);
                  timer.schedule(
                      new TimerTask() {
                        @Override
                        public void run() {
                          cache.invalidate(key);
                        }
                      },
                      ttl + 1);
                }
              });
        });
  }
}
