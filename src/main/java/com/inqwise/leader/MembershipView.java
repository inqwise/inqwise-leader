package com.inqwise.leader;

import java.util.Map;
import java.util.Set;

/**
 * One context-consistent observation for membership control.
 * @param leadership current local readiness and term
 * @param membership effective and committed configurations
 * @param matchIndex confirmed current-leader replication progress
 * @param commitIndex locally durable committed prefix
 * @param responsiveMembers peers with recent correlated durable replication acknowledgements, plus self
 * @param busy whether another local write/proposal is outstanding
 */
public record MembershipView(Leadership leadership, RaftElection.MembershipSnapshot membership,
	Map<String, Long> matchIndex, long commitIndex, Set<String> responsiveMembers, boolean busy) {
	/**
	 * Copies mutable diagnostic collections.
	 * @param leadership readiness
	 * @param membership configurations
	 * @param matchIndex progress
	 * @param commitIndex durable commitment
	 * @param responsiveMembers recent responders
	 * @param busy outstanding operation flag
	 */
	public MembershipView {
		matchIndex = Map.copyOf(matchIndex); responsiveMembers = Set.copyOf(responsiveMembers);
	}
}
