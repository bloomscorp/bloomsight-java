package com.bloomscorp.bloomsight;

/**
 * Where a batch is. {@code UNKNOWN_VALUE} is a status this version of the library does
 * not know yet; {@link Batch#getStatusText()} has the text the server sent.
 */
public enum BatchStatus {
	RUNNING, PAUSED, CANCELLED, DONE,
	/** Sent with a {@code blm_test_...} key: checked, nothing sent. */
	TEST,
	/** A status newer than this library; see {@link Batch#getStatusText()}. */
	UNKNOWN_VALUE;

	static BatchStatus from(String text) {
		if (text == null) return UNKNOWN_VALUE;
		for (BatchStatus s : values()) {
			if (s != UNKNOWN_VALUE && s.name().equalsIgnoreCase(text)) return s;
		}
		return UNKNOWN_VALUE;
	}
}
