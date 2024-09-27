/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache.resource;

import static org.assertj.core.api.Assertions.assertThat;

import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.ws.rs.core.Response;
import org.glassfish.jersey.client.JerseyClient;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class DummyResourceIntegrationTest {

  @ClassRule
  public static final DropwizardAppRule<MainConfiguration> DROPWIZARD =
      new DropwizardAppRule<MainConfiguration>(
          MainApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  @Before
  public void setUp() throws Exception {}

  @Test
  public void testGetBare() throws Exception {
    JerseyClient client = new JerseyClientBuilder().build();
    Response response =
        client
            .target(String.format("http://localhost:%d/bare", DROPWIZARD.getLocalPort()))
            .request()
            .get();

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void allParallelRequestsProduceIdenticalResponses() throws Exception {
    final int NUMBER_OF_PARALLEL_REQ = 255;
    final String path =
        String.format("http://localhost:%d/cached/1?query=abcd", DROPWIZARD.getLocalPort());

    final AtomicInteger threadsNo = new AtomicInteger(0);

    class ClientRunner implements Runnable {
      private final AtomicReference<Response> response;

      ClientRunner(AtomicReference<Response> response) {
        this.response = response;
      }

      @Override
      public void run() {
        synchronized (threadsNo) {
          JerseyClient client = new JerseyClientBuilder().build();
          threadsNo.incrementAndGet();
          try {
            threadsNo.wait();
            response.set(client.target(path).request().get());
          } catch (InterruptedException ex) {
          }
        }
      }
    }

    List<AtomicReference<Response>> responses = new LinkedList<>();
    for (int i = 0; i < NUMBER_OF_PARALLEL_REQ; i++) {
      AtomicReference<Response> ar = new AtomicReference<>();
      responses.add(ar);
      new Thread(new ClientRunner(ar)).start();
    }

    while (threadsNo.get() < NUMBER_OF_PARALLEL_REQ) {
      Thread.currentThread().sleep(100L);
    }
    JerseyClient client = new JerseyClientBuilder().build();
    Response response1 = client.target(path).request().get();
    synchronized (threadsNo) {
      threadsNo.notifyAll();
    }
    Thread.currentThread().sleep(10_000L);

    String firstEntity = response1.readEntity(String.class);
    assertThat(response1.getStatus()).isEqualTo(200);
    int i = 0;
    for (AtomicReference<Response> ar : responses) {
      System.out.println("Step: " + (++i));
      assertThat(ar.get().readEntity(String.class)).isEqualTo(firstEntity);
    }
  }

  @Test
  public void twoResponseDiffersAfter10seconds() throws Exception {
    final JerseyClient client = new JerseyClientBuilder().build();
    final Response response1 =
        client
            .target(
                String.format("http://localhost:%d/cached/1?query=xyz", DROPWIZARD.getLocalPort()))
            .request()
            .get();

    Thread.sleep(10_001);

    final Response response2 =
        client
            .target(
                String.format("http://localhost:%d/cached/1?query=xyz", DROPWIZARD.getLocalPort()))
            .request()
            .get();

    assertThat(response1.getStatus()).isEqualTo(200);
    assertThat(response2.getStatus()).isEqualTo(200);
    final String entity1 = response1.readEntity(String.class);
    final String entity2 = response2.readEntity(String.class);
    System.out.println("Entity of request 1: " + entity1);
    System.out.println("Entity of request 2: " + entity2);
    assertThat(entity1).isNotEqualToIgnoringCase(entity2);
  }

  @Test
  public void respectsDifferentParams() throws Exception {
    final JerseyClient client = new JerseyClientBuilder().build();
    final Response response1a =
        client
            .target(
                String.format("http://localhost:%d/cached/1?query=abc", DROPWIZARD.getLocalPort()))
            .request()
            .get();
    final Response response1b =
        client
            .target(
                String.format("http://localhost:%d/cached/1?query=abc", DROPWIZARD.getLocalPort()))
            .request()
            .get();

    final Response response2 =
        client
            .target(
                String.format("http://localhost:%d/cached/2?query=xyz", DROPWIZARD.getLocalPort()))
            .request()
            .get();

    assertThat(response1a.getStatus()).isEqualTo(200);
    assertThat(response2.getStatus()).isEqualTo(200);
    String actual1a = response1a.readEntity(String.class);
    String actual1b = response1b.readEntity(String.class);
    String actual2 = response2.readEntity(String.class);
    assertThat(actual1a).contains("i=1");
    assertThat(actual1a).contains("s=abc");
    assertThat(actual2).contains("i=2");
    assertThat(actual2).contains("s=xyz");
    assertThat(actual1a).isEqualTo(actual1b);
  }
}
