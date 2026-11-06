package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/** Actual JVM crashes and OS directory locks, complementing the deterministic simulator and in-process transport tests. */
@Timeout(120)
class RaftProcessTest {
	@TempDir Path directory;
	Vertx vertx;
	Context context;
	final List<Child> children = new ArrayList<>();
	final Set<Integer> reservedPorts = new HashSet<>();
	int incarnation;
	final Map<String, Integer> listenerPorts = new HashMap<>();

	@BeforeEach void setup() { vertx = Vertx.vertx(); context = vertx.getOrCreateContext(); }
	@AfterEach void shutdown() throws Exception {
		boolean interrupted = Thread.interrupted(); Exception failure = null;
		// Issue every kill before waiting: one failed/timed-out cleanup must not strand another child JVM.
		for (var child : children) if (child.process.isAlive()) child.process.destroyForcibly();
		for (var child : children) {
			try { child.kill(); }
			catch (InterruptedException e) { interrupted = true; }
			catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
		}
		try { vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS); }
		catch (InterruptedException e) { interrupted = true; }
		catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
		finally { if (interrupted) Thread.currentThread().interrupt(); }
		if (failure != null) throw failure;
	}

	final class Child {
		final Process process;
		final Path output;
		final Path errors;
		final LeaderOptions options;
		final Promise<JsonObject> startup = Promise.promise();
		final Map<Long, Promise<JsonObject>> pending = new HashMap<>();
		final BufferedWriter input;
		final Thread reader;
		long sequence;

		Child(LeaderOptions options, String... arguments) throws Exception {
			this.options = options;
			String name = "child-" + ++incarnation;
			output = directory.resolve(name + ".stdout"); errors = directory.resolve(name + ".stderr");
			String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
			var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
				"-Xmx128m", "-XX:ActiveProcessorCount=2", "-Dvertx.logger-delegate-factory-class-name=io.vertx.core.logging.Log4j2LogDelegateFactory",
				"-Dlog4j.configurationFile=" + Path.of("src/test/resources/log4j2-process-test.xml").toAbsolutePath(),
				"-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
				RaftProcessNode.class.getName()));
			command.addAll(List.of(arguments));
			process = new ProcessBuilder(command).redirectError(errors.toFile()).start();
			input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
			reader = Thread.ofPlatform().daemon().name(name + "-stdout").start(this::readOutput);
			process.onExit().thenRun(() -> context.runOnContext(v -> {
				var cause = new IOException(name + " exited with status " + process.exitValue());
				startup.tryFail(cause); pending.values().forEach(promise -> promise.tryFail(cause)); pending.clear();
			}));
			children.add(this);
		}

		private void readOutput() {
			try (var source = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
				var saved = Files.newBufferedWriter(output)) {
				String line;
				while ((line = source.readLine()) != null) {
					saved.write(line); saved.newLine(); saved.flush();
					if (!line.startsWith(RaftProcessNode.PREFIX)) continue;
					var reply = new JsonObject(line.substring(RaftProcessNode.PREFIX.length()));
					context.runOnContext(v -> {
						if (reply.containsKey("event")) {
							if (reply.getString("event").equals("failed")) startup.tryFail(reply.getString("error"));
							else startup.tryComplete(reply);
						} else {
							var promise = pending.remove(reply.getLong("id")); if (promise != null) promise.tryComplete(reply);
						}
					});
				}
			} catch (Exception e) { context.runOnContext(v -> startup.tryFail(e)); }
		}

		Future<JsonObject> request(String operation) { return request(operation, null); }
		Future<JsonObject> request(String operation, String data) {
			return ContextDispatch.call(context, () -> {
				if (!process.isAlive()) return Future.failedFuture("Child is not alive");
				long id = ++sequence; var completion = ContextDispatch.<JsonObject>promise(context); pending.put(id, completion);
				String line = new JsonObject().put("id", id).put("operation", operation).put("data", data).encode();
				context.executeBlocking(() -> { input.write(line); input.newLine(); input.flush(); return (Void) null; }, true)
					.onFailure(cause -> { pending.remove(id); completion.tryFail(cause); });
				return completion.future().timeout(12, TimeUnit.SECONDS).andThen(done -> pending.remove(id));
			});
		}
		Future<JsonObject> status() { return request("status").map(reply -> reply.getBoolean("ok") ? reply.getJsonObject("result") : null); }
		void kill() throws Exception {
			if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Child did not exit"); }
			reader.join(2000); input.close();
		}
	}

	<T> T await(Future<T> future) throws Exception {
		try { return future.toCompletionStage().toCompletableFuture().get(40, TimeUnit.SECONDS); }
		catch (Exception e) { throw new IOException("Process test failed\n" + diagnostics(), e); }
	}
	String diagnostics() {
		var text = new StringBuilder();
		for (var child : children) for (var file : List.of(child.output, child.errors)) {
			try { if (Files.exists(file)) { String value = Files.readString(file); text.append(file.getFileName()).append(":\n")
				.append(value.substring(Math.max(0, value.length() - 4000))).append('\n'); } }
			catch (IOException ignored) { }
		}
		return text.toString();
	}
	<T> T eventually(Supplier<Future<T>> read, Predicate<T> condition) throws Exception {
		var completed = Promise.<T>promise(); boolean[] busy = {false};
		long timer = vertx.setPeriodic(75, ignored -> {
			if (busy[0] || completed.future().isComplete()) return; busy[0] = true;
			read.get().onComplete(result -> {
				busy[0] = false;
				if (result.succeeded()) {
					try { if (condition.test(result.result())) completed.tryComplete(result.result()); }
					catch (Exception e) { completed.tryFail(e); }
				}
			});
		});
		long deadline = vertx.setTimer(30000, ignored -> completed.tryFail("Process state did not converge"));
		try { return await(completed.future()); } finally { vertx.cancelTimer(timer); vertx.cancelTimer(deadline); }
	}
	int port() throws Exception {
		for (int attempt = 0; attempt < 20; attempt++) {
			var server = await(vertx.createNetServer().connectHandler(socket -> socket.close()).listen(0, "127.0.0.1"));
			int port = server.actualPort(); await(server.close()); if (reservedPorts.add(port)) return port;
		}
		throw new IOException("Could not reserve distinct test ports");
	}
	LeaderOptions options(String id, int port, int target, JsonArray endpoints) {
		listenerPorts.put(directory.resolve(id).toString(), port);
		return LeaderOptions.builder().groupName("process-group").nodeId(id).bootstrapAuthorityId("node-a")
			.dataDirectory(directory.resolve(id).toString()).targetVoterCount(target)
			.timing(new RaftElection.Timing(700, 1400, 100))
			.membership(new JsonObject().put("refreshIntervalMillis", 100).put("replacementDelayMillis", 30000))
			.discovery(DiscoveryOptions.fromJson(new JsonObject().put("type", "static").put("endpoints", endpoints))).build();
	}
	Child launch(LeaderOptions options) throws Exception {
		Path config = directory.resolve("config-" + incarnation + ".json"); Files.writeString(config, options.toJson().encode());
		var child = new Child(options.toBuilder().build(), "node", config.toString(), String.valueOf(listenerPorts.get(options.getDataDirectory()))); await(child.startup.future()); return child;
	}
	List<Child> cluster() throws Exception { return cluster("tcp"); }
	List<Child> cluster(String protocol) throws Exception {
		var ports = List.of(port(), port(), port()); var endpoints = new JsonArray();
		for (int i = 0; i < 3; i++) endpoints.add("127.0.0.1:" + ports.get(i) + "#node-" + (char) ('a' + i));
		var group = new ArrayList<Child>();
		for (int i = 0; i < 3; i++) group.add(launch(options("node-" + (char) ('a' + i), ports.get(i), 3, endpoints)
			.toBuilder().transport(protocol).build()));
		leader(group); return group;
	}
	Future<List<JsonObject>> states(List<Child> group) {
		return Future.all(group.stream().map(Child::status).toList()).map(values -> {
			var states = new ArrayList<JsonObject>();
			for (int i = 0; i < group.size(); i++) states.add(values.resultAt(i));
			return states;
		});
	}
	Child leader(List<Child> group) throws Exception {
		var states = eventually(() -> states(group), observations -> observations.stream().allMatch(value -> value != null)
			&& observations.stream().filter(value -> value.getBoolean("leader")).count() == 1);
		for (int i = 0; i < states.size(); i++) if (states.get(i).getBoolean("leader")) return group.get(i);
		throw new AssertionError("No ready leader");
	}
	JsonObject propose(Child child, String data) throws Exception {
		var reply = await(child.request("propose", data)); assertTrue(reply.getBoolean("ok"), reply::encode); return reply.getJsonObject("result");
	}
	boolean applied(JsonObject state, String value) {
		return state != null && state.getJsonArray("applied").stream().map(JsonObject.class::cast)
			.anyMatch(entry -> value.equals(entry.getString("data")));
	}
	void caughtUp(List<Child> group, String value) throws Exception {
		var states = eventually(() -> states(group), observations -> observations.stream().allMatch(state -> applied(state, value)));
		for (var state : states) assertNull(state.getString("applicationFailure"));
	}
	Set<String> voters(JsonObject state) { return state.getJsonArray("voters").stream().map(String.class::cast).collect(Collectors.toSet()); }

	@Test void leaderCrashPreservesCommittedDataAndMinorityCannotWrite() throws Exception {
		var group = cluster(); var former = leader(group); var before = await(former.status());
		propose(former, "before-crash"); caughtUp(group, "before-crash"); former.kill();
		var survivors = group.stream().filter(child -> child != former).toList(); var successor = leader(survivors);
		assertTrue(await(successor.status()).getLong("term") > before.getLong("term"));
		propose(successor, "after-crash"); caughtUp(survivors, "after-crash");
		var restarted = launch(former.options); var recovered = new ArrayList<>(survivors); recovered.add(restarted);
		caughtUp(recovered, "after-crash");
		for (var state : await(states(recovered))) {
			assertEquals(before.getString("clusterId"), state.getString("clusterId")); assertTrue(applied(state, "before-crash"));
			assertEquals(3, voters(state).size());
		}
		var remaining = leader(recovered); for (var child : recovered) if (child != remaining) child.kill();
		var isolated = eventually(remaining::status, state -> state != null && !state.getBoolean("leader"));
		var rejected = await(remaining.request("propose", "minority-write")); assertFalse(rejected.getBoolean("ok"));
		var elapsed = Promise.<Void>promise(); vertx.setTimer(3000, ignored -> elapsed.complete()); await(elapsed.future());
		var after = await(remaining.status()); assertFalse(after.getBoolean("leader")); assertFalse(applied(after, "minority-write"));
		assertEquals(isolated.getLong("commitIndex"), after.getLong("commitIndex"));
		assertEquals(Set.of("node-a", "node-b", "node-c"), voters(after)); assertFalse(after.getBoolean("effectiveJoint"));
	}

	@Test void fullRestartRestoresSnapshotsAndElectsWithoutBootstrapAuthority() throws Exception {
		var group = cluster(); var originalLeader = leader(group); propose(originalLeader, "checkpoint-one");
		var entry = propose(originalLeader, "checkpoint-two"); caughtUp(group, "checkpoint-two");
		for (var child : group) eventually(() -> child.request("compact"), reply -> reply.getBoolean("ok"));
		var saved = await(states(group));
		for (int i = 0; i < group.size(); i++) {
			assertTrue(saved.get(i).getLong("snapshotIndex") >= entry.getLong("index")); group.get(i).kill();
		}
		for (int i = 0; i < group.size(); i++) {
			try (var store = FileRaftStore.open(group.get(i).options.resolvedDataDirectory(), "process-group", group.get(i).options.getNodeId())) {
				assertEquals(saved.get(i).getString("clusterId"), store.bootstrapState().genesis().clusterId());
				assertTrue(store.persistentState().commitIndex() >= saved.get(i).getLong("commitIndex"));
				assertEquals(saved.get(i).getLong("snapshotIndex"), store.persistentState().baseIndex());
				assertTrue(store.hardState().currentTerm() >= saved.get(i).getLong("term"));
				if (store.hardState().currentTerm() == saved.get(i).getLong("term")) {
					assertEquals(saved.get(i).getString("vote"), store.hardState().votedFor());
				}
			}
		}
		var b = launch(group.get(1).options); var c = launch(group.get(2).options); var withoutAuthority = List.of(b, c);
		var successor = leader(withoutAuthority); caughtUp(withoutAuthority, "checkpoint-two");
		assertTrue(await(successor.status()).getLong("term") > entry.getLong("term"));
		var next = propose(successor, "after-total-restart"); assertTrue(next.getLong("index") > entry.getLong("index"));
		var a = launch(group.getFirst().options); var recovered = List.of(a, b, c); caughtUp(recovered, "after-total-restart");
		for (var state : await(states(recovered))) {
			assertEquals(saved.getFirst().getString("genesisDigest"), state.getString("genesisDigest"));
			assertTrue(applied(state, "checkpoint-one")); assertTrue(applied(state, "checkpoint-two"));
			assertEquals(3, state.getInteger("target")); assertEquals(3, voters(state).size()); assertFalse(state.getBoolean("joint"));
		}
	}

	@Test void coordinatedHttpToTcpRestartPreservesSnapshotsTailAndMembership() throws Exception {
		var original = cluster("http"); var formerLeader = leader(original);
		propose(formerLeader, "before-switch-snapshot"); caughtUp(original, "before-switch-snapshot");
		for (var child : original) eventually(() -> child.request("compact"), reply -> reply.getBoolean("ok"));
		var tail = propose(formerLeader, "before-switch-tail"); caughtUp(original, "before-switch-tail");
		var saved = await(states(original));
		var stopped = await(Future.all(original.stream().map(child -> child.request("close")).toList()));
		for (int i = 0; i < original.size(); i++) {
			assertTrue(((JsonObject) stopped.resultAt(i)).getBoolean("ok")); original.get(i).kill();
		}
		// Every HTTP process is stopped before any TCP listener opens; directories and ports are reused.
		var restarted = new ArrayList<Child>();
		for (var child : original) restarted.add(launch(child.options.toBuilder().transport("tcp").build()));
		var successor = leader(restarted); caughtUp(restarted, "before-switch-tail");
		for (int i = 0; i < restarted.size(); i++) {
			var recovered = await(restarted.get(i).status()); var before = saved.get(i);
			assertEquals(before.getString("nodeId"), recovered.getString("nodeId"));
			assertEquals(before.getString("clusterId"), recovered.getString("clusterId"));
			assertEquals(before.getString("genesisDigest"), recovered.getString("genesisDigest"));
			assertEquals(voters(before), voters(recovered)); assertEquals(3, recovered.getInteger("target"));
			assertEquals(before.getJsonArray("learners"), recovered.getJsonArray("learners"));
			assertTrue(before.getLong("snapshotIndex") > 0);
			assertTrue(recovered.getLong("snapshotIndex") >= before.getLong("snapshotIndex"));
			assertTrue(recovered.getLong("commitIndex") >= before.getLong("commitIndex"));
			assertTrue(recovered.getLong("term") >= before.getLong("term"));
			assertTrue(applied(recovered, "before-switch-snapshot"));
		}
		var next = propose(successor, "after-tcp-switch"); assertTrue(next.getLong("index") > tail.getLong("index"));
		caughtUp(restarted, "after-tcp-switch");
	}

	@Test void nativeOwnershipAndCorruptionFailClosedAcrossJvmBoundaries() throws Exception {
		var original = launch(options("node-a", port(), 1, new JsonArray())); leader(List.of(original)); propose(original, "retained");
		Path duplicateConfig = directory.resolve("duplicate.json");
		Files.writeString(duplicateConfig, original.options.toBuilder().build().toJson().encode());
		var duplicate = new Child(original.options, "node", duplicateConfig.toString(), String.valueOf(port()));
		assertThrows(Exception.class, () -> await(duplicate.startup.future()));
		assertTrue(duplicate.process.waitFor(10, TimeUnit.SECONDS)); assertNotEquals(0, duplicate.process.exitValue());
		assertThrows(IOException.class, () -> FileRaftStore.open(original.options.resolvedDataDirectory(), "process-group", "node-a"));
		propose(original, "after-duplicate"); original.kill();
		try (var store = FileRaftStore.open(original.options.resolvedDataDirectory(), "process-group", "node-a")) {
			assertTrue(store.persistentState().log().stream().anyMatch(item -> item.data().equals("after-duplicate")));
		}
		Path stateFile = original.options.resolvedDataDirectory().resolve("raft-state.bin"); byte[] intact = Files.readAllBytes(stateFile);
		byte[] damaged = intact.clone(); damaged[damaged.length - 1] ^= 1; Files.write(stateFile, damaged);
		Path corruptConfig = directory.resolve("corrupt.json"); Files.writeString(corruptConfig, original.options.toJson().encode());
		var corrupt = new Child(original.options, "node", corruptConfig.toString(), String.valueOf(port()));
		assertThrows(Exception.class, () -> await(corrupt.startup.future())); assertTrue(corrupt.process.waitFor(10, TimeUnit.SECONDS));
		assertNotEquals(0, corrupt.process.exitValue()); assertArrayEquals(damaged, Files.readAllBytes(stateFile));
		assertTrue(Files.exists(original.options.resolvedDataDirectory().resolve("initialized")));
		Files.write(stateFile, intact); var repaired = launch(original.options); leader(List.of(repaired)); caughtUp(List.of(repaired), "after-duplicate");
		assertEquals("node-a", await(repaired.status()).getString("nodeId"));
	}

	@ParameterizedTest @EnumSource(FileRaftStore.WriteStage.class)
	void killAtCompactionBoundaryRecoversWholeStateAndReleasesNativeLock(FileRaftStore.WriteStage boundary) throws Exception {
		Path storage = directory.resolve("node-a");
		var before = new RaftPersistentState(new RaftHardState(2, "node-a"), List.of(
			new RaftLogEntry(1, 1, RaftLogEntry.Kind.DATA, "one"), new RaftLogEntry(2, 2, RaftLogEntry.Kind.DATA, "two")), 2);
		try (var store = FileRaftStore.open(storage, "process-group", "node-a")) {
			store.installGenesis(RaftGenesis.create("crash-cluster", "process-group", "node-a", Set.of("node-a"), 1)); store.savePersistentState(before);
		}
		var child = new Child(null, "store-crash", storage.toString(), boundary.name());
		assertEquals(boundary.name(), await(child.startup.future()).getString("stage"));
		assertThrows(IOException.class, () -> FileRaftStore.open(storage, "process-group", "node-a")); child.kill();
		try (var recovered = FileRaftStore.open(storage, "process-group", "node-a")) {
			var after = new RaftPersistentState(before.hardState(), List.of(), 2, new RaftSnapshot(2, 2,
				RaftConfiguration.stable(Set.of("node-a"), Set.of()), 0, "application-state"));
			assertEquals(boundary == FileRaftStore.WriteStage.STAGED ? before : after, recovered.persistentState());
			assertEquals("crash-cluster", recovered.bootstrapState().genesis().clusterId());
		}
	}
}
