package com.bloomscorp.bloomsight;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A local fake of the blooms.ai email API: nothing leaves the machine. Each test queues
 * the answers it gives, in order, and reads back what the client sent.
 */
final class FakeBlooms implements AutoCloseable {

	static final class Seen {
		final String method;
		final String path;
		final Map<String, String> headers;
		/** The parsed JSON body, or null (no body, or a multipart one). */
		final Object body;
		/** The body exactly as received. */
		final byte[] raw;
		/** The parsed multipart body, or null when it was not multipart/form-data. */
		final Form form;

		Seen(String method, String path, Map<String, String> headers, Object body, byte[] raw, Form form) {
			this.method = method;
			this.path = path;
			this.headers = headers;
			this.body = body;
			this.raw = raw;
			this.form = form;
		}

		String header(String name) {
			return headers.get(name.toLowerCase());
		}
	}

	/** One part of a multipart/form-data body. */
	static final class Part {
		final String name;
		/** null for a field. */
		final String filename;
		/** Every header of the part, names in lower case. */
		final Map<String, String> headers;
		final byte[] content;

		Part(String name, String filename, Map<String, String> headers, byte[] content) {
			this.name = name;
			this.filename = filename;
			this.headers = headers;
			this.content = content;
		}

		String contentType() {
			return headers.get("content-type");
		}

		String text() {
			return new String(content, StandardCharsets.UTF_8);
		}
	}

	/** A multipart/form-data body, parsed strictly: a malformed one fails the test. */
	static final class Form {
		final List<Part> parts;

		Form(List<Part> parts) {
			this.parts = parts;
		}

		/** Field names in order, files included (as "attachments"). */
		List<String> names() {
			List<String> out = new ArrayList<>();
			for (Part p : parts) out.add(p.name);
			return out;
		}

		String field(String name) {
			for (Part p : parts) if (p.name.equals(name) && p.filename == null) return p.text();
			return null;
		}

		List<Part> files() {
			List<Part> out = new ArrayList<>();
			for (Part p : parts) if (p.filename != null) out.add(p);
			return out;
		}

		static Form parse(String contentType, byte[] raw) {
			String marker = "boundary=";
			int at = contentType.indexOf(marker);
			if (at < 0) throw new IllegalStateException("no boundary in " + contentType);
			String boundary = contentType.substring(at + marker.length()).trim();
			if (boundary.startsWith("\"")) boundary = boundary.substring(1, boundary.length() - 1);
			byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
			byte[] next = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);

			if (indexOf(raw, delimiter, 0) != 0) throw new IllegalStateException("body does not start with the boundary");
			int pos = delimiter.length;
			List<Part> parts = new ArrayList<>();
			for (;;) {
				if (startsWith(raw, pos, "--\r\n")) {
					if (pos + 4 != raw.length) throw new IllegalStateException("bytes after the closing boundary");
					return new Form(parts);
				}
				if (!startsWith(raw, pos, "\r\n")) throw new IllegalStateException("no CRLF after a boundary at " + pos);
				pos += 2;
				int headEnd = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), pos);
				if (headEnd < 0) throw new IllegalStateException("part headers never end");
				String head = new String(raw, pos, headEnd - pos, StandardCharsets.UTF_8);
				Map<String, String> headers = new LinkedHashMap<>();
				for (String line : head.split("\r\n", -1)) {
					int colon = line.indexOf(':');
					if (colon < 1) throw new IllegalStateException("bad part header line: " + line);
					String key = line.substring(0, colon).trim().toLowerCase();
					if (headers.containsKey(key)) throw new IllegalStateException("repeated part header " + key);
					headers.put(key, line.substring(colon + 1).trim());
				}
				int contentStart = headEnd + 4;
				int contentEnd = indexOf(raw, next, contentStart);
				if (contentEnd < 0) throw new IllegalStateException("part never ends");
				String disposition = headers.get("content-disposition");
				if (disposition == null || !disposition.startsWith("form-data;")) {
					throw new IllegalStateException("bad Content-Disposition: " + disposition);
				}
				Map<String, String> params = params(disposition.substring("form-data;".length()));
				parts.add(new Part(params.get("name"), params.get("filename"), headers,
					Arrays.copyOfRange(raw, contentStart, contentEnd)));
				pos = contentEnd + next.length;
			}
		}

		/** name="value" pairs; a quote can only end a value. */
		private static Map<String, String> params(String text) {
			Map<String, String> out = new LinkedHashMap<>();
			int i = 0;
			while (i < text.length()) {
				while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == ';')) i++;
				if (i >= text.length()) break;
				int eq = text.indexOf('=', i);
				if (eq < 0) throw new IllegalStateException("bad parameter in " + text);
				String key = text.substring(i, eq).trim();
				if (text.charAt(eq + 1) != '"') throw new IllegalStateException("unquoted parameter in " + text);
				int close = text.indexOf('"', eq + 2);
				if (close < 0) throw new IllegalStateException("unterminated parameter in " + text);
				if (out.put(key, text.substring(eq + 2, close)) != null) throw new IllegalStateException("repeated " + key);
				i = close + 1;
				if (i < text.length() && text.charAt(i) != ';') throw new IllegalStateException("text after a parameter in " + text);
			}
			return out;
		}

		private static boolean startsWith(byte[] hay, int at, String ascii) {
			byte[] b = ascii.getBytes(StandardCharsets.US_ASCII);
			if (at + b.length > hay.length) return false;
			for (int i = 0; i < b.length; i++) if (hay[at + i] != b[i]) return false;
			return true;
		}

		private static int indexOf(byte[] hay, byte[] needle, int from) {
			outer:
			for (int i = from; i + needle.length <= hay.length; i++) {
				for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
				return i;
			}
			return -1;
		}
	}

	/** One queued answer: a response, or HANG (never answer) or CLOSE (drop the connection). */
	static final class Answer {
		final int status;
		final String body;
		final Map<String, String> headers;
		final boolean hang;
		final boolean close;
		final long delayMs;

		private Answer(int status, String body, Map<String, String> headers, boolean hang, boolean close) {
			this(status, body, headers, hang, close, 0);
		}

		private Answer(int status, String body, Map<String, String> headers, boolean hang, boolean close, long delayMs) {
			this.status = status;
			this.body = body;
			this.headers = headers;
			this.hang = hang;
			this.close = close;
			this.delayMs = delayMs;
		}

		/** The same answer, given after a pause. */
		Answer after(long ms) {
			return new Answer(status, body, headers, hang, close, ms);
		}

		/** A finished direct send: 200, as for a send with attachments. */
		static Answer sent(Object data, String... headerPairs) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("success", true);
			body.put("data", data);
			return json(200, body, headerPairs);
		}

		static Answer json(int status, Object body, String... headerPairs) {
			Map<String, String> h = new LinkedHashMap<>();
			for (int i = 0; i + 1 < headerPairs.length; i += 2) h.put(headerPairs[i], headerPairs[i + 1]);
			return new Answer(status, Json.write(body), h, false, false);
		}

		static Answer text(int status, String body) {
			return new Answer(status, body, Collections.emptyMap(), false, false);
		}

		static Answer ok(Object data, String... headerPairs) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("success", true);
			body.put("data", data);
			return json(202, body, headerPairs);
		}

		static final Answer HANG = new Answer(0, null, null, true, false);
		static final Answer CLOSE = new Answer(0, null, null, false, true);
	}

	private final HttpServer server;
	private final ExecutorService pool = Executors.newCachedThreadPool();
	private final CountDownLatch release = new CountDownLatch(1);
	final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
	/** Bodies the fake could not parse; every test's teardown asserts there are none. */
	final List<RuntimeException> malformed = Collections.synchronizedList(new ArrayList<>());
	private final Deque<Answer> answers = new ArrayDeque<>();

	FakeBlooms() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.setExecutor(pool);
		server.createContext("/", this::handle);
		server.start();
	}

	String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	synchronized void answer(Answer... list) {
		Collections.addAll(answers, list);
	}

	private synchronized Answer nextAnswer() {
		Answer a = answers.poll();
		return a != null ? a : Answer.ok(Collections.emptyMap());
	}

	private void handle(HttpExchange ex) throws IOException {
		byte[] raw = readAll(ex.getRequestBody());
		Map<String, String> headers = new LinkedHashMap<>();
		ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.isEmpty() ? null : v.get(0)));
		String text = new String(raw, StandardCharsets.UTF_8);
		String contentType = headers.get("content-type");
		boolean multipart = contentType != null && contentType.startsWith("multipart/form-data");
		Form form = null;
		Object json = null;
		try {
			if (multipart) form = Form.parse(contentType, raw);
			else if (!text.isEmpty()) json = Json.parse(text);
		} catch (RuntimeException e) {
			malformed.add(e);
		}
		seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getRawPath()
			+ (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()),
			headers, json, raw, form));

		Answer a = nextAnswer();
		if (a.delayMs > 0) {
			try {
				release.await(a.delayMs, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		if (a.hang) {
			try {
				release.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			ex.close();
			return;
		}
		if (a.close) {
			ex.close();
			return;
		}
		ex.getResponseHeaders().set("Content-Type", "application/json");
		a.headers.forEach((k, v) -> ex.getResponseHeaders().set(k, v));
		byte[] out = a.body.getBytes(StandardCharsets.UTF_8);
		ex.sendResponseHeaders(a.status, out.length == 0 ? -1 : out.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(out);
		}
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
		return out.toByteArray();
	}

	@Override
	public void close() {
		release.countDown();
		server.stop(0);
		pool.shutdownNow();
	}
}
