package com.inqwise.leader;

import java.io.IOException;

import io.vertx.core.Completable;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;

/** Lightweight logical peer; physical sockets and exchange lifetimes belong to the shared HTTP client. */
final class HttpRaftConnection implements RaftConnection {
	private final HttpRaftClient client;
	private final NodeEndpoint endpoint;

	HttpRaftConnection(HttpRaftClient client, NodeEndpoint endpoint) {
		this.client = client; this.endpoint = endpoint;
	}

	@Override public void identify(Completable<RaftWireCodec.PeerIdentity> completion) {
		client.request(endpoint, HttpMethod.GET, "/identity", Buffer::buffer, (bytes, peer) -> {
			var identity = RaftWireCodec.identity(bytes);
			verify(identity.nodeId(), peer); return identity;
		}, false, completion);
	}
	@Override public void membership(Completable<MemberStatus> completion) {
		client.request(endpoint, HttpMethod.GET, "/membership", Buffer::buffer, HttpRaftConnection::status, false, completion);
	}
	@Override public void genesis(Completable<RaftGenesis> completion) {
		client.request(endpoint, HttpMethod.GET, "/bootstrap", Buffer::buffer,
			(bytes, peer) -> RaftWireCodec.genesis(bytes), false, completion);
	}
	@Override public void install(RaftGenesis genesis, Completable<Void> completion) {
		client.request(endpoint, HttpMethod.POST, "/bootstrap/install", () -> RaftWireCodec.genesis(genesis),
			HttpRaftConnection::accepted, false, completion);
	}
	@Override public void join(JoinRequest request, Completable<MemberStatus> completion) {
		client.request(endpoint, HttpMethod.POST, "/join", () -> RaftWireCodec.join(request),
			HttpRaftConnection::status, false, completion);
	}
	@Override public void leave(LeaveRequest request, Completable<LeaveResult> completion) {
		client.request(endpoint, HttpMethod.POST, "/leave", () -> RaftWireCodec.leave(request), (bytes, peer) -> {
			var result = RaftWireCodec.left(bytes); verify(result.identity().nodeId(), peer); return result;
		}, false, completion);
	}
	@Override public void send(RaftElection.Envelope envelope, Completable<Void> completion) {
		boolean large = envelope.message() instanceof RaftElection.InstallSnapshot;
		if (envelope.message() instanceof RaftElection.AppendEntries append) {
			int characters = 0;
			for (var entry : append.entries()) characters += entry.data().length();
			large = characters > 4096;
		}
		client.request(endpoint, HttpMethod.POST, RaftWireCodec.suffix(envelope.message()),
			() -> RaftWireCodec.encode(envelope), HttpRaftConnection::accepted, large, completion);
	}

	private static MemberStatus status(Buffer bytes, String peer) throws IOException {
		var result = RaftWireCodec.status(bytes); verify(result.identity().nodeId(), peer); return result;
	}
	private static Void accepted(Buffer bytes, String peer) throws IOException {
		if (bytes.length() != 0) throw new IOException("Unexpected dispatch response body");
		return null;
	}
	private static void verify(String claimed, String peer) throws IOException {
		if (!peer.equals(claimed)) throw new IOException("Certificate and response identity disagree");
	}
}
