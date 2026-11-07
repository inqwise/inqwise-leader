package com.inqwise.leader;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Atomic durable checkpoint plus bounded retained log tail. All positions remain absolute after compaction.
 * @param hardState current term and vote
 * @param log contiguous entries after the snapshot boundary
 * @param commitIndex absolute durable committed position
 * @param snapshot committed checkpoint, or null before first compaction
 */
public record RaftPersistentState(RaftHardState hardState, List<RaftLogEntry> log, long commitIndex,
	RaftSnapshot snapshot) {
	/** Maximum retained entries; compaction permits continued operation beyond this total history length. */
	public static final int MAX_LOG_ENTRIES = 4096;
	/** Maximum aggregate retained entry payload. */
	public static final int MAX_LOG_DATA_BYTES = 8 * 1024 * 1024;

	/**
	 * Creates uncompacted state for compatibility with earlier callers.
	 * @param hardState term and vote
	 * @param log entries beginning at one
	 * @param commitIndex committed prefix
	 */
	public RaftPersistentState(RaftHardState hardState, List<RaftLogEntry> log, long commitIndex) {
		this(hardState, log, commitIndex, null);
	}

	/**
	 * Copies and validates the complete atomic state.
	 * @param hardState current term and vote
	 * @param log retained tail
	 * @param commitIndex absolute commit position
	 * @param snapshot checkpoint or null
	 */
	public RaftPersistentState {
		Objects.requireNonNull(hardState, "hardState"); Objects.requireNonNull(log, "log");
		if (log.size() > MAX_LOG_ENTRIES) throw new IllegalArgumentException("Retained log exceeds supported count");
		log = List.copyOf(log);
		long base = snapshot == null ? 0 : snapshot.lastIncludedIndex();
		long previousTerm = snapshot == null ? 0 : snapshot.lastIncludedTerm();
		if (previousTerm > hardState.currentTerm() || base > Long.MAX_VALUE - log.size()
			|| commitIndex < base || commitIndex > base + log.size()) {
			throw new IllegalArgumentException("Invalid snapshot, log size or commit position");
		}
		long bytes = 0;
		for (int i = 0; i < log.size(); i++) {
			var entry = log.get(i);
			if (entry.index() != base + i + 1 || entry.term() < previousTerm || entry.term() > hardState.currentTerm()) {
				throw new IllegalArgumentException("Retained entries must be contiguous with ordered terms");
			}
			previousTerm = entry.term(); bytes += entry.data().getBytes(StandardCharsets.UTF_8).length;
		}
		if (bytes > MAX_LOG_DATA_BYTES) throw new IllegalArgumentException("Retained log payload exceeds supported size");
	}

	/**
	 * Reads the compacted boundary.
	 * @return snapshot boundary, or zero before compaction
	 */
	public long baseIndex() { return snapshot == null ? 0 : snapshot.lastIncludedIndex(); }
	/**
	 * Reads the absolute end of history.
	 * @return absolute last position including the snapshot
	 */
	public long lastIndex() { return baseIndex() + log.size(); }
	/**
	 * Reads a retained entry using an absolute index.
	 * @param index position strictly after baseIndex
	 * @return retained entry
	 */
	public RaftLogEntry entryAt(long index) {
		if (index <= baseIndex() || index > lastIndex()) throw new IllegalArgumentException("Entry is outside retained tail");
		return log.get(Math.toIntExact(index - baseIndex() - 1));
	}
	/**
	 * Reads the snapshot-boundary or retained term.
	 * @param index retained position, including baseIndex
	 * @return term, zero only for an empty origin
	 */
	public long termAt(long index) {
		return index == baseIndex() ? (snapshot == null ? 0 : snapshot.lastIncludedTerm()) : entryAt(index).term();
	}
}
