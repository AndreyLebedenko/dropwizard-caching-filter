package online.adinor.cachingserver.cache.resource;

import static org.assertj.core.api.Assertions.assertThat;

import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.ClassRule;
import org.junit.Test;

public class ColdKeyStampedeTest {

  @ClassRule
  public static final DropwizardAppRule<MainConfiguration> DROPWIZARD =
      new DropwizardAppRule<MainConfiguration>(
          MainApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  @Test
  public void concurrentRequestsOnColdKeyHitEndpointOnce() throws Exception {
    final int n = 50;
    final String path =
        String.format("http://localhost:%d/cached/7?query=cold", DROPWIZARD.getLocalPort());
    // warm up Jersey/Jetty on a different key so timing is not dominated by first-request cost
    new JerseyClientBuilder().build()
        .target(String.format("http://localhost:%d/cached/8?query=warm", DROPWIZARD.getLocalPort()))
        .request().get().close();
    DAO.CALLS.set(0);

    final CountDownLatch ready = new CountDownLatch(n);
    final CountDownLatch go = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(n);
    final ExecutorService pool = Executors.newFixedThreadPool(n);
    for (int i = 0; i < n; i++) {
      pool.submit(() -> {
        try {
          ready.countDown();
          go.await();
          new JerseyClientBuilder().build().target(path).request().get().readEntity(String.class);
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          done.countDown();
        }
      });
    }
    ready.await();
    go.countDown();
    done.await(30, TimeUnit.SECONDS);
    pool.shutdownNow();
    assertThat(DAO.CALLS.get()).isEqualTo(1);
  }
}
