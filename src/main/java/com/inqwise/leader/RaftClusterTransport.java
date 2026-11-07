package com.inqwise.leader;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.vertx.core.Context;
import io.vertx.core.Future;

/** Cluster communication boundary consumed by membership control, independent of HTTP framing and codecs. */
public interface RaftClusterTransport extends RaftTransport {
	/**
	 * Returns the owner context.
	 * @return context owning cluster communication and callbacks
	 */
	Context context();
	/**
	 * Returns the configured designated authority.
	 * @return designated bootstrap authority, or null when automatic bootstrap is unavailable
	 */
	String bootstrapAuthorityId();
	/**
	 * Starts local communication without waiting for cluster readiness.
	 * @return actual listener port after local startup; does not imply leader readiness
	 */
	Future<Integer> listen();
	/**
	 * Closes transport-owned resources.
	 * @return resource cleanup, including accepted codec work
	 */
	Future<Void> close();
	/**
	 * Reads the concrete local listener or advertised address.
	 * @return concrete advertised/listening address
	 */
	Future<NodeEndpoint> localEndpoint();
	/**
	 * Reads retained verified peer endpoints.
	 * @return retained verified routes, including members missing from discovery
	 */
	Future<List<NodeEndpoint>> knownEndpoints();
	/**
	 * Prunes expired nonmember routes.
	 * @param members members whose routes must be retained
	 * @param ageMillis minimum verification age for pruning other routes
	 * @return cleanup completion
	 */
	Future<Void> pruneRoutes(Set<String> members, long ageMillis);
	/**
	 * Verifies a peer callback address.
	 * @param endpoint callback endpoint to verify
	 * @return verified persisted identity and binding
	 */
	Future<DiscoveredPeer> verifyPeer(NodeEndpoint endpoint);
	/**
	 * Observes peer identities for one discovery pass.
	 * @param endpoints discovery contacts; disappearance never changes quorum
	 * @return verified peers and per-contact failures
	 */
	Future<DiscoveryResult> discover(List<NodeEndpoint> endpoints);
	/**
	 * Forms or retries installation of the immutable original genesis.
	 * @param endpoints eligible initial contacts
	 * @return original genesis and installation failures
	 */
	Future<BootstrapResult> bootstrap(List<NodeEndpoint> endpoints);
	/**
	 * Fetches and installs the original genesis from the designated authority.
	 * @param authority original authority contact
	 * @return durably installed original genesis
	 */
	Future<RaftGenesis> fetchBootstrap(NodeEndpoint authority);
	/**
	 * Reads coherent peer cluster status.
	 * @param endpoint authenticated peer contact
	 * @return coherent membership and leadership observation
	 */
	Future<MemberStatus> memberStatus(NodeEndpoint endpoint);
	/**
	 * Requests learner admission and installs the resulting local binding.
	 * @param leader freshly observed ready leader
	 * @param advertised local callback endpoint
	 * @return durably installed learner admission; does not grant a vote
	 */
	Future<RaftGenesis> joinCluster(NodeEndpoint leader, NodeEndpoint advertised);
	/**
	 * Requests committed self-removal through the current leader.
	 * @param leader freshly observed ready leader
	 * @return committed removal evidence; failures can have an uncertain removal outcome
	 */
	Future<LeaveResult> leaveCluster(NodeEndpoint leader);
	/**
	 * Suppresses local admission attempts before departure.
	 * @return completion of suppressing new local admissions while departing
	 */
	Future<Void> beginLeave();
	/**
	 * Reads coherent local participant status.
	 * @return coherent local participant status, or failure while unavailable
	 */
	Future<MemberStatus> localMemberStatus();
	/**
	 * Attaches the participant that handles protocol traffic.
	 * @param node local participant to receive protocol messages and supply membership observations
	 * @return attachment completion
	 */
	Future<Void> attachNode(VertxLeaderNode node);
	/**
	 * Registers leader-side learner admission handling.
	 * @param handler leader-side admission policy, or null to disable
	 * @return registration completion
	 */
	Future<Void> admissionHandler(Function<JoinRequest, Future<MemberStatus>> handler);
	/**
	 * Registers leader-side self-removal handling.
	 * @param handler leader-side self-removal policy, or null to disable
	 * @return registration completion
	 */
	Future<Void> leaveHandler(Function<LeaveRequest, Future<LeaveResult>> handler);

	/**
	 * Original bootstrap installation outcome.
	 * @param genesis immutable original genesis
	 * @param failures selected voters not yet confirmed installed
	 */
	record BootstrapResult(RaftGenesis genesis, Map<String, String> failures) {
		/**
		 * Copies installation outcomes.
		 * @param genesis original genesis
		 * @param failures selected voter failures
		 */
		public BootstrapResult { failures = Map.copyOf(failures); }
	}

	/**
	 * One discovery observation; endpoints do not define voting membership.
	 * @param peers unambiguous verified peers
	 * @param failures failed or conflicting contacts
	 * @param conflicts incompatible assigned cluster IDs
	 */
	record DiscoveryResult(List<DiscoveredPeer> peers, Map<NodeEndpoint, String> failures, Set<String> conflicts) {
		/**
		 * Creates an observation without incompatible cluster claims.
		 * @param peers verified peers
		 * @param failures failed contacts
		 */
		public DiscoveryResult(List<DiscoveredPeer> peers, Map<NodeEndpoint, String> failures) {
			this(peers, failures, Set.of());
		}
		/**
		 * Copies discovery outcomes.
		 * @param peers verified peers
		 * @param failures failed contacts
		 * @param conflicts incompatible assigned cluster IDs
		 */
		public DiscoveryResult(List<DiscoveredPeer> peers, Map<NodeEndpoint, String> failures, Set<String> conflicts) {
			this.peers = List.copyOf(peers);
			this.failures = Map.copyOf(failures);
			this.conflicts = Set.copyOf(conflicts);
		}
	}
}
