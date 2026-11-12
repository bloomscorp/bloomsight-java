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
		final Object body;

		Seen(String method, String path, Map<String, String> headers, Object body) {
			this.method = method;
			this.path = path;
			this.headers = headers;
			this.body = body;
		}

		String header(String name) {
			return headers.get(name.toLowerCase());
		}
	}

	/** One queued answer: a response, or HANG (never answer) or CLOSE (drop the connection). */
	static final class Answer {
		final int status;
		final String body;
		final Map<String, String> headers;
		final boolean hang;
		final boolean close;

		private Answer(int status, String body, Map<String, String> headers, boolean hang, boolean close) {
			this.status = status;
			this.body = body;
			this.headers = headers;
			this.hang = hang;
			this.close = close;
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
		seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getRawPath()
			+ (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()),
			headers, text.isEmpty() ? null : Json.parse(text)));

		Answer a = nextAnswer();
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
