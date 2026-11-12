package com.bloomscorp.bloomsight;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One template sent to many recipients (1 to 5,000 rows). The whole batch is checked
 * first: one bad row refuses all of it, with every fault listed in the error's
 * {@code faults} detail. Addresses on the unsubscribe list are skipped.
 *
 * <pre>{@code
 * SendBatchRequest.builder()
 *     .templateId(templateId)
 *     .engineId(engineId)
 *     .name("November offer")
 *     .recipient(Map.of("name", "Asha", "email", "asha@example.com"))
 *     .recipient(Map.of("name", "Ravi", "email", "ravi@example.com"))
 *     .build();
 * }</pre>
 */
public final class SendBatchRequest {

	private final String templateId;
	private final String engineId;
	private final String name;
	private final List<Map<String, Object>> recipients;
	private final String idempotencyKey;

	private SendBatchRequest(Builder b) {
		this.templateId = b.templateId;
		this.engineId = b.engineId;
		this.name = b.name;
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map<String, Object> row : b.recipients) rows.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
		this.recipients = Collections.unmodifiableList(rows);
		this.idempotencyKey = b.idempotencyKey;
	}

	public static Builder builder() {
		return new Builder();
	}

	public String getTemplateId() { return templateId; }

	public String getEngineId() { return engineId; }

	public String getName() { return name; }

	/** Each recipient's template values. */
	public List<Map<String, Object>> getRecipients() { return recipients; }

	public String getIdempotencyKey() { return idempotencyKey; }

	Map<String, Object> body() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("templateId", templateId);
		body.put("engineId", engineId);
		if (name != null) body.put("name", name);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map<String, Object> data : recipients) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("data", data);
			rows.add(row);
		}
		body.put("recipients", rows);
		return body;
	}

	public static final class Builder {
		private String templateId;
		private String engineId;
		private String name;
		private final List<Map<String, Object>> recipients = new ArrayList<>();
		private String idempotencyKey;

		private Builder() {
		}

		public Builder templateId(String templateId) {
			this.templateId = templateId;
			return this;
		}

		public Builder engineId(String engineId) {
			this.engineId = engineId;
			return this;
		}

		/** Shown in the dashboard, e.g. "November offer". */
		public Builder name(String name) {
			this.name = name;
			return this;
		}

		/** One recipient's template values, including their address if the template takes it from the values. */
		public Builder recipient(Map<String, ?> data) {
			this.recipients.add(new LinkedHashMap<>(data == null ? Collections.<String, Object>emptyMap() : data));
			return this;
		}

		/** Adds every row, in order. */
		public Builder recipients(Collection<? extends Map<String, ?>> rows) {
			if (rows != null) for (Map<String, ?> row : rows) recipient(row);
			return this;
		}

		/** As for {@link SendMailRequest.Builder#idempotencyKey}: one is generated when not set. */
		public Builder idempotencyKey(String idempotencyKey) {
			this.idempotencyKey = idempotencyKey;
			return this;
		}

		public SendBatchRequest build() {
			return new SendBatchRequest(this);
		}
	}
}
