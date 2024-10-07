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

    statefulCacheEntry.setPending();
    assertThat(statefulCacheEntry.isNew()).isFalse();
    assertThat(statefulCacheEntry.isPending()).isTrue();
    assertThat(statefulCacheEntry.isReady()).isFalse();

    statefulCacheEntry.setReady();
    assertThat(statefulCacheEntry.isNew()).isFalse();
    assertThat(statefulCacheEntry.isPending()).isFalse();
    assertThat(statefulCacheEntry.isReady()).isTrue();
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

