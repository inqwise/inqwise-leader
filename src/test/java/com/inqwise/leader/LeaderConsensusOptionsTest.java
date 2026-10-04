package com.inqwise.leader;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.vertx.core.json.JsonObject;

class LeaderConsensusOptionsTest {

	@ParameterizedTest
	@NullSource
	@ValueSource(longs = { 0L, -1L })
	@DisplayName("builder rejects null and nonpositive timer durations")
	void testBuilderRejectsInvalidTimers(Long duration) {
		Class<? extends RuntimeException> expected = duration == null
			? NullPointerException.class : IllegalArgumentException.class;
		Assertions.assertAll(
			() -> Assertions.assertThrows(expected,
				() -> LeaderConsensusOptions.builder().withPendingToLeaderMsgTime(duration).build()),
			() -> Assertions.assertThrows(expected,
				() -> LeaderConsensusOptions.builder().withLeaderCycleMsgTime(duration).build()));
	}

	@ParameterizedTest
	@ValueSource(longs = { 0L, -1L })
	@DisplayName("json options reject nonpositive timer durations")
	void testJsonRejectsInvalidTimers(long duration) {
		Assertions.assertAll(
			() -> Assertions.assertThrows(IllegalArgumentException.class, () -> new LeaderConsensusOptions(
				new JsonObject().put(LeaderConsensusOptions.Keys.PENDING_TO_LEADER_MSG_TIME, duration))),
			() -> Assertions.assertThrows(IllegalArgumentException.class, () -> new LeaderConsensusOptions(
				new JsonObject().put(LeaderConsensusOptions.Keys.LEADER_CYCLE_MSG_TIME, duration))));
	}

	@ParameterizedTest(name = "pending configured={0}, cycle configured={1}")
	@CsvSource({ "false, false", "true, false", "false, true" })
	@DisplayName("builder uses defaults for each unset timer")
	void testBuilderDefaults(boolean configurePending, boolean configureCycle) {
		LeaderConsensusOptions defaults = new LeaderConsensusOptions();
		var builder = LeaderConsensusOptions.builder();
		if (configurePending) {
			builder.withPendingToLeaderMsgTime(42L);
		}
		if (configureCycle) {
			builder.withLeaderCycleMsgTime(84L);
		}
		LeaderConsensusOptions options = builder.build();

		Assertions.assertAll(
			() -> Assertions.assertEquals(configurePending ? 42L : defaults.getPingLeadingTimer(),
				options.getPingLeadingTimer(), "an unset pending timer must retain its default"),
			() -> Assertions.assertEquals(configureCycle ? 84L : defaults.getValidateLeadingTimer(),
				options.getValidateLeadingTimer(), "an unset cycle timer must retain its default"));
	}

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
