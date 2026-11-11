/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache;

import com.google.common.cache.Cache;
import java.time.Duration;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import online.adinor.cachingserver.cache.config.Options;
import online.adinor.cachingserver.cache.storage.HttpResponse;
import online.adinor.cachingserver.cache.storage.StatefulCacheEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores the response of the producer in the cache entry and releases the requests waiting for it.
 *
 * <p><b>Error responses are never cached.</b> A response with a status of 400 or above is not
 * stored in the entry: the entry is marked as failed instead, which releases the waiting requests
 * and lets one of them produce the value again. This is a deliberate decision: an error is usually
 * a temporary condition (an overloaded downstream, a timeout, a broken dependency), and serving it
 * from the cache would spread that condition over the whole TTL, turning a short outage into a long
 * one. The price is that every request during such an outage hits the resource. Deployments that
 * would rather trade the load for the predictability may cache the errors themselves, in the
 * resource, and let this filter deal with the successful responses only.
 *
 * <p>A failed entry holds no data, but it stays in the cache: it is the point where the requests
 * that were waiting for the failed producer meet, and the next request for that key takes it over.
 * Once the coordination window of that producer is over, the failed entry is removed from the
 * cache, unless another producer has taken it over in the meantime.
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class CachingResponseFilter implements ContainerResponseFilter {

  private static final Logger logger = LoggerFactory.getLogger(CachingResponseFilter.class);

  // A daemon thread: a pending cache invalidation must not keep the JVM from shutting down.
  private final Timer timer = new Timer("caching-response-filter-ttl", true);
  private final Cache<String, StatefulCacheEntry<HttpResponse>> cache;
  private final Duration producerLease;

  @Context private volatile ResourceInfo resourceInfo;

  public CachingResponseFilter(
      final Cache<String, StatefulCacheEntry<HttpResponse>> cache, final Duration producerLease) {
    this.cache = cache;
    this.producerLease = producerLease;
  }

  @Override
  public void filter(
      ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
    final Optional<ResponseCachedByFilter> annotation =
        Optional.ofNullable(resourceInfo)
            .map(ResourceInfo::getResourceMethod)
            .map(rm -> rm.getDeclaredAnnotation(ResponseCachedByFilter.class));
    annotation.ifPresent(a -> process(requestContext, responseContext));
  }

  private void process(
      ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
    final Optional<Role> myRole =
        Optional.ofNullable((Role) requestContext.getProperty(Role.OPTION_NAME));
    myRole.ifPresent(role -> {
      if (role.equals(Role.Producer)) {
        populateResponse(requestContext, responseContext);
      }
    });
  }

  private void populateResponse(
      ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
    final String key = (String) requestContext.getProperty(Options.KEY);
    final StatefulCacheEntry<HttpResponse> entry =
        (StatefulCacheEntry<HttpResponse>) requestContext.getProperty(Options.CACHE_ENTRY);
    final int generation = (Integer) requestContext.getProperty(Options.GENERATION);
    logger.debug("Response entry: {}", entry);
    if (responseContext.getStatus() >= 400) {
      // The error is not cached. The entry is marked as failed, so that the
      // requests waiting for it are released and one of them retries.
      logger.debug("Error response is not cached: {}", responseContext.getStatus());
      if (!entry.resolve(generation, null)) {
        logger.debug("The entry has been taken over: {}", entry);
        return;
      }
      scheduleDeletion(entry, generation, key);
      return;
    }
    if (!entry.resolve(generation, HttpResponse.from(responseContext))) {
      // The entry has been taken over by another producer while this one was
      // working: its result is the one that counts, and its TTL must not be cut
      // short by ours.
      logger.debug("The entry has been taken over - the result is ignored: {}", entry);
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
            if (entry.getGeneration() == generation) {
              cache.asMap().remove(key, entry);
            }
          }
        },
        ttl + 1);
  }

  private void scheduleDeletion(StatefulCacheEntry<HttpResponse> entry, int generation, String key) {
    // Nobody may need this entry anymore once the coordination window of that
    // producer is over: free the cache slot. The check and the removal are done
    // under the monitor of the entry, so that a request taking the entry over
    // never ends up with an entry that is not in the cache anymore.
    timer.schedule(
        new TimerTask() {
          @Override
          public void run() {
            synchronized (entry) {
              if (entry.isFailed()
                  && entry.getGeneration() == generation) {
                logger.debug("Failed entry is removed from the cache: {}", key);
                cache.asMap().remove(key, entry);
              }
            }
          }
        },
        producerLease.toMillis());
  }
}
