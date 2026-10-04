package com.inqwise.leader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

@ExtendWith(VertxExtension.class)
class LeaderConsensusRegressionTest {

	private static LeaderConsensusOptions fastOptions() {
		return LeaderConsensusOptions.builder()
			.withPendingToLeaderMsgTime(50L)
			.withLeaderCycleMsgTime(100L)
			.build();
	}

	@Test
	@DisplayName("contenders remain followers while the election lock is held")
	void testNoLeadershipBeforeLockAcquisition(Vertx vertx, VertxTestContext context) {
		String group = "blocked_election_regression";
		vertx.runOnContext(ignored -> vertx.sharedData().getLock(group).onComplete(context.succeeding(lock -> {
			LeaderConsensus first = new LeaderConsensus(group, vertx, fastOptions());
			LeaderConsensus second = new LeaderConsensus(group, vertx, fastOptions());
			AtomicInteger leadershipNotifications = new AtomicInteger();
			first.onLeaderChange(leader -> {
				if (leader) {
					leadershipNotifications.incrementAndGet();
				}
			});
			second.onLeaderChange(leader -> {
				if (leader) {
					leadershipNotifications.incrementAndGet();
				}
			});
			first.start();
			second.start();

			// Keep the lock unavailable for several election timeouts.
			vertx.setTimer(250, timer -> context.verify(() -> {
				try {
					Assertions.assertAll(
						() -> Assertions.assertFalse(first.getIsLeader(), "first contender must remain a follower"),
						() -> Assertions.assertFalse(second.getIsLeader(), "second contender must remain a follower"),
						() -> Assertions.assertEquals(0, leadershipNotifications.get(),
							"leadership must not be announced before acquiring the lock"));
					context.completeNow();
				} finally {
					first.stop();
					second.stop();
					lock.release();
				}
			}));
		})));
	}

	@Test
	@DisplayName("stopping a leader notifies subscribers of demotion exactly once")
	void testStopNotifiesLeadershipLoss(Vertx vertx, VertxTestContext context) {
		String group = "stop_notification_regression";
		vertx.runOnContext(ignored -> {
			LeaderConsensus consensus = new LeaderConsensus(group, vertx, fastOptions());
			List<Boolean> transitions = new ArrayList<>();
			consensus.onLeaderChange(transitions::add);
			var observer = vertx.eventBus().<JsonObject>consumer(group);
			observer.handler(message -> {
				if (Boolean.TRUE.equals(message.body().getBoolean(LeaderConsensus.Keys.IS_LEADER))) {
					observer.unregister();
					consensus.stop();
					consensus.stop();
					context.verify(() -> {
						Assertions.assertFalse(consensus.getIsLeader());
						Assertions.assertEquals(List.of(true, false), transitions,
							"subscribers must receive one demotion when an elected leader stops");
						context.completeNow();
					});
				}
			});
			// Wait for an actual leader publication rather than manufacturing leadership state.
			observer.completion().onComplete(context.succeeding(unused -> consensus.start()));
		});
	}

	@Test
	@DisplayName("stopping from a leadership callback releases the lock and prevents publication")
	void testStopFromLeadershipCallback(Vertx vertx, VertxTestContext context) {
		String group = "callback_stop_regression";
		vertx.runOnContext(ignored -> {
			LeaderConsensus consensus = new LeaderConsensus(group, vertx, fastOptions());
			List<Boolean> transitions = new ArrayList<>();
			AtomicInteger publications = new AtomicInteger();
			var observer = vertx.eventBus().<JsonObject>consumer(group,
				message -> publications.incrementAndGet());
			consensus.onLeaderChange(leader -> {
				transitions.add(leader);
				if (leader) {
					consensus.stop();
					vertx.sharedData().getLock(group).onComplete(context.succeeding(lock -> {
						lock.release();
						vertx.setTimer(250, timer -> context.verify(() -> {
							try {
								Assertions.assertEquals(List.of(true, false), transitions);
								Assertions.assertFalse(consensus.getIsLeader());
								Assertions.assertEquals(0, publications.get(),
									"stopping inside the callback must abort leader publication");
								context.completeNow();
							} finally {
								consensus.stop();
								observer.unregister();
							}
						}));
					}));
				}
			});
			observer.completion().onComplete(context.succeeding(unused -> consensus.start()));
		});
	}

	@Test
	@DisplayName("a failing leadership callback releases the election lock")
	void testFailingLeadershipCallbackReleasesLock(Vertx vertx, VertxTestContext context) {
		String group = "callback_failure_regression";
		vertx.runOnContext(ignored -> {
			LeaderConsensus consensus = new LeaderConsensus(group, vertx, fastOptions());
			List<Boolean> transitions = new ArrayList<>();
			consensus.onLeaderChange(leader -> {
				transitions.add(leader);
				if (leader) {
					throw new IllegalStateException("simulated subscriber failure");
				}
				consensus.stop();
				vertx.sharedData().getLock(group).onComplete(context.succeeding(lock -> {
					lock.release();
					context.verify(() -> {
						Assertions.assertFalse(consensus.getIsLeader());
						Assertions.assertEquals(List.of(true, false), transitions);
						context.completeNow();
					});
				}));
			});
			consensus.start();
		});
	}

	@Test
	@DisplayName("a stopped candidate does not publish when its pending lock request completes")
	void testStopWhileLockAcquisitionIsPending(Vertx vertx, VertxTestContext context) {
		String group = "pending_lock_stop_regression";
		vertx.runOnContext(ignored -> vertx.sharedData().getLock(group).onComplete(context.succeeding(lock -> {
			LeaderConsensus consensus = new LeaderConsensus(group, vertx, fastOptions());
			AtomicInteger publications = new AtomicInteger();
			var observer = vertx.eventBus().<JsonObject>consumer(group,
				message -> publications.incrementAndGet());
			observer.completion().onComplete(context.succeeding(unused -> {
				consensus.start();
				vertx.setTimer(250, timer -> {
					consensus.stop();
					lock.release();

					// A second acquisition waits behind the candidate's queued request. This ensures
					// that request has been processed before checking for any resulting publication.
					vertx.sharedData().getLock(group).onComplete(context.succeeding(barrierLock -> {
						barrierLock.release();
						vertx.setTimer(250, settled -> context.verify(() -> {
							try {
								Assertions.assertFalse(consensus.getIsLeader());
								Assertions.assertEquals(0, publications.get(),
									"a pending election must not publish after stop()");
								context.completeNow();
							} finally {
								consensus.stop();
								observer.unregister();
							}
						}));
					}));
				});
			}));
		})));
	}
}
