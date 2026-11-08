package ufo.org.sms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Synchronous Infobip SMS v3 client. Requests are never automatically retried. */
public final class InfobipSmsClient {
    private final URI baseUrl;
    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public InfobipSmsClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
    }

    public InfobipSmsClient(String baseUrl, String apiKey, HttpClient http) {
        this.baseUrl = URI.create(required(baseUrl, "baseUrl"));
        if (!"https".equalsIgnoreCase(this.baseUrl.getScheme())
                || this.baseUrl.getHost() == null || this.baseUrl.getUserInfo() != null
                || this.baseUrl.getQuery() != null || this.baseUrl.getFragment() != null
                || !(this.baseUrl.getPath().isEmpty() || this.baseUrl.getPath().equals("/"))) {
            throw new IllegalArgumentException("baseUrl must be an HTTPS origin, e.g. https://xxxxx.api.infobip.com");
        }
        this.apiKey = required(apiKey, "apiKey");
        if (apiKey.contains("\r") || apiKey.contains("\n")) {
            throw new IllegalArgumentException("apiKey must not contain line breaks");
        }
        this.http = Objects.requireNonNull(http, "http");
        if (http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("HTTP redirects must be disabled to protect credentials");
        }
    }

    public static InfobipSmsClient fromEnvironment() {
        return new InfobipSmsClient(System.getenv("INFOBIP_BASE_URL"), System.getenv("INFOBIP_API_KEY"));
    }

    /** Sends one text message to one recipient; acceptance does not confirm delivery. */
    public SendResponse sendSingleSms(String sender, String to, String text)
            throws IOException, InterruptedException {
        required(sender, "sender");
        required(to, "to");
        required(text, "text");
        if (!to.matches("[1-9][0-9]{1,14}")) {
            throw new IllegalArgumentException("to must be an international number without + or 00 (max 15 digits)");
        }
        var payload = Map.of("messages", List.of(Map.of(
                "sender", sender,
                "destinations", List.of(Map.of("to", to)),
                "content", Map.of("text", text))));
        var request = request("/sms/3/messages")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                .build();
        return execute(request, SendResponse.class);
    }

    /** Fetches unconsumed reports for this message. Retrieved reports cannot be fetched again. */
    public DeliveryReports getDeliveryReports(String messageId) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(required(messageId, "messageId"), StandardCharsets.UTF_8);
        return execute(request("/sms/3/reports?messageId=" + encoded).GET().build(), DeliveryReports.class);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "App " + apiKey)
                .header("Accept", "application/json");
    }

    private <T> T execute(HttpRequest request, Class<T> type) throws IOException, InterruptedException {
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new InfobipApiException(response.statusCode(), response.body());
        }
        T result = json.readValue(response.body(), type);
        if (result == null) throw new IOException("Infobip returned an empty JSON response");
        return result;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendResponse(String bulkId, List<SentMessage> messages) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SentMessage(String messageId, String destination, Status status, MessageDetails details) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageDetails(Integer messageCount) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(Integer groupId, String groupName, Integer id, String name, String description) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeliveryReports(List<DeliveryReport> results) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeliveryReport(String bulkId, String messageId, String to, String sender,
                                 String sentAt, String doneAt, Integer messageCount,
                                 Status status, DeliveryError error) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeliveryError(Integer groupId, String groupName, Integer id, String name,
                                String description, Boolean permanent) {}
}
