package com.inqwise.leader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/** Test-only child JVM; the stdin control channel is never part of the production HTTP protocol. */
public final class RaftProcessNode {
	static final String PREFIX = "RAFT_PROCESS_TEST ";
	private final Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(2).setWorkerPoolSize(4)
		.setInternalBlockingPoolSize(2));
	private final TreeMap<Long, String> application = new TreeMap<>();
	private LeaderCoordinator coordinator;
	private String applicationFailure;
	private LeaderOptions options;

	public static void main(String[] args) throws Exception {
		if (args[0].equals("store-crash")) { crashStore(args); return; }
		var child = new RaftProcessNode();
		try {
			child.options = LeaderOptions.builder(new JsonObject(java.nio.file.Files.readString(Path.of(args[1])))).build();
			String id = child.options.getNodeId();
			child.coordinator = LeaderCoordinator.create(child.vertx, child.options,
				HttpRaftTransportTest.serverOptions(id), HttpRaftTransportTest.clientOptions(id));
			child.coordinator.exceptionHandler(cause -> {});
			await(child.coordinator.snapshotHandlers(point -> Future.succeededFuture(child.applicationData(point.index()).encode()),
				snapshot -> {
					child.application.clear();
					if (snapshot.data().isEmpty()) return Future.succeededFuture();
					for (Object item : new JsonArray(snapshot.data())) {
						var entry = (JsonObject) item; child.application.put(entry.getLong("index"), entry.getString("data"));
					}
					return Future.succeededFuture();
				}, entry -> {
					if (entry.kind() == RaftLogEntry.Kind.DATA) {
						String previous = child.application.putIfAbsent(entry.index(), entry.data());
						if (previous != null && !previous.equals(entry.data())) child.applicationFailure = "Conflicting applied entry " + entry.index();
					}
				}));
			await(child.coordinator.listen(Integer.parseInt(args[2])));
			emit(new JsonObject().put("event", "ready").put("nodeId", child.coordinator.identity().nodeId()));
			try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
				String line;
				while ((line = input.readLine()) != null) {
					var request = new JsonObject(line); long sequence = request.getLong("id");
					String operation = request.getString("operation");
					try {
						JsonObject result = await(child.command(operation, request));
						emit(new JsonObject().put("id", sequence).put("ok", true).put("result", result));
					} catch (Exception e) {
						emit(new JsonObject().put("id", sequence).put("ok", false).put("error", rootMessage(e)));
					}
					if (operation.equals("close") || operation.equals("leave")) break;
				}
			}
		} catch (Exception e) {
			emit(new JsonObject().put("event", "failed").put("error", rootMessage(e)));
			child.cleanup(); System.exit(2);
		} finally { child.cleanup(); }
	}

	private Future<JsonObject> command(String operation, JsonObject request) {
		return switch (operation) {
			case "status" -> status();
			case "propose" -> coordinator.participant().compose(node -> node.propose(request.getString("data")))
				.map(entry -> new JsonObject().put("index", entry.index()).put("term", entry.term()).put("data", entry.data()));
			case "compact" -> coordinator.participant().compose(VertxLeaderNode::compact)
				.map(snapshot -> new JsonObject().put("index", snapshot.lastIncludedIndex()));
			case "close" -> coordinator.close().map(new JsonObject());
			case "leave" -> coordinator.leave(5000).map(new JsonObject());
			default -> Future.failedFuture("Unknown test operation");
		};
	}

	private Future<JsonObject> status() {
		return ContextDispatch.call(coordinator.context(), () -> vertx.fileSystem()
			.readFile(options.resolvedDataDirectory().resolve("raft-state.bin").toString())
			.compose(bytes -> coordinator.context().executeBlocking(() -> RaftStoreFormat.decode(bytes.getBytes()), true))
			.compose(stored -> coordinator.participant().compose(node -> node.membershipView().compose(view ->
				node.replication().map(replication -> {
					var state = replication.durableState(); var genesis = stored.bootstrap().genesis();
					var log = new JsonArray();
					for (var entry : state.log()) log.add(new JsonObject().put("index", entry.index()).put("term", entry.term())
						.put("kind", entry.kind().name()).put("data", entry.data()));
					return new JsonObject().put("nodeId", coordinator.identity().nodeId()).put("leader", view.leadership().leader())
						.put("term", state.hardState().currentTerm()).put("vote", state.hardState().votedFor())
						.put("commitIndex", state.commitIndex()).put("lastIndex", state.lastIndex()).put("log", log)
						.put("snapshotIndex", state.baseIndex()).put("applied", applicationData(Long.MAX_VALUE))
						.put("applicationFailure", applicationFailure).put("clusterId", genesis.clusterId())
						.put("genesisDigest", genesis.digest()).put("target", genesis.targetVoterCount())
						.put("voters", new JsonArray(new ArrayList<>(view.membership().committed().voters())))
						.put("learners", new JsonArray(new ArrayList<>(view.membership().committed().learners())))
						.put("joint", view.membership().committed().joint()).put("effectiveJoint", view.membership().effective().joint());
				})))));
	}

	private JsonArray applicationData(long through) {
		var data = new JsonArray();
		application.forEach((index, value) -> { if (index <= through) data.add(new JsonObject().put("index", index).put("data", value)); });
		return data;
	}

	private void cleanup() {
		try { if (coordinator != null) await(coordinator.close()); } catch (Exception ignored) { }
		try { await(vertx.close()); } catch (Exception ignored) { }
	}

	private static void crashStore(String[] args) throws Exception {
		var stage = FileRaftStore.WriteStage.valueOf(args[2]);
		try (var store = FileRaftStore.open(Path.of(args[1]), "process-group", "node-a", reached -> {
			if (reached == stage) {
				emit(new JsonObject().put("event", "boundary").put("stage", stage.name()));
				try { new CountDownLatch(1).await(); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
			}
		})) {
			var snapshot = new RaftSnapshot(2, 2, RaftConfiguration.stable(Set.of("node-a"), Set.of()), 0, "application-state");
			store.savePersistentState(new RaftPersistentState(store.hardState(), List.of(), 2, snapshot));
		}
	}

	private static <T> T await(Future<T> future) throws Exception {
		return future.toCompletionStage().toCompletableFuture().get(8, TimeUnit.SECONDS);
	}
	private static String rootMessage(Throwable error) {
		while (error.getCause() != null) error = error.getCause();
		return error.getClass().getSimpleName() + ": " + error.getMessage();
	}
	private static void emit(JsonObject event) { System.out.println(PREFIX + event.encode()); System.out.flush(); }
}
