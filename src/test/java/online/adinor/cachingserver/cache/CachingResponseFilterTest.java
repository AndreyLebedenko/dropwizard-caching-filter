/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.cache.Cache;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
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
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class CachingResponseFilterTest {

  private static final String KEY = "the-key";

  @SuppressWarnings("unchecked")
  private final Cache<String, StatefulCacheEntry<HttpResponse>> cache = mock(Cache.class);

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
    // The entry is invalidated as soon as the response is stored.
    when(requestContext.getProperty(Options.TTL)).thenReturn(0);

    filter = new CachingResponseFilter(cache);
    injectResourceInfo(filter, cachedResourceInfo());
  }

  @Test
  public void theResponseOfTheProducerIsStoredInTheEntry() {
    entry.setPending(Instant.now().plusSeconds(10));
    when(requestContext.getProperty(Options.GENERATION)).thenReturn(entry.getGeneration());

    filter.filter(requestContext, responseContext);

    assertThat(entry.isReady()).isTrue();
    assertThat(entry.getData().getEntity()).isEqualTo("the value");
    verify(cache, timeout(2_000)).invalidate(KEY);
  }

  @Test
  public void theResultOfATakenOverProducerIsIgnored() throws InterruptedException {
    entry.setPending(Instant.now().plusSeconds(10));
    final int staleGeneration = entry.getGeneration();
    // Another producer takes the entry over.
    entry.setPending(Instant.now().plusSeconds(10));
    when(requestContext.getProperty(Options.GENERATION)).thenReturn(staleGeneration);

    filter.filter(requestContext, responseContext);

    assertThat(entry.isPending()).as("the entry belongs to the new producer").isTrue();
    assertThat(entry.getData()).as("the value of the new producer is kept").isNull();
    // Give the invalidation timer the time to fire, if it has been scheduled.
    Thread.sleep(100L);
    verify(cache, never()).invalidate(anyString());
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
