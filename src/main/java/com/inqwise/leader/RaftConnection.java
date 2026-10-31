package com.inqwise.leader;

import io.vertx.core.Completable;

/** Owner-context peer operations. Completion of send/install confirms HTTP acceptance, not a Raft acknowledgement. */
interface RaftConnection {
	void identify(Completable<RaftWireCodec.PeerIdentity> completion);
	void membership(Completable<MemberStatus> completion);
	void genesis(Completable<RaftGenesis> completion);
	void install(RaftGenesis genesis, Completable<Void> completion);
	void join(JoinRequest request, Completable<MemberStatus> completion);
	void leave(LeaveRequest request, Completable<LeaveResult> completion);
	void send(RaftElection.Envelope envelope, Completable<Void> completion);
}
