# Gikko SMS service

Java 21 client for sending a single SMS and looking up delivery reports using Infobip SMS API v3.

## Configuration

Set these environment variables in your application runtime (do not commit credentials):

```sh
export INFOBIP_BASE_URL='https://YOUR_ACCOUNT.api.infobip.com'
export INFOBIP_API_KEY='YOUR_API_KEY'
```

Use the account base URL from the Infobip portal and an API key authorized for SMS sending and delivery reports. Configure a sender approved for your account and destination country.

## Usage

```java
import ufo.org.sms.InfobipSmsClient;

var sms = InfobipSmsClient.fromEnvironment();
var sent = sms.sendSingleSms("Gikko", "263771234567", "Hello from Gikko");
var message = sent.messages().getFirst();
String messageId = message.messageId(); // Persist this for correlation.
System.out.println(message.status().name());

// Run later, after allowing time for delivery.
var reports = sms.getDeliveryReports(messageId);
for (var report : reports.results()) {
    System.out.println(report.status().name());
}
```

The constructor also accepts base URL and API key directly. This module is a reusable Java client; it does not start an HTTP server.

- `sendSingleSms(sender, to, text)` calls `POST /sms/3/messages` with one destination. Supply an international number using digits only, without a leading `+` or `00`. Longer text may be split into multiple billable SMS parts.
- The send response indicates acceptance, not delivery. Check the returned status, including rejections, and retain the message ID.
- `getDeliveryReports(messageId)` calls `GET /sms/3/reports?messageId=...`. Reports are consumed on retrieval and available for the last 48 hours. Persist returned reports if your application needs repeated lookup. An empty result can mean no report yet, already consumed, or expired; it does not prove success or failure.
- `DELIVERED_TO_HANDSET` confirms device delivery; `DELIVERED_TO_OPERATOR` confirms operator receipt only.
- Non-2xx responses throw `InfobipApiException`, exposing `statusCode()` and `responseBody()`. Treat the body as sensitive. Network/JSON failures throw `IOException`; interruption propagates as `InterruptedException`.
- Connect timeout is 10 seconds and request timeout is 30 seconds. No automatic retry is performed: retrying an ambiguous send can duplicate SMS, and repeating a report request may lose consumed results.

## Build and test

```sh
mvn clean verify
```

Tests mock the HTTP transport and never send real SMS. Live account verification requires your credentials and a permitted recipient.

## API documentation

- [Send SMS API reference](https://www.infobip.com/docs/api/channels/sms/sms-messaging/outbound-sms/send-sms-message)
- [Send and fetch a delivery report](https://www.infobip.com/docs/tutorials/send-your-first-sms-message-using-infobip-api)
- [Reporting retention and consumption](https://www.infobip.com/docs/sms/reports-and-insights)
- [Quickstart and delivery status semantics](https://www.infobip.com/docs/developer-resources/quickstart)
