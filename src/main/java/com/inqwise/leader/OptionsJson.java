package com.inqwise.leader;

import java.math.BigInteger;
import java.util.Set;
import io.vertx.core.json.JsonObject;

/** Strict, shared parsing for immutable configuration. */
final class OptionsJson {
	private OptionsJson() {}
	static void checkKeys(JsonObject value, Set<String> allowed) {
		for (var key : value.fieldNames()) {
			if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown option: " + key);
			if (value.getValue(key) == null) throw new IllegalArgumentException("Null option: " + key);
		}
	}
	static JsonObject object(JsonObject json, String key) {
		Object value = json.getValue(key, new JsonObject());
		if (!(value instanceof JsonObject object)) throw new IllegalArgumentException(key + " must be an object");
		return object.copy();
	}
	static String optionalText(JsonObject json, String key) {
		if (!json.containsKey(key)) return null;
		return text(json, key, null);
	}
	static String text(JsonObject json, String key, String fallback) {
		Object value = json.getValue(key, fallback);
		if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " must be nonblank text");
		return text;
	}
	static long integer(JsonObject json, String key, long fallback, long minimum, long maximum) {
		Object value = json.getValue(key, fallback);
		if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
			|| value instanceof BigInteger)) throw new IllegalArgumentException(key + " must be an integer");
		BigInteger integer = new BigInteger(value.toString());
		if (integer.compareTo(BigInteger.valueOf(minimum)) < 0 || integer.compareTo(BigInteger.valueOf(maximum)) > 0) {
			throw new IllegalArgumentException(key + " is outside supported bounds");
		}
		return integer.longValueExact();
	}
}
