package com.inqwise.leader;

import java.io.IOException;

import io.vertx.core.Completable;
import io.vertx.core.buffer.Buffer;

/** Logical peer operations; sockets and correlation IDs belong to the shared RPC runtime. */
final class TcpRaftConnection implements RaftConnection {
	private final TcpRaftRpc rpc;
	private final NodeEndpoint endpoint;

	TcpRaftConnection(TcpRaftRpc rpc, NodeEndpoint endpoint) { this.rpc = rpc; this.endpoint = endpoint; }

	@Override public void identify(Completable<RaftWireCodec.PeerIdentity> completion) {
		rpc.request(endpoint, "/identity", Buffer::buffer, (bytes, peer) -> {
			var result = RaftWireCodec.identity(bytes); verify(result.nodeId(), peer); return result;
		}, false, completion);
	}
	@Override public void membership(Completable<MemberStatus> completion) {
		rpc.request(endpoint, "/membership", Buffer::buffer, TcpRaftConnection::status, false, completion);
	}
	@Override public void genesis(Completable<RaftGenesis> completion) {
		rpc.request(endpoint, "/bootstrap", Buffer::buffer, (bytes, peer) -> RaftWireCodec.genesis(bytes), false, completion);
	}
	@Override public void install(RaftGenesis genesis, Completable<Void> completion) {
		rpc.request(endpoint, "/bootstrap/install", () -> RaftWireCodec.genesis(genesis),
			TcpRaftConnection::accepted, false, completion);
	}
	@Override public void join(JoinRequest request, Completable<MemberStatus> completion) {
		rpc.request(endpoint, "/join", () -> RaftWireCodec.join(request), TcpRaftConnection::status, false, completion);
	}
	@Override public void leave(LeaveRequest request, Completable<LeaveResult> completion) {
		rpc.request(endpoint, "/leave", () -> RaftWireCodec.leave(request), (bytes, peer) -> {
			var result = RaftWireCodec.left(bytes); verify(result.identity().nodeId(), peer); return result;
		}, false, completion);
	}
	@Override public void send(RaftElection.Envelope envelope, Completable<Void> completion) {
		boolean large = envelope.message() instanceof RaftElection.InstallSnapshot;
		if (envelope.message() instanceof RaftElection.AppendEntries append) {
			long characters = 0;
			for (var entry : append.entries()) characters += entry.data().length();
			large = characters > 4096;
		}
		rpc.request(endpoint, RaftWireCodec.suffix(envelope.message()), () -> RaftWireCodec.encode(envelope),
			TcpRaftConnection::accepted, large, completion);
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
