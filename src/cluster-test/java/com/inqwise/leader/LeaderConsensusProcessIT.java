package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/** Actual process loss and provider network isolation, without persistent election storage. */
@Timeout(150)
class LeaderConsensusProcessIT {
	@TempDir Path directory;
	private final List<Child> children = new ArrayList<>();
	private final String clusterName = "election-process-" + UUID.randomUUID();
	private final List<Integer> ports = new ArrayList<>();
	private int incarnation;

	@AfterEach void shutdown() throws Exception {
		boolean interrupted = Thread.interrupted(); Exception failure = null;
		// Kill every child before waiting, even when one cleanup fails or the test was interrupted.
		for (var child : children) if (child.process.isAlive()) child.process.destroyForcibly();
		for (var child : children) {
			try { child.kill(); }
			catch (InterruptedException e) { interrupted = true; }
			catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
		}
		if (interrupted) Thread.currentThread().interrupt();
		if (failure != null) throw failure;
	}

	private List<Child> cluster(boolean protectedMap) throws Exception {
		var reserved = new ArrayList<ServerSocket>();
		try {
			for (int i = 0; i < 3; i++) {
				var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
				reserved.add(socket); ports.add(socket.getLocalPort());
			}
		} finally { for (var socket : reserved) socket.close(); }
		var group = new ArrayList<Child>();
		for (int i = 0; i < 3; i++) group.add(new Child(i, protectedMap));
		for (var child : group) child.awaitStartup();
		converge(group, states -> states.stream().allMatch(state -> state.getInteger("members") == 3)
			&& leaders(states) == 1, "initial group formation");
		return group;
	}

	@Test void killedOwnerFailsOverAndFreshProcessRejoinsWithoutPersistentIdentity() throws Exception {
		var group = cluster(false);
		var initial = states(group); int owner = owner(initial);
		String previousId = initial.get(owner).getString("nodeId");
		var killed = group.get(owner); killed.kill();
		var survivors = group.stream().filter(child -> child != killed).toList();
		converge(survivors, states -> leaders(states) == 1
			&& states.stream().allMatch(state -> state.getInteger("members") == 2), "failover after OS process kill");
		var replacement = new Child(owner, false); replacement.awaitStartup(); group.set(owner, replacement);
		var recovered = converge(group, states -> leaders(states) == 1
			&& states.stream().allMatch(state -> state.getInteger("members") == 3), "replacement admission");
		assertNotEquals(previousId, recovered.get(owner).getString("nodeId"));
		assertFalse(recovered.get(owner).getBoolean("leader"), "a fresh process must not displace the surviving owner");
		maintain(group, observations -> leaders(observations) == 1, 1500);
	}

	@Test void isolatedOwnerFailsClosedAndElectionRecoversAfterNetworkHealing() throws Exception {
		var group = cluster(true);
		maintain(group, observations -> leaders(observations) == 1
			&& observations.stream().allMatch(state -> state.getInteger("members") == 3), 2000);
		int isolated = owner(states(group));
		var island = group.get(isolated);
		var isolatedReply = island.request("isolate");
		assertTrue(isolatedReply.getBoolean("ok"), isolatedReply::encode);
		assertTrue(isolatedReply.getJsonObject("result").getBoolean("providerRunning"));
		assertFalse(isolatedReply.getJsonObject("result").getBoolean("networkLive"));
		var settled = converge(group, observations -> {
			var minority = observations.get(isolated);
			return minority.getInteger("members") == 1 && !minority.getBoolean("protectionPresent")
				&& !minority.getBoolean("leader") && leaders(observations) <= 1
				&& observations.stream().allMatch(state -> state.getBoolean("protectionPresent")
					|| !state.getBoolean("leader"));
		}, "minority rejection after network isolation");
		assertTrue(settled.get(isolated).getBoolean("providerRunning"), "the isolated engine must still be alive");
		assertTrue(island.process.isAlive(), "this fault must not be a process kill");
		var rejected = island.request("read-owner");
		assertFalse(rejected.getBoolean("ok"));
		assertTrue(rejected.getString("error").contains("SplitBrainProtectionException"), rejected::encode);
		System.out.println("Provider views after network isolation: " + settled);
		// The contract starts after the provider detects the split and rejects minority operations.
		// No assertion of instantaneous partition detection or universal no-overlap safety is made.
		maintain(group, observations -> !observations.get(isolated).getBoolean("leader")
			&& leaders(observations) <= 1, 2000);
		var healed = island.request("heal");
		assertTrue(healed.getBoolean("ok"), healed::encode);
		assertTrue(healed.getJsonObject("result").getBoolean("networkLive"));
		var recovered = converge(group, observations -> leaders(observations) == 1
			&& observations.stream().allMatch(state -> state.getInteger("members") == 3
				&& state.getBoolean("protectionPresent")), "provider reconvergence and election recovery after healing");
		var confirmed = group.get(owner(recovered)).request("read-owner");
		assertTrue(confirmed.getBoolean("ok"), confirmed::encode);
		assertNotNull(confirmed.getJsonObject("result").getString("record"));
		maintain(group, observations -> leaders(observations) == 1, 1500);
	}

	private List<JsonObject> states(List<Child> group) throws Exception {
		var result = new ArrayList<JsonObject>();
		for (var child : group) {
			var reply = child.request("status");
			assertTrue(reply.getBoolean("ok"), reply::encode); result.add(reply.getJsonObject("result"));
		}
		return result;
	}

	private List<JsonObject> converge(List<Child> group, Predicate<List<JsonObject>> condition, String description)
		throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
		List<JsonObject> latest;
		do {
			latest = states(group);
			if (condition.test(latest)) return latest;
			Thread.sleep(75);
		} while (System.nanoTime() < deadline);
		throw new AssertionError("Timed out: " + description + "\n" + latest + "\n" + diagnostics());
	}

	private void maintain(List<Child> group, Predicate<List<JsonObject>> condition, long millis) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
		do {
			var observed = states(group);
			assertTrue(condition.test(observed), () -> "Inconsistent process state: " + observed + "\n" + diagnostics());
			Thread.sleep(75);
		} while (System.nanoTime() < deadline);
	}

	private static long leaders(List<JsonObject> states) {
		return states.stream().filter(state -> state.getBoolean("leader")).count();
	}

	private static int owner(List<JsonObject> states) {
		assertEquals(1, leaders(states), () -> "Expected exactly one owner: " + states);
		for (int i = 0; i < states.size(); i++) if (states.get(i).getBoolean("leader")) return i;
		throw new AssertionError("No owner");
	}

	private String diagnostics() {
		var result = new StringBuilder();
		Path saved = Path.of("target", "failsafe-reports", clusterName);
		try { Files.createDirectories(saved); }
		catch (IOException ignored) { }
		result.append("Child logs: ").append(saved.toAbsolutePath()).append('\n');
		for (var child : children) for (var path : List.of(child.output, child.errors)) {
			try {
				if (Files.exists(path)) {
					Files.copy(path, saved.resolve(path.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					String text = Files.readString(path);
					result.append(path.getFileName()).append(":\n")
						.append(text.substring(Math.max(0, text.length() - 2000))).append('\n');
				}
			} catch (IOException ignored) { }
		}
		return result.toString();
	}

	private final class Child {
		final Process process;
		final Path output;
		final Path errors;
		final BufferedWriter input;
		final Thread reader;
		final CompletableFuture<JsonObject> startup = new CompletableFuture<>();
		final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
		long sequence;

		Child(int index, boolean protectedMap) throws Exception {
			String name = "member-" + index + "-" + ++incarnation;
			output = directory.resolve(name + ".stdout"); errors = directory.resolve(name + ".stderr");
			Path config = directory.resolve(name + ".json");
			Files.writeString(config, new JsonObject().put("cluster", clusterName).put("group", "process-election")
				.put("ports", new JsonArray(ports)).put("port", ports.get(index)).put("protectedMap", protectedMap).encode());
			String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
			var command = List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
				"-Xmx192m", "-XX:ActiveProcessorCount=2",
				"-Dvertx.logger-delegate-factory-class-name=io.vertx.core.logging.Log4j2LogDelegateFactory",
				"-Dlog4j.configurationFile=" + Path.of("src/cluster-test/resources/log4j2-cluster-test.xml").toAbsolutePath(),
				"-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
				ClusterElectionProcessNode.class.getName(), config.toString());
			process = new ProcessBuilder(command).redirectError(errors.toFile()).start();
			children.add(this);
			input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
			reader = Thread.ofPlatform().daemon().name(name + "-reader").start(this::read);
			process.onExit().thenRun(() -> {
				var cause = new IOException(name + " exited with status " + process.exitValue());
				startup.completeExceptionally(cause);
				pending.values().forEach(reply -> reply.completeExceptionally(cause)); pending.clear();
			});
		}

		void awaitStartup() throws Exception {
			try { startup.get(60, TimeUnit.SECONDS); }
			catch (Exception e) { throw new IOException("Child startup failed\n" + diagnostics(), e); }
		}

		JsonObject request(String operation) throws Exception {
			long id = ++sequence;
			var reply = new CompletableFuture<JsonObject>(); pending.put(id, reply);
			try {
				input.write(new JsonObject().put("id", id).put("operation", operation).encode());
				input.newLine(); input.flush();
				return reply.get(8, TimeUnit.SECONDS);
			} catch (Exception e) { throw new IOException("Child command failed: " + operation + "\n" + diagnostics(), e); }
			finally { pending.remove(id); }
		}

		private void read() {
			try (var source = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
				var saved = Files.newBufferedWriter(output)) {
				String line;
				while ((line = source.readLine()) != null) {
					saved.write(line); saved.newLine(); saved.flush();
					if (!line.startsWith(ClusterElectionProcessNode.PREFIX)) continue;
					var response = new JsonObject(line.substring(ClusterElectionProcessNode.PREFIX.length()));
					if (response.containsKey("event")) {
						if (response.getString("event").equals("ready")) startup.complete(response);
						else startup.completeExceptionally(new IOException(response.getString("error")));
					} else {
						var reply = pending.remove(response.getLong("id")); if (reply != null) reply.complete(response);
					}
				}
			} catch (Exception e) {
				startup.completeExceptionally(e); pending.values().forEach(reply -> reply.completeExceptionally(e));
			}
		}

		void kill() throws Exception {
			if (process.isAlive()) process.destroyForcibly();
			assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Child did not exit");
			reader.join(2000); input.close();
		}
	}
}
