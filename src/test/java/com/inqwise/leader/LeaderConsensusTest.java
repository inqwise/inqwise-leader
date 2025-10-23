package com.inqwise.leader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.Lock;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

@ExtendWith(VertxExtension.class)
class LeaderConsensusTest {
	private static final String GROUP = "test_group";
	private static final Logger logger = LogManager.getLogger(LeaderConsensusTest.class);
	
	@BeforeEach
	void setUp(Vertx vertx) throws Exception {
	}
	
	@Test
	@DisplayName("test approvals")
	void testApprovals(Vertx vertx, VertxTestContext testContext) {
		logger.debug("testApprovals");
		
		var options = LeaderConsensusOptions.builderFrom(new LeaderConsensusOptions()).withLeaderCycleMsgTime(100l).build();
		
		LeaderConsensus l1 = new LeaderConsensus(GROUP, vertx, options, "l1");
		LeaderConsensus l2 = new LeaderConsensus(GROUP, vertx, options, "l2");
		LeaderConsensus l3 = new LeaderConsensus(GROUP, vertx, options, "l3");
		AtomicInteger counter = new AtomicInteger();
		vertx.deployVerticle(new AbstractVerticle() {
			@Override
			public void start() throws Exception {
				l1.onLeaderChange(leader -> {
					if(counter.incrementAndGet() != 1) {
						testContext.failNow("counter not equal 1");
					} else {
						if(leader) {
							vertx.setTimer(1000l, res -> {
								try {
									Assertions.assertTrue(l1.getIsLeader());
									Assertions.assertFalse(l2.getIsLeader());
									Assertions.assertFalse(l3.getIsLeader());
									Assertions.assertTrue(l1.getApprovalCounter().intValue() == 2);
									Assertions.assertTrue(l1.getRefusalCounter().intValue() == 0);
									testContext.completeNow();
								} catch (Throwable e) {
									testContext.failNow(e);
								}
							});
						}
					}
				});
				
				l2.onLeaderChange(leader -> {
					testContext.failNow("not supposed to be leader");
				});
				
				l3.onLeaderChange(leader -> {
					testContext.failNow("not supposed to be leader");
				});
				
				l1.start();
				vertx.setTimer(500l, t -> {
					l2.start();
				});
				vertx.setTimer(1000l, t -> {
					l3.start();
				});
			}
			
			@Override
			public void stop() throws Exception {
				l1.stop();
				l2.stop();
				l3.stop();
			}
		}, new DeploymentOptions());		
	}
	
	
	@Test
	@DisplayName("test leaders")
	void testLeaders(Vertx vertx, VertxTestContext testContext) {
		logger.debug("testLeaders");
		
		var options = LeaderConsensusOptions.builderFrom(new LeaderConsensusOptions()).withLeaderCycleMsgTime(100l).build();
		
		LeaderConsensus l1 = new LeaderConsensus(GROUP, vertx, options, "l1");
		LeaderConsensus l2 = new LeaderConsensus(GROUP, vertx, options, "l2");
		AtomicInteger counterL1 = new AtomicInteger();
		AtomicInteger counterL2 = new AtomicInteger();
		vertx.deployVerticle(new AbstractVerticle() {
			@Override
			public void start() throws Exception {
				l1.onLeaderChange(leader -> {
					if(leader) {
						counterL1.incrementAndGet();
						vertx.setTimer(1000, id -> {
							l1.stop();
						});
					}
				});
				
				l2.onLeaderChange(leader -> {
					if(leader) {
						counterL2.incrementAndGet();
						vertx.setTimer(1000, id1 -> {
							l1.start();
							
							vertx.setTimer(1000, id2 -> {
								try {
									Assertions.assertTrue(leader);
									Assertions.assertFalse(l1.getIsLeader());
									Assertions.assertEquals(1, counterL1.get());
									Assertions.assertEquals(1, counterL2.get());
									testContext.completeNow();
								} catch (Throwable t) {
									testContext.failNow(t);
								}
							});
						});
					}
				});
				
				l1.start();
				
				vertx.setTimer(500l, t -> {
					l2.start();
				});
			}
			
			@Override
			public void stop() throws Exception {
				l1.stop();
				l2.stop();
			}
		}, new DeploymentOptions());		
	}

	@Test
	@DisplayName("public constructor creates random id and stop releases resources")
	void testPublicConstructorAndStop(Vertx vertx, VertxTestContext context) throws Exception {
		var options = LeaderConsensusOptions.builder().withLeaderCycleMsgTime(50L).withPendingToLeaderMsgTime(50L).build();
		LeaderConsensus consensus = new LeaderConsensus("cleanup_group", vertx, options);

		Field idField = LeaderConsensus.class.getDeclaredField("id");
		idField.setAccessible(true);
		String generatedId = (String) idField.get(consensus);
		context.verify(() -> Assertions.assertNotNull(generatedId));

		LeaderConsensus.Keys keys = consensus.new Keys();
		context.verify(() -> Assertions.assertNotNull(keys));

		RecordingLock lock = new RecordingLock();
		setField(consensus, "leaderLock", lock);
		setField(consensus, "leader", true);

		invoke(consensus, "startPendingToLeaderMsgTimer");
		invoke(consensus, "startPendingToLeaderMsgTimer");
		invoke(consensus, "startLeaderCycleMsgTimer");
		invoke(consensus, "startLeaderCycleMsgTimer");

		Long releaseTimer = vertx.setTimer(1000, id -> {});
		setField(consensus, "releaseLockApproveTimoutTimerId", releaseTimer);

		consensus.stop();

		context.verify(() -> {
			Assertions.assertFalse((Boolean) getField(consensus, "leader"));
			Assertions.assertTrue(lock.released.get());
			Assertions.assertNull(getField(consensus, "consumer"));
			Assertions.assertNull(getField(consensus, "privateConsumer"));
		});

		vertx.setTimer(20, id -> context.completeNow());
	}

	@Test
	@DisplayName("refusal messages demote leader and reset counters")
	void testRefusalFlow(Vertx vertx, VertxTestContext context) throws Exception {
		var options = LeaderConsensusOptions.builder().withLeaderCycleMsgTime(40L).withPendingToLeaderMsgTime(40L).build();
		LeaderConsensus leaderConsensus = new LeaderConsensus(GROUP, vertx, options, "refusal-node");
		setField(leaderConsensus, "leader", true);
		setField(leaderConsensus, "onLeaderChange", (Consumer<Boolean>) value -> {});

		Checkpoint refusalSent = context.checkpoint();
		Checkpoint refusalHandled = context.checkpoint();

		vertx.eventBus().consumer(GROUP + ".competitor", msg -> context.verify(() -> {
			JsonObject body = (JsonObject) msg.body();
			Assertions.assertFalse(body.getBoolean(LeaderConsensus.Keys.IS_ACKNOLEDGE));
			refusalSent.flag();
		}));

		leaderConsensus.start();

		vertx.setTimer(30, id -> {
			try {
				Long timerId = vertx.setTimer(1000, ignore -> {});
				setField(leaderConsensus, "releaseLockApproveTimoutTimerId", timerId);
				setField(leaderConsensus, "validateLeadingTimerId", vertx.setTimer(1000, ignore -> {}));
				RecordingLock lock = new RecordingLock();
				setField(leaderConsensus, "leaderLock", lock);
				setField(leaderConsensus, "isPending", false);

				JsonObject competitorClaim = new JsonObject()
					.put(LeaderConsensus.Keys.ADDRESS_UUID, "competitor")
					.put(LeaderConsensus.Keys.IS_LEADER, true);
				vertx.eventBus().publish(GROUP, competitorClaim);

				JsonObject refusal = new JsonObject()
					.put(LeaderConsensus.Keys.ADDRESS_UUID, "competitor")
					.put(LeaderConsensus.Keys.IS_LEADER, true)
					.put(LeaderConsensus.Keys.IS_ACKNOLEDGE, false);
				vertx.eventBus().send(GROUP + ".refusal-node", refusal);

				vertx.setTimer(80, done -> context.verify(() -> {
					Assertions.assertFalse(leaderConsensus.getIsLeader());
					Assertions.assertEquals(0, leaderConsensus.getApprovalCounter());
					Assertions.assertEquals(0, leaderConsensus.getRefusalCounter());
					Assertions.assertTrue(lock.released.get());
					refusalHandled.flag();
				}));
			} catch (Exception e) {
				context.failNow(e);
			}
		});
	}

	private static Object getField(LeaderConsensus consensus, String name) throws Exception {
		Field field = LeaderConsensus.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(consensus);
	}

	private static void setField(LeaderConsensus consensus, String name, Object value) throws Exception {
		Field field = LeaderConsensus.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(consensus, value);
	}

	private static void invoke(LeaderConsensus consensus, String name) throws Exception {
		Method method = LeaderConsensus.class.getDeclaredMethod(name);
		method.setAccessible(true);
		method.invoke(consensus);
	}

	private static final class RecordingLock implements Lock {
		private final AtomicBoolean released = new AtomicBoolean(false);

		@Override
		public void release() {
			released.set(true);
		}
	}

}
