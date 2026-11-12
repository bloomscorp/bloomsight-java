package com.bloomscorp.bloomsight;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Reads typed fields from a parsed response, tolerating what a newer server may send:
 * a missing field is {@code null}, a field of an unexpected type is {@code null}, and
 * unknown fields are ignored.
 */
final class Fields {

	private Fields() {
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> map(Object value) {
		return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
	}

	static String str(Map<String, Object> m, String key) {
		Object v = m.get(key);
		return v instanceof String ? (String) v : null;
	}

	static Long lng(Map<String, Object> m, String key) {
		Object v = m.get(key);
		return v instanceof Number ? ((Number) v).longValue() : null;
	}

	static long lng(Map<String, Object> m, String key, long fallback) {
		Long v = lng(m, key);
		return v == null ? fallback : v;
	}

	static Integer integer(Map<String, Object> m, String key) {
		Object v = m.get(key);
		return v instanceof Number ? ((Number) v).intValue() : null;
	}

	static boolean bool(Map<String, Object> m, String key) {
		return Boolean.TRUE.equals(m.get(key));
	}

	static Instant instant(Map<String, Object> m, String key) {
		String v = str(m, key);
		if (v == null) return null;
		try {
			return Instant.parse(v);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	static List<String> strings(Map<String, Object> m, String key) {
		Object v = m.get(key);
		if (!(v instanceof List)) return Collections.emptyList();
		List<String> out = new ArrayList<>();
		for (Object item : (List<?>) v) {
			if (item instanceof String) out.add((String) item);
		}
		return Collections.unmodifiableList(out);
	}
}
