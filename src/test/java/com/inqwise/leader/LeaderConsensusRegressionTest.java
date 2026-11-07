package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.LeaderConsensusTest.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.shareddata.AsyncMap;

class LeaderConsensusRegressionTest {
	final Vertx vertx = Vertx.vertx();
	final List<LeaderConsensus> nodes = new ArrayList<>();

	@AfterEach void shutdown() throws Exception {
		nodes.forEach(LeaderConsensus::stop);
		await(vertx.close());
	}

	LeaderConsensus node(String group, Faults faults) {
		var node = new LeaderConsensus(group, vertx, options(), "test-node", () -> Future.succeededFuture(faults.map));
		nodes.add(node); return node;
	}

	Faults faults() throws Exception {
		return new Faults(await(vertx.sharedData().<String, String>getAsyncMap(LeaderConsensus.MAP_NAME)));
	}

	@Test void noDistributedElectionLockIsRequired() throws Exception {
		var lock = await(vertx.sharedData().getLock("unrelated-lock"));
		try {
			var a = node("unrelated-lock", faults()); a.start();
			await(until(vertx, a::getIsLeader));
		} finally { lock.release(); }
	}

	@Test void failedProviderOperationsDemoteAndRecoveryRechecksOwnership() throws Exception {
		var faults = faults(); var a = node("outage", faults); a.start();
		await(until(vertx, a::getIsLeader));
		faults.unavailable = true;
		await(until(vertx, () -> !a.getIsLeader()));
		await(maintain(vertx, () -> !a.getIsLeader(), 250));
		faults.unavailable = false;
		await(until(vertx, a::getIsLeader));
	}

	@Test void delayedRenewalCannotExtendReadinessFromResponseDelivery() throws Exception {
		var faults = faults(); var a = node("delayed", faults);
		var changes = new CopyOnWriteArrayList<Boolean>(); a.onLeaderChange(changes::add); a.start();
		await(until(vertx, a::getIsLeader));
		faults.delayNextWrite.set(true);
		await(faults.applied.future());
		await(until(vertx, () -> !a.getIsLeader() && changes.contains(false)));
		// The delayed write was dispatched later than the preceding confirmed heartbeat.
		// Let its own readiness window expire as well before delivering its result.
		await(maintain(vertx, () -> !a.getIsLeader(), 210));
		faults.blockReads = true;
		faults.deliver();
		await(maintain(vertx, () -> !a.getIsLeader(), 250));
		assertEquals(List.of(true, false), changes);
	}

	@Test void stopDuringAnUnacknowledgedClaimReleasesOnlyThatClaim() throws Exception {
		var faults = faults(); var a = node("pending", faults);
		faults.delayNextWrite.set(true);
		var changes = new CopyOnWriteArrayList<Boolean>(); a.onLeaderChange(changes::add); a.start();
		await(faults.applied.future());
		assertFalse(a.getIsLeader());
		a.stop(); faults.deliver();
		await(until(vertx, () -> faults.delayed.future().isComplete()));
		await(until(vertx, () -> changes.isEmpty() && !a.getIsLeader()));
		await(until(vertx, () -> faults.removed.get()));
		assertNull(await(faults.delegate.get("pending")));
	}

	@Test void lateCleanupCannotRemoveAReplacementOwnersRecord() throws Exception {
		var faults = faults(); var a = node("replacement", faults);
		faults.delayNextWrite.set(true); a.start();
		await(faults.applied.future());
		String old = await(faults.delegate.get("replacement"));
		assertNotNull(old);
		assertTrue(await(faults.delegate.replaceIfPresent("replacement", old, "new-owner-record")));
		a.stop(); faults.deliver();
		await(until(vertx, () -> faults.removed.get()));
		assertEquals("new-owner-record", await(faults.delegate.get("replacement")));
	}

	@Test void callbackStopAbortsPublicationAndNotifiesDemotionOnce() throws Exception {
		var a = node("callback-stop", faults());
		var changes = new CopyOnWriteArrayList<Boolean>();
		var done = Promise.<Void>promise();
		var published = new java.util.concurrent.atomic.AtomicInteger();
		var observer = vertx.eventBus().consumer("callback-stop", msg -> published.incrementAndGet());
		await(observer.completion());
		a.onLeaderChange(value -> {
			changes.add(value);
			if (value) a.stop(); else done.tryComplete();
		});
		a.start(); await(done.future());
		await(maintain(vertx, () -> !a.getIsLeader() && published.get() == 0, 250));
		assertEquals(List.of(true, false), changes);
	}

	@Test void callbackFailureDemotesAndDoesNotSuppressOtherSubscribers() throws Exception {
		var a = node("callback-failure", faults());
		var stopped = Promise.<Void>promise();
		a.onLeaderChange(value -> { if (value) throw new IllegalStateException("subscriber failure"); });
		a.onLeaderChange(value -> { if (!value) stopped.tryComplete(); });
		a.start(); await(stopped.future());
		assertFalse(a.getIsLeader());
	}

	@Test void callbackRestartThenFailureDoesNotStopTheReplacementRun() throws Exception {
		var a = node("callback-restart", faults());
		var restart = new AtomicBoolean(true);
		var recovered = Promise.<Void>promise();
		a.onLeaderChange(value -> {
			if (!value) return;
			if (restart.compareAndSet(true, false)) {
				a.stop(); a.start();
				throw new IllegalStateException("old subscriber failed after restarting");
			}
			recovered.tryComplete();
		});
		a.start(); await(recovered.future());
		await(maintain(vertx, a::getIsLeader, 300));
	}

	@Test void concurrentLifecycleRequestsLeaveNoStoppedRunTimers() throws Exception {
		var timers = ConcurrentHashMap.<Long>newKeySet();
		var tracking = (Vertx) Proxy.newProxyInstance(Vertx.class.getClassLoader(), new Class<?>[] {Vertx.class},
			(proxy, method, args) -> {
				Object result;
				try { result = method.invoke(vertx, args); }
				catch (InvocationTargetException e) { throw e.getCause(); }
				if (method.getName().equals("setPeriodic")) timers.add((Long) result);
				if (method.getName().equals("cancelTimer")) timers.remove((Long) args[0]);
				return result;
			});
		var a = new LeaderConsensus("concurrent", tracking, options()); nodes.add(a);
		try (var workers = Executors.newFixedThreadPool(6)) {
			var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
			for (int worker = 0; worker < 6; worker++) {
				tasks.add(() -> {
					for (int cycle = 0; cycle < 500; cycle++) { a.start(); a.stop(); }
					return null;
				});
			}
			for (var result : workers.invokeAll(tasks)) result.get();
		}
		a.stop();
		await(until(vertx, timers::isEmpty));
		assertFalse(a.getIsLeader());
		// Requests made after the concurrent callers finish must still be usable.
		a.start(); await(until(vertx, a::getIsLeader));
		a.stop(); await(until(vertx, timers::isEmpty));
	}

	@Test void restartedInstanceIgnoresAnOldRunsDelayedWriteCompletion() throws Exception {
		var faults = faults(); var a = node("restart", faults);
		faults.delayNextWrite.set(true); a.start();
		await(faults.applied.future());
		a.stop(); a.start(); faults.deliver();
		await(until(vertx, a::getIsLeader));
		await(maintain(vertx, a::getIsLeader, 300));
	}

	@Test void emptyMapResetDoesNotImmediatelyCreateANewOwner() throws Exception {
		var faults = faults(); var a = node("reset", faults); a.start();
		await(until(vertx, a::getIsLeader));
		// Lose the shared map while the old process remains alive and renewal is unavailable.
		faults.blockReads = true;
		await(faults.delegate.clear());
		var b = new LeaderConsensus("reset", vertx, options()); nodes.add(b); b.start();
		await(maintain(vertx, () -> !(a.getIsLeader() && b.getIsLeader()), 500));
		await(until(vertx, b::getIsLeader));
		assertFalse(a.getIsLeader());
	}

	@Test void startupMapFailureRetriesWithoutAnnouncingLeadership() throws Exception {
		var actual = faults();
		var available = new AtomicBoolean();
		var a = new LeaderConsensus("startup", vertx, options(), "test", () -> available.get()
			? Future.succeededFuture(actual.map) : Future.failedFuture("map unavailable"));
		nodes.add(a); a.start();
		await(maintain(vertx, () -> !a.getIsLeader(), 250));
		available.set(true);
		await(until(vertx, a::getIsLeader));
	}

	@Test void contenderHonorsTheOwnersLongerTimeout() throws Exception {
		var faults = faults();
		var slow = LeaderConsensusOptions.builder().withPendingToLeaderMsgTime(700L)
			.withLeaderCycleMsgTime(30L).build();
		var a = new LeaderConsensus("different-timeouts", vertx, slow, "slow", () -> Future.succeededFuture(faults.map));
		nodes.add(a); a.start(); await(until(vertx, a::getIsLeader));
		faults.blockReads = true;
		var b = new LeaderConsensus("different-timeouts", vertx, options()); nodes.add(b); b.start();
		await(maintain(vertx, () -> !b.getIsLeader(), 400));
		await(until(vertx, b::getIsLeader));
		assertFalse(a.getIsLeader());
	}

	@Test void unrecognizedSharedRecordFailsClosed() throws Exception {
		var faults = faults(); await(faults.delegate.put("corrupt", "not-an-election-record"));
		var a = node("corrupt", faults); a.start();
		await(maintain(vertx, () -> !a.getIsLeader(), 400));
		assertEquals("not-an-election-record", await(faults.delegate.get("corrupt")));
	}

	static final class Faults {
		final AsyncMap<String, String> delegate;
		final AsyncMap<String, String> map;
		volatile boolean unavailable;
		volatile boolean blockReads;
		final AtomicBoolean delayNextWrite = new AtomicBoolean();
		final AtomicBoolean removed = new AtomicBoolean();
		final Promise<Void> applied = Promise.promise();
		final Promise<Object> delayed = Promise.promise();
		volatile Object delayedResult;

		@SuppressWarnings("unchecked")
		Faults(AsyncMap<String, String> delegate) {
			this.delegate = delegate;
			map = (AsyncMap<String, String>) Proxy.newProxyInstance(AsyncMap.class.getClassLoader(),
				new Class<?>[] {AsyncMap.class}, (proxy, method, args) -> {
					if (unavailable) return Future.failedFuture("cluster unavailable");
					if (method.getName().equals("get") && blockReads) return Promise.promise().future();
					Object returned;
					try { returned = method.invoke(delegate, args); }
					catch (InvocationTargetException e) { throw e.getCause(); }
					if (method.getName().equals("removeIfPresent")) {
						((Future<?>) returned).onComplete(result -> removed.set(true));
					}
					if ((method.getName().equals("putIfAbsent") || method.getName().equals("replaceIfPresent"))
						&& delayNextWrite.compareAndSet(true, false)) {
						((Future<?>) returned).onComplete(result -> {
							if (result.failed()) applied.tryFail(result.cause());
							else { delayedResult = result.result(); applied.tryComplete(); }
						});
						return delayed.future();
					}
					return returned;
				});
		}

		void deliver() { delayed.tryComplete(delayedResult); }
	}
}
