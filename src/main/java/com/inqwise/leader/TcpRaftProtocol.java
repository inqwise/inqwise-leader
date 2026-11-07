package com.inqwise.leader;

import java.io.IOException;
import java.util.List;

import io.vertx.core.buffer.Buffer;

/** Length includes the fixed header and payload, excluding the four-byte length prefix. */
final class TcpRaftProtocol {
	static final int MAGIC = 0x49525450;
	static final int VERSION = 1;
	static final int HEADER_BYTES = 17;
	static final int MAX_FRAME_BYTES = HEADER_BYTES + RaftWireCodec.MAX_BYTES;
	static final int HELLO = 0;
	static final int REQUEST = 1;
	static final int RESPONSE = 2;
	private static final List<String> OPERATIONS = List.of("/identity", "/membership", "/bootstrap",
		"/bootstrap/install", "/join", "/leave", "/request-vote", "/vote-response", "/append-entries",
		"/append-response", "/install-snapshot", "/snapshot-response");

	private TcpRaftProtocol() {}

	static int operation(String name) {
		int index = OPERATIONS.indexOf(name);
		if (index < 0) throw new IllegalArgumentException("Unknown TCP operation");
		return index;
	}

	static boolean read(int operation) { return operation >= 0 && operation <= 2; }

	static Buffer frame(int kind, int operation, long requestId, int status, Buffer body) throws IOException {
		int size = body == null ? 0 : body.length();
		if (size > RaftWireCodec.MAX_BYTES) throw new IOException("Protocol body too large");
		var bytes = Buffer.buffer(4 + HEADER_BYTES + size).appendInt(HEADER_BYTES + size)
			.appendInt(MAGIC).appendByte((byte) VERSION).appendByte((byte) kind).appendByte((byte) operation)
			.appendLong(requestId).appendShort((short) status);
		return body == null ? bytes : bytes.appendBuffer(body);
	}

	static Frame decode(Buffer bytes) throws IOException {
		if (bytes.length() < HEADER_BYTES || bytes.length() > MAX_FRAME_BYTES
			|| bytes.getInt(0) != MAGIC || bytes.getUnsignedByte(4) != VERSION) {
			throw new IOException("Unsupported TCP frame");
		}
		int kind = bytes.getUnsignedByte(5), operation = bytes.getUnsignedByte(6);
		long id = bytes.getLong(7);
		int status = bytes.getUnsignedShort(15);
		if (kind > RESPONSE || operation >= OPERATIONS.size()
			|| (kind == HELLO ? id != 0 || operation != 0 || status != 0 : id <= 0)
			|| kind == REQUEST && status != 0) throw new IOException("Invalid TCP frame header");
		return new Frame(kind, operation, id, status, bytes.getBuffer(HEADER_BYTES, bytes.length()));
	}

	record Frame(int kind, int operation, long requestId, int status, Buffer body) {}
}
