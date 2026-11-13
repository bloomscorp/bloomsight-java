package com.bloomscorp.bloomsight;

import com.bloomscorp.bloomsight.FakeBlooms.Answer;
import com.bloomscorp.bloomsight.FakeBlooms.Seen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The same cases as bloomsight-node's tests, against a local fake: nothing leaves the machine. */
class BloomsightTest {

	static final String KEY = "blm_live_" + "a".repeat(64);
	static final String TEST_KEY = "blm_test_" + "b".repeat(64);

	FakeBlooms fake;
	final List<Long> waits = Collections.synchronizedList(new ArrayList<>());

	@BeforeEach
	void start() throws Exception {
		fake = new FakeBlooms();
		waits.clear();
	}

	@AfterEach
	void stop() {
		try {
			assertTrue(fake.malformed.isEmpty(), () -> "the fake got a malformed body: " + fake.malformed);
		} finally {
			fake.close();
		}
	}

	Bloomsight client() {
		return client(Bloomsight.builder());
	}

	Bloomsight client(Bloomsight.Builder builder) {
		Bloomsight c = builder.apiKey(KEY).baseUrl(fake.baseUrl()).build();
		c.setSleeper(waits::add);
		return c;
	}

	static SendMailRequest simple() {
		return SendMailRequest.builder().templateId("t").engineId("e").build();
	}

	static Map<String, Object> map(Object... pairs) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
		return m;
	}

	static Set<String> idempotencyKeys(List<Seen> seen) {
		Set<String> keys = new HashSet<>();
		for (Seen s : seen) keys.add(s.header("Idempotency-Key"));
		return keys;
	}

	@Nested
	class Construction {
		@Test
		void aMissingOrMalformedKeyIsRefusedWithoutEchoingIt() {
			BloomsightException empty = assertThrows(BloomsightException.class, () -> Bloomsight.builder().apiKey("").build());
			assertEquals("INVALID_API_KEY", empty.getCode());
			BloomsightException other = assertThrows(BloomsightException.class,
				() -> Bloomsight.builder().apiKey("sk_live_secret-value").build());
			assertEquals("INVALID_API_KEY", other.getCode());
			assertFalse(other.getMessage().contains("secret-value"));
			assertThrows(BloomsightException.class, () -> Bloomsight.builder().build());
		}

		@Test
		void itKnowsATestKey() {
			assertTrue(Bloomsight.builder().apiKey(TEST_KEY).build().isTestKey());
			assertFalse(Bloomsight.builder().apiKey(KEY).build().isTestKey());
		}

		@Test
		void toStringNeverShowsTheKey() {
			String s = Bloomsight.builder().apiKey(KEY).build().toString();
			assertFalse(s.contains(KEY));
			assertTrue(s.contains("live key"));
		}

		@Test
		void badSettingsAreRefused() {
			assertThrows(IllegalArgumentException.class, () -> Bloomsight.builder().apiKey(KEY).timeout(Duration.ZERO).build());
			assertThrows(IllegalArgumentException.class, () -> Bloomsight.builder().apiKey(KEY).maxRetries(-1).build());
		}
	}

	@Nested
	class SendMail {
		@Test
		void postsTheTemplateMailboxAndValuesWithTheKeyAndAnIdempotencyKey() {
			fake.answer(Answer.ok(map("id", "job1", "status", "queued", "missingVariables", Collections.emptyList())));
			Map<String, Object> data = map("name", "Asha", "lines", Arrays.asList(map("item", "Saree", "qty", 2)));
			Send send = client().sendMail(SendMailRequest.builder().templateId("t1").engineId("e1").data(data).build());

			assertEquals("job1", send.getId());
			assertEquals(SendStatus.QUEUED, send.getStatus());
			assertFalse(send.isReplayed());
			assertTrue(send.getMissingVariables().isEmpty());

			Seen r = fake.seen.get(0);
			assertEquals("POST", r.method);
			assertEquals("/api/v1/bloomsight/email/send", r.path);
			assertEquals("Bearer " + KEY, r.header("Authorization"));
			assertTrue(r.header("Idempotency-Key").matches("^[0-9a-f-]{36}$"));
			assertTrue(r.header("User-Agent").startsWith("bloomsight-java/"));
			assertNull(r.header("Origin"));
			assertEquals(map("templateId", "t1", "engineId", "e1",
				"data", map("name", "Asha", "lines", Arrays.asList(map("item", "Saree", "qty", 2L)))), r.body);
		}

		@Test
		void yourOwnIdempotencyKeyIsUsedAsGivenAndAReplayIsReported() {
			fake.answer(Answer.ok(map("id", "job1", "status", "sent"), "Idempotent-Replayed", "true"));
			Send send = client().sendMail(SendMailRequest.builder().templateId("t").engineId("e")
				.idempotencyKey("order-A-1042-confirmation").build());
			assertEquals("order-A-1042-confirmation", fake.seen.get(0).header("Idempotency-Key"));
			assertFalse(((Map<?, ?>) fake.seen.get(0).body).containsKey("idempotencyKey"));
			assertTrue(send.isReplayed());
			assertEquals(SendStatus.SENT, send.getStatus());
		}

		@Test
		void a503IsRetriedWithTheSameIdempotencyKeyThenSucceeds() {
			fake.answer(
				Answer.json(503, map("success", false, "message", "busy")),
				Answer.text(502, "Bad gateway"),
				Answer.ok(map("id", "job2", "status", "queued")));
			Send send = client().sendMail(simple());
			assertEquals("job2", send.getId());
			assertEquals(3, fake.seen.size());
			assertEquals(1, idempotencyKeys(fake.seen).size());
			assertEquals(2, waits.size());
			assertTrue(waits.get(0) >= 500);
			assertTrue(waits.get(1) >= 1000);
		}

		@Test
		void a429WaitsForRetryAfter() {
			fake.answer(
				Answer.json(429, map("success", false, "code", "RATE_LIMITED", "message", "slow down"), "Retry-After", "7"),
				Answer.ok(map("id", "j", "status", "queued")));
			client().sendMail(simple());
			assertEquals(Collections.singletonList(7000L), waits);
		}

		@Test
		void aRetryAfterLongerThanTheCapIsNotWaitedOutItThrowsWithRetryAfter() {
			fake.answer(Answer.json(429, map("success", false, "code", "MAILBOX_LIMIT", "message", "daily limit"),
				"Retry-After", "86400"));
			BloomsightException e = assertThrows(BloomsightException.class, () -> client().sendMail(simple()));
			assertEquals("MAILBOX_LIMIT", e.getCode());
			assertEquals(429, e.getStatus());
			assertEquals(86400L, e.getRetryAfter());
			assertTrue(e.getIdempotencyKey().matches("^[0-9a-f-]{36}$"));
			assertTrue(waits.isEmpty());
		}

		@Test
		void aDroppedConnectionIsRetriedThenReportedAsNetworkFailure() {
			fake.answer(Answer.CLOSE, Answer.CLOSE, Answer.CLOSE, Answer.CLOSE);
			BloomsightException e = assertThrows(BloomsightException.class, () -> client().sendMail(simple()));
			assertEquals("NETWORK_FAILURE", e.getCode());
			assertEquals(4, fake.seen.size());
			assertEquals(1, idempotencyKeys(fake.seen).size());
			assertFalse(e.getMessage().contains(KEY));
		}

		@Test
		void aSlowAnswerTimesOut() {
			fake.answer(Answer.HANG, Answer.HANG);
			BloomsightException e = assertThrows(BloomsightException.class,
				() -> client(Bloomsight.builder().timeout(Duration.ofMillis(200)).maxRetries(1)).sendMail(simple()));
			assertEquals("TIMEOUT", e.getCode());
			assertEquals(2, fake.seen.size());
		}

		@Test
		void a4xxIsNotRetriedAndItsCodeComesThrough() {
			fake.answer(Answer.json(422, map("success", false, "code", "INVALID_VARIABLES",
				"message", "'bad-name' is not a valid variable name")));
			BloomsightException e = assertThrows(BloomsightException.class,
				() -> client().sendMail(SendMailRequest.builder().templateId("t").engineId("e").value("bad-name", 1).build()));
			assertEquals("INVALID_VARIABLES", e.getCode());
			assertEquals(422, e.getStatus());
			assertEquals(1, fake.seen.size());
		}

		@Test
		void aResponseThatIsNotBloomsJsonIsBadResponse() {
			fake.answer(Answer.text(404, "<html>Not found</html>"));
			BloomsightException e = assertThrows(BloomsightException.class, () -> client().sendMail(simple()));
			assertEquals("BAD_RESPONSE", e.getCode());
			assertEquals(404, e.getStatus());
		}

		@Test
		void theKeyNeverAppearsInAnErrorEvenIfTheServerEchoesIt() {
			fake.answer(Answer.json(401, map("success", false, "code", "INVALID_API_KEY", "message", "Invalid key " + KEY)));
			BloomsightException e = assertThrows(BloomsightException.class, () -> client().sendMail(simple()));
			assertFalse(e.getMessage().contains(KEY));
			assertTrue(e.getMessage().contains("blm_***"));
			assertFalse(e.toString().contains(KEY));
		}

		@Test
		void aValueThatIsNotJsonIsRefusedBeforeAnythingIsSent() {
			BloomsightException e = assertThrows(BloomsightException.class,
				() -> client().sendMail(SendMailRequest.builder().templateId("t").engineId("e").value("when", Instant.now()).build()));
			assertEquals("INVALID_DATA", e.getCode());
			assertTrue(e.getMessage().contains("data.when"));
			assertTrue(fake.seen.isEmpty());
		}

		@Test
		void aNewerStatusDoesNotBreakTheClient() {
			fake.answer(Answer.ok(map("id", "j", "status", "scheduled", "someNewField", 1)));
			Send send = client().sendMail(simple());
			assertEquals(SendStatus.UNKNOWN_VALUE, send.getStatus());
			assertEquals("scheduled", send.getStatusText());
		}

		@Test
		void anInterruptedThreadStopsWaitingAndKeepsItsInterruptFlag() {
			fake.answer(Answer.json(503, map("success", false)));
			Bloomsight c = Bloomsight.builder().apiKey(KEY).baseUrl(fake.baseUrl()).build();
			c.setSleeper(ms -> { throw new InterruptedException(); });
			try {
				BloomsightException e = assertThrows(BloomsightException.class, () -> c.sendMail(simple()));
				assertEquals("INTERRUPTED", e.getCode());
				assertNotNull(e.getIdempotencyKey());
				assertTrue(Thread.currentThread().isInterrupted());
			} finally {
				Thread.interrupted();
			}
		}

		@Test
		void oneClientIsSafeToShareBetweenThreads() throws Exception {
			int n = 20;
			for (int i = 0; i < n; i++) fake.answer(Answer.ok(map("id", "j", "status", "queued")));
			Bloomsight c = client();
			AtomicInteger okCount = new AtomicInteger();
			List<Thread> threads = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				Thread t = new Thread(() -> { if (c.sendMail(simple()).getId() != null) okCount.incrementAndGet(); });
				threads.add(t);
				t.start();
			}
			for (Thread t : threads) t.join(10_000);
			assertEquals(n, okCount.get());
			assertEquals(n, idempotencyKeys(fake.seen).size());
		}
	}

	@Nested
	class BatchesSendsLimitsAndUnsubscribes {
		@Test
		void sendBatchPostsTheRowsWithAnIdempotencyKeyAndInvalidRowsCarriesTheFaults() {
			fake.answer(Answer.json(422, map("success", false, "code", "INVALID_ROWS", "message", "1 row(s) cannot be sent",
				"faults", Arrays.asList(map("row", 3, "code", "INVALID_ADDRESS", "message", "bad")))));
			BloomsightException e = assertThrows(BloomsightException.class, () -> client().sendBatch(SendBatchRequest.builder()
				.templateId("t").engineId("e").name("Nov").recipient(map("email", "a@x.test")).build()));
			Seen r = fake.seen.get(0);
			assertEquals("/api/v1/bloomsight/email/batches", r.path);
			assertNotNull(r.header("Idempotency-Key"));
			assertEquals(map("templateId", "t", "engineId", "e", "name", "Nov",
				"recipients", Arrays.asList(map("data", map("email", "a@x.test")))), r.body);
			assertEquals("INVALID_ROWS", e.getCode());
			Map<?, ?> fault = (Map<?, ?>) ((List<?>) e.getDetails().get("faults")).get(0);
			assertEquals(3L, fault.get("row"));
		}

		@Test
		void aBatchReadsItsProgress() {
			fake.answer(Answer.ok(map("id", "b1", "status", "running", "rows", 3, "accepted", 2, "suppressed", 1,
				"progress", map("queued", 1, "sent", 1), "expectedFinishAt", "2026-11-12T10:00:00.000Z")));
			Batch b = client().getBatch("b1");
			assertEquals(BatchStatus.RUNNING, b.getStatus());
			assertEquals(2, b.getAccepted());
			assertEquals(1, b.getSuppressed());
			assertEquals(1, b.getProgress().getSent());
			assertEquals(Instant.parse("2026-11-12T10:00:00Z"), b.getExpectedFinishAt());
		}

		@Test
		void theBatchControlsAndReadsHitTheirPaths() {
			Bloomsight c = client();
			fake.answer(Answer.ok(map("id", "b1")), Answer.ok(map("id", "b1")), Answer.ok(map("id", "b1")),
				Answer.ok(map("id", "b1")), Answer.ok(map("id", "j1")), Answer.ok(Collections.emptyList()));
			c.getBatch("b1");
			c.pauseBatch("b1");
			c.resumeBatch("b1");
			c.cancelBatch("b1");
			c.getSend("j1");
			c.getLimits();
			List<String> calls = new ArrayList<>();
			for (Seen s : fake.seen) calls.add(s.method + " " + s.path);
			assertEquals(Arrays.asList(
				"GET /api/v1/bloomsight/email/batches/b1",
				"POST /api/v1/bloomsight/email/batches/b1/pause",
				"POST /api/v1/bloomsight/email/batches/b1/resume",
				"POST /api/v1/bloomsight/email/batches/b1/cancel",
				"GET /api/v1/bloomsight/email/sends/j1",
				"GET /api/v1/bloomsight/email/limits"), calls);
		}

		@Test
		void limitsAreRead() {
			fake.answer(Answer.ok(Arrays.asList(map("engineId", "e1", "fromEmail", "hello@x.test",
				"limits", map("kind", "workspace", "perDay", 1800, "perMinute", null),
				"used", map("day", 12, "public", 3), "heldForMs", 0))));
			MailboxLimits l = client().getLimits().get(0);
			assertEquals("hello@x.test", l.getFromEmail());
			assertEquals(1800L, l.getLimits().getPerDay());
			assertNull(l.getLimits().getPerMinute());
			assertEquals(12, l.getUsed().getDay());
			assertEquals(3, l.getUsed().getPublic());
		}

		@Test
		void unsubscribesListWithPagingAddAndRemoveFalseWhenNotOnTheList() {
			Bloomsight c = client();
			fake.answer(
				Answer.ok(Arrays.asList(map("address", "a@x.test", "reason", "api", "createdAt", "2026-11-08T00:00:00Z"))),
				Answer.ok(map("added", true)),
				Answer.ok(map("removed", true)),
				Answer.json(404, map("success", false, "code", "NOT_ON_LIST", "message", "not on the list")));
			List<Unsubscribe> list = c.listUnsubscribes(10, Instant.parse("2026-11-09T00:00:00Z"));
			assertEquals("a@x.test", list.get(0).getAddress());
			assertEquals("/api/v1/bloomsight/email/unsubscribes?limit=10&before=2026-11-09T00%3A00%3A00Z", fake.seen.get(0).path);
			assertTrue(c.addUnsubscribe("a@x.test"));
			assertEquals(map("address", "a@x.test"), fake.seen.get(1).body);
			assertTrue(c.removeUnsubscribe("a+b@x.test"));
			assertEquals("/api/v1/bloomsight/email/unsubscribes/a%2Bb%40x.test", fake.seen.get(2).path);
			assertFalse(c.removeUnsubscribe("nobody@x.test"));
		}
	}
}
