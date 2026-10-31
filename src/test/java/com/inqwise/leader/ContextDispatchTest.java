package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

class ContextDispatchTest {
	final Vertx vertx = Vertx.vertx();
	final Context context = vertx.getOrCreateContext();
	@AfterEach void shutdown() throws Exception { await(vertx.close()); }

	@Test void relayedForeignCompletionAndLateHandlersUseTheOwnerContext() throws Exception {
		var source = Promise.<String>promise();
		var relayed = ContextDispatch.relay(context, source.future());
		var first = Promise.<Context>promise();
		relayed.onSuccess(value -> first.complete(Vertx.currentContext()));
		source.complete("ready");
		assertEquals("ready", await(relayed)); assertSame(context, await(first.future()));
		var late = Promise.<Context>promise();
		relayed.onSuccess(value -> late.complete(Vertx.currentContext()));
		assertSame(context, await(late.future()));
	}

	@Test void completedFailuresAreRelayedOnALaterTurnWithoutWrappingTheirCause() throws Exception {
		var failure = new IllegalStateException("source failure");
		await(ContextDispatch.call(context, () -> {
			var handled = Promise.<Void>promise(); boolean[] registering = {true};
			var relayed = ContextDispatch.relay(context, Future.failedFuture(failure));
			relayed.onComplete(result -> {
				if (registering[0]) handled.tryFail("Completion reentered the current dispatch");
				else if (result.cause() != failure) handled.tryFail("Failure cause was replaced");
				else handled.tryComplete();
			});
			registering[0] = false; return handled.future();
		}));
	}
}
