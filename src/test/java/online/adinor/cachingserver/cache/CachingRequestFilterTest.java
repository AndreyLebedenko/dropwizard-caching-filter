/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ResourceInfo;
import online.adinor.cachingserver.cache.storage.HttpResponse;
import online.adinor.cachingserver.cache.storage.StatefulCacheEntry;
import org.junit.Before;
import org.junit.Test;

/**
 * The lease of a producer is a deadline that has to be taken from the clock the filter was given,
 * so that it can be controlled in the tests.
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class CachingRequestFilterTest {

  private static final String KEY = "the-key";
  private static final int TTL = 300;

  private final Instant fixedNow = Instant.parse("2026-01-01T12:00:00Z");
  private final NowProvider nowProvider = new NowProvider();
  private final Cache<String, StatefulCacheEntry<HttpResponse>> cache =
      CacheBuilder.newBuilder().build();
  private final ContainerRequestContext requestContext = mock(ContainerRequestContext.class);

  private CachingRequestFilter filter;

  @Before
  public void setUp() throws Exception {
    nowProvider.set(() -> fixedNow);
    filter = new CachingRequestFilter(context -> KEY, cache, nowProvider);
    injectResourceInfo(filter, cachedResourceInfo());
  }

  @Test
  public void leaseOfTheProducerIsTakenFromTheGivenClock() {
    filter.filter(requestContext);

    final StatefulCacheEntry<HttpResponse> entry = cache.getIfPresent(KEY);
    assertThat(entry).isNotNull();
    assertThat(entry.isPending()).isTrue();
    assertThat(entry.getProducerDeadline()).isEqualTo(fixedNow.plusMillis(TTL + 1L));
  }

  @Test
  public void entryIsTakenOverAtOnceWhenTheLeaseIsOver() {
    filter.filter(requestContext);
    final StatefulCacheEntry<HttpResponse> entry = cache.getIfPresent(KEY);
    assertThat(entry.getGeneration()).isEqualTo(1);

    // The producer is gone: its lease is over.
    nowProvider.set(() -> fixedNow.plusSeconds(60));
    final long started = System.nanoTime();
    filter.filter(requestContext);
    final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(entry.getGeneration()).isEqualTo(2);
    assertThat(entry.getProducerDeadline()).isEqualTo(fixedNow.plusSeconds(60).plusMillis(TTL + 1L));
    assertThat(elapsedMs).as("an expired lease is taken over at once").isLessThan(TTL);
  }

  @Test
  public void requestWaitsForTheLeaseOfALiveProducer() {
    filter.filter(requestContext);
    final StatefulCacheEntry<HttpResponse> entry = cache.getIfPresent(KEY);

    // The producer is still within its lease: the request waits for it before taking over.
    final long started = System.nanoTime();
    filter.filter(requestContext);
    final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(elapsedMs).as("the request waited for the lease").isGreaterThanOrEqualTo(TTL);
    assertThat(entry.getGeneration()).isEqualTo(2);
  }

  private static ResourceInfo cachedResourceInfo() throws Exception {
    final Method resourceMethod = CachedResource.class.getMethod("cached");
    final ResourceInfo resourceInfo = mock(ResourceInfo.class);
    when(resourceInfo.getResourceMethod()).thenReturn(resourceMethod);
    return resourceInfo;
  }

  private static void injectResourceInfo(
      final CachingRequestFilter filter, final ResourceInfo resourceInfo) throws Exception {
    final Field field = CachingRequestFilter.class.getDeclaredField("resourceInfo");
    field.setAccessible(true);
    field.set(filter, resourceInfo);
  }

  private static final class CachedResource {
    @ResponseCachedByFilter(value = TtlMode.FIXED, ttl = TTL)
    public Object cached() {
      return null;
    }
  }
}
