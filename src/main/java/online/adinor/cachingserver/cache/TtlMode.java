package online.adinor.cachingserver.cache;

import java.util.function.Supplier;

public enum TtlMode {
  DYNAMIC(TtlProvider.getInstance()),
  FIXED(TtlProvider.getDefaultTtlProvider());

  private final Supplier<Integer> ttlSupplier;

  TtlMode(Supplier<Integer> ttl) {
    this.ttlSupplier = ttl;
  }

  public int getTtl() {
    return ttlSupplier.get();
  }
}
