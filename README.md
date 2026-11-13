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
- **Attachments**: PDFs, spreadsheets, images, up to 5 files per email.
- **Spring Boot starter** for Boot 3 and 4: set one property and inject the client.

For browser forms, use [`@bloomscorp/bloomsight.js`](https://www.npmjs.com/package/@bloomscorp/bloomsight.js);
for Node.js backends, [`@bloomscorp/bloomsight-node`](https://www.npmjs.com/package/@bloomscorp/bloomsight-node).

## Install

Plain Java (Java 11+):

```xml
<dependency>
  <groupId>com.bloomscorp</groupId>
  <artifactId>bloomsight-java</artifactId>
  <version>0.2.0</version>
</dependency>
```

```kotlin
implementation("com.bloomscorp:bloomsight-java:0.2.0")
```

Spring Boot 3 or 4 (Java 17+): use the starter instead; it includes the client.

```xml
<dependency>
  <groupId>com.bloomscorp</groupId>
  <artifactId>bloomsight-spring-boot-starter</artifactId>
  <version>0.2.0</version>
</dependency>
```

```kotlin
implementation("com.bloomscorp:bloomsight-spring-boot-starter:0.2.0")
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

## Attachments

```java
import com.bloomscorp.bloomsight.Attachment;

byte[] pdf = invoices.renderPdf(order);    // your own PDF, in memory

Send send = bloomsight.sendMail(SendMailRequest.builder()
    .templateId("…")
    .engineId("…")
    .value("name", "Asha")
    .value("email", "asha@example.com")
    .attachment(Attachment.of("invoice-A-1042.pdf", pdf))         // type from the name
    .attachment(Attachment.fromPath(Path.of("/srv/terms.pdf")))    // read now
    .idempotencyKey("order-A-1042-invoice")
    .build());

send.getStatus();   // SENT: the provider accepted it
                    // FAILED: refused outright, e.g. a bad address (send.getError())
                    // UNKNOWN: the connection dropped mid-send; it may or may not have arrived
```

A send with attachments is **not queued**: the files are kept in memory only and never
stored, so the email is sent while the call waits, and it comes back finished:
`SENT`, `FAILED` or `UNKNOWN`, returned rather than thrown. A retry of the same
`idempotencyKey` that arrives while the first is still sending gets `SENDING`.
`send.getAttachmentCount()` says how many files went.

When the mailbox has no budget or blooms.ai is busy, **nothing is sent** and the client
retries for you with the same idempotency key, honouring `Retry-After`; a longer wait
(a mailbox at its daily limit) is thrown with `getRetryAfter()`, as for any send.

| Rule | Code |
|---|---|
| 1 to 5 files | `TOO_MANY_ATTACHMENTS` |
| each file 1 byte to 5 MB | `ATTACHMENT_TOO_LARGE`; an empty file is `INVALID_ATTACHMENT` |
| 15 MB for all files together | `ATTACHMENTS_TOO_LARGE` |
| a file name of 1 to 150 characters, after cleaning | `INVALID_ATTACHMENT` |
| not a type mail providers refuse to deliver | `ATTACHMENT_TYPE_BLOCKED` |

- Every rule is checked **before anything is uploaded** (blooms.ai checks them again), so
  a bad file fails at once with `BloomsightException` from `sendMail`. A megabyte here
  is 1,048,576 bytes.
- **Blocked types**, by the last extension (`invoice.pdf.exe` is blocked): `ade adp apk
  appx appxbundle bat cab chm cmd com cpl diagcab diagcfg diagpack dll dmg ex ex_ exe hta
  img ins iso isp jar jnlp js jse lib lnk mde mjs msc msi msix msixbundle msp mst nsh pif
  ps1 scr sct shb sys vb vbe vbs vhd vxd wsc wsf wsh xll`. Gmail also refuses a `.zip`
  that contains one of these.
- **File names are cleaned** as blooms.ai cleans them: only the part after the last `/`
  or `\`, without control characters or double quotes, trimmed. `getFilename()` shows
  the name that will be sent.
- **The content type** is guessed from the name (`pdf`, `png`, `jpg`, `csv`, `xlsx`,
  `docx`, `ics`, `zip`, …; otherwise `application/octet-stream`), or pass it:
  `Attachment.of("report", bytes, "application/pdf")`.
- `Attachment.of` copies your bytes, so changing your array afterwards changes nothing.
  `Attachment.fromPath` reads the file at once and refuses one over 5 MB without
  reading it; its errors name the file, never the full path.
- The files go on the **email itself**, never on the template's auto-reply.
- A **test key** checks the files too (every rule above) and sends nothing: the status is
  `TEST`.
- Each attempt may take up to **2 minutes** (`attachmentTimeout`), since the upload and
  the send happen while the call waits. Other calls keep `timeout`.
- Batches take no attachments.

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
| `bloomsight.attachment-timeout` | `2m`, per attempt of a send with attachments |
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
| `IDEMPOTENCY_CONFLICT` | The key was used with a different request (for attachments: a different file) |
| `INVALID_ATTACHMENT`, `ATTACHMENT_TOO_LARGE`, `ATTACHMENTS_TOO_LARGE`, `TOO_MANY_ATTACHMENTS`, `ATTACHMENT_TYPE_BLOCKED` | See [Attachments](#attachments) (checked before sending) |
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
    .attachmentTimeout(Duration.ofMinutes(2))    // the same, for a send with attachments
    .maxRetries(3)
    .maxRetryWait(Duration.ofSeconds(60))        // a longer Retry-After is thrown, not waited out
    .httpClient(myHttpClient)                    // optional: proxies, custom TLS
    .build();
```

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
