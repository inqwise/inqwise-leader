package com.inqwise.leader;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Process-wide acquisition guard, separate from context-owned protocol/store state. */
final class StoreOwnership {
	// Closing even an unsuccessful second channel can release the first native lock on POSIX.
	private static final Set<Path> OWNED = ConcurrentHashMap.newKeySet();

	private StoreOwnership() {}

	static boolean claim(Path canonicalDirectory) {
		return OWNED.add(canonicalDirectory);
	}

	static void release(Path canonicalDirectory) {
		OWNED.remove(canonicalDirectory);
	}
}
