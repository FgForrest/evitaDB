/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.http.routing;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import java.util.Map;

/**
 * Open-addressed string table probed by a region of a longer string, so that the routers look up a path segment or
 * prefix without allocating a substring per request. It probes linearly over a power-of-two capacity kept at a load
 * factor of at most one half, so every probe for an absent key ends at a free slot. Keys are distinct non-null
 * strings, as the source map guarantees; values must not be null, because `get` reports absence with null.
 *
 * @param <T> type of the values
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Immutable
final class RegionRoutingTable<T> {
	/** Lets `get` skip hashing when the table has no entries. */
	private final boolean empty;
	/** Key of each slot; null marks a free slot. */
	private final String[] keys;
	/** Value of each slot, parallel to `keys`; an `Object` array because a generic array cannot be created. */
	private final Object[] values;

	/**
	 * Compiles a table with a load factor of at most one half.
	 *
	 * @param entries keys and their values; the table keeps no reference to the map
	 */
	RegionRoutingTable(@Nonnull Map<String, T> entries) {
		this.empty = entries.isEmpty();
		int capacity = 2;
		while (capacity < entries.size() * 2L) {
			capacity = Math.multiplyExact(capacity, 2);
		}
		this.keys = new String[capacity];
		this.values = new Object[capacity];
		for (final Map.Entry<String, T> entry : entries.entrySet()) {
			final String key = entry.getKey();
			int slot = hash(key, 0, key.length()) & (capacity - 1);
			while (this.keys[slot] != null) {
				slot = (slot + 1) & (capacity - 1);
			}
			this.keys[slot] = key;
			this.values[slot] = entry.getValue();
		}
	}

	/**
	 * Looks up the key equal to the region `path[start, start + length)` without creating that string.
	 *
	 * @param path   string containing the region
	 * @param start  index of the region's first character; the region must lie within `path`
	 * @param length length of the region; 0 looks up the empty key
	 * @return value of the matching key, or null
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	T get(@Nonnull String path, int start, int length) {
		if (this.empty) {
			return null;
		}
		final String[] keys = this.keys;
		final int mask = keys.length - 1;
		int slot = hash(path, start, length) & mask;
		while (keys[slot] != null) {
			final String key = keys[slot];
			if (key.length() == length && path.regionMatches(start, key, 0, length)) {
				return (T) this.values[slot];
			}
			slot = (slot + 1) & mask;
		}
		return null;
	}

	/** Tells whether the table has no entries, so that a caller can skip the probe. */
	boolean isEmpty() {
		return this.empty;
	}

	/**
	 * Probes a validated escaped segment without allocating decoded text, even when literal matching backtracks.
	 *
	 * @param path  string containing the region
	 * @param start index of the region's first character
	 * @param end   exclusive end of the region, whose escapes `RoutingPath#scan` has already validated
	 * @return value of the key equal to the decoded region, or null
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	T getEscaped(@Nonnull String path, int start, int end) {
		if (this.empty) {
			return null;
		}
		final int hash = RoutingPath.decodedHash(path, start, end);
		final int mask = this.keys.length - 1;
		int slot = (hash ^ (hash >>> 16)) & mask;
		while (this.keys[slot] != null) {
			if (RoutingPath.decodedEquals(path, start, end, this.keys[slot])) {
				return (T) this.values[slot];
			}
			slot = (slot + 1) & mask;
		}
		return null;
	}

	/**
	 * Copies every entry into a writer-owned map, keeping each value's identity. The target is not cleared first and
	 * the copy order is unspecified.
	 *
	 * @param target map receiving the entries
	 */
	@SuppressWarnings("unchecked")
	void copyTo(@Nonnull Map<String, T> target) {
		for (int i = 0; i < this.keys.length; i++) {
			if (this.keys[i] != null) {
				target.put(this.keys[i], (T) this.values[i]);
			}
		}
	}

	/**
	 * Computes `String#hashCode` of the region followed by the usual high-bit spread. The constructor and `get` use
	 * this function and `getEscaped` applies the same spread to `RoutingPath#decodedHash`; all three must agree, or a
	 * stored key could not be found again.
	 */
	private static int hash(@Nonnull String text, int start, int length) {
		int hash = 0;
		final int end = start + length;
		for (int i = start; i < end; i++) {
			hash = 31 * hash + text.charAt(i);
		}
		return hash ^ (hash >>> 16);
	}
}
