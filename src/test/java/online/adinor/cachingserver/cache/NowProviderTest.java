/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.Test;

/**
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class NowProviderTest {

  @Test
  public void getReturnsTheCurrentTimeByDefault() {
    final NowProvider nowProvider = new NowProvider();

    assertThat(nowProvider.get()).isCloseTo(Instant.now(), within(1, ChronoUnit.SECONDS));
  }

  @Test
  public void setReplacesTheSourceOfTheCurrentTime() {
    final NowProvider nowProvider = new NowProvider();
    final Instant fixed = Instant.parse("2026-01-01T12:00:00Z");

    nowProvider.set(() -> fixed);
    assertThat(nowProvider.get()).isEqualTo(fixed);
  }

  @Test
  public void resetRestoresTheSystemClock() {
    final NowProvider nowProvider = new NowProvider();
    nowProvider.set(() -> Instant.parse("2026-01-01T12:00:00Z"));

    nowProvider.reset();
    assertThat(nowProvider.get()).isCloseTo(Instant.now(), within(1, ChronoUnit.SECONDS));
  }

  @Test
  public void getInstanceReturnsSameInstance() {
    assertThat(NowProvider.getInstance()).isSameAs(NowProvider.getInstance());
  }
}
