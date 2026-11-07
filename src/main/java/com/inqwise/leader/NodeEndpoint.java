package com.inqwise.leader;

import java.util.Objects;

/**
 * An IPv4 coordination endpoint with an optional expected remote identity.
 * The identity is a handshake constraint, not an instruction to rename a remote node.
 *
 * @param host IPv4 literal
 * @param port coordination port
 * @param expectedNodeId expected identity, or {@code null} to discover it
 */
public record NodeEndpoint(String host, int port, String expectedNodeId) {
	/** Default coordination port when no listener override is supplied. */
	public static final int DEFAULT_PORT = 8080;

	/**
	 * Creates a validated endpoint without resolving or contacting the address.
	 * @param host IPv4 literal
	 * @param port coordination port
	 * @param expectedNodeId expected identity, or {@code null}
	 */
	public NodeEndpoint {
		validateHost(Objects.requireNonNull(host, "host"));
		validatePort(port);
		if (expectedNodeId != null) {
			RaftIdentity.validateNodeId(expectedNodeId);
		}
	}

	/**
	 * Parses {@code ipv4[:port][#expectedNodeId]} using port 8080 when omitted.
	 * @param endpoint IPv4 endpoint text
	 * @return validated endpoint
	 */
	public static NodeEndpoint parse(String endpoint) {
		return parse(endpoint, DEFAULT_PORT);
	}

	/**
	 * Parses an endpoint using the configured listener port when the port is omitted.
	 * An explicit invalid port is rejected rather than replaced with the default.
	 *
	 * @param endpoint IPv4 endpoint text
	 * @param defaultPort configured listener port
	 * @return validated endpoint
	 */
	public static NodeEndpoint parse(String endpoint, int defaultPort) {
		Objects.requireNonNull(endpoint, "endpoint");
		validatePort(defaultPort);
		int fragment = endpoint.indexOf('#');
		if (fragment != endpoint.lastIndexOf('#')) {
			throw new IllegalArgumentException("endpoint must contain at most one identity suffix");
		}
		String address = fragment < 0 ? endpoint : endpoint.substring(0, fragment);
		String expectedId = fragment < 0 ? null : endpoint.substring(fragment + 1);
		int separator = address.indexOf(':');
		String host = separator < 0 ? address : address.substring(0, separator);
		int port = defaultPort;
		if (separator >= 0) {
			String portText = address.substring(separator + 1);
			if (!portText.matches("[0-9]{1,5}")) {
				throw new IllegalArgumentException("port must be a decimal integer from 1 through 65535");
			}
			port = Integer.parseInt(portText);
		}
		return new NodeEndpoint(host, port, expectedId);
	}

	private static void validateHost(String host) {
		String[] octets = host.split("\\.", -1);
		if (octets.length != 4) {
			throw new IllegalArgumentException("host must be an IPv4 literal");
		}
		for (String octet : octets) {
			if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) {
				throw new IllegalArgumentException("host must contain four unambiguous IPv4 octets");
			}
		}
		if (host.equals("0.0.0.0")) {
			throw new IllegalArgumentException("an endpoint must not use the wildcard bind address");
		}
	}

	private static void validatePort(int port) {
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("port must be from 1 through 65535");
		}
	}
}
