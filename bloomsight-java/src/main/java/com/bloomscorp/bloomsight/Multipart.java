package com.bloomscorp.bloomsight;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A {@code multipart/form-data} body (RFC 7578), built once in memory so every retry
 * uploads the very same bytes.
 */
final class Multipart {

	private static final byte[] CRLF = {'\r', '\n'};
	private static final byte[] DASHES = {'-', '-'};

	private final List<Part> parts = new ArrayList<>();

	private static final class Part {
		final String name;
		final String filename;
		final String contentType;
		final byte[] content;

		Part(String name, String filename, String contentType, byte[] content) {
			this.name = name;
			this.filename = filename;
			this.contentType = contentType;
			this.content = content;
		}
	}

	Multipart field(String name, String value) {
		parts.add(new Part(name, null, null, value.getBytes(StandardCharsets.UTF_8)));
		return this;
	}

	Multipart file(String name, String filename, String contentType, byte[] content) {
		parts.add(new Part(name, filename, contentType, content));
		return this;
	}

	/** The finished body and the Content-Type header that goes with it. */
	static final class Body {
		final String contentType;
		final byte[] bytes;

		Body(String contentType, byte[] bytes) {
			this.contentType = contentType;
			this.bytes = bytes;
		}
	}

	Body build() {
		String boundary = boundary();
		byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
		ByteArrayOutputStream out = new ByteArrayOutputStream(size(delimiter.length));
		for (Part p : parts) {
			out.writeBytes(delimiter);
			out.writeBytes(CRLF);
			StringBuilder head = new StringBuilder("Content-Disposition: form-data; name=\"").append(quote(p.name)).append('"');
			if (p.filename != null) head.append("; filename=\"").append(quote(p.filename)).append('"');
			head.append("\r\n");
			if (p.contentType != null) head.append("Content-Type: ").append(p.contentType).append("\r\n");
			head.append("\r\n");
			// UTF-8, as browsers send a file name (RFC 7578 §4.2).
			out.writeBytes(head.toString().getBytes(StandardCharsets.UTF_8));
			out.writeBytes(p.content);
			out.writeBytes(CRLF);
		}
		out.writeBytes(delimiter);
		out.writeBytes(DASHES);
		out.writeBytes(CRLF);
		return new Body("multipart/form-data; boundary=" + boundary, out.toByteArray());
	}

	private int size(int delimiter) {
		long n = delimiter + 4;
		for (Part p : parts) n += delimiter + 200 + p.content.length;
		return (int) Math.min(Integer.MAX_VALUE - 8, n);
	}

	/** Random, and checked against the content, so it can never occur inside a part. */
	private String boundary() {
		for (;;) {
			String b = "bloomsight-" + UUID.randomUUID().toString().replace("-", "");
			byte[] needle = b.getBytes(StandardCharsets.US_ASCII);
			boolean clash = false;
			for (Part p : parts) {
				if (contains(p.content, needle)) {
					clash = true;
					break;
				}
			}
			if (!clash) return b;
		}
	}

	private static boolean contains(byte[] hay, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= hay.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (hay[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	/**
	 * Escapes what would end the quoted value or the header line, as the HTML standard
	 * does for form data: {@code "} as {@code %22}, CR as {@code %0D}, LF as {@code %0A}.
	 */
	static String quote(String value) {
		StringBuilder out = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '"') out.append("%22");
			else if (c == '\r') out.append("%0D");
			else if (c == '\n') out.append("%0A");
			else out.append(c);
		}
		return out.toString();
	}
}
