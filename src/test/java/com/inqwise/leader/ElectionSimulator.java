package com.inqwise.leader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Assertions;

import com.inqwise.leader.RaftElection.CancelTimer;
import com.inqwise.leader.RaftElection.Effect;
import com.inqwise.leader.RaftElection.Envelope;
import com.inqwise.leader.RaftElection.AppendEntries;
import com.inqwise.leader.RaftElection.AppendResponse;
import com.inqwise.leader.RaftElection.Committed;
import com.inqwise.leader.RaftElection.Role;
import com.inqwise.leader.RaftElection.RoleChanged;
import com.inqwise.leader.RaftElection.ScheduleTimer;
import com.inqwise.leader.RaftElection.Send;
import com.inqwise.leader.RaftElection.TimerKind;
import com.inqwise.leader.RaftElection.VoteRequest;
import com.inqwise.leader.RaftElection.VoteResponse;
import com.inqwise.leader.RaftElection.WriteState;

/** Virtual clock, independent durable disks, and a lossy/reordering transport with replayable seeds. */
final class ElectionSimulator {
	static final String GROUP = "election-test";
	static final String CLUSTER = "cluster-test";
	private record Task(long time, long order, Runnable action) {}
	private record VoteKey(String node, long term) {}

	final Map<String, RaftElection> nodes = new HashMap<>();
	final Map<String, RaftPersistentState> disks = new HashMap<>();
	final Map<Long, RaftLogEntry> committed = new HashMap<>();
	final Map<String, Map<Long, RaftLogEntry>> applied = new HashMap<>();
	final Map<Long, String> elected = new HashMap<>();
	final List<String> history = new ArrayList<>();
	final Set<String> isolated = new HashSet<>();
	int dropPercent;
	int maxNetworkDelay = 3;
	int persistenceDelay;
	boolean duplicateMessages;

	private final List<String> members;
	private final Set<String> initialVoters;
	private final long seed;
	private final Random network;
	private final PriorityQueue<Task> tasks = new PriorityQueue<>(
		Comparator.comparingLong(Task::time).thenComparingLong(Task::order));
	private final Set<ScheduleTimer> canceled = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
	private final Map<String, ScheduleTimer> electionTimers = new HashMap<>();
	private final Map<String, Integer> restarts = new HashMap<>();
	private final Map<String, Long> observedTerms = new HashMap<>();
	private final Map<VoteKey, String> castVotes = new HashMap<>();
	private long now;
	private long order;

	ElectionSimulator(long seed, List<String> members) {
		this(seed, members, Set.copyOf(members));
	}

	ElectionSimulator(long seed, List<String> members, Set<String> initialVoters) {
		this.initialVoters = Set.copyOf(initialVoters);
		this.seed = seed;
		this.members = List.copyOf(members);
		network = new Random(seed);
		for (String member : members) {
			disks.put(member, new RaftPersistentState(new RaftHardState(0, null), List.of(), 0));
		}
		for (String member : members) {
			restart(member);
		}
	}

	void restart(String member) {
		crash(member);
		int incarnation = restarts.merge(member, 1, Integer::sum);
		var node = new RaftElection(new RaftIdentity(member, GROUP), CLUSTER, initialVoters, disks.get(member),
			new RaftElection.Timing(100, 200, 25),
			new Random(seed + 31L * members.indexOf(member) + 997L * incarnation), !initialVoters.contains(member));
		nodes.put(member, node);
		// An unacknowledged volatile term can be lost on a crash. The durable disk
		// must never go backwards; live protocol state is monotonic within each incarnation.
		observedTerms.put(member, disks.get(member).hardState().currentTerm());
		accept(member, node, node.start());
		checkInvariants();
	}

	void crash(String member) {
		RaftElection node = nodes.remove(member);
		if (node != null) {
			accept(member, node, node.stop());
		}
		electionTimers.remove(member);
	}

	void forceElection(String member) {
		RaftElection node = nodes.get(member);
		accept(member, node, node.onTimeout(electionTimers.get(member)));
		checkInvariants();
	}

	void advance(long millis) {
		long target = now + millis;
		int steps = 0;
		while (!tasks.isEmpty() && tasks.peek().time() <= target) {
			Assertions.assertTrue(++steps < 100_000, "simulation did not advance");
			Task task = tasks.remove();
			now = task.time();
			task.action().run();
			checkInvariants();
		}
		now = target;
	}

	void propose(String member, String data) {
		RaftElection node = nodes.get(member);
		accept(member, node, node.propose(data));
		checkInvariants();
	}

	void compact(String member) {
		var source = nodes.get(member); var point = source.snapshotPoint();
		accept(member, source, source.compact(new RaftSnapshot(point.index(), point.term(), point.configuration(),
			point.configurationIndex(), "checkpoint-at-" + point.index()))); checkInvariants();
	}

	void addLearner(String leader, String learner) {
		var source = nodes.get(leader); accept(leader, source, source.addLearner(learner)); checkInvariants();
	}

	void removeLearner(String leader, String learner) {
		var source = nodes.get(leader); accept(leader, source, source.removeLearner(learner)); checkInvariants();
	}

	void changeVoters(String leader, Set<String> voters) {
		var source = nodes.get(leader); accept(leader, source, source.changeVoters(voters)); checkInvariants();
	}

	String soleLeader() {
		List<String> leaders = nodes.entrySet().stream()
			.filter(entry -> entry.getValue().snapshot().role() == Role.LEADER).map(Map.Entry::getKey).sorted().toList();
		Assertions.assertEquals(1, leaders.size(), "expected one leader: " + leaders);
		return leaders.getFirst();
	}

	private void accept(String member, RaftElection source, List<Effect> effects) {
		for (Effect effect : effects) {
			history.add(now + ":" + member + ":" + effect);
			switch (effect) {
				case WriteState write -> schedule(persistenceDelay, () -> {
					if (nodes.get(member) == source) {
						persist(member, write.persistentState());
						// Durability and callback delivery are separate crash boundaries.
						schedule(1, () -> {
							if (nodes.get(member) == source) {
								accept(member, source, source.onPersistenceSucceeded(write));
							}
						});
					}
				});
				case Send send -> {
					checkSend(member, send.envelope());
					transmit(send.envelope());
					if (duplicateMessages && network.nextBoolean()) {
						transmit(send.envelope());
					}
				}
				case ScheduleTimer timer -> {
					if (timer.kind() == TimerKind.ELECTION) {
						electionTimers.put(member, timer);
					}
					schedule(timer.delayMillis(), () -> {
						if (!canceled.remove(timer) && nodes.get(member) == source) {
							accept(member, source, source.onTimeout(timer));
						}
					});
				}
				case CancelTimer cancel -> canceled.add(cancel.timer());
				case RoleChanged changed -> {
					if (changed.snapshot().role() == Role.LEADER) {
						Assertions.assertTrue(source.membership().effective().hasQuorum(changed.snapshot().votes()),
							"election lacked separate configuration majorities");
						String previous = elected.putIfAbsent(changed.snapshot().hardState().currentTerm(), member);
						Assertions.assertTrue(previous == null || previous.equals(member),
							"two nodes were elected in the same term");
						for (RaftLogEntry entry : committed.values()) {
							var state = source.replication().state();
							if (entry.index() > state.baseIndex()) {
								Assertions.assertEquals(entry, state.entryAt(entry.index()), "new leader lost a committed entry");
							}
						}
					}
				}
				case Committed notification -> {
					RaftLogEntry entry = notification.entry();
					Assertions.assertTrue(entry.index() <= disks.get(member).commitIndex());
					Assertions.assertEquals(entry, disks.get(member).entryAt(entry.index()));
					RaftLogEntry prior = committed.putIfAbsent(entry.index(), entry);
					Assertions.assertTrue(prior == null || prior.equals(entry), "committed entries disagree");
					prior = applied.computeIfAbsent(member, ignored -> new HashMap<>()).putIfAbsent(entry.index(), entry);
					Assertions.assertTrue(prior == null || prior.equals(entry), "replay changed an applied entry");
				}
				case RaftElection.Failure failure -> Assertions.fail("unexpected simulated failure", failure.cause());
			}
		}
	}

	private void persist(String member, RaftPersistentState next) {
		RaftPersistentState old = disks.get(member);
		RaftHardState state = next.hardState();
		RaftHardState previous = old.hardState();
		Assertions.assertTrue(state.currentTerm() >= previous.currentTerm());
		if (previous.currentTerm() == state.currentTerm() && previous.votedFor() != null) {
			Assertions.assertEquals(previous.votedFor(), state.votedFor());
		}
		if (state.votedFor() != null) {
			String previousVote = castVotes.putIfAbsent(new VoteKey(member, state.currentTerm()), state.votedFor());
			Assertions.assertTrue(previousVote == null || previousVote.equals(state.votedFor()));
		}
		Assertions.assertTrue(next.commitIndex() >= old.commitIndex(), "durable commitment decreased");
		Assertions.assertDoesNotThrow(() -> RaftStoreFormat.validateTransition(old, next), "committed history changed");
		disks.put(member, next);
	}

	private void checkSend(String member, Envelope envelope) {
		RaftHardState disk = disks.get(member).hardState();
		Assertions.assertEquals(disk.currentTerm(), envelope.message().term(), "message preceded durable term");
		if (envelope.message() instanceof VoteRequest) {
			Assertions.assertEquals(member, disk.votedFor(), "vote request preceded durable self-vote");
		} else if (envelope.message() instanceof VoteResponse response && response.granted()) {
			Assertions.assertEquals(envelope.to(), disk.votedFor(), "vote grant preceded durable vote");
		} else if (envelope.message() instanceof AppendEntries request) {
			Assertions.assertEquals(member, elected.get(envelope.message().term()));
			for (RaftLogEntry entry : request.entries()) {
				Assertions.assertEquals(entry, disks.get(member).entryAt(entry.index()),
					"replication send preceded durable append");
			}
		} else if (envelope.message() instanceof AppendResponse response && response.success()) {
			Assertions.assertTrue(response.matchIndex() <= disks.get(member).lastIndex(),
				"success preceded durable follower append");
		} else if (envelope.message() instanceof RaftElection.InstallSnapshot request) {
			Assertions.assertTrue(request.snapshot().lastIncludedIndex() <= disks.get(member).baseIndex());
		} else if (envelope.message() instanceof RaftElection.SnapshotResponse response && response.accepted()) {
			Assertions.assertTrue(response.lastIncludedIndex() <= disks.get(member).commitIndex());
		}
	}

	private void transmit(Envelope envelope) {
		if (network.nextInt(100) < dropPercent || isolated.contains(envelope.from())
			|| isolated.contains(envelope.to())) {
			return;
		}
		schedule(1 + network.nextInt(maxNetworkDelay), () -> {
			RaftElection receiver = nodes.get(envelope.to());
			if (receiver != null && !isolated.contains(envelope.from()) && !isolated.contains(envelope.to())) {
				accept(envelope.to(), receiver, receiver.onMessage(envelope));
			}
		});
	}

	private void schedule(long delay, Runnable action) {
		tasks.add(new Task(now + delay, ++order, action));
	}

	private void checkInvariants() {
		for (var entry : nodes.entrySet()) {
			String member = entry.getKey();
			var state = entry.getValue().snapshot();
			long previous = observedTerms.getOrDefault(member, 0L);
			Assertions.assertTrue(state.hardState().currentTerm() >= previous, "term decreased within an incarnation");
			observedTerms.put(member, state.hardState().currentTerm());
			if (state.role() == Role.LEADER) {
				Assertions.assertEquals(state.hardState(), disks.get(member).hardState());
				Assertions.assertEquals(member, state.hardState().votedFor());

			}
		}
		for (String left : members) {
			for (String right : members) {
				var a = disks.get(left); var b = disks.get(right);
				long start = Math.max(a.baseIndex(), b.baseIndex()) + 1;
				for (long index = start; index <= Math.min(a.lastIndex(), b.lastIndex()); index++) {
					if (a.termAt(index) == b.termAt(index)) {
						for (long prefix = start; prefix <= index; prefix++) {
							Assertions.assertEquals(a.entryAt(prefix), b.entryAt(prefix), "log matching violated");
						}
					}
				}
			}
		}
	}
}
