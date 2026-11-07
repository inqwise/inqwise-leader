package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

class LeaderConsensusTest {
	final Vertx vertx = Vertx.vertx();
	final List<LeaderConsensus> nodes = new ArrayList<>();

	static LeaderConsensusOptions options() {
		return LeaderConsensusOptions.builder().withPendingToLeaderMsgTime(180L)
			.withLeaderCycleMsgTime(30L).build();
	}

	LeaderConsensus node(String group) {
		var node = new LeaderConsensus(group, vertx, options());
		nodes.add(node);
		return node;
	}

	@AfterEach void shutdown() throws Exception {
		nodes.forEach(LeaderConsensus::stop);
		await(vertx.close());
	}

	@Test void simultaneousContendersHaveOneOwnerAndFailOverAfterStop() throws Exception {
		var a = node("workers"); var b = node("workers"); var c = node("workers");
		var contenders = List.of(a, b, c);
		contenders.forEach(LeaderConsensus::start);
		await(until(vertx, () -> leaders(contenders) == 1));
		await(maintain(vertx, () -> leaders(contenders) == 1, 500));
		var previous = contenders.stream().filter(LeaderConsensus::getIsLeader).findFirst().orElseThrow();
		previous.stop();
		assertFalse(previous.getIsLeader(), "stop must invalidate readiness synchronously");
		await(until(vertx, () -> leaders(contenders) == 1));
		assertFalse(previous.getIsLeader());
		previous.start();
		await(maintain(vertx, () -> leaders(contenders) == 1 && !previous.getIsLeader(), 500));
	}

	@Test void lateArrivalDoesNotDisplaceAHealthyOwner() throws Exception {
		var a = node("late"); a.start();
		await(until(vertx, a::getIsLeader));
		var b = node("late"); b.start();
		await(maintain(vertx, () -> a.getIsLeader() && !b.getIsLeader(), 500));
		assertTrue(a.getApprovalCounter() > 0);
		assertEquals(0, a.getRefusalCounter());
	}

	@Test void separateGroupsElectIndependently() throws Exception {
		var a = node("group"); var b = node("group.suffix");
		a.start(); b.start();
		await(until(vertx, () -> a.getIsLeader() && b.getIsLeader()));
		a.stop();
		await(maintain(vertx, b::getIsLeader, 300));
	}

	@Test void repeatedStartAndStopAreIdempotentAndCallbacksUseTheOwnerContext() throws Exception {
		Context owner = vertx.getOrCreateContext();
		var a = await(ContextDispatch.call(owner, () -> Future.succeededFuture(node("lifecycle"))));
		var changes = new CopyOnWriteArrayList<Boolean>();
		var elected = Promise.<Void>promise(); var stopped = Promise.<Void>promise();
		a.onLeaderChange(value -> {
			assertSame(owner, Vertx.currentContext());
			changes.add(value);
			if (value) elected.tryComplete(); else stopped.tryComplete();
		});
		a.start(); a.start();
		await(elected.future());
		a.stop(); a.stop();
		await(stopped.future());
		assertEquals(List.of(true, false), changes);
		assertEquals(0, a.getApprovalCounter()); assertEquals(0, a.getRefusalCounter());
		assertNotNull(a.new Keys());
	}

	@Test void oldUncorrelatedEventBusMessagesCannotGrantOrRevokeOwnership() throws Exception {
		var a = node("messages"); a.start();
		await(until(vertx, a::getIsLeader));
		vertx.eventBus().publish("messages", new JsonObject()
			.put(LeaderConsensus.Keys.ADDRESS_UUID, "old-participant")
			.put(LeaderConsensus.Keys.IS_LEADER, true).put(LeaderConsensus.Keys.IS_ACKNOLEDGE, false));
		vertx.eventBus().publish("messages", "malformed old traffic");
		await(maintain(vertx, a::getIsLeader, 300));
	}

	@Test void identicalDiagnosticIdsStillHaveIndependentRunIdentities() throws Exception {
		var a = new LeaderConsensus("ids", vertx, options(), "same-id");
		var b = new LeaderConsensus("ids", vertx, options(), "same-id");
		nodes.add(a); nodes.add(b); a.start(); b.start();
		await(until(vertx, () -> leaders(List.of(a, b)) == 1));
		await(maintain(vertx, () -> leaders(List.of(a, b)) == 1, 400));
	}

	static long leaders(List<LeaderConsensus> nodes) {
		return nodes.stream().filter(LeaderConsensus::getIsLeader).count();
	}

	static Future<Void> until(Vertx vertx, BooleanSupplier condition) {
		return check(vertx, condition, 5000, false);
	}

	static Future<Void> maintain(Vertx vertx, BooleanSupplier condition, long millis) {
		return check(vertx, condition, millis, true);
	}

	private static Future<Void> check(Vertx vertx, BooleanSupplier condition, long millis, boolean maintain) {
		var result = Promise.<Void>promise();
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
		class Poll implements Runnable {
			public void run() {
				try {
					boolean satisfied = condition.getAsBoolean();
					if (maintain && !satisfied) result.tryFail(new AssertionError("Condition became false"));
					else if (!maintain && satisfied) result.tryComplete();
					else if (System.nanoTime() >= deadline) {
						if (maintain) result.tryComplete();
						else result.tryFail(new AssertionError("Condition did not become true"));
					} else vertx.setTimer(10, ignored -> run());
				} catch (Throwable e) { result.tryFail(e); }
			}
		}
		vertx.runOnContext(ignored -> new Poll().run());
		return result.future();
	}
}
