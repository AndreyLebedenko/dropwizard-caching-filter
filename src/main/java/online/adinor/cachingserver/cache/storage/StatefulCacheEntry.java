/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache.storage;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class StatefulCacheEntry<T> {

  private AtomicInteger state = new AtomicInteger(0); // 0 - new, 1 - pending (not ready), 2 - ready
  private final AtomicReference<T> ar = new AtomicReference<>();

  public StatefulCacheEntry() {
  }

  public StatefulCacheEntry(final T data) {
    ar.set(data);
  }

  public StatefulCacheEntry<T> setData(final T data) {
    ar.set(data);
    return this;
  }

  public T getData() {
    return ar.get();
  }

  public boolean isNew() {
    return state.get() == 0;
  }

  public boolean isPending() {
    return state.get() == 1;
  }

  public StatefulCacheEntry<T> setPending() {
    state.set(1);
    return this;
  }

  public boolean isReady() {
    return state.get() == 2;
  }

  public StatefulCacheEntry<T> setReady() {
    state.set(2);
    return this;
  }

  public void unblock() {
    synchronized (this) {
      notifyAll();
    }
  }

}

