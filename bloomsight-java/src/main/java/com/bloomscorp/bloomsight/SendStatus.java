package com.bloomscorp.bloomsight;

/**
 * Where one email is: {@code QUEUED → SENDING → SENT | FAILED | UNKNOWN}.
 *
 * <p>A send with attachments is never queued: {@link Bloomsight#sendMail} returns it
 * finished ({@code SENT}, {@code FAILED} or {@code UNKNOWN}), or {@code SENDING} when the
 * same Idempotency-Key is still being sent by an earlier call.
 *
 * <p>{@code UNKNOWN} means the connection to the mail provider dropped mid-send, so it
 * cannot be known whether it arrived. It is never retried automatically, because a
 * duplicate order confirmation is worse than a missing one.
 *
 * <p>{@code UNKNOWN_VALUE} is a status this version of the library does not know yet;
 * {@link Send#getStatusText()} has the text the server sent.
 */
public enum SendStatus {
	QUEUED, PAUSED, SENDING, SENT, FAILED, UNKNOWN, CANCELLED,
	/** Sent with a {@code blm_test_...} key: checked, nothing sent. */
	TEST,
	/** A status newer than this library; see {@link Send#getStatusText()}. */
	UNKNOWN_VALUE;

	static SendStatus from(String text) {
		if (text == null) return UNKNOWN_VALUE;
		for (SendStatus s : values()) {
			if (s != UNKNOWN_VALUE && s.name().equalsIgnoreCase(text)) return s;
		}
		return UNKNOWN_VALUE;
	}
}
