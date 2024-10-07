package online.adinor.cachingserver.cache.resource;

import com.google.common.cache.CacheBuilder;
import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import java.util.Arrays;
import online.adinor.cachingserver.cache.CachingFilterFeature;

public class MainApplication extends Application<MainConfiguration> {

  public static void main(final String[] args) throws Exception {
    new MainApplication().run(args);
  }

  @Override
  public String getName() {
    return "Main";
  }

  @Override
  public void initialize(final Bootstrap<MainConfiguration> bootstrap) {
    // TODO: application initialization
  }

  @Override
  public void run(final MainConfiguration configuration, final Environment environment) {
    environment
        .jersey()
        .register(
            new CachingFilterFeature(
                // in addition to the method (e.g. GET) and path, we will use:
                Arrays.asList("Accept"), // optional list of HTTP request headers to use for key
                Arrays.asList("query"), // optional list of query parameters to use for key
                CacheBuilder.newBuilder().maximumSize(10).build()));

    environment.jersey().register(new DummyResource(new DAO()));
  }
}
