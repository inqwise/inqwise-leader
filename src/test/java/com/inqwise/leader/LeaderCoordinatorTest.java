package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;
import static com.inqwise.leader.HttpRaftTransportTest.clientOptions;
import static com.inqwise.leader.HttpRaftTransportTest.serverOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

class LeaderCoordinatorTest {
	@TempDir Path directory;
	final List<Vertx> runtimes = new ArrayList<>();
	final List<LeaderCoordinator> coordinators = new ArrayList<>();
	final java.util.Map<String, Integer> listenerPorts = new java.util.HashMap<>();
	Future<Void> listen(LeaderCoordinator coordinator) {
		return coordinator.listen(listenerPorts.get(coordinator.options().getDataDirectory()));
	}
	@AfterEach void shutdown() throws Exception {
		for (var coordinator : coordinators) await(coordinator.close());
		for (var runtime : runtimes) await(runtime.close());
	}
	Vertx runtime() { var vertx = Vertx.vertx(); runtimes.add(vertx); return vertx; }
	int availablePort(Vertx vertx) throws Exception {
		var server = await(vertx.createNetServer().connectHandler(socket -> socket.close()).listen(0, "127.0.0.1"));
		int port = server.actualPort(); await(server.close()); return port;
	}
	LeaderOptions options(String id, int port) {
		listenerPorts.put(directory.resolve(id).toString(), port);
		return LeaderOptions.builder().groupName("group").bootstrapAuthorityId("node-a").nodeId(id)
			.dataDirectory(directory.resolve(id).toString()).targetVoterCount(1)
			.timing(new RaftElection.Timing(400, 850, 60))
			.membership(new JsonObject().put("refreshIntervalMillis", 50).put("replacementDelayMillis", 1000)).build();
	}
	LeaderCoordinator create(Vertx vertx, LeaderOptions options, String tlsId) throws Exception {
		var coordinator = tlsId == null ? LeaderCoordinator.create(vertx, options)
			: LeaderCoordinator.create(vertx, options, serverOptions(tlsId), clientOptions(tlsId));
		coordinator.exceptionHandler(cause -> {}); coordinators.add(coordinator); return coordinator;
	}
	<T> T eventually(Vertx vertx, Supplier<Future<T>> read, Predicate<T> condition) throws Exception {
		var result = Promise.<T>promise();
		long timer = vertx.setPeriodic(15, id -> read.get().onComplete(value -> {
			if (value.succeeded() && condition.test(value.result())) result.tryComplete(value.result());
		}));
		try { return await(result.future()); } finally { vertx.cancelTimer(timer); }
	}
	LeaderCoordinator leader(List<LeaderCoordinator> group) throws Exception {
		return eventually(group.getFirst().context().owner(), () -> Future.all(group.stream().map(LeaderCoordinator::leadership).toList())
			.map(values -> {
				for (int i = 0; i < group.size(); i++) if (((Leadership) values.resultAt(i)).leader()) return group.get(i);
				return (LeaderCoordinator) null;
			}), value -> value != null);
	}

	@Test void unregisteredAndResetExceptionHandlersLogCauseOnOwnerContext() throws Exception {
		var vertx = runtime();
		var coordinator = LeaderCoordinator.create(vertx, options("node-a", availablePort(vertx)));
		coordinators.add(coordinator);
		var failure = new RuntimeException("subscriber failed");
		var count = new AtomicInteger();
		var logged = new AtomicReference<>(Promise.<io.vertx.core.Context>promise());
		var event = new AtomicReference<org.apache.logging.log4j.core.LogEvent>();
		var logger = (org.apache.logging.log4j.core.Logger)
			org.apache.logging.log4j.LogManager.getLogger(LeaderCoordinator.class);
		var appender = new org.apache.logging.log4j.core.appender.AbstractAppender("coordinator-default-test", null,
			org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(), false,
			org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
			@Override public void append(org.apache.logging.log4j.core.LogEvent value) {
				if (value.getThrown() != failure) return;
				event.set(value.toImmutable()); count.incrementAndGet();
				logged.get().tryComplete(Vertx.currentContext());
			}
		};
		appender.start(); logger.addAppender(appender);
		try {
			coordinator.leadershipHandler(value -> { throw failure; });
			assertSame(coordinator.context(), await(logged.get().future()));
			assertEquals(org.apache.logging.log4j.Level.ERROR, event.get().getLevel());
			assertEquals("Unhandled coordinator exception", event.get().getMessage().getFormattedMessage());
			assertSame(failure, event.get().getThrown()); assertEquals(1, count.get());

			var observed = Promise.<Throwable>promise();
			coordinator.exceptionHandler(cause -> observed.tryComplete(cause));
			coordinator.leadershipHandler(value -> { throw failure; });
			assertSame(failure, await(observed.future())); assertEquals(1, count.get());

			logged.set(Promise.promise()); coordinator.exceptionHandler(null);
			coordinator.leadershipHandler(value -> { throw failure; });
			assertSame(coordinator.context(), await(logged.get().future())); assertEquals(2, count.get());
			coordinator.leadershipHandler(null);
		} finally { logger.removeAppender(appender); appender.stop(); }
	}

	@Test void repeatedListenSharesStartupButDifferentBindingFailsWithoutStoppingOwner() throws Exception {
		var vertx = runtime(); int port = availablePort(vertx);
		var coordinator = create(vertx, options("node-a", port), null);
		var first = coordinator.listen(port); var repeated = coordinator.listen(port, "127.0.0.1");
		var conflicting = coordinator.listen(availablePort(vertx));
		await(first); await(repeated); assertThrows(Exception.class, () -> await(conflicting));
		leader(List.of(coordinator));
		assertEquals(port, await(coordinator.localEndpoint()).port());
		await(coordinator.listen(port));
		assertThrows(Exception.class, () -> await(coordinator.listen(port, "127.0.0.2")));
		assertTrue(await(coordinator.leadership()).leader());
	}

	@Test void invalidBindingsAcquireNoStorageAndCanBeRetriedWithAValidAddress() throws Exception {
		var vertx = runtime(); int port = availablePort(vertx);
		var coordinator = create(vertx, options("node-a", port), null);
		for (int invalid : List.of(-1, 0, 65536)) {
			assertThrows(Exception.class, () -> await(coordinator.listen(invalid)));
		}
		for (String invalid : List.of("localhost", "::1", "0.0.0.0")) {
			assertThrows(Exception.class, () -> await(coordinator.listen(port, invalid)));
		}
		assertFalse(java.nio.file.Files.exists(directory.resolve("node-a")));
		await(coordinator.listen(port)); leader(List.of(coordinator));
	}

	@Test void advertisedEndpointWithOmittedPortUsesSelectedListenerPort() throws Exception {
		var vertx = runtime(); int port = availablePort(vertx);
		var coordinator = create(vertx, options("node-a", port).toBuilder()
			.advertisedEndpoint("127.0.0.1#node-a").build(), null);
		await(coordinator.listen(port)); leader(List.of(coordinator));
		assertEquals(new NodeEndpoint("127.0.0.1", port, "node-a"), await(coordinator.localEndpoint()));
	}

	@Test void defaultFactoryIsAnUnopenedLocalSingletonAndBuilderStillDefaultsToThreeVoters() throws Exception {
		var coordinator = LeaderCoordinator.create(runtime()); coordinators.add(coordinator);
		assertEquals("default", coordinator.options().getGroupName());
		assertEquals("node-a", coordinator.options().getNodeId());
		assertEquals("node-a", coordinator.options().getBootstrapAuthorityId());
		assertEquals(1, coordinator.options().getTargetVoterCount());
		assertFalse(coordinator.options().toJson().containsKey("listenPort"));
		assertTrue(((DiscoveryOptions.Static) coordinator.options().getDiscovery()).endpoints().isEmpty());
		assertEquals(3, LeaderOptions.builder().groupName("group").bootstrapAuthorityId("node-a")
			.build().getTargetVoterCount());
		assertThrows(IllegalStateException.class, coordinator::identity);
		await(coordinator.close());
	}

	@Test void plaintextSingletonBecomesReadyAndRecoversWithoutCertificatesOrExplicitIdentity() throws Exception {
		var vertx = runtime(); var config = options("node-a", availablePort(vertx));
		var first = create(vertx, config, null);
		await(listen(first)); leader(List.of(first));
		var identity = first.identity(); var token = await(first.fencingToken());
		await(first.close());
		var restarted = create(vertx, config.toBuilder().nodeId(null).build(), null);
		await(listen(restarted)); leader(List.of(restarted));
		assertEquals(identity, restarted.identity());
		assertEquals(token.clusterId(), await(restarted.fencingToken()).clusterId());
	}

	@Test void plaintextNodeOutsideSeedsIsAdmittedAsLearnerWithoutChangingVoters() throws Exception {
		var vertx = runtime(); var endpoints = new JsonArray(); var group = new ArrayList<LeaderCoordinator>();
		var ports = new ArrayList<Integer>();
		for (int i = 0; i < 3; i++) {
			int port = availablePort(vertx); ports.add(port);
			endpoints.add("127.0.0.1:" + port + "#node-" + (char) ('a' + i));
		}
		for (int i = 0; i < 3; i++) group.add(create(vertx,
			options("node-" + (char) ('a' + i), ports.get(i)).toBuilder().targetVoterCount(3)
				.discovery(DiscoveryOptions.fromJson(new JsonObject().put("type", "static").put("endpoints", endpoints))).build(), null));
		for (var coordinator : group) await(listen(coordinator));
		var elected = leader(group);
		var joining = create(vertx, options("node-d", availablePort(vertx)).toBuilder().targetVoterCount(3)
			.discovery(DiscoveryOptions.fromJson(new JsonObject().put("type", "static").put("endpoints", endpoints))).build(), null);
		await(listen(joining));
		eventually(vertx, () -> elected.participant().compose(VertxLeaderNode::membership),
			state -> state.committed().learners().contains("node-d"));
		var membership = eventually(vertx, () -> joining.participant().compose(VertxLeaderNode::membership),
			state -> state.committed().learners().contains("node-d"));
		assertEquals(Set.of("node-a", "node-b", "node-c"), membership.committed().voters());
		assertFalse(await(joining.leadership()).leader());
		assertThrows(Exception.class, () -> await(joining.fencingToken()));
	}

	@Test void constructionAndUnopenedClosePerformNoStorageIoAndUnavailableReadsAreExplicit() throws Exception {
		var vertx = runtime(); var config = options("node-a", availablePort(vertx));
		var coordinator = create(vertx, config, "node-a");
		assertFalse(java.nio.file.Files.exists(directory.resolve("node-a")));
		assertThrows(IllegalStateException.class, coordinator::identity);
		assertThrows(Exception.class, () -> await(coordinator.participant()));
		assertFalse(await(coordinator.leadership()).leader());
		await(coordinator.close());
		assertFalse(java.nio.file.Files.exists(directory.resolve("node-a")));
		assertThrows(Exception.class, () -> await(listen(coordinator)));
	}

	@Test void duplicateConstructionDoesNotAcquireOwnershipAndFailedStartDoesNotUnlockRunningOwner() throws Exception {
		var vertx = runtime(); var config = options("node-a", availablePort(vertx));
		var owner = create(vertx, config, "node-a"); await(listen(owner)); leader(List.of(owner));
		var duplicate = create(vertx, config, "node-a");
		assertThrows(Exception.class, () -> await(listen(duplicate)));
		assertTrue(await(owner.leadership()).leader());
		assertThrows(Exception.class, () -> FileRaftStore.open(directory.resolve("node-a"), "group", "node-a"));
		await(owner.close());
		var replacement = create(vertx, config, "node-a"); await(listen(replacement)); leader(List.of(replacement));
		assertEquals(owner.identity(), replacement.identity());
	}

	@Test void configuredPortAndOptionsCopiesDriveStartupHandlersAndCallerKeepsVertxOwnership() throws Exception {
		var vertx = runtime(); int port = availablePort(vertx); var options = options("node-a", port);
		var server = serverOptions("node-a");
		var creating = LeaderCoordinator.create(vertx, options, server, clientOptions("node-a"));
		options.toBuilder().groupName("changed").build(); server.setHost("192.0.2.10");
		var coordinator = creating; coordinators.add(coordinator);
		var failed = new AtomicReference<Throwable>(); var ready = new CountDownLatch(1);
		coordinator.leadershipHandler(value -> {
			if (Vertx.currentContext() != coordinator.context()) failed.set(new AssertionError("callback context"));
			if (value.leader()) ready.countDown();
		});
		assertThrows(IllegalStateException.class, coordinator::identity); assertEquals("group", coordinator.options().getGroupName());
		coordinator.options().toBuilder().groupName("other").build();
		assertThrows(Exception.class, () -> await(coordinator.localEndpoint()));
		assertThrows(Exception.class, () -> await(coordinator.leave(10)));
		await(listen(coordinator)); await(listen(coordinator)); assertTrue(ready.await(5, TimeUnit.SECONDS));
		assertEquals(new NodeEndpoint("127.0.0.1", port, "node-a"), await(coordinator.localEndpoint()));
		var started = listen(coordinator); await(started); var late = new CountDownLatch(1);
		started.onComplete(value -> { if (Vertx.currentContext() != coordinator.context()) failed.set(new AssertionError("future context")); late.countDown(); });
		assertTrue(late.await(5, TimeUnit.SECONDS)); assertNull(failed.get());
		await(coordinator.close()); await(coordinator.close()); assertFalse(await(coordinator.leadership()).leader());
		assertThrows(Exception.class, () -> await(listen(coordinator)));
		var timer = Promise.<Void>promise(); vertx.setTimer(1, id -> timer.complete()); await(timer.future());
		try (var retained = FileRaftStore.open(directory.resolve("node-a"), "group", "node-a")) { assertEquals(1, retained.bootstrapState().genesis().targetVoterCount()); }
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void staticJsonFormsThreeVotersAndGracefulLeaveKeepsDurableTarget(boolean tls) throws Exception {
		var runtimes = List.of(runtime(), runtime(), runtime()); var ports = new ArrayList<Integer>();
		for (var vertx : runtimes) ports.add(availablePort(vertx));
		var endpoints = new JsonArray();
		for (int i = 0; i < 3; i++) endpoints.add("127.0.0.1:" + ports.get(i) + "#node-" + (char) ('a' + i));
		var group = new ArrayList<LeaderCoordinator>();
		for (int i = 0; i < 3; i++) {
			String id = "node-" + (char) ('a' + i);
			var config = options(id, ports.get(i)).toBuilder().targetVoterCount(3).leaveTimeoutMillis(5000)
				.discovery(DiscoveryOptions.fromJson(new JsonObject().put("type", "static").put("endpoints", endpoints))).build();
			group.add(create(runtimes.get(i), LeaderOptions.builder(config.toJson()).build(), tls ? id : null));
		}
		for (var coordinator : group) await(listen(coordinator)); var elected = leader(group);
		var committed = await(elected.participant().compose(VertxLeaderNode::membership));
		assertEquals(Set.of("node-a", "node-b", "node-c"), committed.committed().voters());
		var departing = group.stream().filter(node -> node != elected).findFirst().orElseThrow();
		await(departing.leave()); assertFalse(await(departing.leadership()).leader());
		assertEquals(2, await(elected.participant().compose(VertxLeaderNode::membership)).committed().voters().size());
		await(elected.close());
		try (var retained = FileRaftStore.open(directory.resolve(elected.identity().nodeId()), "group", elected.identity().nodeId())) {
			assertEquals(3, retained.bootstrapState().genesis().targetVoterCount());
		}
	}

	@Test void restartRecoversIdentityWithoutLocalNodeIdAndKeepsOriginalTargetDespiteNewConfiguration() throws Exception {
		var vertx = runtime(); var original = create(vertx, options("node-a", availablePort(vertx)), "node-a");
		await(listen(original)); leader(List.of(original)); await(original.close());
		var restarted = create(vertx, options("node-a", availablePort(vertx)).toBuilder().nodeId(null).targetVoterCount(9).build(), "node-a");
		await(listen(restarted)); assertEquals(original.identity(), restarted.identity()); leader(List.of(restarted));
		await(restarted.close());
		try (var retained = FileRaftStore.open(directory.resolve("node-a"), "group", "node-a")) {
			assertEquals(1, retained.bootstrapState().genesis().targetVoterCount());
		}
	}

	@Test void invalidTransportOrChangedAuthorityFailsStartupAndBindFailureCleansUpAutomatically() throws Exception {
		var vertx = runtime(); int port = availablePort(vertx); var config = options("node-a", port);
		assertThrows(Exception.class, () -> await(LeaderCoordinator.create(vertx, config, serverOptions("node-a"),
			clientOptions("node-a").setTrustAll(true)).listen(port)));
		try (var retained = FileRaftStore.open(directory.resolve("node-a"), "group", "node-a")) {
			assertEquals("node-a", retained.identity().nodeId());
		}
		var occupied = await(runtime().createNetServer().connectHandler(socket -> socket.close()).listen(port, "127.0.0.1"));
		try {
			var coordinator = create(vertx, config, "node-a"); assertThrows(Exception.class, () -> await(listen(coordinator)));
			try (var retained = FileRaftStore.open(directory.resolve("node-a"), "group", "node-a")) { assertNotNull(retained.identity()); }
		} finally { await(occupied.close()); }
		var good = create(vertx, config, "node-a"); await(listen(good)); leader(List.of(good)); await(good.close());
		assertThrows(Exception.class, () -> await(LeaderCoordinator.create(vertx, config.toBuilder().bootstrapAuthorityId("node-b").build(),
			serverOptions("node-a"), clientOptions("node-a")).listen(port)));
		try (var retained = FileRaftStore.open(directory.resolve("node-a"), "group", "node-a")) {
			assertEquals("node-a", retained.bootstrapState().genesis().authorityId());
		}
	}

	@Test void srvJsonUsesVertxDnsAndRecordPortWithOwnedClientCleanup() throws Exception {
		var vertx = runtime(); int port = availablePort(vertx);
		var dns = await(vertx.createDatagramSocket().listen(0, "127.0.0.1")); var queries = new AtomicInteger();
		try {
			dns.handler(packet -> {
				queries.incrementAndGet(); Buffer query = packet.data(); int cursor = 12;
				while (query.getUnsignedByte(cursor) != 0) cursor += query.getUnsignedByte(cursor) + 1;
				int type = query.getUnsignedShort(cursor + 1);
				var response = query.getBuffer(0, cursor + 5).setShort(2, (short) 0x8180)
					.setShort(6, (short) 1).setShort(8, (short) 0).setShort(10, (short) 0);
				Buffer answer = type == 33 ? Buffer.buffer().appendShort((short) 0).appendShort((short) 0)
					.appendShort((short) port).appendBuffer(SrvNodeDiscoveryTest.name("node-a.test"))
					: Buffer.buffer(new byte[] {127, 0, 0, 1});
				SrvNodeDiscoveryTest.answer(response, type, answer); dns.send(response, packet.sender().port(), packet.sender().host());
			});
			var config = options("node-a", port).toBuilder().discovery(DiscoveryOptions.fromJson(new JsonObject().put("type", "srv").put("name", "_leader._tcp.test")
				.put("dns", new JsonObject().put("host", "127.0.0.1").put("port", dns.localAddress().port()).put("queryTimeout", 500)))).build();
			var coordinator = create(vertx, config, "node-a"); await(listen(coordinator)); leader(List.of(coordinator));
			assertTrue(queries.get() >= 2); assertEquals(port, await(coordinator.localEndpoint()).port());
			await(coordinator.close()); assertFalse(await(coordinator.leadership()).leader());
		} finally { await(dns.close()); }
	}

	@Test void closeDuringSrvLookupCancelsLateFormationAndKeepsApplicationVertxUsable() throws Exception {
		var vertx = runtime(); var dns = await(vertx.createDatagramSocket().listen(0, "127.0.0.1")); var queried = new CountDownLatch(1);
		try {
			dns.handler(packet -> queried.countDown());
			var config = options("node-a", availablePort(vertx)).toBuilder().discovery(DiscoveryOptions.fromJson(new JsonObject().put("type", "srv")
				.put("name", "_leader._tcp.test").put("dns", new JsonObject().put("host", "127.0.0.1")
					.put("port", dns.localAddress().port()).put("queryTimeout", 5000)))).build();
			var coordinator = create(vertx, config, "node-a"); await(listen(coordinator)); assertTrue(queried.await(5, TimeUnit.SECONDS));
			await(coordinator.close());
			try (var retained = FileRaftStore.open(directory.resolve("node-a"), "group", "node-a")) {
				assertEquals(RaftBootstrapState.UNASSIGNED, retained.bootstrapState());
			}
			var alive = Promise.<Void>promise(); vertx.runOnContext(v -> alive.complete()); await(alive.future());
		} finally { await(dns.close()); }
	}

	@Test void applicationSnapshotHooksAreForwardedBeforeParticipantRecovery() throws Exception {
		var vertx = runtime(); var original = create(vertx, options("node-a", availablePort(vertx)), "node-a");
		await(original.snapshotHandlers(point -> Future.succeededFuture("application"), saved -> Future.succeededFuture(), entry -> {}));
		await(listen(original)); leader(List.of(original));
		await(original.participant().compose(node -> node.propose("state"))); await(original.participant().compose(VertxLeaderNode::compact));
		await(original.close());
		var recovered = create(vertx, options("node-a", availablePort(vertx)), "node-a"); var restored = new CountDownLatch(1);
		await(recovered.snapshotHandlers(point -> Future.succeededFuture("application"), saved -> {
			assertEquals("application", saved.data()); restored.countDown(); return Future.succeededFuture();
		}, entry -> {}));
		await(listen(recovered)); assertTrue(restored.await(5, TimeUnit.SECONDS)); leader(List.of(recovered));
	}
	@Test void mismatchedServerOrClientCertificateFailsStartupAndPreservesIdentity() throws Exception {
		var vertx = runtime(); var config = options("node-a", availablePort(vertx));
		for (boolean wrongServer : List.of(true, false)) {
			assertThrows(Exception.class, () -> await(LeaderCoordinator.create(vertx, config,
				serverOptions(wrongServer ? "node-b" : "node-a"), clientOptions(wrongServer ? "node-a" : "node-b")).listen(listenerPorts.get(directory.resolve("node-a").toString()))));
			try (var retained = FileRaftStore.open(directory.resolve("node-a"), "group", "node-a")) {
				assertEquals("node-a", retained.identity().nodeId()); assertEquals(RaftBootstrapState.UNASSIGNED, retained.bootstrapState());
			}
		}
	}

	@Test void foreignContextStartupAndCancellationCompleteOnOwnerAndReleaseAcceptedInitialization() throws Exception {
		var vertx = runtime(); var coordinator = create(vertx, options("node-a", availablePort(vertx)), "node-a");
		var foreign = vertx.getOrCreateContext(); assertNotSame(coordinator.context(), foreign);
		var starting = Promise.<Future<Void>>promise(); var closing = Promise.<Void>promise();
		var startContext = Promise.<io.vertx.core.Context>promise();
		var closeContext = Promise.<io.vertx.core.Context>promise();
		var activations = new AtomicInteger();
		coordinator.leadershipHandler(value -> { if (value.leader()) activations.incrementAndGet(); });
		foreign.runOnContext(ignored -> {
			var startup = listen(coordinator); starting.complete(startup);
			startup.onComplete(result -> startContext.tryComplete(Vertx.currentContext()));
			coordinator.close().onComplete(result -> {
				closeContext.tryComplete(Vertx.currentContext()); closing.complete(result.result(), result.cause());
			});
		});
		await(closing.future());
		assertThrows(Exception.class, () -> await(await(starting.future())));
		assertSame(coordinator.context(), await(startContext.future()));
		assertSame(coordinator.context(), await(closeContext.future()));
		assertEquals(0, activations.get()); assertFalse(await(coordinator.leadership()).leader());
		try (var retained = FileRaftStore.open(directory.resolve("node-a"), "group", "node-a")) {
			assertEquals(RaftBootstrapState.UNASSIGNED, retained.bootstrapState());
		}
	}

	@Test void fencingTokensUseDurableClusterBindingReadyTermAndOwnerContext() throws Exception {
		var vertx = runtime(); var coordinator = create(vertx, options("node-a", availablePort(vertx)), "node-a");
		assertThrows(Exception.class, () -> await(coordinator.fencingToken()));
		await(listen(coordinator)); leader(List.of(coordinator));
		var reading = coordinator.fencingToken(); var token = await(reading);
		var owner = Promise.<io.vertx.core.Context>promise();
		reading.onSuccess(ignored -> owner.tryComplete(Vertx.currentContext()));
		assertSame(coordinator.context(), await(owner.future()));
		assertEquals(coordinator.identity().groupName(), token.groupName());
		assertEquals(await(coordinator.participant().compose(VertxLeaderNode::leadership)).term(), token.term());
		await(coordinator.close()); assertThrows(Exception.class, () -> await(coordinator.fencingToken()));
		try (var store = FileRaftStore.open(directory.resolve("node-a"), "group", "node-a")) {
			assertEquals(store.bootstrapState().genesis().clusterId(), token.clusterId());
		}
	}

	@Test void closeBeforeStartOrDuringStartupReleasesOwnershipAndSuppressesReadiness() throws Exception {
		var vertx = runtime(); var config = options("node-a", availablePort(vertx));
		var unopened = create(vertx, config, "node-a"); await(unopened.close());
		var coordinator = create(vertx, config, "node-a"); var activations = new AtomicInteger();
		coordinator.leadershipHandler(value -> { if (value.leader()) activations.incrementAndGet(); });
		var starting = listen(coordinator); var closing = coordinator.close(); await(closing);
		assertThrows(Exception.class, () -> await(starting)); assertEquals(0, activations.get());
		try (var retained = FileRaftStore.open(directory.resolve("node-a"), "group", "node-a")) {
			assertEquals(RaftBootstrapState.UNASSIGNED, retained.bootstrapState());
		}
	}

}
