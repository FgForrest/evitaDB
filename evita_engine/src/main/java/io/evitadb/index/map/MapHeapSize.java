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

import io.evitadb.dataType.champ.ChampMap;
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
 * backing state, and the shapes differ enough in layout that a single "close enough" formula would be wrong for one
 * of them. Two are recognised — a plain {@link HashMap} and a {@link ChampMap}, which between them cover every state
 * either decorator can hold — and anything else is a programming error that throws rather than being silently
 * mispriced.
 *
 * The `HashMap` arm matches the class **exactly**, not with `instanceof`: `LinkedHashMap` extends `HashMap` and adds
 * both a pair of list pointers to the map object and two more references to every entry, so an `instanceof` test
 * would accept it and under-report. `ChampMap` is `final`, so no such confusion is possible there, and it prices
 * itself — its nodes are private, and the arithmetic below could not see them.
 *
 * # What the figure is exact about, and what it is not
 *
 * Everything except the bucket table is exact. The table's capacity cannot be read from outside the JDK — the field
 * is not public and `java.base` does not open `java.util` — so it is reconstructed from the entry count. Two
 * construction paths produce different tables for the same content, and neither can be told from the other after
 * the fact:
 *
 * - **organic growth**, `put` by `put`, doubles when the entry count *exceeds* the load-factor threshold, so a map
 *   holding exactly 12 entries still sits on a 16-slot table.
 * - **`new HashMap<>(source)`** — how {@link PersistentTransactionalMap} builds and thaws its warm-up buffer, and
 *   what `EntityCollection` hands to its index maps — sizes the table to hold the whole source *without* an
 *   immediate resize, so the same 12 entries get 32 slots.
 *
 * {@link #tableCapacityFor} therefore reports the **larger** of the two, on the standing rule that where several
 * figures are defensible the higher one is the answer: under-reporting memory is the failure that matters, since it
 * is what leads to under-provisioning. The cost is that an organically grown map holding exactly 12, 24, 48 or 96
 * entries — the sizes sitting precisely on a threshold — is over-reported by one table doubling. Every other size,
 * and every copied map at or above the floor, is exact. Both the exact sizes and the over-reported ones are pinned
 * by tests so the choice stays visible.
 *
 * A map created **pre-sized above its eventual content** is the one case that still reads low:
 * `CollectionUtils.createHashMap(64)` holding three entries really owns a 128-slot table while the reconstruction
 * infers 16. Nothing in the entry count can reveal that — the map was simply asked for more room than it uses. The
 * error is bounded by the initial capacity and disappears once the map holds more entries than that capacity
 * implied.
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
	 * Smallest table **organic growth** allocates once something is put into a map. The copy constructor goes below
	 * it — two slots for a single-entry source — which is exactly why this is applied as a floor rather than as the
	 * answer: the reconstruction reports whichever path would have allocated more.
	 */
	private static final int MINIMUM_TABLE_CAPACITY = 16;

	/**
	 * Largest table `HashMap` will allocate, restated here because the field is not reachable. Reaching it needs
	 * some 800 million entries, but the rounding below overflows to a negative capacity past this point — and the
	 * floor would then turn that into the smallest table of all, at the one method that promises never to read low.
	 */
	private static final int MAXIMUM_TABLE_CAPACITY = 1 << 30;

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
	 * @param map         the map to price; must be a plain {@link HashMap} or a {@link ChampMap}
	 * @param keySizer    prices one key, or returns `0` when the map does not own it
	 * @param valueSizer  prices one value, or returns `0` when the map does not own it
	 * @param <K>         key type
	 * @param <V>         value type
	 * @return the heap footprint in bytes, including alignment padding
	 */
	@SuppressWarnings("unchecked")
	static <K, V> long sizeOf(
		@Nonnull Map<K, V> map,
		@Nonnull ToLongFunction<? super K> keySizer,
		@Nonnull ToLongFunction<? super V> valueSizer
	) {
		if (map instanceof ChampMap) {
			// the trie prices itself: its nodes are a private implementation detail, and only it can tell a node's
			// own zero-length array from the shared empty singleton the same field often points at instead
			return ((ChampMap<K, V>) map).getHeapSizeInBytes(keySizer, valueSizer);
		}
		// exact class, not instanceof - see the class javadoc on LinkedHashMap
		if (map.getClass() != HashMap.class) {
			throw new GenericEvitaInternalError(
				"Cannot price the heap of map implementation `" + map.getClass().getName() + "` - only a plain " +
					"java.util.HashMap and a ChampMap are supported here. Add its layout to MapHeapSize rather than " +
					"letting it be mispriced as a HashMap."
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
	 * Reconstructs the bucket-table capacity of a `HashMap` holding `entryCount` entries, as the **larger** of what
	 * the two construction paths would have produced — see the class javadoc for why the upper bound is the right
	 * one to take.
	 *
	 * The formula is `HashMap(Map)`'s own: `(size / loadFactor) + 1` rounded up to a power of two, floored at
	 * {@link #MINIMUM_TABLE_CAPACITY}. That floor is what makes it an upper bound rather than just the copy
	 * constructor's answer: the copy constructor allocates below 16 slots for a small source (two slots for a
	 * single entry), where a grown map would hold the default 16. Above the floor the same expression already
	 * dominates organic growth, which rounds one doubling lower at the sizes that sit exactly on a load-factor
	 * threshold.
	 *
	 * @param entryCount number of entries currently in the map; must be positive
	 * @return the inferred table length in slots, never below what either construction path would allocate
	 */
	private static int tableCapacityFor(int entryCount) {
		final int required = (int) (entryCount / LOAD_FACTOR + 1.0f);
		if (required >= MAXIMUM_TABLE_CAPACITY) {
			return MAXIMUM_TABLE_CAPACITY;
		}
		final int rounded = required <= 1 ? 1 : Integer.highestOneBit(required - 1) << 1;
		return Math.max(MINIMUM_TABLE_CAPACITY, rounded);
	}

}
