package com.inqwise.leader.examples;

import java.util.Objects;
import java.util.function.Supplier;

import com.inqwise.leader.LeadershipToken;

import io.vertx.core.Completable;
import io.vertx.core.Context;

/**
 * Compiled destination example. State is in memory: a real destination must persist binding, highest term and data atomically.
 * This register is the destination itself; its checks cannot fence writes to a different, unchecked external service.
 */
public final class FencedRegister {
	/** Immutable observation of the example resource. */
	public record State(long highestTerm, String value) {}
	private final Context context;
	private final String groupName;
	private final String clusterId;
	private long highestTerm;
	private String value;

	/** Provision the resource explicitly from trusted group/cluster configuration, never from an incoming request. */
	public FencedRegister(Context context, String groupName, String clusterId) {
		this.context = Objects.requireNonNull(context, "context");
		if (!context.isEventLoopContext()) throw new IllegalArgumentException("An event-loop context is required");
		var binding = new LeadershipToken(groupName, clusterId, 1);
		this.groupName = binding.groupName(); this.clusterId = binding.clusterId();
	}

	/** Register a ready leader's term before allowing its application work to begin. Equal terms are idempotent. */
	public void claim(LeadershipToken token, Completable<Void> completion) {
		execute(() -> { accept(token); return null; }, completion);
	}

	/** Check the token and change the resource together in one owner-context operation. */
	public void write(LeadershipToken token, String next, Completable<Void> completion) {
		execute(() -> {
			Objects.requireNonNull(next, "next");
			accept(token); value = next; return null;
		}, completion);
	}

	/** Read the current example state. */
	public void read(Completable<State> completion) {
		execute(() -> new State(highestTerm, value), completion);
	}

	private void accept(LeadershipToken token) {
		Objects.requireNonNull(token, "token");
		if (!groupName.equals(token.groupName()) || !clusterId.equals(token.clusterId())) {
			throw new IllegalArgumentException("Resource is bound to another group or cluster");
		}
		if (token.term() < highestTerm) throw new IllegalStateException("Stale fencing term");
		highestTerm = token.term();
	}

	private <T> void execute(Supplier<T> operation, Completable<T> completion) {
		Objects.requireNonNull(completion, "completion");
		context.runOnContext(ignored -> {
			T result;
			try { result = operation.get(); } catch (Exception e) { completion.fail(e); return; }
			completion.succeed(result);
		});
	}
}
