package com.bloomscorp.bloomsight;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The attachment rules of blooms.ai documentation/bloomsight/backend-sending.md §8.1,
 * applied before anything is uploaded. The server applies them again; these must never
 * be looser than its own.
 */
final class Attachments {

	static final int MAX_FILES = 5;
	static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
	static final long MAX_TOTAL_BYTES = 15L * 1024 * 1024;
	static final int MAX_NAME_LENGTH = 150;

	/**
	 * Types Gmail refuses to deliver, by the last extension of the cleaned name. The same
	 * list as the server's utils/bloomsight-email-attachments.util.js.
	 */
	static final Set<String> BLOCKED_EXTENSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		"ade", "adp", "apk", "appx", "appxbundle", "bat", "cab", "chm", "cmd", "com", "cpl",
		"diagcab", "diagcfg", "diagpack", "dll", "dmg", "ex", "ex_", "exe", "hta", "img", "ins",
		"iso", "isp", "jar", "jnlp", "js", "jse", "lib", "lnk", "mde", "mjs", "msc", "msi", "msix",
		"msixbundle", "msp", "mst", "nsh", "pif", "ps1", "scr", "sct", "shb", "sys", "vb", "vbe",
		"vbs", "vhd", "vxd", "wsc", "wsf", "wsh", "xll")));

	private static final Map<String, String> TYPES = new HashMap<>();

	static {
		TYPES.put("pdf", "application/pdf");
		TYPES.put("png", "image/png");
		TYPES.put("jpg", "image/jpeg");
		TYPES.put("jpeg", "image/jpeg");
		TYPES.put("gif", "image/gif");
		TYPES.put("webp", "image/webp");
		TYPES.put("svg", "image/svg+xml");
		TYPES.put("csv", "text/csv");
		TYPES.put("txt", "text/plain");
		TYPES.put("html", "text/html");
		TYPES.put("ics", "text/calendar");
		TYPES.put("json", "application/json");
		TYPES.put("xml", "application/xml");
		TYPES.put("zip", "application/zip");
		TYPES.put("doc", "application/msword");
		TYPES.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
		TYPES.put("xls", "application/vnd.ms-excel");
		TYPES.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
		TYPES.put("ppt", "application/vnd.ms-powerpoint");
		TYPES.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
		TYPES.put("odt", "application/vnd.oasis.opendocument.text");
		TYPES.put("ods", "application/vnd.oasis.opendocument.spreadsheet");
		TYPES.put("rtf", "application/rtf");
		TYPES.put("mp3", "audio/mpeg");
		TYPES.put("mp4", "video/mp4");
	}

	static final String DEFAULT_TYPE = "application/octet-stream";

	private Attachments() {
	}

	/**
	 * The part after the last {@code /} or {@code \}, without control characters
	 * (U+0000 to U+001F, U+007F) or double quotes, trimmed as JavaScript trims.
	 */
	static String clean(String name) {
		if (name == null) return "";
		int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		String base = name.substring(cut + 1);
		StringBuilder out = new StringBuilder(base.length());
		for (int i = 0; i < base.length(); i++) {
			char c = base.charAt(i);
			if (c <= 0x1F || c == 0x7F || c == '"') continue;
			out.append(c);
		}
		return trim(out.toString());
	}

	/** {@code String.prototype.trim}: Java's {@code trim()} misses e.g. U+00A0 and U+3000. */
	private static String trim(String s) {
		int start = 0;
		int end = s.length();
		while (start < end && isJsSpace(s.charAt(start))) start++;
		while (end > start && isJsSpace(s.charAt(end - 1))) end--;
		return s.substring(start, end);
	}

	private static boolean isJsSpace(char c) {
		return c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r' || c == ' ' || c == 0xA0
			|| c == 0x1680 || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F
			|| c == 0x205F || c == 0x3000 || c == 0xFEFF;
	}

	/**
	 * The last extension, lower case, or {@code ""}. Trailing dots and spaces are passed
	 * over, because Windows drops them: {@code invoice.exe.} runs as {@code invoice.exe}.
	 */
	static String extension(String cleanedName) {
		int end = cleanedName.length();
		while (end > 0 && (cleanedName.charAt(end - 1) == '.' || isJsSpace(cleanedName.charAt(end - 1)))) end--;
		String name = cleanedName.substring(0, end);
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	static String guessType(String cleanedName) {
		String type = TYPES.get(extension(cleanedName));
		return type != null ? type : DEFAULT_TYPE;
	}

	/** Every rule, before anything is uploaded. */
	static void check(List<Attachment> attachments) {
		if (attachments.size() > MAX_FILES) {
			throw new BloomsightException("TOO_MANY_ATTACHMENTS",
				"an email takes at most " + MAX_FILES + " attachments; this one has " + attachments.size());
		}
		long total = 0;
		for (Attachment a : attachments) {
			String name = a.getFilename();
			if (name.isEmpty()) {
				throw new BloomsightException("INVALID_ATTACHMENT", "an attachment needs a file name");
			}
			if (name.length() > MAX_NAME_LENGTH) {
				throw new BloomsightException("INVALID_ATTACHMENT", "attachment name '" + name.substring(0, 40)
					+ "...' is longer than " + MAX_NAME_LENGTH + " characters");
			}
			if (BLOCKED_EXTENSIONS.contains(extension(name))) {
				throw new BloomsightException("ATTACHMENT_TYPE_BLOCKED",
					"attachment '" + name + "' is a type that mail providers refuse to deliver (." + extension(name) + ")");
			}
			if (!isHeaderSafe(a.getContentType())) {
				throw new BloomsightException("INVALID_ATTACHMENT",
					"attachment '" + name + "' has a content type that is not a plain MIME type");
			}
			long size = a.getSize();
			if (size == 0) throw new BloomsightException("INVALID_ATTACHMENT", "attachment '" + name + "' is empty");
			if (size > MAX_FILE_BYTES) throw tooLarge(name);
			total += size;
		}
		if (total > MAX_TOTAL_BYTES) {
			throw new BloomsightException("ATTACHMENTS_TOO_LARGE",
				"attachments may be at most 15 MB together; these are " + total + " bytes");
		}
	}

	static BloomsightException tooLarge(String name) {
		return new BloomsightException("ATTACHMENT_TOO_LARGE", "attachment '" + name + "' is over 5 MB");
	}

	/** Printable ASCII with a slash, so it cannot break or add a header line. */
	private static boolean isHeaderSafe(String type) {
		if (type.indexOf('/') < 1) return false;
		for (int i = 0; i < type.length(); i++) {
			char c = type.charAt(i);
			if (c < 0x20 || c > 0x7E) return false;
		}
		return true;
	}
}
