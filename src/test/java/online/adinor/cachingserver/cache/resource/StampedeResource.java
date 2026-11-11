package online.adinor.cachingserver.cache.resource;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import online.adinor.cachingserver.cache.ResponseCachedByFilter;
import online.adinor.cachingserver.cache.TtlMode;
import online.adinor.cachingserver.cache.TtlProvider;

/**
 * Endpoints with controllable behaviour. Every endpoint counts its invocations per id, so a test
 * can tell a cache hit from a real call. TTL is deliberately long: a request that waits for the
 * TTL is clearly visible in timings.
 */
@Path("/")
@Produces(MediaType.TEXT_PLAIN)
public class StampedeResource {

  public static final int TTL_MS = 10_000;
  public static final int SLOW_MS = 300;

  private static final ConcurrentHashMap<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();

  public StampedeResource() {
    TtlProvider.getInstance().set(TTL_MS);
  }

  public static int calls(final String endpoint, final int id) {
    final AtomicInteger counter = CALLS.get(endpoint + "/" + id);
    return counter == null ? 0 : counter.get();
  }

  private static int countCall(final String endpoint, final int id) {
    return CALLS.computeIfAbsent(endpoint + "/" + id, k -> new AtomicInteger()).incrementAndGet();
  }

  /** Always succeeds. */
  @GET
  @Path("/plain/{id}")
  @ResponseCachedByFilter(TtlMode.DYNAMIC)
  public Response plain(@PathParam("id") final int id) {
    final int n = countCall("plain", id);
    return Response.ok("plain-" + id + "-call-" + n).build();
  }

  /** First call for an id answers 500, every later call answers 200. */
  @GET
  @Path("/flaky-status/{id}")
  @ResponseCachedByFilter(TtlMode.DYNAMIC)
  public Response flakyStatus(@PathParam("id") final int id) {
    final int n = countCall("flaky-status", id);
    if (n == 1) {
      return Response.status(500).entity("boom").build();
    }
    return Response.ok("flaky-status-" + id + "-call-" + n).build();
  }

  /** First call for an id throws, every later call answers 200. */
  @GET
  @Path("/flaky-throw/{id}")
  @ResponseCachedByFilter(TtlMode.DYNAMIC)
  public Response flakyThrow(@PathParam("id") final int id) {
    final int n = countCall("flaky-throw", id);
    if (n == 1) {
      throw new IllegalStateException("boom");
    }
    return Response.ok("flaky-throw-" + id + "-call-" + n).build();
  }

  /** Slow. First call for an id answers 500, every later call answers 200. */
  @GET
  @Path("/slow-flaky/{id}")
  @ResponseCachedByFilter(TtlMode.DYNAMIC)
  public Response slowFlaky(@PathParam("id") final int id) throws InterruptedException {
    final int n = countCall("slow-flaky", id);
    Thread.sleep(SLOW_MS);
    if (n == 1) {
      return Response.status(500).entity("boom").build();
    }
    return Response.ok("slow-flaky-" + id + "-call-" + n).build();
  }
}
