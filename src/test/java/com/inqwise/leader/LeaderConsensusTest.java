package com.inqwise.leader;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
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

}
