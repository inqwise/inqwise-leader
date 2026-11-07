package com.inqwise.leader;

import io.vertx.core.Completable;

/** Caller completion and cancellation observation, independent of HTTP. Check activity on the owner context. */
interface RaftRequestCompletion<T> extends Completable<T> {
	boolean isActive();
}
