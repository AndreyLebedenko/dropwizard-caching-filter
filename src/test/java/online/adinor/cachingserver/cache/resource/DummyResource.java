/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache.resource;

import java.util.function.BiFunction;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import java.util.function.Supplier;

import online.adinor.cachingserver.cache.ResponseCachedByFilter;
import online.adinor.cachingserver.cache.TtlMode;
import online.adinor.cachingserver.cache.TtlProvider;

/**
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
@Path("/")
public class DummyResource {

  private final BiFunction<Integer, String, Object> dao;

  public DummyResource(final BiFunction<Integer, String, Object> dao) {
    this.dao = dao;
    TtlProvider.getInstance().set(10_000);
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/bare")
  public Object getBare() {
    return dao.apply(0, "default");
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/cached/{id}")
  @ResponseCachedByFilter(TtlMode.DYNAMIC)
  public Object getCached(@PathParam("id") int id, @QueryParam("query") String query) {
    return dao.apply(id, query);
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/cached_default/{id}")
  @ResponseCachedByFilter(TtlMode.FIXED)
  public Object getCachedFixed(@PathParam("id") int id, @QueryParam("query") String query) {
    return dao.apply(id, query);
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/cached_fixed_local/{id}")
  @ResponseCachedByFilter(value = TtlMode.FIXED, ttl = 5_000)
  public Object getCachedFixedDefined(@PathParam("id") int id, @QueryParam("query") String query) {
    return dao.apply(id, query);
  }
}
