package ufo.org.sms;

import java.io.IOException;

/** HTTP failure; the provider body is available explicitly and is not logged automatically. */
public final class InfobipApiException extends IOException {
    private final int statusCode;
    private final String responseBody;

    public InfobipApiException(int statusCode, String responseBody) {
        super("Infobip API returned HTTP " + statusCode);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int statusCode() { return statusCode; }
    public String responseBody() { return responseBody; }
}
