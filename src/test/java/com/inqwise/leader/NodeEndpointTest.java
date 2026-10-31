package com.inqwise.leader;

import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class NodeEndpointTest {
	static Stream<Arguments> validEndpoints() {
		return Stream.of(
			Arguments.of("192.0.2.10", 8080, new NodeEndpoint("192.0.2.10", 8080, null)),
			Arguments.of("192.0.2.10", 9000, new NodeEndpoint("192.0.2.10", 9000, null)),
			Arguments.of("192.0.2.10#node-a", 9000, new NodeEndpoint("192.0.2.10", 9000, "node-a")),
			Arguments.of("192.0.2.10:9090#node-a", 9000, new NodeEndpoint("192.0.2.10", 9090, "node-a")),
			Arguments.of("127.0.0.1:1", 8080, new NodeEndpoint("127.0.0.1", 1, null)),
			Arguments.of("192.0.2.255:65535", 8080, new NodeEndpoint("192.0.2.255", 65535, null)),
			Arguments.of("192.0.2.0:00080#Node_1.example", 8080,
				new NodeEndpoint("192.0.2.0", 80, "Node_1.example")));
	}

	@ParameterizedTest
	@MethodSource("validEndpoints")
	void testValidEndpoints(String text, int defaultPort, NodeEndpoint expected) {
		Assertions.assertEquals(expected, NodeEndpoint.parse(text, defaultPort));
	}

	@Test
	void testDefaultPort() {
		Assertions.assertEquals(new NodeEndpoint("192.0.2.10", 8080, null), NodeEndpoint.parse("192.0.2.10"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"", "192.0.2", "192.0.2.10.1", "192.0.2.256", "192.0.-2.10", "192.00.2.10", "0xC0.0.2.10",
		"3221225994", "192..2.10", " 192.0.2.10", "192.0.2.10 ", "node-a.example.net", "::1", "[::1]:8080",
		"0.0.0.0", "192.0.2.10:", "192.0.2.10:0", "192.0.2.10:65536", "192.0.2.10:-1", "192.0.2.10:+80",
		"192.0.2.10:999999999999", "192.0.2.10:8080:80", "http://192.0.2.10:8080", "192.0.2.10:8080/path",
		"user@192.0.2.10:8080", "192.0.2.10#", "192.0.2.10#a#b", "192.0.2.10#node a", "192.0.2.10#node/a",
		"192.0.2.10#node:a", "192.0.2.10#node?a", "192.0.2.10#-node", "192.0.2.10:8080\n"
	})
	void testInvalidEndpoints(String text) {
		Assertions.assertThrows(IllegalArgumentException.class, () -> NodeEndpoint.parse(text));
	}

	@ParameterizedTest
	@ValueSource(ints = { -1, 0, 65536, Integer.MAX_VALUE })
	void testInvalidDefaultPort(int port) {
		Assertions.assertThrows(IllegalArgumentException.class, () -> NodeEndpoint.parse("192.0.2.10", port));
		Assertions.assertThrows(IllegalArgumentException.class, () -> NodeEndpoint.parse("192.0.2.10:8080", port));
	}

	@Test
	void testConstructorCannotBypassValidation() {
		Assertions.assertAll(
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> new NodeEndpoint("localhost", 8080, null)),
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> new NodeEndpoint("192.0.2.10", 0, null)),
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> new NodeEndpoint("192.0.2.10", 8080, "a".repeat(129))),
			() -> Assertions.assertThrows(NullPointerException.class, () -> NodeEndpoint.parse(null)),
			() -> Assertions.assertThrows(NullPointerException.class, () -> new NodeEndpoint(null, 8080, null)));
	}
}
