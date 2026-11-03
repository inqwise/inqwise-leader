package com.inqwise.leader;

import io.vertx.core.Future;

/** Owner-context outgoing peer operations and lifecycle. */
interface RaftRpcClient {
	RaftConnection connection(NodeEndpoint endpoint);
	Future<Void> close();
}
