package com.bloomscorp.bloomsight;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** One email: what {@link Bloomsight#sendMail} returns and {@link Bloomsight#getSend} reads. */
public final class Send {

	private final String id;
	private final String statusText;
	private final String templateId;
	private final String engineId;
	private final String priority;
	private final Integer attempts;
	private final Integer recipientCount;
	private final Instant nextAttemptAt;
	private final String errorCode;
	private final String error;
	private final Instant createdAt;
	private final Instant finishedAt;
	private final boolean test;
	private final List<String> missingVariables;
	private final boolean replayed;

	Send(Map<String, Object> m, boolean replayed) {
		this.id = Fields.str(m, "id");
		this.statusText = Fields.str(m, "status");
		this.templateId = Fields.str(m, "templateId");
		this.engineId = Fields.str(m, "engineId");
		this.priority = Fields.str(m, "priority");
		this.attempts = Fields.integer(m, "attempts");
		this.recipientCount = Fields.integer(m, "recipientCount");
		this.nextAttemptAt = Fields.instant(m, "nextAttemptAt");
		this.errorCode = Fields.str(m, "errorCode");
		this.error = Fields.str(m, "error");
		this.createdAt = Fields.instant(m, "createdAt");
		this.finishedAt = Fields.instant(m, "finishedAt");
		this.test = Fields.bool(m, "test");
		this.missingVariables = Fields.strings(m, "missingVariables");
		this.replayed = replayed;
	}

	/** Pass to {@link Bloomsight#getSend} to follow it. */
	public String getId() { return id; }

	public SendStatus getStatus() { return SendStatus.from(statusText); }

	/** The status exactly as the server sent it. */
	public String getStatusText() { return statusText; }

	public String getTemplateId() { return templateId; }

	public String getEngineId() { return engineId; }

	/** {@code transactional} or {@code bulk}. */
	public String getPriority() { return priority; }

	public Integer getAttempts() { return attempts; }

	public Integer getRecipientCount() { return recipientCount; }

	/** When a waiting send will be tried next, e.g. when the mailbox's budget frees. */
	public Instant getNextAttemptAt() { return nextAttemptAt; }

	public String getErrorCode() { return errorCode; }

	public String getError() { return error; }

	public Instant getCreatedAt() { return createdAt; }

	public Instant getFinishedAt() { return finishedAt; }

	/** True when sent with a {@code blm_test_...} key: checked, nothing sent. */
	public boolean isTest() { return test; }

	/** Values the template uses that this send did not supply. */
	public List<String> getMissingVariables() { return missingVariables; }

	/** True when the Idempotency-Key matched an earlier send and nothing new was queued. */
	public boolean isReplayed() { return replayed; }

	@Override
	public String toString() {
		return "Send[" + id + ", " + statusText + (replayed ? ", replayed" : "") + "]";
	}
}
