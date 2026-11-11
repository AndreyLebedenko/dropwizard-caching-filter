package online.adinor.cachingserver.cache.resource;

import static org.assertj.core.api.Assertions.assertThat;

import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.core.Response;
import online.adinor.cachingserver.cache.storage.HttpResponse;
import online.adinor.cachingserver.cache.storage.StatefulCacheEntry;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * The TTL of an entry says how long the value stays valid, the lease of its producer says how long
 * the producer may work on it. A resource slower than the TTL of its entry must still be called
 * once: the waiting requests wait for that producer instead of taking the entry over one after
 * another.
 */
public class SlowResourceTest {

  @ClassRule
  public static final DropwizardAppRule<MainConfiguration> DROPWIZARD =
      new DropwizardAppRule<MainConfiguration>(
          StampedeApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  @Test
  public void concurrentRequestsHitASlowResourceOnce() throws Exception {
    final int n = 10;
    final String path =
        String.format(
            "http://localhost:%d/slower-than-ttl/1", DROPWIZARD.getLocalPort());

    final CountDownLatch ready = new CountDownLatch(n);
    final CountDownLatch go = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(n);
    final List<String> bodies = new ArrayList<>();
    final ExecutorService pool = Executors.newFixedThreadPool(n);
    for (int i = 0; i < n; i++) {
      pool.submit(
          () -> {
            try {
              ready.countDown();
              go.await();
              final Response response =
                  new JerseyClientBuilder().build().target(path).request().get();
              synchronized (bodies) {
                bodies.add(response.readEntity(String.class));
              }
            } catch (Exception e) {
              e.printStackTrace();
            } finally {
              done.countDown();
            }
          });
    }
    ready.await();
    go.countDown();
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    pool.shutdownNow();

    assertThat(StampedeResource.calls("slower-than-ttl", 1))
        .as("the slow resource must be called once")
        .isEqualTo(1);
    assertThat(bodies).hasSize(n);
    assertThat(bodies).as("all the requests must get the same value").containsOnly(bodies.get(0));

    // Nobody took the entry over, so there was only one producer and no result was thrown away.
    final StatefulCacheEntry<HttpResponse> entry = findEntry("slower-than-ttl/1");
    assertThat(entry.getGeneration()).as("the entry has been produced once").isEqualTo(1);
  }

  private static StatefulCacheEntry<HttpResponse> findEntry(final String path) {
    for (final String key : StampedeApplication.CACHE.asMap().keySet()) {
      if (key.contains(path)) {
        return StampedeApplication.CACHE.getIfPresent(key);
      }
    }
    throw new AssertionError("no cache entry for " + path);
  }
}
