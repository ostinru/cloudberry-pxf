package org.apache.cloudberry.pxf.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import org.apache.cloudberry.pxf.api.model.RequestContext;
import org.apache.cloudberry.pxf.service.controller.ReadService;
import org.apache.cloudberry.pxf.service.controller.WriteService;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.stubbing.Answer;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = PxfServiceApplication.class)
@AutoConfigureObservability
public class PxfMetricsIT {

    @LocalServerPort
    private int port;

    @MockBean
    private RequestParser<MultiValueMap<String, String>> mockParser;

    @MockBean
    private ReadService readService;

    @MockBean
    private WriteService mockWriteService;

    @Mock
    private RequestContext mockContext;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void test_HttpServerRequests_Metric() throws Exception {
        mockServices();
        // call PXF read API
        String readResponse = send(request("/pxf/read")
                .header("X-GP-ENCODED-HEADER-VALUES", "true")
                .header("X-GP-USER", "reader")
                .header("X-GP-SEGMENT-ID", "77")
                .header("X-GP-OPTIONS-PROFILE", "profile%3Atest")
                .header("X-GP-OPTIONS-SERVER", "speedy"));
        assertEquals("Hello from read!", readResponse);

        // assert metric got reported with proper tags
        JsonNode readMetrics = mapper.readTree(send(request("/actuator/metrics/http.server.requests?tag=uri:/pxf/read")));
        assertEquals(1.0, measurementValue(readMetrics, "COUNT"));
        assertTrue(measurementValue(readMetrics, "TOTAL_TIME") >= 0.5);
        assertTag(readMetrics, "application", "pxf-service");
        assertTag(readMetrics, "user", "reader");
        assertTag(readMetrics, "segment", "77");
        assertTag(readMetrics, "profile", "profile:test");
        assertTag(readMetrics, "server", "speedy");

        // call PXF write API
        String writeResponse = send(request("/pxf/write")
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("X-GP-USER", "writer")
                .header("X-GP-SEGMENT-ID", "77")
                .header("X-GP-OPTIONS-PROFILE", "profile:test")
                .header("X-GP-OPTIONS-SERVER", "speedy"));
        assertEquals("Hello from write!", writeResponse);

        // assert metric got reported with proper tags
        JsonNode writeMetrics = mapper.readTree(send(request("/actuator/metrics/http.server.requests?tag=uri:/pxf/write")));
        assertEquals(1.0, measurementValue(writeMetrics, "COUNT"));
        assertTag(writeMetrics, "application", "pxf-service");
        assertTag(writeMetrics, "user", "writer");
        assertTag(writeMetrics, "segment", "77");
        assertTag(writeMetrics, "profile", "profile:test");
        assertTag(writeMetrics, "server", "speedy");

        // assert metric for segment access is aggregate
        JsonNode segmentMetrics = mapper.readTree(send(request("/actuator/metrics/http.server.requests?tag=segment:77")));
        assertEquals(2.0, measurementValue(segmentMetrics, "COUNT"));
        assertTag(segmentMetrics, "application", "pxf-service");
        assertTag(segmentMetrics, "user", "reader", "writer");
        assertTag(segmentMetrics, "profile", "profile:test");
        assertTag(segmentMetrics, "server", "speedy");

        // hit the actuator health endpoint
        JsonNode health = mapper.readTree(send(request("/actuator/health")));
        assertEquals("UP", health.path("status").asText());
        String[] healthGroups = mapper.treeToValue(health.path("groups"), String[].class);
        Arrays.sort(healthGroups);
        assertArrayEquals(new String[]{"liveness", "readiness"}, healthGroups);

        // assert prometheus endpoint reflects the metric as well
        String prometheusResponse = send(request("/actuator/prometheus"));
        assertNotNull(prometheusResponse);
        assertTrue(prometheusResponse.contains("http_server_requests_seconds_count{application=\"pxf-service\",error=\"none\",exception=\"none\",method=\"GET\",outcome=\"SUCCESS\",profile=\"profile:test\",segment=\"77\",server=\"speedy\",status=\"200\",uri=\"/pxf/read\",user=\"reader\"} 1\n"));
        assertTrue(prometheusResponse.contains("http_server_requests_seconds_count{application=\"pxf-service\",error=\"none\",exception=\"none\",method=\"POST\",outcome=\"SUCCESS\",profile=\"profile:test\",segment=\"77\",server=\"speedy\",status=\"200\",uri=\"/pxf/write\",user=\"writer\"} 1\n"));
        assertTrue(prometheusResponse.contains("http_server_requests_seconds_count{application=\"pxf-service\",error=\"none\",exception=\"none\",method=\"GET\",outcome=\"SUCCESS\",profile=\"unknown\",segment=\"unknown\",server=\"unknown\",status=\"200\",uri=\"/actuator/health\",user=\"unknown\"} 1\n"));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5));
    }

    private String send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private double measurementValue(JsonNode metrics, String statistic) {
        for (JsonNode measurement : metrics.path("measurements")) {
            if (statistic.equals(measurement.path("statistic").asText())) {
                JsonNode value = measurement.path("value");
                assertTrue(value.isNumber(), "Expected a numeric measurement: " + statistic);
                return value.doubleValue();
            }
        }
        return fail("Missing measurement: " + statistic);
    }

    private void assertTag(JsonNode metrics, String name, String... values) {
        for (JsonNode tag : metrics.path("availableTags")) {
            if (name.equals(tag.path("tag").asText())) {
                assertEquals(mapper.valueToTree(values), tag.path("values"), name);
                return;
            }
        }
        fail("Missing metric tag: " + name);
    }

    private void mockServices() throws Exception {
        // mock ReadService
        when(mockParser.parseRequest(any(), eq(RequestContext.RequestType.READ_BRIDGE))).thenReturn(mockContext);
        Answer<Void> readAnswer = invocation -> {
            // sleep to simulate time it takes to execute, check that reported metric takes into account async time
            Thread.sleep(500);
            invocation.getArgument(1, OutputStream.class).write("Hello from read!".getBytes(Charsets.UTF_8));
            return null;
        };
        doAnswer(readAnswer).when(readService).readData(any(), any());

        // mock WriteService
        when(mockParser.parseRequest(any(), eq(RequestContext.RequestType.WRITE_BRIDGE))).thenReturn(mockContext);
        when(mockWriteService.writeData(same(mockContext), any())).thenReturn("Hello from write!");
    }

}
