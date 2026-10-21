package org.apache.cloudberry.pxf.service;

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
import org.springframework.boot.test.json.JsonContentAssert;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        JsonContentAssert readMetrics = new JsonContentAssert(PxfMetricsIT.class,
                send(request("/actuator/metrics/http.server.requests?tag=uri:/pxf/read")));
        readMetrics.extractingJsonPathArrayValue("$.measurements[?(@.statistic == 'COUNT')].value").containsExactly(1.0);
        readMetrics.extractingJsonPathArrayValue("$.measurements[?(@.statistic == 'TOTAL_TIME')].value")
                .singleElement().isInstanceOfSatisfying(Number.class, value -> assertTrue(value.doubleValue() >= 0.5));
        readMetrics.extractingJsonPathArrayValue("$.availableTags[?(@.tag == 'application')].values[*]").containsExactly("pxf-service");
        readMetrics.extractingJsonPathArrayValue("$.availableTags[?(@.tag == 'user')].values[*]").containsExactly("reader");
        readMetrics.extractingJsonPathArrayValue("$.availableTags[?(@.tag == 'segment')].values[*]").containsExactly("77");
        readMetrics.extractingJsonPathArrayValue("$.availableTags[?(@.tag == 'profile')].values[*]").containsExactly("profile:test");
        readMetrics.extractingJsonPathArrayValue("$.availableTags[?(@.tag == 'server')].values[*]").containsExactly("speedy");

        // call PXF write API
        String writeResponse = send(request("/pxf/write")
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("X-GP-USER", "writer")
                .header("X-GP-SEGMENT-ID", "77")
                .header("X-GP-OPTIONS-PROFILE", "profile:test")
                .header("X-GP-OPTIONS-SERVER", "speedy"));
        assertEquals("Hello from write!", writeResponse);

        // assert metric got reported with proper tags
        JsonContentAssert writeMetrics = new JsonContentAssert(PxfMetricsIT.class,
                send(request("/actuator/metrics/http.server.requests?tag=uri:/pxf/write")));
        writeMetrics.extractingJsonPathArrayValue("$.measurements[?(@.statistic == 'COUNT')].value").containsExactly(1.0);
        writeMetrics.extractingJsonPathArrayValue("$.availableTags[?(@.tag == 'application')].values[*]").containsExactly("pxf-service");
        writeMetrics.extractingJsonPathArrayValue("$.availableTags[?(@.tag == 'user')].values[*]").containsExactly("writer");
        writeMetrics.extractingJsonPathArrayValue("$.availableTags[?(@.tag == 'segment')].values[*]").containsExactly("77");
        writeMetrics.extractingJsonPathArrayValue("$.availableTags[?(@.tag == 'profile')].values[*]").containsExactly("profile:test");
        writeMetrics.extractingJsonPathArrayValue("$.availableTags[?(@.tag == 'server')].values[*]").containsExactly("speedy");

        // assert metric for segment access is aggregate
        JsonContentAssert segmentMetrics = new JsonContentAssert(PxfMetricsIT.class,
                send(request("/actuator/metrics/http.server.requests?tag=segment:77")));
        segmentMetrics.extractingJsonPathArrayValue("$.measurements[?(@.statistic == 'COUNT')].value").containsExactly(2.0);
        segmentMetrics.extractingJsonPathArrayValue("$.availableTags[?(@.tag == 'application')].values[*]").containsExactly("pxf-service");
        segmentMetrics.extractingJsonPathArrayValue("$.availableTags[?(@.tag == 'user')].values[*]").containsExactly("reader", "writer");
        segmentMetrics.extractingJsonPathArrayValue("$.availableTags[?(@.tag == 'profile')].values[*]").containsExactly("profile:test");
        segmentMetrics.extractingJsonPathArrayValue("$.availableTags[?(@.tag == 'server')].values[*]").containsExactly("speedy");

        // hit the actuator health endpoint
        new JsonContentAssert(PxfMetricsIT.class, send(request("/actuator/health")))
                .isEqualToJson("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}");

        // assert prometheus endpoint reflects the metric as well
        String prometheusResponse = send(request("/actuator/prometheus"));
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
