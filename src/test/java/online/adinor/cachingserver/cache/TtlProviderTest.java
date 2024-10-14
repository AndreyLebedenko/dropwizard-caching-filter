package online.adinor.cachingserver.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Before;
import org.junit.Test;

public class TtlProviderTest {

  private TtlProvider ttlProvider;

  @Before
  public void setUp() {
    ttlProvider = TtlProvider.getInstance();
    ttlProvider.set(-1);
  }

  @Test
  public void getThrowsExceptionWhenTtlNotSet() {
    assertThatThrownBy(() -> ttlProvider.get()).isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void getInstanceReturnsSameInstance() {
    TtlProvider anotherInstance = TtlProvider.getInstance();
    assertThat(ttlProvider).isSameAs(anotherInstance);
  }

  @Test
  public void getReturnsSetValue() {
    ttlProvider.set(10);
    assertThat(ttlProvider.get()).isEqualTo(10);
  }

  @Test
  public void setUpdatesTtlValue() {
    ttlProvider.set(5);
    assertThat(ttlProvider.get()).isEqualTo(5);
    ttlProvider.set(15);
    assertThat(ttlProvider.get()).isEqualTo(15);
  }
}
