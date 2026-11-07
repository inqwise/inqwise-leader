package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MembershipPolicyTest {
	static final Set<String> ABC = Set.of("a", "b", "c");
	MembershipView view(RaftConfiguration configuration, Set<String> healthy, Map<String, Long> matched,
		boolean ready, boolean busy) {
		return new MembershipView(new Leadership(ready, 2, "a"),
			new RaftElection.MembershipSnapshot(configuration, 3, configuration, 3), matched, 10, healthy, busy);
	}
	MembershipPolicy.Decision plan(MembershipView view, int target, Map<String, Long> missing, long now) {
		return MembershipPolicy.plan("a", view, target, missing, now, 500);
	}

	@Test void healthyVotersRemainSelectedWhenCandidatesArrive() {
		var current = RaftConfiguration.stable(ABC, Set.of("d", "e"));
		assertInstanceOf(MembershipPolicy.None.class, plan(view(current, current.members(), Map.of("d", 10L, "e", 10L),
			true, false), 3, Map.of(), 1000));
	}

	@Test void replacementRequiresDelayCatchupAndRetainsTargetCount() {
		var current = RaftConfiguration.stable(ABC, Set.of("d"));
		var healthy = Set.of("a", "c", "d");
		var caught = view(current, healthy, Map.of("d", 10L), true, false);
		assertInstanceOf(MembershipPolicy.None.class, plan(caught, 3, Map.of("b", 0L), 499));
		assertInstanceOf(MembershipPolicy.None.class, plan(view(current, healthy, Map.of("d", 9L), true, false),
			3, Map.of("b", 0L), 1000));
		var replacement = assertInstanceOf(MembershipPolicy.ChangeVoters.class, plan(caught, 3, Map.of("b", 0L), 500));
		assertEquals(Set.of("a", "c", "d"), replacement.voters());
	}

	@Test void minorityAndUnsettledOperationsNeverAuthorizeReplacement() {
		var current = RaftConfiguration.stable(ABC, Set.of("d"));
		for (var state : java.util.List.of(view(current, Set.of("a", "d"), Map.of("d", 10L), false, false),
			view(current, Set.of("a", "d"), Map.of("d", 10L), true, false),
			view(current, Set.of("a", "c", "d"), Map.of("d", 10L), true, true))) {
			assertInstanceOf(MembershipPolicy.None.class, plan(state, 3, Map.of("b", 0L, "c", 0L), 1000));
		}
		var joint = new RaftConfiguration(ABC, Set.of("a", "c", "d"), Set.of());
		assertInstanceOf(MembershipPolicy.None.class, plan(view(joint, joint.members(), Map.of(), true, false),
			3, Map.of("b", 0L), 1000));
	}

	@ParameterizedTest @ValueSource(ints = {3, 7, 9})
	void promotionConvergesTowardPersistedTargetWithoutInventingVoters(int target) {
		var current = RaftConfiguration.stable(Set.of("a"), Set.of("d", "e"));
		var result = assertInstanceOf(MembershipPolicy.ChangeVoters.class,
			plan(view(current, current.members(), Map.of("d", 10L, "e", 10L), true, false), target, Map.of(), 1000));
		assertEquals(Set.of("a", "d", "e"), result.voters());
		assertTrue(result.voters().size() <= target);
	}

	@Test void expiredLearnersAreEvictedWithoutReducingVotingQuorum() {
		var current = RaftConfiguration.stable(ABC, Set.of("d"));
		var result = assertInstanceOf(MembershipPolicy.RemoveLearner.class,
			plan(view(current, ABC, Map.of("d", 0L), true, false), 3, Map.of("d", 0L), 1000));
		assertEquals("d", result.identity());
	}
}
