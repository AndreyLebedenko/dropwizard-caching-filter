/*
 * The author disclaims copyright to this source code. In place of
 * a legal notice, here is a blessing:
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package online.adinor.cachingserver.cache.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 *
 * @author Andrey Lebedenko (andrey.lebedenko@gmail.com)
 */
public class HttpResponseTest {

  @Test
  public void testFrom() {
    final int expectedHttpCode = ThreadLocalRandom.current().nextInt(100, 600);
    final MultivaluedMap<String, String> expectedHeaders = new MultivaluedHashMap<>();
    expectedHeaders.add("Header1", "H1Value1");
    expectedHeaders.add("Header1", "H1Value2");
    expectedHeaders.add("Header2", "H2Value1");
    final int expectedLength = ThreadLocalRandom.current().nextInt(1, 1000);
    final String expectedEntity = "Expected entity";

    final Response.ResponseBuilder builder = Response
        .status(expectedHttpCode).entity(expectedEntity);
    for (Map.Entry<String, List<String>> header : expectedHeaders.entrySet()) {
      for (String value : header.getValue()) {
        builder.header(header.getKey(), value);
      }
    }
    final Response expectedResponse = builder.build();

    final ContainerResponseContext mockContainerResponseContext = mock(ContainerResponseContext.class);
    when(mockContainerResponseContext.getStatus()).thenReturn(expectedHttpCode);
    when(mockContainerResponseContext.getStringHeaders()).thenReturn(expectedHeaders);
    when(mockContainerResponseContext.getLength()).thenReturn(expectedLength);
    when(mockContainerResponseContext.getEntity()).thenReturn(expectedEntity);

    final HttpResponse httpResponse = HttpResponse.from(mockContainerResponseContext);

    assertThat(httpResponse.getStatusCode()).isEqualTo(expectedHttpCode);
    assertThat(httpResponse.getHeaders()).containsAllEntriesOf(expectedHeaders);
    assertThat(expectedHeaders).containsAllEntriesOf(httpResponse.getHeaders());
    assertThat(httpResponse.getLength()).isEqualTo(expectedLength);
    assertThat(httpResponse.getEntity()).isEqualTo(expectedEntity);

    final Response actualResponse = httpResponse.asResponse();
    assertThat(actualResponse).isEqualToComparingFieldByFieldRecursively(expectedResponse);
  }

}

