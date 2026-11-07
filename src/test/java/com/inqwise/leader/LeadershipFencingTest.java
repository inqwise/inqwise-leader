package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.inqwise.leader.examples.FencedRegister;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

class LeadershipFencingTest {
	@TempDir Path directory;
	final Vertx vertx = Vertx.vertx();
	final List<VertxLeaderNode> nodes = new ArrayList<>();
	final RaftElection.Timing timing = new RaftElection.Timing(400, 850, 60);

	@AfterEach void shutdown() throws Exception {
		for (var node : nodes) await(node.close());
		await(vertx.close());
	}

	VertxLeaderNode node(String id, Set<String> voters, RaftTransport transport) {
		var store = VertxRaftStore.create(vertx, directory.resolve(id), "scheduled-job", id);
		var node = new VertxLeaderNode(store, "cluster", voters, timing, new Random(nodes.size() + 1), transport);
		nodes.add(node); return node;
	}
	<T> T eventually(Supplier<Future<T>> read, Predicate<T> condition) throws Exception {
		var result = Promise.<T>promise();
		long timer = vertx.setPeriodic(15, ignored -> read.get().onComplete(value -> {
			if (value.failed()) result.tryFail(value.cause());
			else if (condition.test(value.result())) result.tryComplete(value.result());
		}));
		try { return await(result.future()); } finally { vertx.cancelTimer(timer); }
	}
	VertxLeaderNode leader(List<VertxLeaderNode> candidates) throws Exception {
		return eventually(() -> Future.all(candidates.stream().map(VertxLeaderNode::leadership).toList()).map(observations -> {
			for (int i = 0; i < candidates.size(); i++) if (((Leadership) observations.resultAt(i)).leader()) return candidates.get(i);
			return (VertxLeaderNode) null;
		}), value -> value != null);
	}
	Future<Void> claim(FencedRegister destination, LeadershipToken token) {
		return Future.future(completion -> destination.claim(token, completion));
	}
	Future<Void> write(FencedRegister destination, LeadershipToken token, String value) {
		return Future.future(completion -> destination.write(token, value, completion));
	}
	FencedRegister.State read(FencedRegister destination) throws Exception {
		return await(Future.<FencedRegister.State>future(destination::read));
	}

	@Test void delayedOldLeaderWorkIsRejectedAfterTheReplacementClaimsTheDestination() throws Exception {
		Map<String, VertxLeaderNode> network = new ConcurrentHashMap<>();
		var isolated = new AtomicReference<String>();
		Set<String> voters = Set.of("node-a", "node-b", "node-c");
		RaftTransport transport = envelope -> {
			String blocked = isolated.get();
			if (!envelope.from().equals(blocked) && !envelope.to().equals(blocked)) {
				var recipient = network.get(envelope.to());
				if (recipient != null) recipient.receive(envelope).onFailure(ignored -> {});
			}
			return Future.succeededFuture();
		};
		for (String id : voters) network.put(id, node(id, voters, transport));
		for (var node : nodes) await(node.start());
		var original = leader(List.copyOf(nodes));
		var oldToken = await(original.fencingToken());
		var destination = new FencedRegister(vertx.getOrCreateContext(), "scheduled-job", "cluster");
		await(claim(destination, oldToken)); await(write(destination, oldToken, "original"));
		var resumeJob = Promise.<Void>promise();
		var delayedWrite = resumeJob.future().compose(ignored -> write(destination, oldToken, "stale-overwrite"));
		isolated.set(await(original.leadership()).leaderId());
		var replacement = leader(nodes.stream().filter(node -> node != original).toList());
		var newToken = await(replacement.fencingToken());
		assertEquals(oldToken.groupName(), newToken.groupName()); assertEquals(oldToken.clusterId(), newToken.clusterId());
		assertTrue(newToken.term() > oldToken.term());
		eventually(original::leadership, value -> !value.leader());
		assertThrows(Exception.class, () -> await(original.fencingToken()));
		var follower = nodes.stream().filter(node -> node != original && node != replacement).findFirst().orElseThrow();
		assertThrows(Exception.class, () -> await(follower.fencingToken()));
		// Election alone cannot invalidate a token at a destination that has not observed the newer term.
		await(write(destination, oldToken, "before-new-claim"));
		await(claim(destination, newToken)); await(write(destination, newToken, "replacement"));
		resumeJob.complete();
		assertThrows(Exception.class, () -> await(delayedWrite));
		assertTrue(delayedWrite.cause().getMessage().contains("Stale"));
		assertEquals(new FencedRegister.State(newToken.term(), "replacement"), read(destination));
	}

	@Test void retainedRestartUsesAHigherTermAndTokensAreUnavailableBeforeStartDuringDrainAndAfterClose() throws Exception {
		var original = node("node-a", Set.of("node-a"), envelope -> Future.succeededFuture());
		assertThrows(Exception.class, () -> await(original.fencingToken()));
		await(original.start()); leader(List.of(original)); var first = await(original.fencingToken());
		assertEquals("scheduled-job", first.groupName()); assertEquals("cluster", first.clusterId());
		assertEquals(await(original.leadership()).term(), first.term());
		await(original.drain()); assertThrows(Exception.class, () -> await(original.fencingToken()));
		await(original.close()); assertThrows(Exception.class, () -> await(original.fencingToken()));
		var restarted = node("node-a", Set.of("node-a"), envelope -> Future.succeededFuture());
		await(restarted.start()); leader(List.of(restarted)); var next = await(restarted.fencingToken());
		assertEquals(first.clusterId(), next.clusterId()); assertTrue(next.term() > first.term());
	}

	@Test void destinationRejectsForeignBindingsBeforeChangingItsFenceAndAllowsMultipleWritesWithinOneTerm() throws Exception {
		var destination = new FencedRegister(vertx.getOrCreateContext(), "scheduled-job", "cluster");
		var first = new LeadershipToken("scheduled-job", "cluster", 3);
		await(claim(destination, first)); await(write(destination, first, "keep"));
		for (var foreign : List.of(new LeadershipToken("scheduled-job", "another-cluster", Long.MAX_VALUE),
			new LeadershipToken("another-group", "cluster", Long.MAX_VALUE))) {
			assertThrows(Exception.class, () -> await(write(destination, foreign, "foreign")));
			assertEquals(new FencedRegister.State(3, "keep"), read(destination));
		}
		var next = new LeadershipToken("scheduled-job", "cluster", 4);
		await(claim(destination, next)); await(claim(destination, next));
		assertThrows(Exception.class, () -> await(write(destination, first, "stale")));
		assertEquals(new FencedRegister.State(4, "keep"), read(destination));
		await(write(destination, next, "same-term-first")); await(write(destination, next, "same-term-second"));
		assertEquals(new FencedRegister.State(4, "same-term-second"), read(destination));
	}

	@Test void tokenValuesRequireBoundedNonblankScopesAndPositiveTerms() {
		assertThrows(NullPointerException.class, () -> new LeadershipToken(null, "cluster", 1));
		assertThrows(NullPointerException.class, () -> new LeadershipToken("group", null, 1));
		for (String invalid : List.of("", " ", "a".repeat(1025))) {
			assertThrows(IllegalArgumentException.class, () -> new LeadershipToken(invalid, "cluster", 1));
			assertThrows(IllegalArgumentException.class, () -> new LeadershipToken("group", invalid, 1));
		}
		assertThrows(IllegalArgumentException.class, () -> new LeadershipToken("group", "cluster", 0));
		assertThrows(IllegalArgumentException.class, () -> new LeadershipToken("group", "cluster", -1));
		assertEquals(Long.MAX_VALUE, new LeadershipToken("group", "cluster", Long.MAX_VALUE).term());
	}
}
