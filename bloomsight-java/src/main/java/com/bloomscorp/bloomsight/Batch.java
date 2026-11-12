package com.bloomscorp.bloomsight;

import java.time.Instant;
import java.util.Map;

/** A batch: one template sent to many recipients. */
public final class Batch {

	private final String id;
	private final String name;
	private final String statusText;
	private final String templateId;
	private final String engineId;
	private final long rows;
	private final long accepted;
	private final long duplicates;
	private final long suppressed;
	private final Progress progress;
	private final Instant expectedFinishAt;
	private final Instant createdAt;
	private final Instant cancelledAt;
	private final boolean test;
	private final boolean replayed;

	Batch(Map<String, Object> m, boolean replayed) {
		this.id = Fields.str(m, "id");
		this.name = Fields.str(m, "name");
		this.statusText = Fields.str(m, "status");
		this.templateId = Fields.str(m, "templateId");
		this.engineId = Fields.str(m, "engineId");
		this.rows = Fields.lng(m, "rows", 0);
		this.accepted = Fields.lng(m, "accepted", 0);
		this.duplicates = Fields.lng(m, "duplicates", 0);
		this.suppressed = Fields.lng(m, "suppressed", 0);
		this.progress = m.get("progress") instanceof Map ? new Progress(Fields.map(m.get("progress"))) : null;
		this.expectedFinishAt = Fields.instant(m, "expectedFinishAt");
		this.createdAt = Fields.instant(m, "createdAt");
		this.cancelledAt = Fields.instant(m, "cancelledAt");
		this.test = Fields.bool(m, "test");
		this.replayed = replayed;
	}

	/** {@code null} for a test batch, which is checked and never stored. */
	public String getId() { return id; }

	public String getName() { return name; }

	public BatchStatus getStatus() { return BatchStatus.from(statusText); }

	/** The status exactly as the server sent it. */
	public String getStatusText() { return statusText; }

	public String getTemplateId() { return templateId; }

	public String getEngineId() { return engineId; }

	/** Rows received. */
	public long getRows() { return rows; }

	/** Rows queued to send. */
	public long getAccepted() { return accepted; }

	/** Rows dropped because the same recipient appeared earlier in the batch. */
	public long getDuplicates() { return duplicates; }

	/** Rows dropped because the recipient is on the unsubscribe list. */
	public long getSuppressed() { return suppressed; }

	/** Counts per status; {@code null} when the server did not include them. */
	public Progress getProgress() { return progress; }

	/** When the mailbox's budget should let the last email go. */
	public Instant getExpectedFinishAt() { return expectedFinishAt; }

	public Instant getCreatedAt() { return createdAt; }

	public Instant getCancelledAt() { return cancelledAt; }

	/** True when sent with a {@code blm_test_...} key: checked, nothing sent. */
	public boolean isTest() { return test; }

	/** True when the Idempotency-Key matched an earlier batch and nothing new was queued. */
	public boolean isReplayed() { return replayed; }

	@Override
	public String toString() {
		return "Batch[" + id + ", " + statusText + ", " + accepted + "/" + rows + (replayed ? ", replayed" : "") + "]";
	}

	/** How many of a batch's emails are in each status. */
	public static final class Progress {
		private final long queued;
		private final long paused;
		private final long sending;
		private final long sent;
		private final long failed;
		private final long unknown;
		private final long cancelled;

		Progress(Map<String, Object> m) {
			this.queued = Fields.lng(m, "queued", 0);
			this.paused = Fields.lng(m, "paused", 0);
			this.sending = Fields.lng(m, "sending", 0);
			this.sent = Fields.lng(m, "sent", 0);
			this.failed = Fields.lng(m, "failed", 0);
			this.unknown = Fields.lng(m, "unknown", 0);
			this.cancelled = Fields.lng(m, "cancelled", 0);
		}

		public long getQueued() { return queued; }

		public long getPaused() { return paused; }

		public long getSending() { return sending; }

		public long getSent() { return sent; }

		public long getFailed() { return failed; }

		public long getUnknown() { return unknown; }

		public long getCancelled() { return cancelled; }
	}
}
