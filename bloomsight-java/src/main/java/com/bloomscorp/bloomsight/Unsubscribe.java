package com.bloomscorp.bloomsight;

import java.time.Instant;
import java.util.Map;

/** An address on the project's unsubscribe list: batches skip it. */
public final class Unsubscribe {

	private final String address;
	private final String reason;
	private final Instant createdAt;

	Unsubscribe(Map<String, Object> m) {
		this.address = Fields.str(m, "address");
		this.reason = Fields.str(m, "reason");
		this.createdAt = Fields.instant(m, "createdAt");
	}

	public String getAddress() { return address; }

	/** {@code unsubscribe} (the person clicked the link), {@code manual} or {@code api}. */
	public String getReason() { return reason; }

	public Instant getCreatedAt() { return createdAt; }

	@Override
	public String toString() {
		return "Unsubscribe[" + address + ", " + reason + "]";
	}
}
