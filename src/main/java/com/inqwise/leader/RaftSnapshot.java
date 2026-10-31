package com.inqwise.leader;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Immutable committed-prefix checkpoint. Application data represents state through exactly lastIncludedIndex.
 * @param lastIncludedIndex absolute committed boundary
 * @param lastIncludedTerm term at the boundary
 * @param configuration membership at the boundary, including joint membership
 * @param configurationIndex absolute position of that configuration, zero for genesis
 * @param data opaque UTF-8 application checkpoint; empty for coordination-only state
 */
public record RaftSnapshot(long lastIncludedIndex, long lastIncludedTerm, RaftConfiguration configuration,
	long configurationIndex, String data) {
	/** Maximum application checkpoint bytes in one bounded atomic transfer. */
	public static final int MAX_DATA_BYTES = 512 * 1024;
	/**
	 * Validates checkpoint bounds and lossless UTF-8 data.
	 * @param lastIncludedIndex committed boundary
	 * @param lastIncludedTerm boundary term
	 * @param configuration membership at boundary
	 * @param configurationIndex configuration position
	 * @param data application checkpoint
	 */
	public RaftSnapshot {
		Objects.requireNonNull(configuration, "configuration"); Objects.requireNonNull(data, "data");
		if (lastIncludedIndex <= 0 || lastIncludedIndex == Long.MAX_VALUE || lastIncludedTerm <= 0
			|| configurationIndex < 0 || configurationIndex > lastIncludedIndex || data.length() > MAX_DATA_BYTES) {
			throw new IllegalArgumentException("Invalid snapshot boundary");
		}
		try {
			if (StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(data)).remaining() > MAX_DATA_BYTES) {
				throw new IllegalArgumentException("Snapshot application data exceeds supported transfer size");
			}
		} catch (java.nio.charset.CharacterCodingException e) { throw new IllegalArgumentException("Invalid snapshot UTF-8", e); }
	}
}
