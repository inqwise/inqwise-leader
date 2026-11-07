package com.inqwise.leader;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Pure policy decisions; Raft remains responsible for authorizing and committing every change. */
final class MembershipPolicy {
	sealed interface Decision permits ChangeVoters, RemoveLearner, None {}
	record ChangeVoters(Set<String> voters) implements Decision { ChangeVoters { voters = Set.copyOf(voters); } }
	record RemoveLearner(String identity) implements Decision {}
	record None() implements Decision {}

	private MembershipPolicy() {}

	static Decision plan(String self, MembershipView view, int target, Map<String, Long> missingSince,
		long now, long replacementDelay) {
		var membership = view.membership(); var configuration = membership.effective();
		if (!view.leadership().leader() || view.busy() || configuration.joint()
			|| !configuration.hasQuorum(view.responsiveMembers())
			|| membership.configurationIndex() > view.commitIndex()) return new None();
		var desired = new TreeSet<>(configuration.voters());
		var caughtUp = configuration.learners().stream().filter(view.responsiveMembers()::contains)
			.filter(id -> view.matchIndex().getOrDefault(id, 0L) >= view.commitIndex()).sorted().toList();
		var failed = configuration.voters().stream().filter(id -> !id.equals(self))
			.filter(id -> expired(id, missingSince, now, replacementDelay))
			.sorted(Comparator.<String>comparingLong(missingSince::get).thenComparing(Comparator.naturalOrder())).toList();
		if (desired.size() < target && !caughtUp.isEmpty()) {
			caughtUp.stream().limit(target - desired.size()).forEach(desired::add);
			return new ChangeVoters(desired);
		}
		if (desired.size() > target) {
			String victim = failed.isEmpty() ? desired.descendingSet().stream().filter(id -> !id.equals(self))
				.findFirst().orElse(null) : failed.getFirst();
			if (victim != null) { desired.remove(victim); return new ChangeVoters(desired); }
		}
		if (desired.size() == target && !failed.isEmpty() && !caughtUp.isEmpty()) {
			desired.remove(failed.getFirst()); desired.add(caughtUp.getFirst()); return new ChangeVoters(desired);
		}
		return configuration.learners().stream().filter(id -> expired(id, missingSince, now, replacementDelay)).sorted()
			.<Decision>map(RemoveLearner::new).findFirst().orElseGet(None::new);
	}

	private static boolean expired(String id, Map<String, Long> missing, long now, long delay) {
		Long since = missing.get(id); return since != null && now - since >= delay;
	}
}
