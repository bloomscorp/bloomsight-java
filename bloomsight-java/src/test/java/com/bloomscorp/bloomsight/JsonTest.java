package com.bloomscorp.bloomsight;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

	@Test
	void writesTemplateValuesAsJavaScriptWould() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("text", "Asha \"A\" \\ \n\t\u0001 ");
		m.put("int", 2);
		m.put("long", 10_000_000_000L);
		m.put("whole", 3.0);
		m.put("frac", 2.5);
		m.put("big", new BigDecimal("12.50"));
		m.put("yes", true);
		m.put("none", null);
		m.put("list", Arrays.asList(1, "x"));
		m.put("array", new int[]{1, 2});
		m.put("strings", new String[]{"a"});
		assertEquals("{\"text\":\"Asha \\\"A\\\" \\\\ \\n\\t\\u0001\\u2028\",\"int\":2,\"long\":10000000000,"
			+ "\"whole\":3,\"frac\":2.5,\"big\":12.50,\"yes\":true,\"none\":null,\"list\":[1,\"x\"],"
			+ "\"array\":[1,2],\"strings\":[\"a\"]}", Json.write(m));
	}

	@Test
	void refusesValuesThatAreNotJsonNamingWhere() {
		BloomsightException e = assertThrows(BloomsightException.class,
			() -> Json.write(Collections.singletonMap("order", Collections.singletonMap("id", UUID.randomUUID()))));
		assertEquals("INVALID_DATA", e.getCode());
		assertTrue(e.getMessage().startsWith("data.order.id is a java.util.UUID"), e.getMessage());
		assertThrows(BloomsightException.class, () -> Json.write(Collections.singletonMap("n", Double.NaN)));
		assertThrows(BloomsightException.class, () -> Json.write(Collections.singletonMap(1, "x")));
	}

	@Test
	void readsWhatTheServerSends() {
		Object v = Json.parse(" {\"a\":[1,-2.5,1e3,true,false,null,\"\\u00e9\\n\\/\"],\"b\":{},\"c\":[],\"d\":12345678901234567890} ");
		Map<?, ?> m = (Map<?, ?>) v;
		List<?> a = (List<?>) m.get("a");
		assertEquals(1L, a.get(0));
		assertEquals(-2.5, a.get(1));
		assertEquals(1000.0, a.get(2));
		assertEquals(Boolean.TRUE, a.get(3));
		assertEquals("é\n/", a.get(6));
		assertEquals(Collections.emptyMap(), m.get("b"));
		assertEquals(1.2345678901234567e19, m.get("d"));
	}

	@Test
	void refusesTextThatIsNotJson() {
		for (String bad : new String[]{"", "<html>", "{\"a\":}", "[1,]", "{\"a\":1} x", "\"open", "tru", "01x"}) {
			assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
		}
	}

	@Test
	void roundTrips() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", "Ünïcødé ✓ 😀");
		m.put("rows", Arrays.asList(Collections.singletonMap("qty", 2L)));
		assertEquals(m, Json.parse(Json.write(m)));
	}
}
