package com.bloomscorp.bloomsight;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
 *
 * <p>With {@link Builder#attachment(Attachment) attachments} the email is sent at once,
 * while the call waits, instead of being queued; see {@link Bloomsight#sendMail}.
 */
public final class SendMailRequest {

	private final String templateId;
	private final String engineId;
	private final Map<String, Object> data;
	private final String idempotencyKey;
	private final List<Attachment> attachments;

	private SendMailRequest(Builder b) {
		this.templateId = b.templateId;
		this.engineId = b.engineId;
		this.data = Collections.unmodifiableMap(new LinkedHashMap<>(b.data));
		this.idempotencyKey = b.idempotencyKey;
		this.attachments = Collections.unmodifiableList(new ArrayList<>(b.attachments));
	}

	public static Builder builder() {
		return new Builder();
	}

	public String getTemplateId() { return templateId; }

	public String getEngineId() { return engineId; }

	public Map<String, Object> getData() { return data; }

	public String getIdempotencyKey() { return idempotencyKey; }

	/** The files to send, in order; empty when there are none. Unmodifiable. */
	public List<Attachment> getAttachments() { return attachments; }

	/** The JSON body: the idempotency key travels as a header, not in here. */
	Map<String, Object> body() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("templateId", templateId);
		body.put("engineId", engineId);
		if (!data.isEmpty()) body.put("data", data);
		return body;
	}

	/**
	 * The multipart body of a send with attachments: the same fields, the values as JSON
	 * text, then one {@code attachments} part per file. Checks every attachment rule
	 * first, so nothing is uploaded that the server would refuse.
	 */
	Multipart.Body multipart() {
		// Values first, as for a JSON send, so a bad value is INVALID_DATA either way.
		String json = data.isEmpty() ? null : Json.write(data);
		Attachments.check(attachments);
		Multipart m = new Multipart();
		if (templateId != null) m.field("templateId", templateId);
		if (engineId != null) m.field("engineId", engineId);
		if (json != null) m.field("data", json);
		for (Attachment a : attachments) m.file("attachments", a.getFilename(), a.getContentType(), a.bytes());
		return m.build();
	}

	public static final class Builder {
		private String templateId;
		private String engineId;
		private final Map<String, Object> data = new LinkedHashMap<>();
		private String idempotencyKey;
		private final List<Attachment> attachments = new ArrayList<>();

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

		/**
		 * Adds a file. Up to 5, each up to 5 MB and 15 MB together; checked when the email
		 * is sent, before anything is uploaded. The files go on the email itself, never on
		 * an auto-reply.
		 *
		 * @throws BloomsightException {@code INVALID_ATTACHMENT} when it is {@code null}
		 */
		public Builder attachment(Attachment attachment) {
			if (attachment == null) throw new BloomsightException("INVALID_ATTACHMENT", "an attachment must not be null");
			this.attachments.add(attachment);
			return this;
		}

		/**
		 * Adds files, in the collection's order, to any already added. A {@code null}
		 * collection adds nothing.
		 *
		 * @throws BloomsightException {@code INVALID_ATTACHMENT} when one of them is {@code null}
		 */
		public Builder attachments(Collection<Attachment> attachments) {
			if (attachments == null) return this;
			List<Attachment> all = new ArrayList<>(attachments);
			for (Attachment a : all) {
				if (a == null) throw new BloomsightException("INVALID_ATTACHMENT", "an attachment must not be null");
			}
			this.attachments.addAll(all);
			return this;
		}

		public SendMailRequest build() {
			return new SendMailRequest(this);
		}
	}
}
