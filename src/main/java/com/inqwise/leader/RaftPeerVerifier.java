package com.inqwise.leader;

import io.vertx.core.http.HttpConnection;

/** Shared incoming/outgoing identity boundary. Called on the owning context. */
@FunctionalInterface
interface RaftPeerVerifier {
	/** Returns the certificate identity, or null only for explicitly configured plaintext. */
	String verify(HttpConnection connection) throws Exception;

	/** TLS binds a claim to its certificate; plaintext accepts only a syntactically valid self-report. */
	default String verify(HttpConnection connection, String claimed) throws Exception {
		String authenticated = verify(connection);
		if (authenticated != null) {
			if (claimed != null && !authenticated.equals(claimed)) {
				throw new java.io.IOException("Certificate and claimed peer identity disagree");
			}
			return authenticated;
		}
		RaftIdentity.validateNodeId(claimed);
		return claimed;
	}
}
