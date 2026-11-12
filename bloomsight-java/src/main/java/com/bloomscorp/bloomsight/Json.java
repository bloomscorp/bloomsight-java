package com.bloomscorp.bloomsight;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The small JSON reader and writer the client needs, so the library has no
 * dependencies and never clashes with an application's own Jackson or Gson.
 *
 * <p>Writing accepts exactly what a template value may be: {@code String}, numbers,
 * {@code Boolean}, {@code null}, {@code Map} with text keys, and {@code Collection}s or
 * arrays of those. Anything else is refused ({@code INVALID_DATA}) rather than turned
 * into a surprise string. Reading returns {@code Map}, {@code List}, {@code String},
 * {@code Long} or {@code Double}, {@code Boolean} and {@code null}.
 */
final class Json {

	private Json() {
	}

	// ── Writing ──────────────────────────────────────────────────────────────

	static String write(Object value) {
		StringBuilder out = new StringBuilder();
		write(value, out, "data", 0);
		return out.toString();
	}

	private static void write(Object value, StringBuilder out, String path, int depth) {
		if (depth > 64) {
			throw invalid(path, "is nested too deeply");
		}
		if (value == null) {
			out.append("null");
		} else if (value instanceof CharSequence) {
			string(value.toString(), out);
		} else if (value instanceof Boolean) {
			out.append(value.toString());
		} else if (value instanceof Number) {
			number((Number) value, out, path);
		} else if (value instanceof Map) {
			out.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
				if (!(e.getKey() instanceof String)) {
					throw invalid(path, "has a key that is not text (" + typeName(e.getKey()) + ")");
				}
				if (!first) out.append(',');
				first = false;
				String key = (String) e.getKey();
				string(key, out);
				out.append(':');
				write(e.getValue(), out, path + "." + key, depth + 1);
			}
			out.append('}');
		} else if (value instanceof Collection) {
			out.append('[');
			int i = 0;
			for (Object item : (Collection<?>) value) {
				if (i > 0) out.append(',');
				write(item, out, path + "[" + i + "]", depth + 1);
				i++;
			}
			out.append(']');
		} else if (value.getClass().isArray()) {
			out.append('[');
			int n = Array.getLength(value);
			for (int i = 0; i < n; i++) {
				if (i > 0) out.append(',');
				write(Array.get(value, i), out, path + "[" + i + "]", depth + 1);
			}
			out.append(']');
		} else {
			throw invalid(path, "is a " + typeName(value)
				+ "; template values must be text, numbers, true/false, null, or maps and lists of those");
		}
	}

	private static void number(Number n, StringBuilder out, String path) {
		if (n instanceof Double || n instanceof Float) {
			double d = n.doubleValue();
			if (Double.isNaN(d) || Double.isInfinite(d)) {
				throw invalid(path, "is not a finite number");
			}
			// Whole doubles print without ".0", as JavaScript would.
			if (d == Math.rint(d) && Math.abs(d) < 1e15) {
				out.append((long) d);
			} else {
				out.append(n instanceof Float ? Float.toString(n.floatValue()) : Double.toString(d));
			}
		} else if (n instanceof BigDecimal) {
			out.append(((BigDecimal) n).toString());
		} else if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte
			|| n instanceof BigInteger) {
			out.append(n.toString());
		} else {
			// AtomicInteger, LongAdder and the like: their value, not their toString.
			double d = n.doubleValue();
			if (Double.isNaN(d) || Double.isInfinite(d)) {
				throw invalid(path, "is not a finite number");
			}
			out.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d));
		}
	}

	private static void string(String s, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"': out.append("\\\""); break;
				case '\\': out.append("\\\\"); break;
				case '\n': out.append("\\n"); break;
				case '\r': out.append("\\r"); break;
				case '\t': out.append("\\t"); break;
				case '\b': out.append("\\b"); break;
				case '\f': out.append("\\f"); break;
				default:
					if (c < 0x20 || c == 0x2028 || c == 0x2029) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
			}
		}
		out.append('"');
	}

	private static String typeName(Object o) {
		return o == null ? "null" : o.getClass().getName();
	}

	private static BloomsightException invalid(String path, String problem) {
		return new BloomsightException("INVALID_DATA", path + " " + problem);
	}

	// ── Reading ──────────────────────────────────────────────────────────────

	/** Parses JSON text; throws {@link IllegalArgumentException} when it is not JSON. */
	static Object parse(String text) {
		Reader r = new Reader(text);
		r.space();
		Object value = r.value(0);
		r.space();
		if (r.pos != text.length()) {
			throw new IllegalArgumentException("unexpected text after the JSON value at " + r.pos);
		}
		return value;
	}

	private static final class Reader {
		private final String s;
		private int pos;

		Reader(String s) {
			this.s = s;
		}

		void space() {
			while (pos < s.length()) {
				char c = s.charAt(pos);
				if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
				else break;
			}
		}

		Object value(int depth) {
			if (depth > 512) throw fail("nested too deeply");
			if (pos >= s.length()) throw fail("unexpected end");
			char c = s.charAt(pos);
			switch (c) {
				case '{': return object(depth);
				case '[': return array(depth);
				case '"': return string();
				case 't': return literal("true", Boolean.TRUE);
				case 'f': return literal("false", Boolean.FALSE);
				case 'n': return literal("null", null);
				default:
					if (c == '-' || (c >= '0' && c <= '9')) return number();
					throw fail("unexpected '" + c + "'");
			}
		}

		Map<String, Object> object(int depth) {
			Map<String, Object> map = new LinkedHashMap<>();
			pos++;
			space();
			if (peek() == '}') {
				pos++;
				return map;
			}
			for (;;) {
				space();
				if (peek() != '"') throw fail("expected a key");
				String key = string();
				space();
				expect(':');
				space();
				map.put(key, value(depth + 1));
				space();
				char c = next();
				if (c == '}') return map;
				if (c != ',') throw fail("expected ',' or '}'");
			}
		}

		List<Object> array(int depth) {
			List<Object> list = new ArrayList<>();
			pos++;
			space();
			if (peek() == ']') {
				pos++;
				return list;
			}
			for (;;) {
				space();
				list.add(value(depth + 1));
				space();
				char c = next();
				if (c == ']') return list;
				if (c != ',') throw fail("expected ',' or ']'");
			}
		}

		String string() {
			expect('"');
			StringBuilder out = new StringBuilder();
			for (;;) {
				if (pos >= s.length()) throw fail("unterminated text");
				char c = s.charAt(pos++);
				if (c == '"') return out.toString();
				if (c == '\\') {
					char e = next();
					switch (e) {
						case '"': out.append('"'); break;
						case '\\': out.append('\\'); break;
						case '/': out.append('/'); break;
						case 'b': out.append('\b'); break;
						case 'f': out.append('\f'); break;
						case 'n': out.append('\n'); break;
						case 'r': out.append('\r'); break;
						case 't': out.append('\t'); break;
						case 'u':
							if (pos + 4 > s.length()) throw fail("bad \\u escape");
							try {
								out.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
							} catch (NumberFormatException ex) {
								throw fail("bad \\u escape");
							}
							pos += 4;
							break;
						default:
							throw fail("bad escape");
					}
				} else if (c < 0x20) {
					throw fail("control character in text");
				} else {
					out.append(c);
				}
			}
		}

		Object number() {
			int start = pos;
			if (peek() == '-') pos++;
			digits();
			boolean fraction = false;
			if (pos < s.length() && s.charAt(pos) == '.') {
				fraction = true;
				pos++;
				digits();
			}
			if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
				fraction = true;
				pos++;
				if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
				digits();
			}
			String text = s.substring(start, pos);
			if (!fraction) {
				try {
					return Long.parseLong(text);
				} catch (NumberFormatException tooBig) {
					return Double.parseDouble(text);
				}
			}
			return Double.parseDouble(text);
		}

		void digits() {
			int start = pos;
			while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
			if (pos == start) throw fail("expected a digit");
		}

		Object literal(String word, Object value) {
			if (!s.startsWith(word, pos)) throw fail("unexpected text");
			pos += word.length();
			return value;
		}

		char peek() {
			if (pos >= s.length()) throw fail("unexpected end");
			return s.charAt(pos);
		}

		char next() {
			char c = peek();
			pos++;
			return c;
		}

		void expect(char c) {
			if (next() != c) throw fail("expected '" + c + "'");
		}

		IllegalArgumentException fail(String why) {
			return new IllegalArgumentException("not JSON: " + why + " at " + pos);
		}
	}
}
