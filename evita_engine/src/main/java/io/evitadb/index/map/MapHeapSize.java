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

package io.evitadb.index.map;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Prices the heap occupied by the plain JDK maps this package's transactional decorators wrap.
 *
 * # Why the shape is checked rather than assumed
 *
 * Both {@link TransactionalMap} and {@link PersistentTransactionalMap} accept an arbitrary {@link Map} as their
 * backing state, and the two differ enough in layout that a single "close enough" formula would be wrong for one of
 * them. So the shape is matched **exactly** — `getClass() == HashMap.class`, not `instanceof`, because
 * `LinkedHashMap` extends `HashMap` and adds both a pair of list pointers to the map object and two more references
 * to every entry. An unrecognised map is a programming error and throws rather than being silently mispriced.
 *
 * # What the figure is exact about, and what it is not
 *
 * Everything except the bucket table is exact. The table's capacity cannot be read from outside the JDK — the field
 * is not public and `java.base` does not open `java.util` — so it is reconstructed from the entry count using
 * `HashMap`'s own growth rule. That is exact for a map that grew organically, and **under-reports a map created
 * pre-sized above its eventual content**: `CollectionUtils.createHashMap(64)` holding three entries really owns a
 * 128-slot table while the reconstruction infers 16. The error is bounded by the initial capacity, disappears the
 * moment the map holds more entries than that capacity implied, and is pinned by a test so it stays deliberate.
 *
 * A bin that has treeified — eight hash collisions in one bucket on a table of at least 64 — holds `TreeNode`s,
 * which carry six more references and a flag than the `Node`s priced here, so such a map reads slightly low. It
 * cannot be detected without the same blocked reflection, and the key types in play (boxed ints, locales, attribute
 * keys) do not collide that way in practice.
 *
 * @author Claude (heap-size accounting), FG Forrest a.s. (c) 2026
 */
final class MapHeapSize {
	/**
	 * Smallest table `HashMap` ever allocates once something is put into it.
	 */
	private static final int MINIMUM_TABLE_CAPACITY = 16;

	/**
	 * `HashMap`'s default load factor. Restated here because the field is not reachable, and the growth rule below
	 * has to replay the JDK's own arithmetic to land on the same capacity.
	 */
	private static final float LOAD_FACTOR = 0.75f;

	private MapHeapSize() {
		// utility class, never instantiated
	}

	/**
	 * Returns the heap occupied by `map`, its bucket table, its per-entry nodes and — as priced by the two supplied
	 * sizers — its keys and values.
	 *
	 * Keys and values get **separate** sizers rather than the single `ToLongFunction<Object>` the B+ tree family
	 * uses. The maps in this codebase are pervasively heterogeneous (`Integer` → `TransactionalBitmap`, `Locale` →
	 * `Integer`, `AttributeIndexKey` → a sub-index), so one shared sizer would force an `instanceof` dispatch at
	 * every call site; two sizers say what each side is at the point where the caller already knows. Either may
	 * return `0` for a key or value the map only borrows.
	 *
	 * @param map         the map to price; must be a plain {@link HashMap}
	 * @param keySizer    prices one key, or returns `0` when the map does not own it
	 * @param valueSizer  prices one value, or returns `0` when the map does not own it
	 * @param <K>         key type
	 * @param <V>         value type
	 * @return the heap footprint in bytes, including alignment padding
	 */
	static <K, V> long sizeOf(
		@Nonnull Map<K, V> map,
		@Nonnull ToLongFunction<? super K> keySizer,
		@Nonnull ToLongFunction<? super V> valueSizer
	) {
		// exact class, not instanceof - see the class javadoc on LinkedHashMap
		if (map.getClass() != HashMap.class) {
			throw new GenericEvitaInternalError(
				"Cannot price the heap of map implementation `" + map.getClass().getName() + "` - only a plain " +
					"java.util.HashMap is supported here. Add its layout to MapHeapSize rather than letting it be " +
					"mispriced as a HashMap."
			);
		}

		final VMLayout layout = VMLayout.current();
		// AbstractMap.keySet/values + HashMap.table/entrySet references, then size/modCount/threshold ints and the
		// loadFactor float
		long size = layout.sizeOfObject(4L * layout.referenceSize() + 4L * Integer.BYTES);

		final int entryCount = map.size();
		if (entryCount > 0) {
			// the table is allocated lazily on the first put, so an empty map genuinely owns none - charging a
			// phantom 16-slot table would roughly double the reported size of every empty map, and several of the
			// index-level maps sit empty for the lifetime of their index
			size += layout.sizeOfArray(tableCapacityFor(entryCount), layout.referenceSize());
			// one Node per entry: the `hash` int plus the key / value / next references
			size += entryCount * layout.sizeOfObject(Integer.BYTES + 3L * layout.referenceSize());
		}

		// `forEach` walks the table directly. Iterating `entrySet()` instead would lazily allocate and cache the
		// view object on first call - growing the very map being measured, and by an amount that depends on whether
		// anyone had asked for it before
		final long[] payload = new long[1];
		map.forEach((key, value) -> {
			if (key != null) {
				payload[0] += keySizer.applyAsLong(key);
			}
			if (value != null) {
				payload[0] += valueSizer.applyAsLong(value);
			}
		});
		return size + payload[0];
	}

	/**
	 * Reconstructs the bucket-table capacity a `HashMap` holding `entryCount` entries would have grown to: the
	 * smallest power of two, never below {@link #MINIMUM_TABLE_CAPACITY}, whose load-factor threshold still covers
	 * the entries.
	 *
	 * @param entryCount number of entries currently in the map; must be positive
	 * @return the inferred table length in slots
	 */
	private static int tableCapacityFor(int entryCount) {
		final int required = Math.max(1, (int) (entryCount / LOAD_FACTOR));
		return Math.max(MINIMUM_TABLE_CAPACITY, Integer.highestOneBit(required - 1) << 1);
	}

}
