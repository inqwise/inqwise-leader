package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.dns.DnsClient;
import io.vertx.core.dns.DnsClientOptions;

class SrvNodeDiscoveryTest {
	Vertx vertx;
	DatagramSocket server;
	DnsClient dns;
	@BeforeEach void setup() throws Exception {
		vertx = Vertx.vertx();
		server = await(vertx.createDatagramSocket().listen(0, "127.0.0.1"));
		dns = vertx.createDnsClient(new DnsClientOptions().setHost("127.0.0.1")
			.setPort(server.localAddress().port()).setQueryTimeout(1000));
	}
	@AfterEach void shutdown() throws Exception {
		await(dns.close()); await(server.close()); await(vertx.close());
	}

	static Buffer name(String value) {
		Buffer body = Buffer.buffer();
		if (!value.equals(".")) for (String label : value.split("\\.")) {
			body.appendByte((byte) label.length()).appendString(label);
		}
		return body.appendByte((byte) 0);
	}

	void answers(boolean unavailable) {
		server.handler(packet -> {
			Buffer query = packet.data();
			int cursor = 12;
			StringBuilder queriedName = new StringBuilder();
			while (query.getUnsignedByte(cursor) != 0) {
				int size = query.getUnsignedByte(cursor++);
				if (!queriedName.isEmpty()) queriedName.append('.');
				queriedName.append(query.getString(cursor, cursor + size)); cursor += size;
			}
			int type = query.getUnsignedShort(cursor + 1);
			int questionEnd = cursor + 5;
			Buffer response = query.getBuffer(0, questionEnd).setShort(2, (short) 0x8180)
				.setShort(6, (short) 0).setShort(8, (short) 0).setShort(10, (short) 0);
			if (queriedName.toString().contains("missing")) response.setShort(2, (short) 0x8183);
			else if (type == 33) {
				List<Buffer> records = unavailable ? List.of(Buffer.buffer().appendShort((short) 0)
					.appendShort((short) 0).appendShort((short) 0).appendBuffer(name(".")))
					: List.of(Buffer.buffer().appendShort((short) 10).appendShort((short) 50)
						.appendShort((short) 9090).appendBuffer(name("node-a.test")),
						Buffer.buffer().appendShort((short) 0).appendShort((short) 0)
						.appendShort((short) 8081).appendBuffer(name("node-empty.test")));
				response.setShort(6, (short) records.size());
				for (Buffer record : records) answer(response, 33, record);
			} else if (type == 1 && queriedName.toString().equals("node-a.test")) {
				response.setShort(6, (short) 2);
				answer(response, 1, Buffer.buffer(new byte[] {127, 0, 0, 1}));
				answer(response, 1, Buffer.buffer(new byte[] {127, 0, 0, 2}));
			}
			server.send(response, packet.sender().port(), packet.sender().host());
		});
	}

	static void answer(Buffer packet, int type, Buffer data) {
		packet.appendShort((short) 0xc00c).appendShort((short) type).appendShort((short) 1)
			.appendInt(30).appendShort((short) data.length()).appendBuffer(data);
	}

	@Test void realDnsSrvResolvesAllIpv4AddressesAndPreservesExplicitPortAndTtl() throws Exception {
		answers(false);
		var context = vertx.getOrCreateContext();
		var discovery = new SrvNodeDiscovery(context, dns, "_inqwise-leader._tcp.test");
		var resolved = discovery.resolve();
		var result = await(resolved);
		assertEquals(List.of(new NodeEndpoint("127.0.0.1", 9090, null), new NodeEndpoint("127.0.0.2", 9090, null)),
			result.endpoints());
		assertEquals(30000, result.refreshAfterMillis());
		assertTrue(result.failures().containsKey("node-empty.test.:8081")
			|| result.failures().containsKey("node-empty.test:8081"));
		CountDownLatch callback = new CountDownLatch(1);
		AtomicReference<Object> actual = new AtomicReference<>();
		resolved.onComplete(value -> { actual.set(Vertx.currentContext()); callback.countDown(); });
		assertTrue(callback.await(5, TimeUnit.SECONDS));
		assertSame(context, actual.get());
	}

	@Test void unavailableSrvServiceAndDnsFailureDoNotInventEndpoints() throws Exception {
		answers(true);
		var unavailable = new SrvNodeDiscovery(vertx.getOrCreateContext(), dns, "_inqwise-leader._tcp.test");
		var result = await(unavailable.resolve());
		assertTrue(result.endpoints().isEmpty());
		assertEquals(1, result.failures().size());
		var missing = new SrvNodeDiscovery(vertx.getOrCreateContext(), dns, "_inqwise-leader._tcp.missing");
		assertThrows(Exception.class, () -> await(missing.resolve()));
	}

	@Test void staticDiscoveryDeduplicatesAndNeverUsesDns() throws Exception {
		var endpoint = NodeEndpoint.parse("127.0.0.1#node-a");
		var source = NodeDiscovery.staticEndpoints(List.of(endpoint, endpoint));
		assertEquals(List.of(endpoint), await(source.resolve()).endpoints());
		assertEquals(Long.MAX_VALUE, await(source.resolve()).refreshAfterMillis());
		assertThrows(IllegalArgumentException.class,
			() -> new SrvNodeDiscovery(vertx.getOrCreateContext(), dns, "http://example.net"));
	}
}
