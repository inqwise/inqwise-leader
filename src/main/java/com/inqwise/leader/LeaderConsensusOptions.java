package com.inqwise.leader;

import java.util.Objects;

import io.vertx.core.json.JsonObject;

public class LeaderConsensusOptions {
	public class Keys {
		public static final String PENDING_TO_LEADER_MSG_TIME = "pending_to_leader_msg_time";
		public static final String LEADER_CYCLE_MSG_TIME = "leader_cycle_msg_time";
	}
	private static final long DEFAULT_PENDING_TO_LEADER_MSG_TIME = 2500;
	private static final long DEFAULT_LEADER_CYCLE_MSG_TIME = 1000;
	
	private	Long pendingToLeaderMsgTime;
	private Long leaderCycleMsgTime;

	private LeaderConsensusOptions(Builder builder) {
		this.pendingToLeaderMsgTime = validateTimer(builder.pendingToLeaderMsgTime, Keys.PENDING_TO_LEADER_MSG_TIME);
		this.leaderCycleMsgTime = validateTimer(builder.leaderCycleMsgTime, Keys.LEADER_CYCLE_MSG_TIME);
	}

	public LeaderConsensusOptions() {
		this.pendingToLeaderMsgTime = DEFAULT_PENDING_TO_LEADER_MSG_TIME;
		this.leaderCycleMsgTime = DEFAULT_LEADER_CYCLE_MSG_TIME;
	}
	
	public LeaderConsensusOptions(JsonObject json) {
		Objects.requireNonNull(json, "json");
		this.pendingToLeaderMsgTime = validateTimer(
			json.getLong(Keys.PENDING_TO_LEADER_MSG_TIME, DEFAULT_PENDING_TO_LEADER_MSG_TIME), Keys.PENDING_TO_LEADER_MSG_TIME);
		this.leaderCycleMsgTime = validateTimer(
			json.getLong(Keys.LEADER_CYCLE_MSG_TIME, DEFAULT_LEADER_CYCLE_MSG_TIME), Keys.LEADER_CYCLE_MSG_TIME);
	}

	private static Long validateTimer(Long duration, String name) {
		Objects.requireNonNull(duration, name);
		if (duration <= 0) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return duration;
	}

	public Long getPingLeadingTimer() {
		return pendingToLeaderMsgTime;
	}

	public Long getValidateLeadingTimer() {
		return leaderCycleMsgTime;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static Builder builderFrom(LeaderConsensusOptions leaderConsensusOptions) {
		return new Builder(leaderConsensusOptions);
	}

	public static final class Builder {
		private Long pendingToLeaderMsgTime = DEFAULT_PENDING_TO_LEADER_MSG_TIME;
		private Long leaderCycleMsgTime = DEFAULT_LEADER_CYCLE_MSG_TIME;

		private Builder() {
		}

		private Builder(LeaderConsensusOptions leaderConsensusOptions) {
			this.pendingToLeaderMsgTime = leaderConsensusOptions.pendingToLeaderMsgTime;
			this.leaderCycleMsgTime = leaderConsensusOptions.leaderCycleMsgTime;
		}

		public Builder withPendingToLeaderMsgTime(Long pendingToLeaderMsgTime) {
			this.pendingToLeaderMsgTime = pendingToLeaderMsgTime;
			return this;
		}

		public Builder withLeaderCycleMsgTime(Long leaderCycleMsgTime) {
			this.leaderCycleMsgTime = leaderCycleMsgTime;
			return this;
		}

		public LeaderConsensusOptions build() {
			return new LeaderConsensusOptions(this);
		}
	}
}
