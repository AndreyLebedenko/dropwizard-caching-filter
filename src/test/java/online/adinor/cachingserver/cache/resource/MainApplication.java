package online.adinor.cachingserver.cache.resource;

import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

import java.util.Arrays;

import com.google.common.cache.CacheBuilder;

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
    environment.jersey().register(
        new CachingFilterFeature(
            Arrays.asList("Accept"), // list of HTTP request headers to use for key in addition to the method (e.g. GET) and URL
            CacheBuilder.newBuilder().softValues().build()
        ));

    environment.jersey().register(new DummyResource(new DAO()));
  }

}

