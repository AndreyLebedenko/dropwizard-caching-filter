/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache.resource;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import java.util.function.Supplier;

import online.adinor.cachingserver.cache.ResponseCachedByFilter;

/**
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
@Path("/")
public class DummyResource {

  private final Supplier<Object> dao;

  public DummyResource(final Supplier<Object> dao) {
    this.dao = dao;
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/bare")
  public Object getBare() {
    return dao.get();
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/cached")
  @ResponseCachedByFilter(10000)
  public Object getCached() {
    return dao.get();
  }

}

