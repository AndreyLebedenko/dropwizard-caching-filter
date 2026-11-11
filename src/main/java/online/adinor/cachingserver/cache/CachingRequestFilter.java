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
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import online.adinor.cachingserver.cache.config.Options;
import online.adinor.cachingserver.cache.storage.HttpResponse;
import online.adinor.cachingserver.cache.storage.StatefulCacheEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class CachingRequestFilter implements ContainerRequestFilter {
  private static final Logger logger = LoggerFactory.getLogger(CachingRequestFilter.class);

  private final Function<ContainerRequestContext, String> keyFactory;
  private final Cache<String, StatefulCacheEntry<HttpResponse>> cache;
  private final Clock clock;
  private final Duration producerLease;

  @Context private volatile ResourceInfo resourceInfo;

  public CachingRequestFilter(
      final Function<ContainerRequestContext, String> keyFactory,
      final Cache<String, StatefulCacheEntry<HttpResponse>> cache,
      final Clock clock,
      final Duration producerLease) {
    this.keyFactory = keyFactory;
    this.cache = cache;
    this.clock = clock;
    this.producerLease = producerLease;
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
      ContainerRequestContext requestContextContainer, ResponseCachedByFilter annotation) {
    try {
      final String key = keyFactory.apply(requestContextContainer);
      requestContextContainer.setProperty(Options.KEY, key);
      // In worst case scenario if cache implementation does not guarantee the per-key atomicity of
      // the `get` function.
      // Such a cache hit will result in double processing with race condition on update, which is
      // unfortunate, but not catastrophic.
      final StatefulCacheEntry<HttpResponse> element = cache.get(key, StatefulCacheEntry::new);
      final int ttl = TtlResolver.resolve(annotation);
      synchronized (element) {
        // How long this request is willing to wait for another request to produce the value. It is
        // measured with a monotonic clock: it is a real wait, it must always come to an end.
        final long waitBudget = System.nanoTime() + producerLease.toNanos();
        while (true) {
          final Instant now = clock.instant();
          if (element.isReady()) {
            // The value is cached: serve it without touching the resource.
            requestContextContainer.setProperty(Role.OPTION_NAME, Role.Consumer);
            requestContextContainer.abortWith(element.getData().asResponse());
            return;
          }
          if (element.isNew() || element.isFailed()) {
            // Nobody is producing the value, or the previous producer failed: produce it.
            becomeProducer(requestContextContainer, element, ttl);
            return;
          }
          if (!element.hasLiveProducer(now)) {
            // The producer is gone without resolving the entry: take the entry over.
            becomeProducer(requestContextContainer, element, ttl);
            return;
          }
          // Another request is producing the value: wait for it. The wait is not longer than the
          // lease of that producer, and not longer than this request is willing to wait.
          requestContextContainer.setProperty(Role.OPTION_NAME, Role.Consumer);
          final long left = waitBudget - System.nanoTime();
          if (left <= 0L) {
            if (element.hasLiveProducer(clock.instant())) {
              // This request is not willing to wait any longer, but the producer is still
              // alive: leave the entry to it, its result will serve the requests that
              // follow. This one goes to the resource itself, without taking the entry
              // over - a live producer's work must not be thrown away.
              return;
            }
            becomeProducer(requestContextContainer, element, ttl);
            return;
          }
          if (element.awaitResolution(earlier(element.getRemainingLease(now), left))
              && element.isReady()) {
            // The producer resolved the entry while we were waiting.
            requestContextContainer.abortWith(element.getData().asResponse());
            return;
          }
          // The producer failed, or the entry has been taken over by somebody else in the
          // meantime: look at the state of the entry again.
        }
      }
    } catch (ExecutionException | InterruptedException ex) {
      logger.warn(ex.getLocalizedMessage(), ex);
    }
  }

  /**
   * Takes the entry over: this request becomes the producer of the value.
   *
   * @param requestContextContainer the context of the request
   * @param element the entry to produce
   * @param ttl the TTL of the value in milliseconds
   */
  private void becomeProducer(
      final ContainerRequestContext requestContextContainer,
      final StatefulCacheEntry<HttpResponse> element,
      final int ttl) {
    element.setPending(clock.instant().plus(producerLease));
    requestContextContainer.setProperty(Options.TTL, ttl);
    requestContextContainer.setProperty(Role.OPTION_NAME, Role.Producer);
    requestContextContainer.setProperty(Options.CACHE_ENTRY, element);
    requestContextContainer.setProperty(Options.GENERATION, element.getGeneration());
  }

  /**
   * @param duration the duration to shorten
   * @param nanos the number of nanoseconds it may not exceed
   * @return the shorter of the two
   */
  private static Duration earlier(final Duration duration, final long nanos) {
    final Duration limit = Duration.ofNanos(nanos);
    return duration.compareTo(limit) > 0 ? limit : duration;
  }
}
