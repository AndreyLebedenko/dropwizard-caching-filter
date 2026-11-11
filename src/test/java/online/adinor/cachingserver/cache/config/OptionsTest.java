package online.adinor.cachingserver.cache.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class OptionsTest {

  @Test
  public void optionsCanBeInstantiated() {
    assertThat(new Options()).isNotNull();
  }
}
