# Bloomsight for Java

Send [Bloomsight](https://bloomscorp.com) email templates from your Java backend.

Bloomsight decides what every email says: you send a **template id and values**, never
a subject or HTML. The template, its recipients and the mailbox it goes from are set up
in your project's Bloomsight dashboard.

- **Safe to retry.** Every send carries an idempotency key, so a retry, a timeout or a
  restarted process never sends the same email twice.
- **Retries for you** on network errors, timeouts, `429` and `5xx`, honouring
  `Retry-After`.
- **One exception type**, `BloomsightException`, with a code to branch on. Your key
  never appears in an error.
- **No dependencies.** Java 11+, built on `java.net.http`, so it never clashes with your
  own Jackson, OkHttp or Apache client.
- **Spring Boot starter** for Boot 3 and 4: set one property and inject the client.

For browser forms, use [`@bloomscorp/bloomsight.js`](https://www.npmjs.com/package/@bloomscorp/bloomsight.js);
for Node.js backends, [`@bloomscorp/bloomsight-node`](https://www.npmjs.com/package/@bloomscorp/bloomsight-node).

## Install

Plain Java (Java 11+):

```xml
<dependency>
  <groupId>com.bloomscorp</groupId>
  <artifactId>bloomsight-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("com.bloomscorp:bloomsight-java:0.1.0")
```

Spring Boot 3 or 4 (Java 17+): use the starter instead; it includes the client.

```xml
<dependency>
  <groupId>com.bloomscorp</groupId>
  <artifactId>bloomsight-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("com.bloomscorp:bloomsight-spring-boot-starter:0.1.0")
```

## Set up

1. In the Bloomsight dashboard, open the template and set its **sending channel** to
   **API only**. A template that takes its recipient from the values (for example
   `{{email}}`) must be API only, so nobody can trigger it from a web page.
2. In **Project Settings → API keys**, create a key with the **`bloomsight-email`**
   scope. Keep it in your server's environment, never in code or a browser.
   A `blm_test_…` key checks everything and sends nothing: use it in development.

## Send an email

```java
import com.bloomscorp.bloomsight.Bloomsight;
import com.bloomscorp.bloomsight.Send;
import com.bloomscorp.bloomsight.SendMailRequest;

Bloomsight bloomsight = Bloomsight.builder()
    .apiKey(System.getenv("BLOOMSIGHT_API_KEY"))
    .build();                              // one per application; thread-safe

Send send = bloomsight.sendMail(SendMailRequest.builder()
    .templateId("…")
    .engineId("…")                         // the mailbox to send from
    .value("name", "Asha")
    .value("email", "asha@example.com")
    .value("lines", List.of(Map.of("item", "Saree", "qty", 2)))
    .idempotencyKey("order-A-1042-confirmation")   // optional; one is made for you
    .build());
// send.getStatus() == SendStatus.QUEUED

bloomsight.getSend(send.getId());          // QUEUED → SENDING → SENT | FAILED | UNKNOWN
```

A send is **queued** and goes out within about a second while the mailbox has budget.
If the mailbox is at its daily limit, it waits and goes when the budget frees.

`UNKNOWN` means the connection to the mail provider dropped mid-send, so it cannot be
known whether it arrived. It is never retried automatically, because a duplicate order
confirmation is worse than a missing one.

**Pass your own `idempotencyKey`** for anything that must go once: the same key and body
within 24 hours returns the original send (`isReplayed()` is true); the same key with a
different body throws `IDEMPOTENCY_CONFLICT`.

**Template values** may be `String`, numbers, `Boolean`, `null`, `Map<String, ?>`, and
lists or arrays of those. Anything else (a `LocalDate`, a `UUID`, your own class) is
refused with `INVALID_DATA` before anything is sent; turn it into text first.

Calls block until blooms.ai answers, through any retries. Call them from a background
thread or a virtual thread if you don't want a request to wait.

## Spring Boot

```properties
bloomsight.api-key=${BLOOMSIGHT_API_KEY}
```

```java
@Service
class OrderEmails {
    private final Bloomsight bloomsight;

    OrderEmails(Bloomsight bloomsight) {
        this.bloomsight = bloomsight;
    }
}
```

- With no key (or an empty one, such as `${BLOOMSIGHT_API_KEY:}` in a dev profile), no
  client is created and the application still starts.
- A key that is not a Bloomsight key stops startup with `INVALID_API_KEY`, so a wrong
  key is found at deploy, not at the first order confirmation.
- Your own `Bloomsight` bean always wins.

| Property | Default |
|---|---|
| `bloomsight.api-key` | (none: required for a client) |
| `bloomsight.base-url` | `https://z.bloomscorp.com` |
| `bloomsight.timeout` | `15s`, per attempt |
| `bloomsight.max-retries` | `3` |
| `bloomsight.max-retry-wait` | `60s`; a longer `Retry-After` is thrown, not waited out |

## Send to a list

```java
Batch batch = bloomsight.sendBatch(SendBatchRequest.builder()
    .templateId("…")
    .engineId("…")
    .name("November offer")
    .recipients(subscribers.stream()
        .map(s -> Map.of("email", s.email(), "name", s.name()))
        .collect(Collectors.toList()))      // up to 5,000
    .build());

bloomsight.getBatch(batch.getId());        // getProgress(): queued, sent, failed, …
bloomsight.pauseBatch(batch.getId());
bloomsight.resumeBatch(batch.getId());
bloomsight.cancelBatch(batch.getId());
```

- Every row is checked first; one bad row refuses the whole batch, and the exception's
  `getDetails().get("faults")` lists the rows at fault.
- Repeated addresses and addresses on the unsubscribe list are dropped.
- Batches run at the mailbox's bulk share (70% of its daily limit), across days if
  needed, so they never use up the budget your transactional email relies on.
- Every batch email carries a one-click unsubscribe link (`List-Unsubscribe`). Show it
  in the template too with `{{unsubscribeUrl}}`.
- Bulk email must be included in the project's Bloomsight plan.

## Unsubscribes and budgets

```java
bloomsight.listUnsubscribes(100, null);                  // newest first; pass a date to page
bloomsight.addUnsubscribe("someone@example.com");        // e.g. opted out on your site
bloomsight.removeUnsubscribe("someone@example.com");     // only when they opt back in

bloomsight.getLimits();   // each mailbox: limits, used today, paused by its provider?
```

## Errors

```java
try {
    bloomsight.sendMail(request);
} catch (BloomsightException e) {
    e.getCode();            // "TEMPLATE_NOT_FOUND", "TEMPLATE_PUBLIC_ONLY", "INVALID_VARIABLES", …
    e.getStatus();          // HTTP status, when there was a response
    e.getRetryAfter();      // seconds, when blooms.ai said when to try again
    e.getIdempotencyKey();  // retry with this to stay safe
    e.getDetails();         // e.g. faults for INVALID_ROWS, fits for MONTHLY_LIMIT
}
```

| Code | Meaning |
|---|---|
| `INVALID_API_KEY` | The key is missing, revoked or not a Bloomsight key |
| `KEY_SCOPE` | The key does not list the `bloomsight-email` scope |
| `TEMPLATE_NOT_FOUND`, `ENGINE_NOT_FOUND` | Not in this key's project |
| `TEMPLATE_PUBLIC_ONLY` | The template's channel is "Browser forms"; set it to "API only" or "Both" |
| `INVALID_VARIABLES` | A value name or size is not allowed (100 values, 5,000 characters each, 16 KB in all) |
| `INVALID_DATA` | A value is not text, a number, true/false, null, or a map or list of those (checked before sending) |
| `IDEMPOTENCY_CONFLICT` | The key was used with a different request |
| `INVALID_ROWS`, `BULK_NOT_IN_PLAN`, `MONTHLY_LIMIT` | Batches |
| `RATE_LIMITED` | Too many requests; retried for you, thrown when retries run out |
| `NETWORK_FAILURE`, `TIMEOUT`, `BAD_RESPONSE` | blooms.ai could not be reached or answered oddly, after retries |
| `INTERRUPTED` | Your thread was interrupted while waiting; its interrupt flag is kept |

## Options

```java
Bloomsight.builder()
    .apiKey(apiKey)                              // required
    .baseUrl("https://z.bloomscorp.com")
    .timeout(Duration.ofSeconds(15))             // per attempt, end to end
    .maxRetries(3)
    .maxRetryWait(Duration.ofSeconds(60))        // a longer Retry-After is thrown, not waited out
    .httpClient(myHttpClient)                    // optional: proxies, custom TLS
    .build();
```

## Not in 0.1.0

Attachments.

## Development

```bash
mvn verify                          # both modules, Spring Boot 3.5
mvn -pl bloomsight-spring-boot-starter -am verify -Dspring-boot.version=4.1.2
scripts/verify-package.sh           # what would be published (set JAVA11_HOME to prove Java 11)
```

Releases are published to Maven Central by the release workflow when a `v*` tag is
pushed; a published version can never be changed, so nothing is published by hand.

## License

MIT
