package com.inqwise.leader;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;

/**
 * Storage-free, cluster-scoped leader election using the configured cluster manager's shared map.
 * This is not durable Raft. Exclusivity depends on the provider's conditional-map operations and
 * partition policy; previously started external work cannot be revoked by a local demotion.
 * All protocol state and subscriber callbacks belong to the context captured at construction.
 */
public class LeaderConsensus {
	public class Keys {
		public static final String ADDRESS_UUID = "address_uuid";
		public static final String IS_LEADER = "is_leader";
		public static final String IS_ACKNOLEDGE = "is_acknoledge";
	}

	private static final Logger logger = LogManager.getLogger(LeaderConsensus.class);
	static final String MAP_NAME = "com.inqwise.leader.cluster-election.v1";
	private static final class Run {}

	private final String groupName;
	private final Vertx vertx;
	private final Context context;
	private final String id;
	private final long heartbeatMillis;
	private final long timeoutNanos;
	private final Supplier<Future<AsyncMap<String, String>>> maps;
	private final List<Consumer<Boolean>> callbacks = new ArrayList<>();
	// Only lifecycle intent and readiness observations cross threads. Mutations stay on context.
	private final AtomicReference<Run> requestedRun = new AtomicReference<>();
	private volatile Run currentRun;
	private volatile boolean leader;
	private volatile long confirmedAt;
	private volatile int approvalCounter;
	private volatile int refusalCounter;
	private AsyncMap<String, String> map;
	private String incarnation;
	private String heldRecord;
	private String observedRecord;
	private boolean observed;
	private long observedAt;
	private boolean busy;
	private Long timer;

	public LeaderConsensus(String leaderConsensusGroupName, Vertx vertx, LeaderConsensusOptions options) {
		this(leaderConsensusGroupName, vertx, options, null);
	}

	protected LeaderConsensus(String leaderConsensusGroupName, Vertx vertx, LeaderConsensusOptions options, String id) {
		this(leaderConsensusGroupName, vertx, options, id,
			() -> vertx.sharedData().<String, String>getAsyncMap(MAP_NAME));
	}

	LeaderConsensus(String groupName, Vertx vertx, LeaderConsensusOptions options, String id,
		Supplier<Future<AsyncMap<String, String>>> maps) {
		this.groupName = Objects.requireNonNull(groupName, "leaderConsensusGroupName");
		this.vertx = Objects.requireNonNull(vertx, "vertx");
		this.context = vertx.getOrCreateContext();
		Objects.requireNonNull(options, "options");
		this.id = id == null ? UUID.randomUUID().toString() : id;
		this.maps = Objects.requireNonNull(maps, "maps");
		heartbeatMillis = options.getValidateLeadingTimer();
		long heartbeatNanos = TimeUnit.MILLISECONDS.toNanos(heartbeatMillis);
		long threeHeartbeats = heartbeatNanos > Long.MAX_VALUE / 3 ? Long.MAX_VALUE : heartbeatNanos * 3;
		timeoutNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(options.getPingLeadingTimer()), threeHeartbeats);
	}

	/** Starts asynchronously; repeated calls while running are harmless. */
	public void start() {
		var run = new Run();
		if (!requestedRun.compareAndSet(null, run)) return;
		dispatch(() -> start(run));
	}

	private void start(Run run) {
		if (requestedRun.get() != run) return;
		if (currentRun != null) stop(currentRun);
		if (requestedRun.get() != run) return;
		currentRun = run;
		incarnation = UUID.randomUUID().toString();
		approvalCounter = 0;
		refusalCounter = 0;
		observed = false;
		busy = false;
		timer = vertx.setPeriodic(heartbeatMillis, ignored -> tick(run));
		tick(run);
	}

	private void tick(Run run) {
		if (!active(run)) return;
		if (leader && !getIsLeader()) changeLeader(false);
		if (!active(run) || busy) return;
		busy = true;
		if (map == null) {
			relay(maps, opened -> {
				if (!active(run)) return;
				busy = false;
				if (opened.failed()) failed(opened.cause());
				else if (opened.result() == null) failed(new IllegalStateException("Cluster returned no shared map"));
				else {
					map = opened.result();
					tick(run);
				}
			});
		} else {
			relay(() -> map.get(groupName), read -> {
				if (!active(run)) return;
				if (read.failed()) {
					busy = false;
					failed(read.cause());
				} else observed(run, read.result());
			});
		}
	}

	private void observed(Run run, String record) {
		long now = System.nanoTime();
		long observationTimeout;
		try {
			observationTimeout = record == null ? timeoutNanos : Math.max(timeoutNanos, recordTimeout(record));
		} catch (RuntimeException e) {
			busy = false;
			failed(e);
			return;
		}
		if (heldRecord != null && heldRecord.equals(record)) {
			claim(run, record);
			return;
		}
		heldRecord = null;
		changeLeader(false);
		if (!active(run)) return;
		if (!observed || !Objects.equals(observedRecord, record)) {
			observed = true;
			observedRecord = record;
			observedAt = now;
		}
		// Even an empty map waits: losing a record does not instantly revoke a previous owner.
		if (now - observedAt >= observationTimeout) claim(run, record);
		else busy = false;
	}

	private static long recordTimeout(String record) {
		var value = new JsonObject(record);
		Long timeout = value.getLong("timeoutNanos");
		if (!Integer.valueOf(1).equals(value.getInteger("version")) || timeout == null || timeout <= 0
			|| value.getString("owner") == null || value.getString("incarnation") == null
			|| value.getString("heartbeat") == null) {
			throw new IllegalStateException("Invalid cluster election record");
		}
		return timeout;
	}

	private void claim(Run run, String previous) {
		String next = new JsonObject().put("version", 1).put("timeoutNanos", timeoutNanos)
			.put("owner", id).put("incarnation", incarnation)
			.put("heartbeat", UUID.randomUUID().toString()).encode();
		AsyncMap<String, String> target = map;
		long dispatchedAt = System.nanoTime();
		relay(() -> previous == null
			? target.putIfAbsent(groupName, next).map(existing -> existing == null)
			: target.replaceIfPresent(groupName, previous, next), written -> {
			if (!active(run)) {
				// A claim may have succeeded after stop, or after a new run began.
				// Remove only that exact record, never the new run's ownership.
				if (written.succeeded() && written.result()) release(target, next);
				return;
			}
			busy = false;
			if (written.failed()) {
				failed(written.cause());
			} else if (!written.result()) {
				refusalCounter = increment(refusalCounter);
				heldRecord = null;
				observed = false;
				changeLeader(false);
			} else {
				approvalCounter = increment(approvalCounter);
				heldRecord = next;
				confirmedAt = dispatchedAt;
				// A delayed response must not extend local readiness from its delivery time.
				changeLeader(System.nanoTime() - dispatchedAt < timeoutNanos);
				if (active(run) && getIsLeader()) publish();
			}
		});
	}

	private void failed(Throwable cause) {
		observed = false;
		changeLeader(false);
		logger.warn("Cluster election operation failed for group {}", groupName, cause);
	}

	private void publish() {
		try {
			vertx.eventBus().publish(groupName, new JsonObject().put(Keys.ADDRESS_UUID, id)
				.put(Keys.IS_LEADER, true));
		} catch (Exception e) {
			logger.warn("Leader announcement failed for group {}", groupName, e);
		}
	}

	/** Invalidates readiness immediately; cleanup and callbacks execute on the owning context. */
	public void stop() {
		Run run = requestedRun.getAndSet(null);
		dispatch(() -> stop(run));
	}

	private void stop(Run run) {
		if (run == null || currentRun != run) return;
		requestedRun.compareAndSet(run, null);
		currentRun = null;
		if (timer != null) vertx.cancelTimer(timer);
		timer = null;
		AsyncMap<String, String> previousMap = map;
		String previousRecord = heldRecord;
		map = null;
		heldRecord = null;
		observedRecord = null;
		observed = false;
		busy = false;
		approvalCounter = 0;
		refusalCounter = 0;
		if (previousRecord != null) release(previousMap, previousRecord);
		changeLeader(false);
	}

	private void release(AsyncMap<String, String> target, String record) {
		relay(() -> target.removeIfPresent(groupName, record), result -> {
			if (result.failed()) logger.warn("Election record cleanup failed for group {}", groupName, result.cause());
		});
	}

	private void changeLeader(boolean value) {
		if (leader == value) return;
		leader = value;
		Run notifyingRun = currentRun;
		for (var callback : List.copyOf(callbacks)) {
			if (value && !getIsLeader()) return;
			try {
				callback.accept(value);
			} catch (Throwable e) {
				logger.error("Leadership subscriber failed for group {}", groupName, e);
				if (value) {
					stop(notifyingRun);
					return;
				}
			}
		}
	}

	private boolean active(Run run) { return currentRun == run && requestedRun.get() == run; }

	private void dispatch(Runnable operation) {
		if (Vertx.currentContext() == context) operation.run();
		else context.runOnContext(ignored -> operation.run());
	}

	private <T> void relay(Supplier<Future<T>> operation, Consumer<io.vertx.core.AsyncResult<T>> completion) {
		Future<T> result;
		try { result = Objects.requireNonNull(operation.get(), "operation future"); }
		catch (Throwable e) { result = Future.failedFuture(e); }
		ContextDispatch.relay(context, result).onComplete(completion::accept);
	}

	private static int increment(int count) { return count == Integer.MAX_VALUE ? count : count + 1; }

	/** Reads locally confirmed, unexpired ownership; this is not a destination fencing token. */
	public boolean getIsLeader() {
		Run run = currentRun;
		return run != null && requestedRun.get() == run && leader && System.nanoTime() - confirmedAt < timeoutNanos;
	}

	public void onLeaderChange(Consumer<Boolean> callback) {
		Objects.requireNonNull(callback, "callback");
		dispatch(() -> callbacks.add(callback));
	}

	/** Successful conditional ownership writes in this run; not a Raft vote count. */
	public Integer getApprovalCounter() { return approvalCounter; }

	/** Refused conditional ownership writes in this run; not a Raft vote count. */
	public Integer getRefusalCounter() { return refusalCounter; }
}
