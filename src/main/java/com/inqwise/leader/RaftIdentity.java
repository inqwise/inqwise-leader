package com.inqwise.leader;

import java.util.Objects;

/**
 * Persistent identity of one participant in one consensus group.
 *
 * @param nodeId stable node ID
 * @param groupName consensus group
 */
public record RaftIdentity(String nodeId, String groupName) {
	/**
	 * Creates a validated identity; does not generate or persist an ID.
	 * @param nodeId stable node ID
	 * @param groupName consensus group
	 */
	public RaftIdentity {
		validateNodeId(Objects.requireNonNull(nodeId, "nodeId"));
		Objects.requireNonNull(groupName, "groupName");
		if (groupName.isBlank() || groupName.length() > 1024) {
			throw new IllegalArgumentException("groupName must contain 1 through 1024 characters and not be blank");
		}
	}

	static void validateNodeId(String nodeId) {
		Objects.requireNonNull(nodeId, "nodeId");
		if (!nodeId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
			throw new IllegalArgumentException("nodeId must contain 1 through 128 ASCII letters, digits, '.', '_' or '-'"
				+ " and start with a letter or digit");
		}
	}
}
