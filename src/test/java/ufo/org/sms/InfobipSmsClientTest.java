package ufo.org.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InfobipSmsClientTest {
    private HttpClient http;
    private InfobipSmsClient client;

    @BeforeEach void setup() {
        http = mock(HttpClient.class);
        when(http.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        client = new InfobipSmsClient("https://example.api.infobip.com", "test-key", http);
    }

    @SuppressWarnings("unchecked")
    private void respond(int code, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(code);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    @Test void sendsExactlyOneDestinationAndPreservesText() throws Exception {
        respond(200, """
                {"bulkId":"bulk-1","messages":[{"messageId":"msg-1","destination":"263771234567",
                "status":{"id":26,"name":"PENDING_ACCEPTED"},"details":{"messageCount":1}}],"futureField":true}
                """);
        var result = client.sendSingleSms("Gikko", "263771234567", "Hello \"world\" 🌍");
        assertEquals("msg-1", result.messages().getFirst().messageId());
        var request = capturedRequest();
        assertEquals("POST", request.method());
        assertEquals("/sms/3/messages", request.uri().getPath());
        assertEquals("App test-key", request.headers().firstValue("Authorization").orElseThrow());
        var content = new StringBuilder();
        var done = new CompletableFuture<Void>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { content.append(StandardCharsets.UTF_8.decode(buffer)); }
            public void onError(Throwable t) { done.completeExceptionally(t); }
            public void onComplete() { done.complete(null); }
        });
        done.join();
        var messages = new ObjectMapper().readTree(content.toString()).get("messages");
        assertEquals(1, messages.size());
        assertEquals(1, messages.get(0).get("destinations").size());
        assertEquals("263771234567", messages.get(0).at("/destinations/0/to").asText());
        assertTrue(messages.get(0).at("/content/text").asText().contains("🌍"));
    }

    @Test void looksUpEncodedMessageIdAndParsesReport() throws Exception {
        respond(200, """
                {"results":[{"messageId":"a&b","status":{"id":5,"name":"DELIVERED_TO_HANDSET"},
                "error":{"id":0,"permanent":false},"futureField":1}]}
                """);
        var result = client.getDeliveryReports("a&b");
        assertEquals("DELIVERED_TO_HANDSET", result.results().getFirst().status().name());
        assertEquals("messageId=a%26b", capturedRequest().uri().getRawQuery());
    }

    @Test void emptyReportsDoNotMeanDelivered() throws Exception {
        respond(200, "{\"results\":[]}");
        assertTrue(client.getDeliveryReports("msg-1").results().isEmpty());
    }

    @Test void exposesHttpFailureWithoutRetrying() throws Exception {
        respond(429, "{\"requestError\":{}}");
        var error = assertThrows(InfobipApiException.class,
                () -> client.sendSingleSms("Gikko", "263771234567", "Hello"));
        assertEquals(429, error.statusCode());
        assertEquals("{\"requestError\":{}}", error.responseBody());
        capturedRequest(); // verifies exactly one request
    }

    @Test void rejectsInvalidInputBeforeNetworkCall() {
        assertThrows(IllegalArgumentException.class, () -> client.sendSingleSms("Gikko", "+263771234567", "Hello"));
        assertThrows(IllegalArgumentException.class, () -> client.getDeliveryReports(" "));
        assertThrows(IllegalArgumentException.class, () -> new InfobipSmsClient("http://example.com", "key"));
        verifyNoInteractionsExceptConfiguration();
    }

    private void verifyNoInteractionsExceptConfiguration() {
        verify(http).followRedirects();
        verifyNoMoreInteractions(http);
    }

    @Test void propagatesTransportFailure() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("timeout"));
        assertThrows(IOException.class, () -> client.getDeliveryReports("msg-1"));
        capturedRequest();
    }

    private HttpRequest capturedRequest() throws Exception {
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        return captor.getValue();
    }
}
