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
import java.util.concurrent.atomic.AtomicLong;
import javax.ws.rs.core.Response;
import online.adinor.cachingserver.cache.storage.HttpResponse;
import online.adinor.cachingserver.cache.storage.StatefulCacheEntry;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * Failure paths of request coalescing. The contract these tests pin down:
 *
 * <ol>
 *   <li>a failed response is never served from the cache;
 *   <li>a producer always resolves its entry, so waiters never sit out the TTL;
 *   <li>an entry left pending by a producer that never came back does not block later requests.
 * </ol>
 *
 * What a waiter receives when its producer fails (the producer's error, or its own trip to the
 * endpoint) is a design choice these tests leave open.
 */
public class StampedeFailurePathTest {

  /**
   * Upper bound for any single request in these tests. Far below the 10 s TTL, far above the
   * 300 ms endpoint. It also caps how long a waiter may wait for a producer.
   */
  private static final long MAX_REQUEST_MS = 2_000;

  @ClassRule
  public static final DropwizardAppRule<MainConfiguration> DROPWIZARD =
      new DropwizardAppRule<MainConfiguration>(
          StampedeApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final class Result {
    final int status;
    final String body;
    final long elapsedMs;

    Result(final int status, final String body, final long elapsedMs) {
      this.status = status;
      this.body = body;
      this.elapsedMs = elapsedMs;
    }
  }

  private static Result get(final String path) {
    final long started = System.nanoTime();
    final Response response =
        new JerseyClientBuilder()
            .build()
            .target(String.format("http://localhost:%d%s", DROPWIZARD.getLocalPort(), path))
            .request()
            .get();
    final String body = response.readEntity(String.class);
    final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    return new Result(response.getStatus(), body, elapsedMs);
  }

  @Test
  public void errorStatusIsNotCached() {
    final Result first = get("/flaky-status/1");
    final Result second = get("/flaky-status/1");

    assertThat(first.status).isEqualTo(500);
    assertThat(second.status).as("the 500 must not be replayed from the cache").isEqualTo(200);
    assertThat(StampedeResource.calls("flaky-status", 1)).isEqualTo(2);
    assertThat(second.elapsedMs).isLessThan(MAX_REQUEST_MS);
  }

  @Test
  public void unhandledExceptionIsNotCachedAndLeavesNoStuckEntry() {
    final Result first = get("/flaky-throw/2");
    final Result second = get("/flaky-throw/2");

    assertThat(first.status).isEqualTo(500);
    assertThat(second.status).as("the failure must not be replayed from the cache").isEqualTo(200);
    assertThat(StampedeResource.calls("flaky-throw", 2)).isEqualTo(2);
    assertThat(second.elapsedMs)
        .as("the request after a failed producer must not wait on its entry")
        .isLessThan(MAX_REQUEST_MS);
  }

  @Test
  public void waitersAreReleasedWhenProducerFails() throws Exception {
    final int n = 20;
    final CountDownLatch ready = new CountDownLatch(n);
    final CountDownLatch go = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(n);
    final AtomicLong slowest = new AtomicLong();
    final ExecutorService pool = Executors.newFixedThreadPool(n);
    for (int i = 0; i < n; i++) {
      pool.submit(
          () -> {
            try {
              ready.countDown();
              go.await();
              final Result r = get("/slow-flaky/3");
              slowest.accumulateAndGet(r.elapsedMs, Math::max);
            } catch (Exception e) {
              e.printStackTrace();
            } finally {
              done.countDown();
            }
          });
    }
    ready.await();
    go.countDown();
    final boolean allFinished = done.await(StampedeResource.TTL_MS - 1_000, TimeUnit.MILLISECONDS);
    pool.shutdownNow();

    assertThat(allFinished).as("no request may sit out the TTL").isTrue();
    // A waiter may wait for the failed producer and then make its own slow call: two endpoint
    // durations plus headroom, nowhere near the TTL.
    assertThat(slowest.get()).isLessThan(MAX_REQUEST_MS + 2L * StampedeResource.SLOW_MS);

    final Result afterwards = get("/slow-flaky/3");
    assertThat(afterwards.status).as("the key must recover after a failed producer").isEqualTo(200);
    assertThat(afterwards.elapsedMs).isLessThan(MAX_REQUEST_MS);
  }

  @Test
  public void orphanedPendingEntryDoesNotBlockForTtl() {
    // Learn the key the filter builds for this path, then replace its entry with one that a
    // vanished producer left pending.
    assertThat(get("/plain/4").status).isEqualTo(200);
    final List<String> keys = new ArrayList<>();
    for (final String k : StampedeApplication.CACHE.asMap().keySet()) {
      if (k.contains("plain/4")) {
        keys.add(k);
      }
    }
    assertThat(keys).hasSize(1);
    final String key = keys.get(0);
    StampedeApplication.CACHE.put(key, new StatefulCacheEntry<HttpResponse>().setPending());

    final Result blocked = get("/plain/4");
    assertThat(blocked.status).isEqualTo(200);
    assertThat(blocked.elapsedMs)
        .as("a request must not wait the TTL for a producer that never returns")
        .isLessThan(MAX_REQUEST_MS);

    final Result next = get("/plain/4");
    assertThat(next.status).isEqualTo(200);
    assertThat(next.elapsedMs)
        .as("the orphaned entry must be taken over or dropped, not left in place")
        .isLessThan(MAX_REQUEST_MS);
  }
}
