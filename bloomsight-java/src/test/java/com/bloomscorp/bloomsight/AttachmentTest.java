package com.bloomscorp.bloomsight;

import com.bloomscorp.bloomsight.FakeBlooms.Answer;
import com.bloomscorp.bloomsight.FakeBlooms.Part;
import com.bloomscorp.bloomsight.FakeBlooms.Seen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sends with attachments (backend-sending.md §8.1), against a local fake: nothing leaves the machine. */
class AttachmentTest {

	static final String KEY = "blm_live_" + "a".repeat(64);
	static final String TEST_KEY = "blm_test_" + "b".repeat(64);
	static final int MB = 1024 * 1024;

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

	static Map<String, Object> map(Object... pairs) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
		return m;
	}

	/** Every byte value, so nothing is lost to text encoding. */
	static byte[] allBytes(int length) {
		byte[] b = new byte[length];
		for (int i = 0; i < length; i++) b[i] = (byte) i;
		return b;
	}

	static SendMailRequest.Builder mail() {
		return SendMailRequest.builder().templateId("t1").engineId("e1");
	}

	static Map<String, Object> sentJob(int files) {
		return map("id", "job1", "status", "sent", "attachmentCount", files, "test", false,
			"missingVariables", Collections.emptyList());
	}

	/** The call must throw this code and nothing may have reached the fake. */
	void refused(String code, Executable call) {
		BloomsightException e = assertThrows(BloomsightException.class, call);
		assertEquals(code, e.getCode(), e.getMessage());
		assertEquals(0, fake.seen.size(), "a request was made");
	}

	@Nested
	class TheRequest {
		@Test
		void isMultipartWithTheFieldsTheValuesAsJsonAndEachFileExactly() {
			fake.answer(Answer.sent(sentJob(2)));
			byte[] pdf = allBytes(70_000);
			byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0};
			Map<String, Object> data = map("name", "Asha", "total", 1250.5, "paid", true, "note", null,
				"lines", Arrays.asList(map("item", "Saree", "qty", 2)));
			Send send = client().sendMail(mail().data(data)
				.attachment(Attachment.of("invoice-A-1042.pdf", pdf))
				.attachment(Attachment.of("logo.png", png))
				.idempotencyKey("order-A-1042-invoice")
				.build());

			assertEquals("job1", send.getId());
			assertEquals(SendStatus.SENT, send.getStatus());
			assertEquals(2, send.getAttachmentCount());
			assertFalse(send.isTest());

			assertEquals(1, fake.seen.size());
			Seen r = fake.seen.get(0);
			assertEquals("POST", r.method);
			assertEquals("/api/v1/bloomsight/email/send", r.path);
			assertTrue(r.header("Content-Type").matches("^multipart/form-data; boundary=[A-Za-z0-9-]+$"), r.header("Content-Type"));
			assertEquals("Bearer " + KEY, r.header("Authorization"));
			assertEquals("application/json", r.header("Accept"));
			assertEquals("bloomsight-java/" + Bloomsight.VERSION, r.header("User-Agent"));
			assertEquals("order-A-1042-invoice", r.header("Idempotency-Key"));
			assertNull(r.body);

			assertEquals(Arrays.asList("templateId", "engineId", "data", "attachments", "attachments"), r.form.names());
			assertEquals("t1", r.form.field("templateId"));
			assertEquals("e1", r.form.field("engineId"));
			assertEquals(map("name", "Asha", "total", 1250.5, "paid", true, "note", null,
				"lines", Arrays.asList(map("item", "Saree", "qty", 2L))), Json.parse(r.form.field("data")));
			for (Part field : r.form.parts.subList(0, 3)) {
				assertEquals(Collections.singleton("content-disposition"), field.headers.keySet());
			}

			List<Part> files = r.form.files();
			assertEquals("invoice-A-1042.pdf", files.get(0).filename);
			assertEquals("application/pdf", files.get(0).contentType());
			assertArrayEquals(pdf, files.get(0).content);
			assertEquals("logo.png", files.get(1).filename);
			assertEquals("image/png", files.get(1).contentType());
			assertArrayEquals(png, files.get(1).content);
		}

		@Test
		void withNoValuesThereIsNoDataPart() {
			fake.answer(Answer.sent(sentJob(1)));
			client().sendMail(mail().attachment(Attachment.of("a.txt", "hello".getBytes(StandardCharsets.UTF_8))).build());
			assertEquals(Arrays.asList("templateId", "engineId", "attachments"), fake.seen.get(0).form.names());
		}

		@Test
		void aSendWithoutAttachmentsIsStillJson() {
			fake.answer(Answer.ok(map("id", "j", "status", "queued")));
			Send send = client().sendMail(mail().value("name", "Asha").attachments(Collections.emptyList()).attachments(null).build());
			Seen r = fake.seen.get(0);
			assertEquals("application/json", r.header("Content-Type"));
			assertNull(r.form);
			assertEquals(map("templateId", "t1", "engineId", "e1", "data", map("name", "Asha")), r.body);
			assertNull(send.getAttachmentCount());
			assertEquals(SendStatus.QUEUED, send.getStatus());
		}

		@Test
		void aNonAsciiNameTravelsAsUtf8() {
			fake.answer(Answer.sent(sentJob(1)));
			client().sendMail(mail().attachment(Attachment.of("চালান নভেম্বর.pdf", new byte[]{1})).build());
			assertEquals("চালান নভেম্বর.pdf", fake.seen.get(0).form.files().get(0).filename);
		}

		@Test
		void aBadValueIsInvalidDataBeforeAnythingIsSent() {
			refused("INVALID_DATA", () -> client().sendMail(mail().value("when", Instant.now())
				.attachment(Attachment.of("a.pdf", new byte[]{1})).build()));
		}

		@Test
		void aBatchIsUnaffected() {
			fake.answer(Answer.ok(map("id", "b1", "status", "running")));
			client().sendBatch(SendBatchRequest.builder().templateId("t").engineId("e").name("Nov")
				.recipient(map("email", "a@x.test")).build());
			Seen r = fake.seen.get(0);
			assertEquals("/api/v1/bloomsight/email/batches", r.path);
			assertEquals("application/json", r.header("Content-Type"));
			assertEquals(map("templateId", "t", "engineId", "e", "name", "Nov",
				"recipients", Arrays.asList(map("data", map("email", "a@x.test")))), r.body);
		}
	}

	@Nested
	class Files_ {
		@Test
		void fromPathReadsTheFileNowAndNamesItAfterTheFile(@TempDir Path dir) throws Exception {
			Path file = dir.resolve("November Report.xlsx");
			byte[] bytes = allBytes(3000);
			Files.write(file, bytes);
			Attachment a = Attachment.fromPath(file);
			Files.delete(file);   // read already: deleting it changes nothing
			assertEquals("November Report.xlsx", a.getFilename());
			assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", a.getContentType());
			assertEquals(3000, a.getSize());

			fake.answer(Answer.sent(sentJob(1)));
			client().sendMail(mail().attachment(a).build());
			Part sent = fake.seen.get(0).form.files().get(0);
			assertEquals("November Report.xlsx", sent.filename);
			assertArrayEquals(bytes, sent.content);
		}

		@Test
		void fromPathTakesAContentType(@TempDir Path dir) throws Exception {
			Path file = dir.resolve("data.bin");
			Files.write(file, new byte[]{1, 2});
			assertEquals("application/x-custom", Attachment.fromPath(file, "application/x-custom").getContentType());
		}

		@Test
		void aFileThatCannotBeReadIsInvalidAttachmentNamingTheFileNotItsPath(@TempDir Path dir) {
			Path missing = dir.resolve("secret-folder").resolve("missing.pdf");
			BloomsightException e = assertThrows(BloomsightException.class, () -> Attachment.fromPath(missing));
			assertEquals("INVALID_ATTACHMENT", e.getCode());
			assertTrue(e.getMessage().contains("missing.pdf"), e.getMessage());
			assertFalse(e.getMessage().contains("secret-folder"), e.getMessage());
			assertFalse(e.toString().contains(dir.toString()));
			assertNull(e.getCause());

			BloomsightException folder = assertThrows(BloomsightException.class, () -> Attachment.fromPath(dir));
			assertEquals("INVALID_ATTACHMENT", folder.getCode());
			assertFalse(folder.getMessage().contains(dir.getParent().toString()), folder.getMessage());
			assertEquals("INVALID_ATTACHMENT", assertThrows(BloomsightException.class, () -> Attachment.fromPath(null)).getCode());
		}

		@Test
		void fromPathRefusesAFileOverTheLimitWithoutReadingIt(@TempDir Path dir) throws Exception {
			Path big = dir.resolve("big.pdf");
			Files.write(big, new byte[5 * MB + 1]);
			assertEquals("ATTACHMENT_TOO_LARGE", assertThrows(BloomsightException.class, () -> Attachment.fromPath(big)).getCode());
		}

		@Test
		void theContentTypeIsGuessedFromTheName() {
			String[][] cases = {
				{"a.pdf", "application/pdf"}, {"a.png", "image/png"}, {"a.jpg", "image/jpeg"},
				{"SCAN.JPEG", "image/jpeg"}, {"a.gif", "image/gif"}, {"a.webp", "image/webp"},
				{"a.svg", "image/svg+xml"}, {"a.csv", "text/csv"}, {"a.txt", "text/plain"},
				{"a.html", "text/html"}, {"invite.ics", "text/calendar"}, {"a.json", "application/json"},
				{"a.xml", "application/xml"}, {"a.zip", "application/zip"}, {"a.doc", "application/msword"},
				{"a.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
				{"a.xls", "application/vnd.ms-excel"},
				{"a.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
				{"a.ppt", "application/vnd.ms-powerpoint"},
				{"a.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"},
				{"a.odt", "application/vnd.oasis.opendocument.text"},
				{"a.ods", "application/vnd.oasis.opendocument.spreadsheet"}, {"a.rtf", "application/rtf"},
				{"a.mp3", "audio/mpeg"}, {"a.mp4", "video/mp4"},
				{"a.bin", "application/octet-stream"}, {"README", "application/octet-stream"},
				{"archive.tar.gz", "application/octet-stream"}, {"photo.png.pdf", "application/pdf"},
			};
			for (String[] c : cases) assertEquals(c[1], Attachment.of(c[0], new byte[]{1}).getContentType(), c[0]);
			assertEquals("application/x-mine", Attachment.of("a.pdf", new byte[]{1}, "application/x-mine").getContentType());
			assertEquals("application/pdf", Attachment.of("a.pdf", new byte[]{1}, "  ").getContentType());
		}

		@Test
		void theGivenContentTypeIsSentAsGiven() {
			fake.answer(Answer.sent(sentJob(1)));
			client().sendMail(mail().attachment(Attachment.of("a.dat", new byte[]{1}, "text/plain; charset=utf-8")).build());
			assertEquals("text/plain; charset=utf-8", fake.seen.get(0).form.files().get(0).contentType());
		}

		@Test
		void theBytesAreCopiedInAndOut() {
			byte[] mine = {1, 2, 3};
			Attachment a = Attachment.of("a.pdf", mine);
			mine[0] = 99;
			assertArrayEquals(new byte[]{1, 2, 3}, a.getContent());
			a.getContent()[1] = 99;
			assertArrayEquals(new byte[]{1, 2, 3}, a.getContent());

			fake.answer(Answer.sent(sentJob(1)));
			client().sendMail(mail().attachment(a).build());
			assertArrayEquals(new byte[]{1, 2, 3}, fake.seen.get(0).form.files().get(0).content);
		}

		@Test
		void nullsAreRefusedAtOnceAndTheListCannotBeChanged() {
			assertEquals("INVALID_ATTACHMENT", assertThrows(BloomsightException.class, () -> Attachment.of(null, new byte[]{1})).getCode());
			assertEquals("INVALID_ATTACHMENT", assertThrows(BloomsightException.class, () -> Attachment.of("a.pdf", null)).getCode());
			assertEquals("INVALID_ATTACHMENT", assertThrows(BloomsightException.class, () -> mail().attachment(null)).getCode());
			SendMailRequest.Builder b = mail().attachment(Attachment.of("a.pdf", new byte[]{1}));
			assertEquals("INVALID_ATTACHMENT", assertThrows(BloomsightException.class,
				() -> b.attachments(Arrays.asList(Attachment.of("b.pdf", new byte[]{1}), null))).getCode());
			SendMailRequest r = b.build();
			assertEquals(1, r.getAttachments().size(), "a refused list adds nothing");
			assertThrows(UnsupportedOperationException.class, () -> r.getAttachments().add(Attachment.of("c.pdf", new byte[]{1})));
		}
	}

	@Nested
	class TheLimits {
		Attachment file(String name, int size) {
			return Attachment.of(name, new byte[size]);
		}

		@Test
		void moreThanFiveFilesIsTooMany() {
			List<Attachment> six = new ArrayList<>();
			for (int i = 0; i < 6; i++) six.add(file("f" + i + ".pdf", 1));
			refused("TOO_MANY_ATTACHMENTS", () -> client().sendMail(mail().attachments(six).build()));
		}

		@Test
		void fiveFilesAreAllowed() {
			fake.answer(Answer.sent(sentJob(5)));
			List<Attachment> five = new ArrayList<>();
			for (int i = 0; i < 5; i++) five.add(file("f" + i + ".pdf", 1));
			client().sendMail(mail().attachments(five).build());
			assertEquals(5, fake.seen.get(0).form.files().size());
		}

		@Test
		void oneFileOverFiveMbIsTooLargeAndExactlyFiveMbIsAllowed() {
			refused("ATTACHMENT_TOO_LARGE", () -> client().sendMail(mail().attachment(file("big.pdf", 5 * MB + 1)).build()));
			fake.answer(Answer.sent(sentJob(1)));
			client().sendMail(mail().attachment(file("five.pdf", 5 * MB)).build());
			assertEquals(5 * MB, fake.seen.get(0).form.files().get(0).content.length);
		}

		@Test
		void anEmptyFileIsInvalid() {
			refused("INVALID_ATTACHMENT", () -> client().sendMail(mail().attachment(file("empty.pdf", 0)).build()));
		}

		@Test
		void overFifteenMbTogetherIsTooLargeAndExactlyFifteenIsAllowed() {
			refused("ATTACHMENTS_TOO_LARGE", () -> client().sendMail(mail()
				.attachment(file("a.pdf", 5 * MB)).attachment(file("b.pdf", 5 * MB))
				.attachment(file("c.pdf", 5 * MB)).attachment(file("d.pdf", 1)).build()));
			fake.answer(Answer.sent(sentJob(3)));
			client().sendMail(mail()
				.attachment(file("a.pdf", 5 * MB)).attachment(file("b.pdf", 5 * MB)).attachment(file("c.pdf", 5 * MB)).build());
			assertEquals(1, fake.seen.size());
		}

		@Test
		void aNameIsOneTo150CharactersAfterCleaning() {
			for (String bad : new String[]{"", "   ", "\"\"", "../", "C:\\\\", "\u0000\u001f\u007f", "\u00a0\u3000"}) {
				refused("INVALID_ATTACHMENT", () -> client().sendMail(mail().attachment(file(bad, 1)).build()));
			}
			refused("INVALID_ATTACHMENT", () -> client().sendMail(mail().attachment(file("a".repeat(147) + ".pdf", 1)).build()));
			fake.answer(Answer.sent(sentJob(1)));
			client().sendMail(mail().attachment(file("dir/" + "a".repeat(146) + ".pdf", 1)).build());
			assertEquals(150, fake.seen.get(0).form.files().get(0).filename.length());
		}

		@Test
		void aBlockedTypeIsRefusedByItsLastExtension() {
			for (String bad : new String[]{"x.pdf.exe", "RUN.BAT", "app.Js", "tool.jar", "setup.msi", "x.ex_",
				"invoice.exe.", "invoice.exe . ", "a.PS1", ".exe"}) {
				refused("ATTACHMENT_TYPE_BLOCKED", () -> client().sendMail(mail().attachment(file(bad, 1)).build()));
			}
			fake.answer(Answer.sent(sentJob(1)));
			client().sendMail(mail().attachment(file("exe.pdf", 1)).build());
			assertEquals("exe.pdf", fake.seen.get(0).form.files().get(0).filename);
		}

		@Test
		void theBlockedListIsExactlyTheServers() {
			Set<String> server = new HashSet<>(Arrays.asList(("ade adp apk appx appxbundle bat cab chm cmd com cpl diagcab "
				+ "diagcfg diagpack dll dmg ex ex_ exe hta img ins iso isp jar jnlp js jse lib lnk mde mjs msc msi msix "
				+ "msixbundle msp mst nsh pif ps1 scr sct shb sys vb vbe vbs vhd vxd wsc wsf wsh xll").split(" ")));
			assertEquals(server, Attachments.BLOCKED_EXTENSIONS);
			for (String ext : server) {
				assertEquals("ATTACHMENT_TYPE_BLOCKED", assertThrows(BloomsightException.class,
					() -> Attachments.check(Collections.singletonList(Attachment.of("file." + ext.toUpperCase(), new byte[]{1})))).getCode(), ext);
			}
		}

		@Test
		void aContentTypeThatCouldBreakTheHeaderIsRefused() {
			refused("INVALID_ATTACHMENT", () -> client().sendMail(mail()
				.attachment(Attachment.of("a.pdf", new byte[]{1}, "application/pdf\r\nX-Evil: 1")).build()));
			refused("INVALID_ATTACHMENT", () -> client().sendMail(mail()
				.attachment(Attachment.of("a.pdf", new byte[]{1}, "pdf")).build()));
		}

		@Test
		void aTestKeyChecksTheFilesToo() {
			Bloomsight c = Bloomsight.builder().apiKey(TEST_KEY).baseUrl(fake.baseUrl()).build();
			refused("ATTACHMENT_TYPE_BLOCKED", () -> c.sendMail(mail().attachment(file("x.exe", 1)).build()));
			fake.answer(Answer.sent(map("id", "j", "status", "test", "test", true, "attachmentCount", 1)));
			Send send = c.sendMail(mail().attachment(file("x.pdf", 1)).build());
			assertEquals(SendStatus.TEST, send.getStatus());
			assertTrue(send.isTest());
		}
	}

	@Nested
	class Names {
		@Test
		void aPathIsCutToTheFileName() {
			assertEquals("passwd", Attachment.of("../../etc/passwd", new byte[]{1}).getFilename());
			assertEquals("invoice.pdf", Attachment.of("C:\\Users\\asha\\invoice.pdf", new byte[]{1}).getFilename());
			assertEquals("b.pdf", Attachment.of("a\\..//b.pdf", new byte[]{1}).getFilename());
		}

		@Test
		void quotesAndControlCharactersAreRemovedAndTheNameTrimmed() {
			assertEquals("my invoice.pdf", Attachment.of("my \"invoice\".pdf", new byte[]{1}).getFilename());
			assertEquals("ab.pdf", Attachment.of("a\u0000\u0007\u001f\u007fb.pdf", new byte[]{1}).getFilename());
			assertEquals("a b.pdf", Attachment.of(" \u00a0\u3000a b.pdf\ufeff\t ", new byte[]{1}).getFilename());
		}

		@Test
		void crAndLfCannotAddAHeaderOrAPart() {
			fake.answer(Answer.sent(sentJob(1)));
			String evil = "a\r\nContent-Type: text/html\r\n\r\n<script>\r\n--x\"; name=\"templateId.pdf";
			client().sendMail(mail().attachment(Attachment.of(evil, new byte[]{7})).build());
			Seen r = fake.seen.get(0);
			assertEquals(Arrays.asList("templateId", "engineId", "attachments"), r.form.names());
			Part p = r.form.files().get(0);
			assertEquals("html<script>--x; name=templateId.pdf", p.filename);   // "/" cut the path
			assertEquals("application/pdf", p.contentType());
			assertEquals(new HashSet<>(Arrays.asList("content-disposition", "content-type")), p.headers.keySet());
			assertArrayEquals(new byte[]{7}, p.content);
			assertEquals("t1", r.form.field("templateId"));
		}

		@Test
		void theHeaderQuotingEscapesWhatCleaningWouldHaveRemoved() {
			assertEquals("a%22b%0D%0Ac", Multipart.quote("a\"b\r\nc"));
		}
	}

	@Nested
	class TheAnswer {
		@Test
		void serverBusyIsRetriedWithTheSameKeyAndTheSameBytes() {
			fake.answer(
				Answer.json(503, map("success", false, "code", "SERVER_BUSY", "message", "busy"), "Retry-After", "5"),
				Answer.json(503, map("success", false, "code", "MAILBOX_UNREACHABLE", "message", "later"), "Retry-After", "30"),
				Answer.sent(sentJob(1)));
			Send send = client().sendMail(mail().value("name", "Asha").attachment(Attachment.of("a.pdf", allBytes(5000))).build());
			assertEquals(SendStatus.SENT, send.getStatus());
			assertEquals(3, fake.seen.size());
			assertEquals(Arrays.asList(5000L, 30000L), waits);
			Seen first = fake.seen.get(0);
			for (Seen again : fake.seen) {
				assertEquals(first.header("Idempotency-Key"), again.header("Idempotency-Key"));
				assertEquals(first.header("Content-Type"), again.header("Content-Type"));
				assertArrayEquals(first.raw, again.raw);
			}
		}

		@Test
		void aMailboxLimitBeyondTheWaitCapIsThrownWithRetryAfter() {
			fake.answer(Answer.json(429, map("success", false, "code", "MAILBOX_LIMIT", "message", "daily limit"),
				"Retry-After", "3600"));
			BloomsightException e = assertThrows(BloomsightException.class,
				() -> client().sendMail(mail().attachment(Attachment.of("a.pdf", new byte[]{1})).idempotencyKey("k1").build()));
			assertEquals("MAILBOX_LIMIT", e.getCode());
			assertEquals(3600L, e.getRetryAfter());
			assertEquals("k1", e.getIdempotencyKey());
			assertEquals(1, fake.seen.size());
		}

		@Test
		void unknownFailedAndSendingAreReturnedNotThrown() {
			fake.answer(
				Answer.sent(map("id", "j1", "status", "unknown", "attachmentCount", 1, "errorCode", "CONNECTION_DROPPED")),
				Answer.sent(map("id", "j2", "status", "failed", "attachmentCount", 1, "error", "address refused")),
				Answer.sent(map("id", "j3", "status", "sending", "attachmentCount", 1), "Idempotent-Replayed", "true"));
			Bloomsight c = client();
			Send unknown = c.sendMail(mail().attachment(Attachment.of("a.pdf", new byte[]{1})).build());
			assertEquals(SendStatus.UNKNOWN, unknown.getStatus());
			assertEquals("CONNECTION_DROPPED", unknown.getErrorCode());
			Send failed = c.sendMail(mail().attachment(Attachment.of("a.pdf", new byte[]{1})).build());
			assertEquals(SendStatus.FAILED, failed.getStatus());
			assertEquals("address refused", failed.getError());
			Send sending = c.sendMail(mail().attachment(Attachment.of("a.pdf", new byte[]{1})).build());
			assertEquals(SendStatus.SENDING, sending.getStatus());
			assertTrue(sending.isReplayed());
			assertEquals(3, fake.seen.size());
		}

		@Test
		void aRefusalIsNotRetried() {
			fake.answer(Answer.json(422, map("success", false, "code", "ATTACHMENT_TYPE_BLOCKED", "message", "no")));
			BloomsightException e = assertThrows(BloomsightException.class,
				() -> client().sendMail(mail().attachment(Attachment.of("a.pdf", new byte[]{1})).build()));
			assertEquals("ATTACHMENT_TYPE_BLOCKED", e.getCode());
			assertEquals(1, fake.seen.size());
		}
	}

	@Nested
	class Timeouts {
		Send withFile(Bloomsight c) {
			return c.sendMail(mail().attachment(Attachment.of("a.pdf", new byte[]{1})).build());
		}

		Send plain(Bloomsight c) {
			return c.sendMail(mail().build());
		}

		@Test
		void anAttachmentSendWaitsForTheAttachmentTimeoutNotTheTimeout() {
			Bloomsight c = client(Bloomsight.builder().timeout(Duration.ofMillis(300))
				.attachmentTimeout(Duration.ofSeconds(10)).maxRetries(0));
			fake.answer(Answer.sent(sentJob(1)).after(1000), Answer.ok(map("id", "j", "status", "queued")).after(1000));
			assertEquals(SendStatus.SENT, withFile(c).getStatus());
			BloomsightException e = assertThrows(BloomsightException.class, () -> plain(c));
			assertEquals("TIMEOUT", e.getCode());
			assertTrue(e.getMessage().contains("300 ms"), e.getMessage());
		}

		@Test
		void theAttachmentTimeoutCapsOnlyAttachmentSends() {
			Bloomsight c = client(Bloomsight.builder().timeout(Duration.ofSeconds(10))
				.attachmentTimeout(Duration.ofMillis(300)).maxRetries(0));
			fake.answer(Answer.sent(sentJob(1)).after(1000), Answer.ok(map("id", "j", "status", "queued")).after(1000));
			long start = System.nanoTime();
			BloomsightException e = assertThrows(BloomsightException.class, () -> withFile(c));
			long ms = (System.nanoTime() - start) / 1_000_000;
			assertEquals("TIMEOUT", e.getCode());
			assertTrue(e.getMessage().contains("300 ms"), e.getMessage());
			assertTrue(ms < 900, ms + " ms");
			assertEquals(SendStatus.QUEUED, plain(c).getStatus());
		}

		@Test
		void theAttachmentTimeoutMustBePositive() {
			assertThrows(IllegalArgumentException.class,
				() -> Bloomsight.builder().apiKey(KEY).attachmentTimeout(Duration.ZERO).build());
			assertThrows(IllegalArgumentException.class,
				() -> Bloomsight.builder().apiKey(KEY).attachmentTimeout(Duration.ofSeconds(-1)).build());
		}
	}
}
