package com.inqwise.leader;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Immutable entry in the coordination log; DATA is opaque coordination metadata, not an application job API.
 * @param index one-based log position
 * @param term term in which the entry was created
 * @param kind entry purpose
 * @param data bounded UTF-8 payload; empty for a no-op
 */
public record RaftLogEntry(long index, long term, Kind kind, String data) {
	/** Maximum encoded payload size for one entry. */
	public static final int MAX_DATA_BYTES = 16 * 1024;

	/** Supported coordination entry purposes. */
	public enum Kind {
		/** Establishes commitment in a newly elected leader's term. */
		NO_OP,
		/** Opaque coordination metadata, interpreted by a later state-machine adapter. */
		DATA,
		/** Canonical replicated voting/learner configuration. */
		CONFIGURATION
	}

	/**
	 * Validates entry position, purpose, and losslessly encodable payload.
	 * @param index one-based position
	 * @param term positive term
	 * @param kind entry purpose
	 * @param data UTF-8 payload
	 */
	public RaftLogEntry {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(data, "data");
		if (index <= 0 || term <= 0 || data.length() > MAX_DATA_BYTES || (kind == Kind.NO_OP && !data.isEmpty())) {
			throw new IllegalArgumentException("Entries need positive index/term; no-ops must have empty data");
		}
		if (kind == Kind.CONFIGURATION) RaftConfiguration.decode(data);
		try {
			if (StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(data)).remaining() > MAX_DATA_BYTES) {
				throw new IllegalArgumentException("Entry data exceeds the supported size");
			}
		} catch (CharacterCodingException e) {
			throw new IllegalArgumentException("Entry data is not valid Unicode", e);
		}
	}
}
