package com.inqwise.leader;

import java.util.function.Supplier;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.internal.ContextInternal;

/** Native context-bound dispatch, including callbacks registered after a future completes. */
final class ContextDispatch {
	private ContextDispatch() {}

	static <T> Promise<T> promise(Context context) { return ((ContextInternal) context).promise(); }

	/** Relays completion on a later owner-context turn, preserving effect-batch and persistence ordering. */
	static <T> Future<T> relay(Context context, Future<T> source) {
		Promise<T> result = promise(context);
		source.onComplete(outcome -> context.runOnContext(ignored -> result.handle(outcome)));
		return result.future();
	}

	static <T> Future<T> call(Context context, Supplier<Future<T>> operation) {
		Promise<T> result = promise(context);
		context.runOnContext(ignored -> {
			try {
				operation.get().onComplete(result);
			} catch (Exception e) {
				result.tryFail(e);
			}
		});
		return result.future();
	}
}
