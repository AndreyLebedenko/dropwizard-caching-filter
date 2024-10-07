package online.adinor.cachingserver.cache;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

// ToDo Make it implementing SettableSupplier<Integer> and add a `set` method?
public class TtlProvider implements Supplier<Integer> {

  public static final int DEFAULT_TTL = 1_000;

  private static final TtlProvider INSTANCE = new TtlProvider();
  private final AtomicInteger ttl = new AtomicInteger(-1);

  private TtlProvider() {}

  public static TtlProvider getInstance() {
    return INSTANCE;
  }

  public static Supplier<Integer> getDefaultTtlProvider() {
    return () -> DEFAULT_TTL;
  }

  @Override
  public Integer get() {
    int value = ttl.get();
    if (value == -1) {
      throw new IllegalStateException("TTL is not set");
    }
    return value;
  }

  public void set(int newValue) {
    this.ttl.set(newValue);
  }
}
