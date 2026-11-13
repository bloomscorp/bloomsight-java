package com.bloomscorp.bloomsight;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every failure is a {@code BloomsightException}, so callers branch on {@link #getCode()},
 * never on the message text.
 *
 * <p>Server codes pass through unchanged ({@code TEMPLATE_NOT_FOUND}, {@code KEY_SCOPE},
 * {@code IDEMPOTENCY_CONFLICT}, {@code INVALID_ROWS}, {@code MAILBOX_LIMIT}, ...). The
 * library's own:
 * <ul>
 *   <li>{@code INVALID_API_KEY}: the key is missing or not a Bloomsight key</li>
 *   <li>{@code INVALID_DATA}: a template value is not text, a number, true/false, null,
 *       or a map or list of those</li>
 *   <li>{@code NETWORK_FAILURE}: blooms.ai could not be reached, after the retries</li>
 *   <li>{@code TIMEOUT}: an attempt took longer than the timeout, after the retries</li>
 *   <li>{@code BAD_RESPONSE}: blooms.ai answered with something that is not its JSON</li>
 *   <li>{@code INTERRUPTED}: the calling thread was interrupted while waiting</li>
 * </ul>
 *
 * <p>The attachment rules are checked before anything is uploaded, with the server's
 * own codes: {@code INVALID_ATTACHMENT}, {@code ATTACHMENT_TOO_LARGE},
 * {@code ATTACHMENTS_TOO_LARGE}, {@code TOO_MANY_ATTACHMENTS},
 * {@code ATTACHMENT_TYPE_BLOCKED}.
 *
 * <p>The API key never appears in a message.
 */
public class BloomsightException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String code;
	private final Integer status;
	private final Long retryAfter;
	private final String idempotencyKey;
	private final transient Map<String, Object> details;

	BloomsightException(String code, String message) {
		this(code, message, null, null, null, null, null);
	}

	BloomsightException(String code, String message, Integer status, Long retryAfter,
	                    String idempotencyKey, Map<String, Object> details, Throwable cause) {
		super(message, cause);
		this.code = code;
		this.status = status;
		this.retryAfter = retryAfter;
		this.idempotencyKey = idempotencyKey;
		this.details = details == null || details.isEmpty()
			? Collections.emptyMap()
			: Collections.unmodifiableMap(new LinkedHashMap<>(details));
	}

	/** What went wrong, e.g. {@code MAILBOX_LIMIT} or {@code NETWORK_FAILURE}. */
	public String getCode() {
		return code;
	}

	/** The HTTP status, when the failure came from a response; otherwise {@code null}. */
	public Integer getStatus() {
		return status;
	}

	/** Seconds to wait before trying again, when blooms.ai said so; otherwise {@code null}. */
	public Long getRetryAfter() {
		return retryAfter;
	}

	/** The Idempotency-Key the request carried: retry with it and nothing is sent twice. */
	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	/**
	 * Extra detail from the server, e.g. {@code faults} for {@code INVALID_ROWS} or
	 * {@code fits} for {@code MONTHLY_LIMIT}. Empty when there is none.
	 */
	public Map<String, Object> getDetails() {
		return details;
	}

	@Override
	public String toString() {
		return "BloomsightException[" + code + (status == null ? "" : ", HTTP " + status) + "]: " + getMessage();
	}
}
