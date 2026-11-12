package com.bloomscorp.bloomsight;

import java.util.Map;

/**
 * One mailbox's sending budget and today's use. A {@code null} limit means not limited.
 * Bloomsight keeps every mailbox under its provider's limits, so a send never gets the
 * mailbox blocked.
 */
public final class MailboxLimits {

	private final String engineId;
	private final String name;
	private final String fromEmail;
	private final Limits limits;
	private final Used used;
	private final long heldForMs;

	MailboxLimits(Map<String, Object> m) {
		this.engineId = Fields.str(m, "engineId");
		this.name = Fields.str(m, "name");
		this.fromEmail = Fields.str(m, "fromEmail");
		this.limits = new Limits(Fields.map(m.get("limits")));
		this.used = new Used(Fields.map(m.get("used")));
		this.heldForMs = Fields.lng(m, "heldForMs", 0);
	}

	/** The mailbox: what {@code engineId} means in a send. */
	public String getEngineId() { return engineId; }

	public String getName() { return name; }

	public String getFromEmail() { return fromEmail; }

	public Limits getLimits() { return limits; }

	public Used getUsed() { return used; }

	/** Above zero while the provider has asked for a pause: sends wait, nothing is lost. */
	public long getHeldForMs() { return heldForMs; }

	/** The budget. Each value is {@code null} when not limited. */
	public static final class Limits {
		private final String kind;
		private final Long perMinute;
		private final Long perDay;
		private final Long recipientsPerDay;
		private final Long uniqueRecipientsPerDay;
		private final Long publicPerDay;
		private final Long bulkPerDay;

		Limits(Map<String, Object> m) {
			this.kind = Fields.str(m, "kind");
			this.perMinute = Fields.lng(m, "perMinute");
			this.perDay = Fields.lng(m, "perDay");
			this.recipientsPerDay = Fields.lng(m, "recipientsPerDay");
			this.uniqueRecipientsPerDay = Fields.lng(m, "uniqueRecipientsPerDay");
			this.publicPerDay = Fields.lng(m, "publicPerDay");
			this.bulkPerDay = Fields.lng(m, "bulkPerDay");
		}

		/** {@code workspace}, {@code consumer} or {@code smtp}. */
		public String getKind() { return kind; }

		public Long getPerMinute() { return perMinute; }

		public Long getPerDay() { return perDay; }

		public Long getRecipientsPerDay() { return recipientsPerDay; }

		public Long getUniqueRecipientsPerDay() { return uniqueRecipientsPerDay; }

		/** The part of the day kept for website forms. */
		public Long getPublicPerDay() { return publicPerDay; }

		/** The part of the day batches may use. */
		public Long getBulkPerDay() { return bulkPerDay; }
	}

	/** Today's use so far. */
	public static final class Used {
		private final long minute;
		private final long day;
		private final long recipients;
		private final long unique;
		private final long publicSends;
		private final long bulk;

		Used(Map<String, Object> m) {
			this.minute = Fields.lng(m, "minute", 0);
			this.day = Fields.lng(m, "day", 0);
			this.recipients = Fields.lng(m, "recipients", 0);
			this.unique = Fields.lng(m, "unique", 0);
			this.publicSends = Fields.lng(m, "public", 0);
			this.bulk = Fields.lng(m, "bulk", 0);
		}

		public long getMinute() { return minute; }

		public long getDay() { return day; }

		public long getRecipients() { return recipients; }

		public long getUnique() { return unique; }

		/** Sends from website forms today. */
		public long getPublic() { return publicSends; }

		public long getBulk() { return bulk; }
	}
}
