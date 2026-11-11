package online.adinor.cachingserver.cache.resource;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.dropwizard.Application;
import io.dropwizard.setup.Environment;
import java.time.Duration;
import java.util.Collections;
import online.adinor.cachingserver.cache.CachingFilterFeature;
import online.adinor.cachingserver.cache.storage.HttpResponse;
import online.adinor.cachingserver.cache.storage.StatefulCacheEntry;

/**
 * Test application for the failure-path tests. The cache is exposed so a test can plant an entry
 * in a chosen state. No headers or query parameters take part in the key.
 */
public class StampedeApplication extends Application<MainConfiguration> {

  public static final Cache<String, StatefulCacheEntry<HttpResponse>> CACHE =
      CacheBuilder.newBuilder().maximumSize(100).build();

  @Override
  public void run(final MainConfiguration configuration, final Environment environment) {
    environment
        .jersey()
        .register(
            new CachingFilterFeature(
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                CACHE,
                // The lease of a producer is independent of the TTL of the value.
                Duration.ofSeconds(5)));
    environment.jersey().register(new StampedeResource());
  }
}
