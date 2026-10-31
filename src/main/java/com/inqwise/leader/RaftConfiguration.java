package com.inqwise.leader;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Effective stable or joint membership. Learners replicate but never contribute to a quorum.
 * @param oldVoters previous voters; equals newVoters for a stable configuration
 * @param newVoters desired voters
 * @param learners admitted replication-only participants
 */
public record RaftConfiguration(Set<String> oldVoters, Set<String> newVoters, Set<String> learners) {
	/** Maximum distinct participants, including learners. */
	public static final int MAX_MEMBERS = 63;

	/**
	 * Validates and copies membership sets.
	 * @param oldVoters old quorum
	 * @param newVoters new quorum
	 * @param learners non-voting participants
	 */
	public RaftConfiguration {
		oldVoters = copy(oldVoters); newVoters = copy(newVoters); learners = copy(learners);
		var voters = new HashSet<>(oldVoters); voters.addAll(newVoters);
		if (oldVoters.isEmpty() || newVoters.isEmpty() || !Collections.disjoint(voters, learners)) {
			throw new IllegalArgumentException("Nonempty voter sets must be disjoint from learners");
		}
		voters.addAll(learners);
		if (voters.size() > MAX_MEMBERS) throw new IllegalArgumentException("Too many participants");
	}

	/**
	 * Builds a stable configuration.
	 * @param voters voters
	 * @param learners replication-only members
	 * @return stable membership
	 */
	public static RaftConfiguration stable(Set<String> voters, Set<String> learners) {
		return new RaftConfiguration(voters, voters, learners);
	}
	/**
	 * Identifies a joint configuration.
	 * @return whether both voter sets differ
	 */
	public boolean joint() { return !oldVoters.equals(newVoters); }
	/**
	 * Reads all voters without counting learners.
	 * @return union of both voter sets
	 */
	public Set<String> voters() { var all = new TreeSet<>(oldVoters); all.addAll(newVoters); return Collections.unmodifiableSet(all); }
	/**
	 * Reads all replication participants.
	 * @return voters and learners
	 */
	public Set<String> members() { var all = new TreeSet<>(voters()); all.addAll(learners); return Collections.unmodifiableSet(all); }
	/**
	 * Tests separate majorities; a majority of the union is insufficient during a joint transition.
	 * @param supporters distinct supporting identities
	 * @return whether both quorum requirements are satisfied
	 */
	public boolean hasQuorum(Set<String> supporters) {
		return majority(oldVoters, supporters) && majority(newVoters, supporters);
	}

	String encode() {
		return "membership-v1|" + String.join(",", oldVoters) + "|" + String.join(",", newVoters)
			+ "|" + String.join(",", learners);
	}

	static RaftConfiguration decode(String value) {
		String[] parts = value.split("\\|", -1);
		if (parts.length != 4 || !parts[0].equals("membership-v1")) {
			throw new IllegalArgumentException("Invalid membership encoding");
		}
		var result = new RaftConfiguration(parse(parts[1]), parse(parts[2]), parse(parts[3]));
		if (!result.encode().equals(value)) throw new IllegalArgumentException("Membership must be canonical");
		return result;
	}

	static History history(Set<String> initial, List<RaftLogEntry> log, long commitIndex) {
		return history(initial, log, commitIndex, null);
	}

	static History history(Set<String> initial, List<RaftLogEntry> log, long commitIndex, RaftSnapshot snapshot) {
		if (snapshot != null && snapshot.configurationIndex() == 0
			&& !snapshot.configuration().equals(stable(initial, Set.of()))) {
			throw new IllegalArgumentException("A genesis checkpoint cannot change initial membership");
		}
		RaftConfiguration current = snapshot == null ? stable(initial, Set.of()) : snapshot.configuration();
		RaftConfiguration committed = current;
		RaftConfiguration previous = current;
		long index = snapshot == null ? 0 : snapshot.configurationIndex(); long committedAt = index;
		for (var entry : log) if (entry.kind() == RaftLogEntry.Kind.CONFIGURATION) {
			var next = decode(entry.data());
			validateTransition(current, next);
			previous = current; current = next; index = entry.index();
			if (index <= commitIndex) { committed = current; committedAt = index; }
		}
		return new History(current, index, committed, committedAt, previous);
	}

	private static void validateTransition(RaftConfiguration previous, RaftConfiguration next) {
		boolean valid;
		if (previous.joint()) {
			valid = !next.joint() && next.oldVoters.equals(previous.newVoters)
				&& next.learners.equals(previous.learners);
		} else if (next.joint()) {
			var promoted = new HashSet<>(next.newVoters); promoted.removeAll(previous.oldVoters);
			var remaining = new HashSet<>(previous.learners); remaining.removeAll(next.newVoters);
			valid = next.oldVoters.equals(previous.oldVoters) && previous.learners.containsAll(promoted)
				&& next.learners.equals(remaining);
		} else valid = next.oldVoters.equals(previous.oldVoters);
		if (!valid) throw new IllegalArgumentException("Invalid membership transition");
	}

	record History(RaftConfiguration effective, long index, RaftConfiguration committed, long committedIndex,
		RaftConfiguration previous) {}

	private static Set<String> copy(Set<String> values) {
		var sorted = new TreeSet<>(Objects.requireNonNull(values, "members"));
		sorted.forEach(RaftIdentity::validateNodeId);
		return Collections.unmodifiableSet(sorted);
	}
	private static Set<String> parse(String value) {
		if (value.isEmpty()) return Set.of();
		String[] ids = value.split(",", -1);
		var result = new TreeSet<>(List.of(ids));
		if (result.size() != ids.length) throw new IllegalArgumentException("Duplicate member");
		return result;
	}
	private static boolean majority(Set<String> members, Set<String> supporters) {
		return members.stream().filter(supporters::contains).count() > members.size() / 2;
	}
}
