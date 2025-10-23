package com.inqwise.leader;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonObject;

class LeaderConsensusOptionsTest {

	@Test
	@DisplayName("json constructor falls back to defaults")
	void testJsonConstructorDefaults() {
		JsonObject json = new JsonObject();
		LeaderConsensusOptions options = new LeaderConsensusOptions(json);
		Assertions.assertEquals(2500L, options.getPingLeadingTimer());
		Assertions.assertEquals(1000L, options.getValidateLeadingTimer());
	}

	@Test
	@DisplayName("builder customises timers and builderFrom copies values")
	void testBuilderVariants() {
		LeaderConsensusOptions tuned = LeaderConsensusOptions.builder()
			.withPendingToLeaderMsgTime(42L)
			.withLeaderCycleMsgTime(84L)
			.build();
		Assertions.assertEquals(42L, tuned.getPingLeadingTimer());
		Assertions.assertEquals(84L, tuned.getValidateLeadingTimer());

		LeaderConsensusOptions copied = LeaderConsensusOptions.builderFrom(tuned)
			.withLeaderCycleMsgTime(21L)
			.build();
		Assertions.assertEquals(42L, copied.getPingLeadingTimer());
		Assertions.assertEquals(21L, copied.getValidateLeadingTimer());
	}

	@Test
	@DisplayName("keys inner classes are instantiable for coverage")
	void testKeysConstructors() {
		LeaderConsensusOptions options = new LeaderConsensusOptions();
		LeaderConsensusOptions.Keys optionKeys = options.new Keys();
		Assertions.assertNotNull(optionKeys);

		Assertions.assertNotNull(options.new Keys());
	}
}
