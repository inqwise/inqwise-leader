package com.inqwise.leader;

import java.util.function.ToIntFunction;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

/** Typed operation registration shared by HTTP and TCP framing. Status codes describe dispatch outcomes. */
interface RaftRpcServer {
	@FunctionalInterface interface Decoder<T> { T decode(Buffer bytes) throws Exception; }
	@FunctionalInterface interface Encoder<T> { Buffer encode(T value) throws Exception; }
	@FunctionalInterface interface RequestHandler<T, R> {
		void handle(String peer, T message, RaftRequestCompletion<R> completion);
	}
	<R> void get(String operation, Encoder<R> encoder, RequestHandler<Void, R> handler,
		ToIntFunction<String> rejection, int failureStatus);
	<T, R> void post(String operation, Decoder<T> decoder, Encoder<R> encoder, RequestHandler<T, R> handler,
		ToIntFunction<String> rejection, int failureStatus);
	Future<Integer> listen();
	int actualPort();
	Future<Void> close();
}
