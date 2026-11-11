/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class StatefulCacheEntryTest {

  private static final String EXPECTED_DATA = "Expected string data";

  @Test
  public void testConstructorWithData() {
    final StatefulCacheEntry<String> statefulCacheEntry = new StatefulCacheEntry(EXPECTED_DATA);
    assertThat(statefulCacheEntry.getData()).isEqualTo(EXPECTED_DATA);
  }

  @Test
  public void testSetData() {
    final StatefulCacheEntry<String> statefulCacheEntry =
        new StatefulCacheEntry().setData(EXPECTED_DATA);
    assertThat(statefulCacheEntry.getData()).isEqualTo(EXPECTED_DATA);
  }

  @Test
  public void testStateTransition() {
    final StatefulCacheEntry<String> statefulCacheEntry = new StatefulCacheEntry();
    assertThat(statefulCacheEntry.isNew()).isTrue();
    assertThat(statefulCacheEntry.isPending()).isFalse();
    assertThat(statefulCacheEntry.isReady()).isFalse();

    statefulCacheEntry.setPending(null);
    assertThat(statefulCacheEntry.isNew()).isFalse();
    assertThat(statefulCacheEntry.isPending()).isTrue();
    assertThat(statefulCacheEntry.isReady()).isFalse();

  }

  @Test
  public void testUnblock() throws Exception {
    final StatefulCacheEntry<String> statefulCacheEntry = new StatefulCacheEntry();
    final Watchman watchman = new Watchman(statefulCacheEntry);
    watchman.start();
    Thread.sleep(100L);
    statefulCacheEntry.unblock();
    Thread.sleep(100L);
    assertThat(watchman.isOk.get()).isTrue();
  }

  @Test
  public void testResolveWithDataMakesEntryReady() {
    final StatefulCacheEntry<String> statefulCacheEntry = new StatefulCacheEntry();
    statefulCacheEntry.setPending(Instant.now().plusSeconds(10));

    assertThat(statefulCacheEntry.resolve(statefulCacheEntry.getGeneration(), EXPECTED_DATA))
        .isTrue();
    assertThat(statefulCacheEntry.isReady()).isTrue();
    assertThat(statefulCacheEntry.isFailed()).isFalse();
    assertThat(statefulCacheEntry.getData()).isEqualTo(EXPECTED_DATA);
  }

  @Test
  public void testResolveWithoutDataMakesEntryFailed() {
    final StatefulCacheEntry<String> statefulCacheEntry = new StatefulCacheEntry();
    statefulCacheEntry.setPending(Instant.now().plusSeconds(10));

    assertThat(statefulCacheEntry.resolve(statefulCacheEntry.getGeneration(), null)).isTrue();
    assertThat(statefulCacheEntry.isFailed()).isTrue();
    assertThat(statefulCacheEntry.isReady()).isFalse();
    assertThat(statefulCacheEntry.getData()).isNull();
  }

  @Test
  public void testResultOfTakenOverProducerIsIgnored() {
    final StatefulCacheEntry<String> statefulCacheEntry = new StatefulCacheEntry();
    statefulCacheEntry.setPending(Instant.now().plusSeconds(10));
    final int staleGeneration = statefulCacheEntry.getGeneration();

    // Another producer takes the entry over.
    statefulCacheEntry.setPending(Instant.now().plusSeconds(10));
    final int currentGeneration = statefulCacheEntry.getGeneration();
    assertThat(currentGeneration).isNotEqualTo(staleGeneration);

    assertThat(statefulCacheEntry.resolve(staleGeneration, "stale")).isFalse();
    assertThat(statefulCacheEntry.isPending()).isTrue();
    assertThat(statefulCacheEntry.getData()).isNull();

    assertThat(statefulCacheEntry.resolve(currentGeneration, EXPECTED_DATA)).isTrue();
    assertThat(statefulCacheEntry.isReady()).isTrue();
    assertThat(statefulCacheEntry.getData()).isEqualTo(EXPECTED_DATA);
  }

  @Test
  public void testEntryWithoutProducerIsNotLive() {
    final StatefulCacheEntry<String> statefulCacheEntry = new StatefulCacheEntry();
    statefulCacheEntry.setPending(null);

    assertThat(statefulCacheEntry.isPending()).isTrue();
    assertThat(statefulCacheEntry.hasLiveProducer(Instant.now())).isFalse();
    assertThat(statefulCacheEntry.getProducerDeadline()).isNull();
  }

  @Test
  public void testRemainingLeaseOfAnEntryWithoutProducerIsZero() {
    final StatefulCacheEntry<String> statefulCacheEntry = new StatefulCacheEntry();
    statefulCacheEntry.setPending(null);

    assertThat(statefulCacheEntry.getRemainingLease(Instant.now())).isEqualTo(Duration.ZERO);
  }

  @Test
  public void testProducerIsLiveUntilItsDeadline() {
    final StatefulCacheEntry<String> statefulCacheEntry = new StatefulCacheEntry();
    final Instant now = Instant.now();
    statefulCacheEntry.setPending(now.plusSeconds(10));
    assertThat(statefulCacheEntry.hasLiveProducer(now)).isTrue();
    assertThat(statefulCacheEntry.hasLiveProducer(now.plusSeconds(11))).isFalse();
  }

  @Test
  public void testRemainingLeaseNeverGoesNegative() {
    final StatefulCacheEntry<String> statefulCacheEntry = new StatefulCacheEntry();
    final Instant now = Instant.now();
    statefulCacheEntry.setPending(now.plusSeconds(10));

    assertThat(statefulCacheEntry.getRemainingLease(now)).isEqualTo(Duration.ofSeconds(10));
    assertThat(statefulCacheEntry.getRemainingLease(now.plusSeconds(20))).isEqualTo(Duration.ZERO);
  }

  @Test
  public void testAwaitResolutionReturnsWhenProducerResolves() throws Exception {
    final StatefulCacheEntry<String> statefulCacheEntry = new StatefulCacheEntry();
    statefulCacheEntry.setPending(Instant.now().plusSeconds(10));

    final Thread producer =
        new Thread(
            () -> {
              try {
                Thread.sleep(100L);
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              }
              statefulCacheEntry.resolve(statefulCacheEntry.getGeneration(), EXPECTED_DATA);
            });
    producer.start();

    assertThat(statefulCacheEntry.awaitResolution(Duration.ofSeconds(5))).isTrue();
    assertThat(statefulCacheEntry.getData()).isEqualTo(EXPECTED_DATA);
    producer.join();
  }

  @Test
  public void testAwaitResolutionTimesOutWhileProducerIsSilent() throws Exception {
    final StatefulCacheEntry<String> statefulCacheEntry = new StatefulCacheEntry();
    statefulCacheEntry.setPending(Instant.now().plusSeconds(10));

    assertThat(statefulCacheEntry.awaitResolution(Duration.ofMillis(100))).isFalse();
    assertThat(statefulCacheEntry.isPending()).isTrue();
  }

  private static class Watchman extends Thread {
    private final StatefulCacheEntry<String> statefulCacheEntry;
    private AtomicBoolean isOk = new AtomicBoolean(false);

    private Watchman(StatefulCacheEntry<String> statefulCacheEntry) {
      this.statefulCacheEntry = statefulCacheEntry;
    }

    @Override
    public void run() {
      synchronized (this.statefulCacheEntry) {
        try {
          statefulCacheEntry.wait(1000L);
          isOk.set(true);
        } catch (InterruptedException ex) {
          throw new RuntimeException(ex);
        }
      }
    }
  }
}

