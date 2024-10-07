/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache.storage;

import java.util.List;
import java.util.Map.Entry;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

/**
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class HttpResponse {

  public static HttpResponse from(final ContainerResponseContext responseContext) {
    return new HttpResponse(
        responseContext.getStatus(),
        responseContext.getStringHeaders(),
        responseContext.getLength(),
        responseContext.getEntity()
    );
  }

  /**
   * @return the statusCode
   */
  public int getStatusCode() {
    return statusCode;
  }

  /**
   * @return the headers
   */
  public MultivaluedMap<String, String> getHeaders() {
    return headers;
  }

  /**
   * @return the length
   */
  public int getLength() {
    return length;
  }

  /**
   * @return the entity
   */
  public Object getEntity() {
    return entity;
  }

  private final int statusCode;
  private final MultivaluedMap<String, String> headers;
  private final int length;
  private final Object entity;

  public HttpResponse(
      final int statusCode,
      final MultivaluedMap<String, String> headers,
      final int length,
      final Object entity) {
    this.statusCode = statusCode;
    this.headers = headers;
    this.length = length;
    this.entity = entity;
  }

  public Response asResponse() {
    final Response.ResponseBuilder responseBuilder = Response.status(getStatusCode()).entity(getEntity());
    for (Entry<String, List<String>> e : getHeaders().entrySet()) {
      for (String v : e.getValue()) {
        responseBuilder.header(e.getKey(), v);
      }
    }
    return responseBuilder.build();
  }
}

