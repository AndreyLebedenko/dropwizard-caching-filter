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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.MultivaluedHashMap;
import online.adinor.cachingserver.cache.config.Options;
import online.adinor.cachingserver.cache.storage.HttpResponse;
import online.adinor.cachingserver.cache.storage.StatefulCacheEntry;
import org.junit.Before;
import org.junit.Test;

/**
 * A producer that has been taken over while it was working must not overwrite the value of the
 * producer that owns the entry now, and must not cut its TTL short either.
 *
 * <p>The removal from the cache is conditional: a timer removes the entry it was scheduled for,
 * and never whatever is at the key now. The tests below check that against a real map, not
 * against the number of calls to the cache.
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class CachingResponseFilterTest {

  private static final String KEY = "the-key";
  private static final Duration LEASE = Duration.ofMillis(100);

  @SuppressWarnings("unchecked")
  private final Cache<String, StatefulCacheEntry<HttpResponse>> cache = mock(Cache.class);
  private final ConcurrentMap<String, StatefulCacheEntry<HttpResponse>> asMap =
      new ConcurrentHashMap<>();

  private final ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
  private final ContainerResponseContext responseContext = mock(ContainerResponseContext.class);

  private StatefulCacheEntry<HttpResponse> entry;
  private CachingResponseFilter filter;

  @Before
  public void setUp() throws Exception {
    entry = new StatefulCacheEntry<HttpResponse>();
    when(responseContext.getStatus()).thenReturn(200);
    when(responseContext.getStringHeaders()).thenReturn(new MultivaluedHashMap<String, String>());
    when(responseContext.getLength()).thenReturn(0);
    when(responseContext.getEntity()).thenReturn("the value");

    when(requestContext.getProperty(Role.OPTION_NAME)).thenReturn(Role.Producer);
    when(requestContext.getProperty(Options.KEY)).thenReturn(KEY);
    when(requestContext.getProperty(Options.CACHE_ENTRY)).thenReturn(entry);
    // The TTL timer fires right after the response is stored.
    when(requestContext.getProperty(Options.TTL)).thenReturn(0);

    // The entry sits in the cache like it does in production: the timers of the
    // filter remove it through this map.
    when(cache.asMap()).thenReturn(asMap);
    asMap.put(KEY, entry);

    filter = new CachingResponseFilter(cache, LEASE);
    injectResourceInfo(filter, cachedResourceInfo());
  }

  @Test
  public void theResponseOfTheProducerIsStoredInTheEntry() throws Exception {
    entry.setPending(Instant.now().plusSeconds(10));
    when(requestContext.getProperty(Options.GENERATION)).thenReturn(entry.getGeneration());

    filter.filter(requestContext, responseContext);

    assertThat(entry.isReady()).isTrue();
    assertThat(entry.getData().getEntity()).isEqualTo("the value");
    assertRemovedWithin(2_000L);
  }

  @Test
  public void theResultOfATakenOverProducerIsIgnored() throws Exception {
    entry.setPending(Instant.now().plusSeconds(10));
    final int staleGeneration = entry.getGeneration();
    // Another producer takes the entry over.
    entry.setPending(Instant.now().plusSeconds(10));
    when(requestContext.getProperty(Options.GENERATION)).thenReturn(staleGeneration);

    filter.filter(requestContext, responseContext);

    assertThat(entry.isPending()).as("the entry belongs to the new producer").isTrue();
    assertThat(entry.getData()).as("the value of the new producer is kept").isNull();
    // Give the timers the time to fire, if any has been scheduled.
    Thread.sleep(100L);
    assertThat(asMap).as("no timer was scheduled for a taken over producer").containsKey(KEY);
  }

  @Test
  public void theFailedEntryIsRemovedFromTheCacheWhenNobodyTakesItOver() throws Exception {
    when(responseContext.getStatus()).thenReturn(500);
    entry.setPending(Instant.now().plusSeconds(10));
    when(requestContext.getProperty(Options.GENERATION)).thenReturn(entry.getGeneration());

    filter.filter(requestContext, responseContext);

    assertThat(entry.isFailed()).isTrue();
    assertRemovedWithin(2_000L);
  }

  @Test
  public void theFailedEntryIsKeptWhenAnotherProducerHasTakenItOver() throws Exception {
    when(responseContext.getStatus()).thenReturn(500);
    entry.setPending(Instant.now().plusSeconds(10));
    when(requestContext.getProperty(Options.GENERATION)).thenReturn(entry.getGeneration());
    filter.filter(requestContext, responseContext);
    assertThat(entry.isFailed()).isTrue();

    // Another producer takes the entry over and fails as well.
    entry.setPending(Instant.now().plusSeconds(10));
    final int newGeneration = entry.getGeneration();
    when(requestContext.getProperty(Options.GENERATION)).thenReturn(newGeneration);
    filter.filter(requestContext, responseContext);

    // The timer of the stale failure skips the entry, the timer of the last one removes it.
    assertRemovedWithin(2_000L);
  }

  @Test
  public void anErrorOfATakenOverProducerIsIgnored() throws Exception {
    when(responseContext.getStatus()).thenReturn(500);
    entry.setPending(Instant.now().plusSeconds(10));
    final int staleGeneration = entry.getGeneration();
    // Another producer takes the entry over.
    entry.setPending(Instant.now().plusSeconds(10));
    when(requestContext.getProperty(Options.GENERATION)).thenReturn(staleGeneration);

    filter.filter(requestContext, responseContext);

    assertThat(entry.isPending()).as("the entry belongs to the new producer").isTrue();
    assertThat(entry.getGeneration()).isEqualTo(2);
    assertThat(entry.getData()).as("nothing was stored").isNull();
    Thread.sleep(100L);
    assertThat(asMap).as("no timer was scheduled for a taken over producer").containsKey(KEY);
  }

  @Test
  public void aTakenOverEntryIsNotInvalidatedByTheTtlTimerOfTheOldProducer() throws Exception {
    entry.setPending(Instant.now().plusSeconds(10));
    when(requestContext.getProperty(Options.GENERATION)).thenReturn(entry.getGeneration());
    when(requestContext.getProperty(Options.TTL)).thenReturn(200);
    filter.filter(requestContext, responseContext);
    assertThat(entry.isReady()).isTrue();

    // Another producer takes the entry over before the TTL timer of this one fires.
    entry.setPending(Instant.now().plusSeconds(10));

    Thread.sleep(500L);
    assertThat(asMap).as("the TTL timer of the old producer is not theirs to fire").containsKey(KEY);
  }

  @Test
  public void theTtlTimerRemovesOnlyItsOwnEntry() throws Exception {
    entry.setPending(Instant.now().plusSeconds(10));
    when(requestContext.getProperty(Options.GENERATION)).thenReturn(entry.getGeneration());
    when(requestContext.getProperty(Options.TTL)).thenReturn(200);
    filter.filter(requestContext, responseContext);
    assertThat(entry.isReady()).isTrue();

    // The entry is evicted and the key is taken by a fresh entry before the TTL
    // timer of the old one fires: the timer must not remove the fresh entry.
    final StatefulCacheEntry<HttpResponse> replacement = new StatefulCacheEntry<>();
    asMap.put(KEY, replacement);

    Thread.sleep(500L);
    assertThat(asMap.get(KEY))
        .as("the timer removed only its own entry")
        .isSameAs(replacement);
  }

  @Test
  public void aFailedEntryTakenOverAndProducedIsNotRemovedByTheOldTimer() throws Exception {
    when(responseContext.getStatus()).thenReturn(500);
    entry.setPending(Instant.now().plusSeconds(10));
    when(requestContext.getProperty(Options.GENERATION)).thenReturn(entry.getGeneration());
    filter.filter(requestContext, responseContext);
    assertThat(entry.isFailed()).isTrue();

    // Another producer takes the entry over and succeeds before the deletion timer
    // of the failed producer fires.
    entry.setPending(Instant.now().plusSeconds(10));
    assertThat(entry.resolve(entry.getGeneration(), mock(HttpResponse.class))).isTrue();

    Thread.sleep(200L); // the lease of this test class is 100 ms
    assertThat(asMap).as("the entry is not failed anymore").containsKey(KEY);
  }

  @Test
  public void theDeletionTimerRemovesOnlyItsOwnEntry() throws Exception {
    when(responseContext.getStatus()).thenReturn(500);
    entry.setPending(Instant.now().plusSeconds(10));
    when(requestContext.getProperty(Options.GENERATION)).thenReturn(entry.getGeneration());
    filter.filter(requestContext, responseContext);
    assertThat(entry.isFailed()).isTrue();

    // The key is taken by a fresh entry before the deletion timer of the failed
    // one fires: the timer must not remove the fresh entry.
    final StatefulCacheEntry<HttpResponse> replacement = new StatefulCacheEntry<>();
    asMap.put(KEY, replacement);

    Thread.sleep(200L); // the lease of this test class is 100 ms
    assertThat(asMap.get(KEY))
        .as("the timer removed only its own entry")
        .isSameAs(replacement);
  }

  private void assertRemovedWithin(final long timeoutMs) throws InterruptedException {
    final long deadline = System.currentTimeMillis() + timeoutMs;
    while (asMap.containsKey(KEY) && System.currentTimeMillis() < deadline) {
      Thread.sleep(10L);
    }
    assertThat(asMap).as("the entry is removed from the cache").doesNotContainKey(KEY);
  }

  private static ResourceInfo cachedResourceInfo() throws Exception {
    final Method resourceMethod = CachedResource.class.getMethod("cached");
    final ResourceInfo resourceInfo = mock(ResourceInfo.class);
    when(resourceInfo.getResourceMethod()).thenReturn(resourceMethod);
    return resourceInfo;
  }

  private static void injectResourceInfo(
      final CachingResponseFilter filter, final ResourceInfo resourceInfo) throws Exception {
    final Field field = CachingResponseFilter.class.getDeclaredField("resourceInfo");
    field.setAccessible(true);
    field.set(filter, resourceInfo);
  }

  private static final class CachedResource {
    @ResponseCachedByFilter(value = TtlMode.FIXED, ttl = 0)
    public Object cached() {
      return null;
    }
  }
}
