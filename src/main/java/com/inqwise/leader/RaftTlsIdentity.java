package com.inqwise.leader;

import java.io.IOException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

import javax.net.ssl.X509KeyManager;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.net.KeyCertOptions;

/** Certificate identity convention for the HTTP transport. */
public final class RaftTlsIdentity {
	/** URI subject-alternative-name prefix for a persisted node ID. */
	public static final String NODE_URI_PREFIX = "urn:inqwise:leader:node:";
	private RaftTlsIdentity() {}

	/**
	 * Extracts exactly one node URI SAN from an already TLS-verified leaf certificate.
	 * Certificate issuance must authorize the persisted identity; self-reported HTTP IDs are insufficient.
	 * @param certificate authenticated leaf certificate
	 * @return persisted node ID in the URI SAN
	 */
	public static String nodeId(X509Certificate certificate) {
		try {
			var alternatives = certificate.getSubjectAlternativeNames();
			String identity = null;
			if (alternatives != null) for (var alternative : alternatives) {
				if (Integer.valueOf(6).equals(alternative.get(0))
					&& alternative.get(1) instanceof String uri && uri.startsWith(NODE_URI_PREFIX)) {
					if (identity != null) throw new IllegalArgumentException("Multiple node identity SANs");
					identity = uri.substring(NODE_URI_PREFIX.length());
					RaftIdentity.validateNodeId(identity);
				}
			}
			if (identity == null) throw new IllegalArgumentException("Missing node identity URI SAN");
			return identity;
		} catch (CertificateParsingException e) {
			throw new IllegalArgumentException("Invalid certificate SANs", e);
		}
	}
	/** Loads local key material; invoke on a worker, never on an event loop. */
	static void validateCredentials(Vertx vertx, KeyCertOptions credentials,
		String expectedId) throws Exception {
		boolean found = false;
		// Key loading uses the standard Vert.x adapter on a worker, never the event loop.
		for (var manager : credentials.getKeyManagerFactory(vertx).getKeyManagers()) {
			if (!(manager instanceof X509KeyManager x509)) continue;
			var aliases = new HashSet<String>();
			for (String type : List.of("RSA", "EC", "EdDSA", "Ed25519", "Ed448", "RSASSA-PSS", "DSA")) {
				var server = x509.getServerAliases(type, null); var client = x509.getClientAliases(type, null);
				if (server != null) aliases.addAll(List.of(server)); if (client != null) aliases.addAll(List.of(client));
			}
			for (String alias : aliases) {
				var chain = x509.getCertificateChain(alias);
				if (chain == null || chain.length == 0) continue;
				if (!expectedId.equals(nodeId(chain[0]))) {
					throw new IllegalArgumentException("TLS certificate identity must match persisted node " + expectedId);
				}
				found = true;
			}
		}
		if (!found) throw new IllegalArgumentException("No enumerable X.509 node credentials; use the lower-level transport for custom key managers");
	}

	static String verify(HttpConnection connection, String localNodeId,
		Function<X509Certificate, String> certificateIdentity) throws Exception {
		if (!connection.isSsl()) throw new IOException("TLS required");
		var local = connection.sslSession().getLocalCertificates();
		if (local == null || local.length == 0
			|| !localNodeId.equals(certificateIdentity.apply((X509Certificate) local[0]))) {
			throw new IOException("Local certificate and persisted identity disagree");
		}
		var certificates = connection.peerCertificates();
		if (certificates == null || certificates.isEmpty()) throw new IOException("Peer certificate required");
		String id = certificateIdentity.apply((X509Certificate) certificates.getFirst());
		RaftIdentity.validateNodeId(id);
		return id;
	}

}
