/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ResourceInfo;
import online.adinor.cachingserver.cache.storage.HttpResponse;
import online.adinor.cachingserver.cache.storage.StatefulCacheEntry;
import org.junit.Before;
import org.junit.Test;

/**
 * The lease of a producer is a deadline that has to be taken from the clock the filter was given,
 * so that it can be controlled in the tests. The lease is not the TTL of the value: the TTL says
 * how long a cached value stays valid, the lease says how long a producer may work on it.
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class CachingRequestFilterTest {

  private static final String KEY = "the-key";
  private static final int TTL = 300;
  private static final Duration LEASE = Duration.ofSeconds(1);

  private final Instant fixedNow = Instant.parse("2026-01-01T12:00:00Z");
  private final Clock clock = mock(Clock.class);
  private final Cache<String, StatefulCacheEntry<HttpResponse>> cache =
      CacheBuilder.newBuilder().build();
  private final ContainerRequestContext requestContext = mock(ContainerRequestContext.class);

  private CachingRequestFilter filter;

  @Before
  public void setUp() throws Exception {
    when(clock.instant()).thenReturn(fixedNow);
    filter = new CachingRequestFilter(context -> KEY, cache, clock, LEASE);
    injectResourceInfo(filter, cachedResourceInfo());
  }

  @Test
  public void leaseOfTheProducerIsTakenFromTheGivenClockAndIsNotTheTtl() {
    filter.filter(requestContext);

    final StatefulCacheEntry<HttpResponse> entry = cache.getIfPresent(KEY);
    assertThat(entry).isNotNull();
    assertThat(entry.isPending()).isTrue();
    assertThat(entry.getProducerDeadline()).isEqualTo(fixedNow.plus(LEASE));
    assertThat(entry.getProducerDeadline()).isNotEqualTo(fixedNow.plusMillis(TTL));
  }

  @Test
  public void entryIsTakenOverAtOnceWhenTheLeaseIsOver() {
    filter.filter(requestContext);
    final StatefulCacheEntry<HttpResponse> entry = cache.getIfPresent(KEY);
    assertThat(entry.getGeneration()).isEqualTo(1);

    // The producer is gone: its lease is over.
    when(clock.instant()).thenReturn(fixedNow.plusSeconds(60));
    final long started = System.nanoTime();
    filter.filter(requestContext);
    final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(entry.getGeneration()).isEqualTo(2);
    assertThat(entry.getProducerDeadline()).isEqualTo(fixedNow.plusSeconds(60).plus(LEASE));
    assertThat(elapsedMs).as("an expired lease is taken over at once").isLessThan(LEASE.toMillis());
  }

  @Test
  public void requestGivesUpWaitingWithoutTakingOverALiveProducer() {
    filter.filter(requestContext);
    final StatefulCacheEntry<HttpResponse> entry = cache.getIfPresent(KEY);

    // The producer is still within its lease: the request waits for its whole budget and
    // then goes to the resource itself instead of taking the entry over.
    final long started = System.nanoTime();
    filter.filter(requestContext);
    final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(elapsedMs).as("the request waited for its whole budget").isGreaterThanOrEqualTo(LEASE.toMillis());
    assertThat(entry.getGeneration())
        .as("a live producer is not taken over")
        .isEqualTo(1);
    verify(requestContext, never()).abortWith(any());
  }

  @Test
  public void anExpiredLeaseIsTakenOverWhenTheWaitBudgetIsOver() throws Exception {
    filter.filter(requestContext);
    final StatefulCacheEntry<HttpResponse> entry = cache.getIfPresent(KEY);
    assertThat(entry.getGeneration()).isEqualTo(1);

    // A second request with a shorter budget: it waits its 100 ms out, and by then
    // the lease of the producer is over as well - the entry is taken over.
    final Clock impatientClock = mock(Clock.class);
    when(impatientClock.instant())
        .thenReturn(fixedNow, fixedNow, fixedNow.plusSeconds(60));
    final CachingRequestFilter impatient =
        new CachingRequestFilter(context -> KEY, cache, impatientClock, Duration.ofMillis(100));
    injectResourceInfo(impatient, cachedResourceInfo());
    impatient.filter(requestContext);

    assertThat(entry.getGeneration()).isEqualTo(2);
    assertThat(entry.isPending()).isTrue();
    assertThat(entry.getProducerDeadline())
        .isEqualTo(fixedNow.plusSeconds(60).plusMillis(100));
  }

  @Test
  public void aFailureOfTheCacheIsLoggedAndTheRequestProceeds() throws Exception {
    @SuppressWarnings("unchecked")
    final Cache<String, StatefulCacheEntry<HttpResponse>> throwingCache = mock(Cache.class);
    when(throwingCache.get(anyString(), any(Callable.class)))
        .thenThrow(new ExecutionException(new RuntimeException("cache is broken")));
    final CachingRequestFilter filterWithBrokenCache =
        new CachingRequestFilter(context -> KEY, throwingCache, clock, LEASE);
    injectResourceInfo(filterWithBrokenCache, cachedResourceInfo());

    filterWithBrokenCache.filter(requestContext);

    assertThat(cache.getIfPresent(KEY)).isNull();
    verify(requestContext, never()).abortWith(any());
  }

  @Test
  public void anInterruptedWaiterIsLoggedAndTheRequestProceeds() throws Exception {
    filter.filter(requestContext);

    // The second request waits for the producer: the wait is interrupted, which
    // must be caught and logged, and the request proceeds to the resource.
    final Thread waiter = new Thread(() -> filter.filter(requestContext));
    waiter.start();
    Thread.sleep(100L);
    waiter.interrupt();
    waiter.join(2_000);

    assertThat(waiter.isAlive()).isFalse();
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
