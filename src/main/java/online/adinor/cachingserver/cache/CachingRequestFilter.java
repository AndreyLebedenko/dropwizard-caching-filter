/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache;

import com.google.common.cache.Cache;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import online.adinor.cachingserver.cache.config.Options;
import online.adinor.cachingserver.cache.storage.HttpResponse;
import online.adinor.cachingserver.cache.storage.StatefulCacheEntry;

/**
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class CachingRequestFilter implements ContainerRequestFilter {
  private final Function<ContainerRequestContext, String> keyFactory;
  private final Cache<String, StatefulCacheEntry<HttpResponse>> cache;
  private final NowProvider nowProvider;

  @Context private volatile ResourceInfo resourceInfo;

  public CachingRequestFilter(
      final Function<ContainerRequestContext, String> keyFactory,
      final Cache<String, StatefulCacheEntry<HttpResponse>> cache) {
    this(keyFactory, cache, NowProvider.getInstance());
  }

  public CachingRequestFilter(
      final Function<ContainerRequestContext, String> keyFactory,
      final Cache<String, StatefulCacheEntry<HttpResponse>> cache,
      final NowProvider nowProvider) {
    this.keyFactory = keyFactory;
    this.cache = cache;
    this.nowProvider = nowProvider;
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
        final Instant now = nowProvider.get();
        if (element.isReady()) {
          // The value is cached: serve it without touching the resource.
          requestContextContainer.setProperty(Role.OPTION_NAME, Role.Consumer);
          requestContextContainer.abortWith(element.getData().asResponse());
          return;
        }
        if (element.isNew() || element.isFailed() || !element.hasLiveProducer(now)) {
          // Nobody is producing the value: either nobody started yet, the previous producer
          // failed, or it vanished without resolving the entry. This request becomes the producer.
          becomeProducer(requestContextContainer, element, ttl);
          return;
        }
        // Another request is producing the value: wait for it. The wait is not longer than the
        // lease of that producer, so we never wait for it longer than the TTL.
        requestContextContainer.setProperty(Role.OPTION_NAME, Role.Consumer);
        if (element.awaitResolution(element.getRemainingLease(now)) && element.isReady()) {
          // The producer resolved the entry while we were waiting.
          requestContextContainer.abortWith(element.getData().asResponse());
          return;
        }
        // The producer failed, or it did not make it in time: take the entry over.
        becomeProducer(requestContextContainer, element, ttl);
      }
    } catch (ExecutionException | InterruptedException ex) {
      Logger.getLogger(CachingRequestFilter.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

  /**
   * Takes the entry over: this request becomes the producer of the value.
   *
   * @param requestContextContainer the context of the request
   * @param element the entry to produce
   * @param ttl the TTL of the entry in milliseconds
   */
  private void becomeProducer(
      final ContainerRequestContext requestContextContainer,
      final StatefulCacheEntry<HttpResponse> element,
      final int ttl) {
    element.setPending(nowProvider.get().plusMillis(ttl + 1));
    requestContextContainer.setProperty(Options.TTL, ttl);
    requestContextContainer.setProperty(Role.OPTION_NAME, Role.Producer);
    requestContextContainer.setProperty(Options.CACHE_ENTRY, element);
    requestContextContainer.setProperty(Options.GENERATION, element.getGeneration());
  }
}
