package com.bloomscorp.bloomsight;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Sends Bloomsight email templates from a Java backend
 * (blooms.ai documentation/bloomsight/backend-sending.md §10.2).
 *
 * <pre>{@code
 * Bloomsight bloomsight = Bloomsight.builder()
 *     .apiKey(System.getenv("BLOOMSIGHT_API_KEY"))
 *     .build();
 *
 * Send send = bloomsight.sendMail(SendMailRequest.builder()
 *     .templateId(templateId)
 *     .engineId(engineId)
 *     .value("name", "Asha")
 *     .idempotencyKey("order-A-1042-confirmation")
 *     .build());
 * }</pre>
 *
 * <ul>
 *   <li>Every send and batch carries an Idempotency-Key (yours, or one made here), kept
 *       the same across retries, so a retry can never send twice.</li>
 *   <li>Network errors, timeouts, {@code 429} and {@code 5xx} are retried with backoff,
 *       honouring {@code Retry-After}.</li>
 *   <li>Failures throw {@link BloomsightException} with a code; the key never appears in
 *       one.</li>
 * </ul>
 *
 * <p>Thread-safe: create one per application and share it. Calls block until blooms.ai
 * answers (and through any retries).
 */
public final class Bloomsight {

	/** This library's version, as sent in the User-Agent. */
	public static final String VERSION = readVersion();

	static final String DEFAULT_BASE_URL = "https://z.bloomscorp.com";
	private static final Pattern KEY_PATTERN = Pattern.compile("^blm_(live|test)_[0-9a-f]{64}$");
	private static final int[] RETRY_STATUSES = {429, 500, 502, 503, 504};

	/** How the client waits between attempts. A seam for tests. */
	interface Sleeper {
		void sleep(long ms) throws InterruptedException;
	}

	private final String apiKey;
	private final String base;
	private final Duration timeout;
	private final Duration attachmentTimeout;
	private final int maxRetries;
	private final Duration maxRetryWait;
	private final HttpClient http;
	private volatile Sleeper sleeper = Thread::sleep;

	private Bloomsight(Builder b) {
		String key = b.apiKey == null ? "" : b.apiKey.trim();
		if (!KEY_PATTERN.matcher(key).matches()) {
			// Never echo what was passed: it may be some other secret.
			throw new BloomsightException("INVALID_API_KEY",
				"apiKey must be a Bloomsight API key (blm_live_... or blm_test_...). "
					+ "Create one with the bloomsight-email scope in Project Settings > API keys.");
		}
		this.apiKey = key;
		String baseUrl = b.baseUrl == null || b.baseUrl.trim().isEmpty() ? DEFAULT_BASE_URL : b.baseUrl.trim();
		this.base = baseUrl.replaceAll("/+$", "");
		this.timeout = positive(b.timeout, Duration.ofSeconds(15), "timeout");
		this.attachmentTimeout = positive(b.attachmentTimeout, Duration.ofMinutes(2), "attachmentTimeout");
		if (b.maxRetries < 0) throw new IllegalArgumentException("maxRetries must be 0 or more");
		this.maxRetries = b.maxRetries;
		this.maxRetryWait = b.maxRetryWait == null ? Duration.ofSeconds(60) : b.maxRetryWait;
		if (this.maxRetryWait.isNegative()) throw new IllegalArgumentException("maxRetryWait must not be negative");
		this.http = b.httpClient != null ? b.httpClient : HttpClient.newBuilder()
			.connectTimeout(this.timeout)
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	private static Duration positive(Duration value, Duration fallback, String name) {
		if (value == null) return fallback;
		if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
		return value;
	}

	public static Builder builder() {
		return new Builder();
	}

	/** True for a {@code blm_test_...} key: everything is checked and nothing is sent. */
	public boolean isTestKey() {
		return apiKey.startsWith("blm_test_");
	}

	// ── Sending ──────────────────────────────────────────────────────────────

	/**
	 * Queues one email. It goes out within about a second while the mailbox has budget;
	 * follow it with {@link #getSend(String)}.
	 *
	 * <p>With attachments it is not queued: it is sent while this call waits (up to the
	 * {@link Builder#attachmentTimeout(Duration) attachment timeout} per attempt) and
	 * comes back finished: {@link SendStatus#SENT}, {@link SendStatus#FAILED} (the provider
	 * refused it) or {@link SendStatus#UNKNOWN} (the connection dropped mid-send). These
	 * are returned, not thrown. The attachment rules are checked before anything is
	 * uploaded.
	 */
	public Send sendMail(SendMailRequest request) {
		String key = request.getIdempotencyKey() != null ? request.getIdempotencyKey() : UUID.randomUUID().toString();
		Result r;
		if (request.getAttachments().isEmpty()) {
			r = request("POST", "/send", request.body(), key, null);
		} else {
			Multipart.Body body = request.multipart();
			r = exchange("POST", "/send", null, body.contentType,
				HttpRequest.BodyPublishers.ofByteArray(body.bytes), key, attachmentTimeout);
		}
		return new Send(Fields.map(r.data), replayed(r.headers));
	}

	public Send getSend(String id) {
		return new Send(Fields.map(request("GET", "/sends/" + segment(id), null, null, null).data), false);
	}

	/** Sends one template to many recipients. Checked whole first; one bad row refuses all. */
	public Batch sendBatch(SendBatchRequest request) {
		String key = request.getIdempotencyKey() != null ? request.getIdempotencyKey() : UUID.randomUUID().toString();
		Result r = request("POST", "/batches", request.body(), key, null);
		return new Batch(Fields.map(r.data), replayed(r.headers));
	}

	public Batch getBatch(String id) {
		return new Batch(Fields.map(request("GET", "/batches/" + segment(id), null, null, null).data), false);
	}

	public Batch pauseBatch(String id) {
		return new Batch(Fields.map(request("POST", "/batches/" + segment(id) + "/pause", null, null, null).data), false);
	}

	public Batch resumeBatch(String id) {
		return new Batch(Fields.map(request("POST", "/batches/" + segment(id) + "/resume", null, null, null).data), false);
	}

	/** Stops every email of the batch that has not started. Cannot be undone. */
	public Batch cancelBatch(String id) {
		return new Batch(Fields.map(request("POST", "/batches/" + segment(id) + "/cancel", null, null, null).data), false);
	}

	// ── Budgets and the unsubscribe list ─────────────────────────────────────

	/** Each mailbox's budget and today's use. */
	public List<MailboxLimits> getLimits() {
		List<MailboxLimits> out = new ArrayList<>();
		for (Object row : list(request("GET", "/limits", null, null, null).data)) out.add(new MailboxLimits(Fields.map(row)));
		return Collections.unmodifiableList(out);
	}

	/** The newest entries of the unsubscribe list. */
	public List<Unsubscribe> listUnsubscribes() {
		return listUnsubscribes(null, null);
	}

	/**
	 * A page of the unsubscribe list, newest first.
	 *
	 * @param limit  how many, or {@code null} for the server's default
	 * @param before only entries added before this moment (for the next page), or {@code null}
	 */
	public List<Unsubscribe> listUnsubscribes(Integer limit, Instant before) {
		Map<String, String> query = new LinkedHashMap<>();
		if (limit != null) query.put("limit", String.valueOf(limit));
		if (before != null) query.put("before", before.toString());
		List<Unsubscribe> out = new ArrayList<>();
		for (Object row : list(request("GET", "/unsubscribes", null, null, query).data)) out.add(new Unsubscribe(Fields.map(row)));
		return Collections.unmodifiableList(out);
	}

	/** Adds an address; returns {@code false} when it was already on the list. */
	public boolean addUnsubscribe(String address) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("address", address);
		return Fields.bool(Fields.map(request("POST", "/unsubscribes", body, null, null).data), "added");
	}

	/**
	 * Removes an address, only when the person has opted back in. Returns {@code false}
	 * when it was not on the list.
	 */
	public boolean removeUnsubscribe(String address) {
		try {
			return Fields.bool(Fields.map(request("DELETE", "/unsubscribes/" + segment(address), null, null, null).data), "removed");
		} catch (BloomsightException e) {
			if ("NOT_ON_LIST".equals(e.getCode())) return false;
			throw e;
		}
	}

	// ── Transport ────────────────────────────────────────────────────────────

	/** Test seam. */
	void setSleeper(Sleeper sleeper) {
		this.sleeper = sleeper;
	}

	private static final class Result {
		final Object data;
		final HttpHeaders headers;

		Result(Object data, HttpHeaders headers) {
			this.data = data;
			this.headers = headers;
		}
	}

	private Result request(String method, String path, Map<String, Object> body, String idempotencyKey,
	                       Map<String, String> query) {
		// Serialise first, so a bad template value fails before anything is sent.
		String json = body == null ? null : Json.write(body);
		return exchange(method, path, query, json == null ? null : "application/json", json == null
			? HttpRequest.BodyPublishers.noBody()
			: HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8), idempotencyKey, timeout);
	}

	/**
	 * Sends a request, retrying as needed. The body publisher replays the same bytes on
	 * every attempt; {@code attemptTimeout} caps each attempt end to end.
	 */
	private Result exchange(String method, String path, Map<String, String> query, String contentType,
	                        HttpRequest.BodyPublisher bodyPublisher, String idempotencyKey, Duration attemptTimeout) {
		URI uri = URI.create(base + "/api/v1/bloomsight/email" + path + queryString(query));

		HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
			.timeout(attemptTimeout)
			.header("Authorization", "Bearer " + apiKey)
			.header("Accept", "application/json")
			.header("User-Agent", "bloomsight-java/" + VERSION);
		if (contentType != null) rb.header("Content-Type", contentType);
		if (idempotencyKey != null) rb.header("Idempotency-Key", idempotencyKey);
		rb.method(method, bodyPublisher);
		HttpRequest httpRequest = rb.build();

		// GETs are safe to repeat; every send and batch has an idempotency key; the batch
		// controls and list edits are idempotent by nature. So everything may be retried.
		int attempt = 0;
		for (;;) {
			HttpResponse<String> response;
			try {
				response = send(httpRequest, attemptTimeout);
			} catch (TimeoutException | HttpTimeoutException e) {
				if (attempt < maxRetries) {
					pause(backoff(attempt++), idempotencyKey);
					continue;
				}
				throw new BloomsightException("TIMEOUT",
					redact("blooms.ai did not answer within " + attemptTimeout.toMillis() + " ms"),
					null, null, idempotencyKey, null, null);
			} catch (IOException e) {
				if (attempt < maxRetries) {
					pause(backoff(attempt++), idempotencyKey);
					continue;
				}
				throw new BloomsightException("NETWORK_FAILURE",
					redact("could not reach blooms.ai: " + e), null, null, idempotencyKey, null, null);
			}

			int status = response.statusCode();
			Map<String, Object> parsed = null;
			try {
				Object value = Json.parse(response.body() == null ? "" : response.body());
				if (value instanceof Map) parsed = Fields.map(value);
			} catch (IllegalArgumentException notJson) {
				parsed = null;
			}

			if (status >= 200 && status < 300 && parsed != null && Boolean.TRUE.equals(parsed.get("success"))) {
				return new Result(parsed.get("data"), response.headers());
			}

			Long retryAfter = retryAfterSeconds(response.headers());
			Long waitMs = retryAfter == null ? null : retryAfter * 1000;
			if (isRetryStatus(status) && attempt < maxRetries
				&& (waitMs == null || waitMs <= maxRetryWait.toMillis())) {
				pause(waitMs != null ? waitMs : backoff(attempt), idempotencyKey);
				attempt++;
				continue;
			}

			if (parsed == null) {
				throw new BloomsightException("BAD_RESPONSE",
					"blooms.ai answered HTTP " + status + " without its usual JSON",
					status, null, idempotencyKey, null, null);
			}
			String code = parsed.get("code") instanceof String ? (String) parsed.get("code") : "HTTP_" + status;
			String message = parsed.get("message") instanceof String
				? (String) parsed.get("message")
				: "request failed (HTTP " + status + ")";
			Map<String, Object> details = new LinkedHashMap<>(parsed);
			details.remove("success");
			details.remove("code");
			details.remove("message");
			details.remove("data");
			throw new BloomsightException(code, redact(message), status, retryAfter, idempotencyKey, details, null);
		}
	}

	/** One attempt, capped at the timeout end to end (headers and body). */
	private HttpResponse<String> send(HttpRequest request, Duration attemptTimeout) throws IOException, TimeoutException {
		CompletableFuture<HttpResponse<String>> future =
			http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		try {
			return future.get(attemptTimeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			future.cancel(true);
			throw e;
		} catch (InterruptedException e) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new BloomsightException("INTERRUPTED", "interrupted while waiting for blooms.ai",
				null, null, request.headers().firstValue("Idempotency-Key").orElse(null), null, e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof HttpTimeoutException) throw (HttpTimeoutException) cause;
			if (cause instanceof IOException) throw (IOException) cause;
			throw new IOException(cause == null ? "request failed" : cause.getClass().getSimpleName()
				+ (cause.getMessage() == null ? "" : ": " + cause.getMessage()), cause);
		}
	}

	private void pause(long ms, String idempotencyKey) {
		try {
			sleeper.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BloomsightException("INTERRUPTED", "interrupted while waiting to retry",
				null, null, idempotencyKey, null, e);
		}
	}

	/** 0.5 s, 1 s, 2 s, 4 s ... with up to 25% jitter, capped at 10 s. */
	private static long backoff(int attempt) {
		long base = Math.min(10_000L, 500L << Math.min(attempt, 20));
		return Math.round(base * (1 + ThreadLocalRandom.current().nextDouble() * 0.25));
	}

	private static boolean isRetryStatus(int status) {
		for (int s : RETRY_STATUSES) if (s == status) return true;
		return false;
	}

	private static Long retryAfterSeconds(HttpHeaders headers) {
		String value = headers.firstValue("retry-after").orElse(null);
		if (value == null) return null;
		try {
			long seconds = Long.parseLong(value.trim());
			return seconds > 0 ? seconds : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static boolean replayed(HttpHeaders headers) {
		return "true".equalsIgnoreCase(headers.firstValue("idempotent-replayed").orElse(""));
	}

	private static List<?> list(Object data) {
		return data instanceof List ? (List<?>) data : Collections.emptyList();
	}

	private static String segment(String value) {
		if (value == null || value.isEmpty()) throw new IllegalArgumentException("an id or address is required");
		return encode(value);
	}

	private static String queryString(Map<String, String> query) {
		if (query == null || query.isEmpty()) return "";
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : query.entrySet()) {
			sb.append(sb.length() == 0 ? '?' : '&').append(encode(e.getKey())).append('=').append(encode(e.getValue()));
		}
		return sb.toString();
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private String redact(String text) {
		return text == null ? null : text.replace(apiKey, "blm_***");
	}

	private static String readVersion() {
		try (InputStream in = Bloomsight.class.getResourceAsStream("bloomsight-java.properties")) {
			if (in == null) return "dev";
			Properties p = new Properties();
			p.load(in);
			String v = p.getProperty("version", "dev");
			return v.startsWith("${") ? "dev" : v;
		} catch (IOException e) {
			return "dev";
		}
	}

	@Override
	public String toString() {
		return "Bloomsight[" + base + ", " + (isTestKey() ? "test key" : "live key") + "]";
	}

	/** Settings for a {@link Bloomsight} client. Only the API key is required. */
	public static final class Builder {
		private String apiKey;
		private String baseUrl;
		private Duration timeout;
		private Duration attachmentTimeout;
		private int maxRetries = 3;
		private Duration maxRetryWait;
		private HttpClient httpClient;

		private Builder() {
		}

		/**
		 * A project API key with the {@code bloomsight-email} scope: {@code blm_live_...}
		 * or {@code blm_test_...}. Keep it in the server's environment, never in code.
		 */
		public Builder apiKey(String apiKey) {
			this.apiKey = apiKey;
			return this;
		}

		/** Defaults to {@code https://z.bloomscorp.com}. */
		public Builder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		/** Per attempt, end to end. Default 15 seconds. Sends with attachments use {@link #attachmentTimeout(Duration)}. */
		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * Per attempt, end to end, for a send with attachments: it is sent while the call
		 * waits, and the upload takes time. Default 2 minutes. Other calls use
		 * {@link #timeout(Duration)}.
		 */
		public Builder attachmentTimeout(Duration attachmentTimeout) {
			this.attachmentTimeout = attachmentTimeout;
			return this;
		}

		/** Retries after the first attempt, for network errors, timeouts, 429 and 5xx. Default 3. */
		public Builder maxRetries(int maxRetries) {
			this.maxRetries = maxRetries;
			return this;
		}

		/**
		 * The longest {@code Retry-After} the client waits out by itself. Longer waits
		 * (a mailbox at its daily limit) are thrown with {@code getRetryAfter()} instead.
		 * Default 60 seconds.
		 */
		public Builder maxRetryWait(Duration maxRetryWait) {
			this.maxRetryWait = maxRetryWait;
			return this;
		}

		/** An {@link HttpClient} to use instead of the client's own (proxies, custom TLS). */
		public Builder httpClient(HttpClient httpClient) {
			this.httpClient = httpClient;
			return this;
		}

		/** @throws BloomsightException {@code INVALID_API_KEY} when the key is not a Bloomsight key */
		public Bloomsight build() {
			return new Bloomsight(this);
		}
	}
}
