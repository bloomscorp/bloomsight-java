package com.bloomscorp.bloomsight;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One email to send: a template, the mailbox it goes from, and the template's values.
 * Bloomsight decides what the email says; you never send a subject or HTML.
 *
 * <pre>{@code
 * SendMailRequest.builder()
 *     .templateId(templateId)
 *     .engineId(engineId)
 *     .value("name", "Asha")
 *     .value("email", "asha@example.com")
 *     .idempotencyKey("order-A-1042-confirmation")
 *     .build();
 * }</pre>
 */
public final class SendMailRequest {

	private final String templateId;
	private final String engineId;
	private final Map<String, Object> data;
	private final String idempotencyKey;

	private SendMailRequest(Builder b) {
		this.templateId = b.templateId;
		this.engineId = b.engineId;
		this.data = Collections.unmodifiableMap(new LinkedHashMap<>(b.data));
		this.idempotencyKey = b.idempotencyKey;
	}

	public static Builder builder() {
		return new Builder();
	}

	public String getTemplateId() { return templateId; }

	public String getEngineId() { return engineId; }

	public Map<String, Object> getData() { return data; }

	public String getIdempotencyKey() { return idempotencyKey; }

	/** The JSON body: the idempotency key travels as a header, not in here. */
	Map<String, Object> body() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("templateId", templateId);
		body.put("engineId", engineId);
		if (!data.isEmpty()) body.put("data", data);
		return body;
	}

	public static final class Builder {
		private String templateId;
		private String engineId;
		private final Map<String, Object> data = new LinkedHashMap<>();
		private String idempotencyKey;

		private Builder() {
		}

		/** The template, from the Bloomsight dashboard. */
		public Builder templateId(String templateId) {
			this.templateId = templateId;
			return this;
		}

		/** The mailbox to send from, from the Bloomsight dashboard. */
		public Builder engineId(String engineId) {
			this.engineId = engineId;
			return this;
		}

		/**
		 * The template's values: text, numbers, true/false, null, and maps and lists of
		 * those (up to 4 levels). Adds to any values already set.
		 */
		public Builder data(Map<String, ?> data) {
			if (data != null) this.data.putAll(data);
			return this;
		}

		/** One template value. */
		public Builder value(String name, Object value) {
			this.data.put(name, value);
			return this;
		}

		/**
		 * Makes the send safe to repeat: the same key and body within 24 hours returns the
		 * original send. One is generated when you don't set one; set your own (for
		 * example {@code order-A-1042-confirmation}) so even a restarted process cannot
		 * send twice.
		 */
		public Builder idempotencyKey(String idempotencyKey) {
			this.idempotencyKey = idempotencyKey;
			return this;
		}

		public SendMailRequest build() {
			return new SendMailRequest(this);
		}
	}
}
