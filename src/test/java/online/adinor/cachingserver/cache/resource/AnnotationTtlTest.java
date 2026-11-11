package online.adinor.cachingserver.cache.resource;

import static org.assertj.core.api.Assertions.assertThat;

import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import javax.ws.rs.core.Response;
import org.glassfish.jersey.client.JerseyClient;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * {@code @ResponseCachedByFilter(value = TtlMode.FIXED, ttl = 5_000)} must cache for 5 s. The
 * existing tests sleep 500 ms and 5001 ms, which cannot tell a 5 s TTL from the 1 s default.
 */
public class AnnotationTtlTest {

  @ClassRule
  public static final DropwizardAppRule<MainConfiguration> DROPWIZARD =
      new DropwizardAppRule<MainConfiguration>(
          MainApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  @Test
  public void fixedTtlFromAnnotationIsHonoured() throws Exception {
    final JerseyClient client = new JerseyClientBuilder().build();
    final String path =
        String.format(
            "http://localhost:%d/cached_fixed_local/9?query=ttl", DROPWIZARD.getLocalPort());

    final Response response1 = client.target(path).request().get();
    Thread.sleep(2_000); // longer than the 1 s default, shorter than the declared 5 s
    final Response response2 = client.target(path).request().get();

    assertThat(response1.getStatus()).isEqualTo(200);
    assertThat(response2.getStatus()).isEqualTo(200);
    assertThat(response2.readEntity(String.class))
        .as("still within the declared 5 s TTL")
        .isEqualTo(response1.readEntity(String.class));
  }
}
