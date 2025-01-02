package com.inqwise.leader;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.Lock;

public class LeaderConsensus {
	public class Keys {
		public static final String ADDRESS_UUID = "address_uuid";
		public static final String IS_LEADER = "is_leader";
		public static final String IS_ACKNOLEDGE = "is_acknoledge";
	}
	
	private static final Logger logger = LogManager.getLogger(LeaderConsensus.class);
	
	private final String leaderConsensusGroupName;
	private boolean leader;
	private Consumer<Boolean> onLeaderChange = l -> {
		logger.debug("currently leader");
		leader = l;
		};
	private Lock leaderLock;
	private Vertx vertx;
	private int approvalCounter;
	private int refusalCounter;
	private Long pingLeadingTimerId;
	private Long validateLeadingTimerId;
	private EventBus eventBus;
	private MessageConsumer<Object> consumer;
	private MessageConsumer<Object> privateConsumer;
	private String id;
	private LeaderConsensusOptions options;
	private boolean isPending;
	private Long releaseLockApproveTimoutTimerId;
	
	public LeaderConsensus(String leaderConsensusGroupName, Vertx vertx, LeaderConsensusOptions options) {
		this(leaderConsensusGroupName, vertx, options, null);
	}
	
	protected LeaderConsensus(String leaderConsensusGroupName, Vertx vertx, LeaderConsensusOptions options, String id) {
		this.leaderConsensusGroupName = Objects.requireNonNull(leaderConsensusGroupName, "leaderConsensusGroupName");
		this.vertx = Objects.requireNonNull(vertx, "vertx");
		this.eventBus = vertx.eventBus();
		this.options = Objects.requireNonNull(options, "options");
		this.id = null == id ? UUID.randomUUID().toString() : id;
		this.approvalCounter = 0;
		this.refusalCounter = 0;
	}
	
	public void start() {
		logger.trace("start");
		leaderConsensusLock();
	}
	
	private void leaderConsensusLock() {
		logger.trace("leaderConsensusLock leader:{}, id:{}, for group:{}", leader, id, leaderConsensusGroupName);
		
		registerToMessageFromLeader();
		registerToPrivateMessage();
		startPendingToLeaderMsgTimer();		
	}
	
	private void registerToMessageFromLeader() {
		logger.debug("registerToMessageFromLeader id:{}, in group:{}", id, leaderConsensusGroupName);
		consumer = eventBus.consumer(leaderConsensusGroupName);
		consumer.handler(msg -> {
			logger.trace("recieved leaderMsg for id:{}", id);
			JsonObject msgBody = (JsonObject)msg.body();
			logger.trace("uuid:{} got msg:{}", id, msgBody);
			//check if has leader
			String publisherUuid = Objects.requireNonNull(msgBody.getString(Keys.ADDRESS_UUID), Keys.ADDRESS_UUID);
			Boolean isLeader = Objects.requireNonNull(msgBody.getBoolean(Keys.IS_LEADER), Keys.IS_LEADER);
			
			if(!publisherUuid.equals( id)) {
				if(isLeader) {
					if(leader) {
						//refuse
						eventBus.send(combineStr(leaderConsensusGroupName, publisherUuid), new JsonObject().put(Keys.ADDRESS_UUID, id).put(Keys.IS_LEADER, leader).put(Keys.IS_ACKNOLEDGE, false));	
					} else {
						//acknoledge
						eventBus.send(combineStr(leaderConsensusGroupName, publisherUuid), new JsonObject().put(Keys.ADDRESS_UUID, id).put(Keys.IS_LEADER, leader).put(Keys.IS_ACKNOLEDGE, true));
						//reset timer
						startPendingToLeaderMsgTimer();
					}
				}
			}
		});
	}
	
	private void registerToPrivateMessage() {
		logger.trace("registerToPrivateMessage id:{}, in group:{}", id, leaderConsensusGroupName);
		privateConsumer = eventBus.consumer(combineStr(leaderConsensusGroupName, id));
		privateConsumer.handler(msg -> {
			logger.trace("recieved privateMsg for id:{}", id);
			JsonObject msgBody = (JsonObject)msg.body();
			logger.trace("uuid:{} got msg:{}", id, msgBody);
			String addressIp = Objects.requireNonNull(msgBody.getString(Keys.ADDRESS_UUID), Keys.ADDRESS_UUID);
			Boolean isLeader = Objects.requireNonNull(msgBody.getBoolean(Keys.IS_LEADER), Keys.IS_LEADER);
			Boolean isAcknoledge = Objects.requireNonNull(msgBody.getBoolean(Keys.IS_ACKNOLEDGE), Keys.IS_ACKNOLEDGE);
			
			if (null != releaseLockApproveTimoutTimerId){
				vertx.cancelTimer(releaseLockApproveTimoutTimerId);
			}
			releaseLock();
			
			if(isAcknoledge) {
				approvalCounter++;
			} else {
				refusalCounter++;
				if(leader) {
					//cancel leadership
					changeLeader(false);
					//cancel leaderTimer
					stopLeaderCycleMsgTimer();
					//wait for leader msg
					startPendingToLeaderMsgTimer();
				}
			}
		});
		
	}
	
	private void publishNewLeader() {
		logger.debug("publishNewLeader uuid:{}", id);
		vertx.sharedData().getLock(leaderConsensusGroupName).map(l -> {
			
			if(isPending) {
				logger.debug("id:{} is pending", id);
				l.release();
			} else {
				leaderLock = l;
				startLeaderCycleMsgTimer();
				publishLeader();
				releaseLockApproveTimoutTimerId = vertx.setTimer(options.getValidateLeadingTimer(), timerId-> {
					logger.debug("releasing without acknoledge id {}", id);
					releaseLock();
				});
			}
			return null;
		}).onFailure(e -> {
			if(!isPending) {
				startPendingToLeaderMsgTimer();
			}
		});
		
	}
	
	private synchronized void releaseLock() {
		logger.trace("releaseLock id:{}, leaderLock:{}, for group:{}", id, leaderLock, leaderConsensusGroupName);
		if(null != leaderLock) {
			leaderLock.release();
			leaderLock = null;
		}
	}
	
	private void fireOnLeaderChange () {
		if(null != onLeaderChange) {
			onLeaderChange.accept(leader);
		}
	}
	
	private void publishLeader() {
		logger.trace("publishLeader id:{}, for group:{}", id, leaderConsensusGroupName);
		eventBus.publish(leaderConsensusGroupName, new JsonObject().put(Keys.ADDRESS_UUID, id).put(Keys.IS_LEADER, leader));
		resetCounters();
	};
	
	private void resetCounters() {
		logger.trace("resetCounters");
		approvalCounter = 0;
		refusalCounter = 0;
	}

	private String combineStr(String str1, String str2) {
		return new StringBuilder().append(str1).append(".").append(str2).toString();
	}
	
	private final void validateLeadingTimerAction(long id) {
		if(null != consumer) {
			publishLeader();
			validateLeadingTimerId = vertx.setTimer(options.getValidateLeadingTimer(), this::validateLeadingTimerAction);
		}
	}
	
	private void startLeaderCycleMsgTimer() {
		logger.debug("startLeaderCycleMsgTimer id:{}, for group:{}", id, leaderConsensusGroupName);
		if (null == validateLeadingTimerId) {
			validateLeadingTimerId = vertx.setTimer(options.getValidateLeadingTimer(), this::validateLeadingTimerAction);
		} else {
			vertx.cancelTimer(validateLeadingTimerId.longValue());
			validateLeadingTimerId = vertx.setTimer(options.getValidateLeadingTimer(), this::validateLeadingTimerAction);
		}
	}
	
	private void stopLeaderCycleMsgTimer() {
		logger.debug("stopLeaderCycleMsgTimer id:{}, for group:{}", id, leaderConsensusGroupName);
		if(null != validateLeadingTimerId) {
			vertx.cancelTimer(validateLeadingTimerId.longValue());
		}
	}
	
	private final void pingLeadingTimerAction(long id) {
		if(null != consumer) {
			changeLeader(true);
			stopPendingToLeaderMsgTimer();
			publishNewLeader();
		}
	}
	
	private void startPendingToLeaderMsgTimer() {
		logger.trace("startPendingToLeaderMsgTimer id:{}, for group:{}", id, leaderConsensusGroupName);
		isPending = true;
		if(null == pingLeadingTimerId) {
			pingLeadingTimerId = vertx.setTimer(options.getPingLeadingTimer(), this::pingLeadingTimerAction);
		} else {
			vertx.cancelTimer(pingLeadingTimerId.longValue());
			pingLeadingTimerId = vertx.setTimer(options.getPingLeadingTimer(), this::pingLeadingTimerAction);
		}
	}
	
	private void stopPendingToLeaderMsgTimer() {
		logger.trace("stopPendingToLeaderMsgTimer id:{}, for group:{}", id, leaderConsensusGroupName);
		if(null != pingLeadingTimerId) {
			vertx.cancelTimer(pingLeadingTimerId.longValue());
		}
		isPending = false;
	}
	
	public void stop() {
		logger.trace("stop");
		if(null != consumer) {
			consumer.unregister();
			consumer = null;
		}
		if(null != privateConsumer) {
			privateConsumer.unregister();
			privateConsumer = null;
		}
		if(null != leaderLock) {
			leaderLock.release();
			leaderLock = null;
		}
		if(null != pingLeadingTimerId) {
			vertx.cancelTimer(pingLeadingTimerId.longValue());
			pingLeadingTimerId = null;
		}
		
		if(null != validateLeadingTimerId) {
			vertx.cancelTimer(validateLeadingTimerId.longValue());
			validateLeadingTimerId = null;
		}
		
		if(leader) {
			leader = false;
		}
	}
	
	private void changeLeader(boolean leader) {
		logger.debug("changeLeader id:{}, for group:{}", id, leaderConsensusGroupName);
		approvalCounter = 0;
		refusalCounter = 0;
		if(this.leader != leader) {
			this.leader = leader;
			fireOnLeaderChange();
		}
	}
	
	public boolean getIsLeader() {
		return leader;
	}
	
	public synchronized void onLeaderChange(Consumer<Boolean> callback) {
		onLeaderChange = onLeaderChange.andThen(callback);
	}
	
	public Integer getApprovalCounter() {
		return approvalCounter;
	}
	
	public Integer getRefusalCounter() {
		return refusalCounter;
	}
}
