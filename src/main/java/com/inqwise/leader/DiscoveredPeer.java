package com.inqwise.leader;

/**
 * Identity verified against the remote TLS certificate and identity endpoint.
 * This is routing information, not voting membership.
 * @param identity persisted peer identity
 * @param clusterId remote cluster binding, or null while unassigned
 * @param endpoint concrete verified IPv4 endpoint
 * @param departing whether this transport instance has begun graceful departure
 */
public record DiscoveredPeer(RaftIdentity identity, String clusterId, NodeEndpoint endpoint, boolean departing) {
	/**
	 * Creates a peer observation without departure intent.
	 * @param identity persisted peer identity
	 * @param clusterId existing binding, or null
	 * @param endpoint verified endpoint
	 */
	public DiscoveredPeer(RaftIdentity identity, String clusterId, NodeEndpoint endpoint) {
		this(identity, clusterId, endpoint, false);
	}
}
