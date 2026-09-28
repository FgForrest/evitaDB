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

package io.evitadb.core.management;

import io.evitadb.dataType.champ.ChampMap;
import io.evitadb.index.map.PersistentTransactionalMap;

import javax.annotation.Nonnull;
import java.util.ConcurrentModificationException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reads the management surface takes of live index structures that a warm-up writer may be mutating in place.
 *
 * # Why these reads need their own entry point
 *
 * The statistics and index-inspection calls (`EvitaManagement#getIndexDetail`, the `INDEX_CARDINALITY` statistic and
 * the index browse) need no session and take no snapshot, so they run on a management thread that shares no
 * happens-before edge with a warm-up writer. Outside a transaction that writer mutates the `HashMap`s behind the
 * index maps in place, and a `HashMap` walked while it is written to fails fast with
 * {@link ConcurrentModificationException}.
 *
 * That exception is the right answer everywhere **but** here. On the flush, persistence and query paths the walker and
 * the writer are the same thread, so a `ConcurrentModificationException` there is a genuine bug and must stay loud -
 * which is why nothing below is folded into `TransactionalMap#forEach`, `AttributeIndex#forEachInFamily` or
 * `PersistentTransactionalMap#snapshot`. A monitoring call, on the other hand, must not fail because the catalog it
 * describes is being loaded, so the tolerance lives at this boundary and nowhere else.
 *
 * # What a disturbed walk is worth
 *
 * Verified against the JDK 21 bytecode of `java.util.HashMap`: `forEach` on the map and on its `keySet()` and
 * `values()` views reads `modCount` once before the walk and once after the whole table, and throws only then. A
 * `forEach` that throws has therefore **visited every bucket** - its result is complete for the entries the walk
 * reached and off only by the entries written meanwhile. The iterators behind `entrySet()` / `keySet()` loops are the
 * opposite: `HashIterator#nextNode` checks `modCount` on every step and abandons the walk part-way.
 *
 * Both shapes are handled by a **bounded retry**: a walk a write disturbed is started over from a fresh accumulator,
 * up to {@link #MAX_ATTEMPTS} times, because a clean walk describes the map as it stood at one moment, where a
 * disturbed one may have missed entries a resize moved behind it. Only the last attempt settles for less, and only
 * when its walk is end-checked, so its answer is still complete for everything it reached.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ManagementReads {
	/**
	 * How many times a walk a concurrent warm-up write disturbed is attempted before the last attempt's result is
	 * accepted. Writes that disturb a walk - a new index, a new attribute key - are rare against the walks' length, so
	 * the second attempt almost always succeeds; the bound only keeps a continuously loading catalog from turning one
	 * monitoring call into an unbounded loop.
	 */
	static final int MAX_ATTEMPTS = 3;

	private ManagementReads() {
		throw new UnsupportedOperationException("This class cannot be instantiated!");
	}

	/**
	 * Runs `walk` into a fresh accumulator and returns it, starting over when a concurrent warm-up write disturbs the
	 * walk - see the class javadoc.
	 *
	 * **The walk must consist of end-checked walks only** - `forEach` on a `HashMap`, on its `keySet()` or `values()`,
	 * or on a decorator that delegates to one. Then a {@link ConcurrentModificationException} means the walk that
	 * threw has finished, and the last attempt can answer with what it gathered. A walk made of several maps in
	 * sequence is the one exception worth knowing: when an earlier map throws on the last attempt, the maps after it are
	 * not walked, and the answer lacks their entries. That needs {@link #MAX_ATTEMPTS} consecutive walks each
	 * overlapping a new key, and it degrades the monitoring answer rather than failing it.
	 *
	 * @param freshAccumulator creates an empty accumulator for one attempt
	 * @param walk             walks the live structure into the accumulator it is handed
	 * @param <A>              accumulator type
	 * @return the accumulator of the first undisturbed attempt, or of the last attempt when every attempt was disturbed
	 */
	@Nonnull
	public static <A> A walkTolerantly(@Nonnull Supplier<A> freshAccumulator, @Nonnull Consumer<A> walk) {
		for (int attempt = 1; ; attempt++) {
			final A accumulator = freshAccumulator.get();
			try {
				walk.accept(accumulator);
				return accumulator;
			} catch (ConcurrentModificationException ex) {
				// a warm-up write landed in the walk - see the class javadoc for why the last attempt's result stands
				if (attempt >= MAX_ATTEMPTS) {
					return accumulator;
				}
			}
		}
	}

	/**
	 * Returns an immutable snapshot of the given map for a management reader - the `INDEX_CARDINALITY` statistic and
	 * the index browse of an entity collection, which both need the lookups they make and the total they report to
	 * come from one state of the collection's index map.
	 *
	 * A sealed map is answered in `O(1)` by {@link PersistentTransactionalMap#snapshot()}. A thawed one - a warm-up
	 * catalog between two flushes, whose index map gains an entry for every newly referenced entity - is copied, and the
	 * copy iterates `entrySet()`, which fails **mid-copy** on a concurrent write. A copy abandoned half-way cannot be
	 * used at all, so it is retried; should every attempt meet a write, the last one copies with an end-checked
	 * `forEach` instead, whose result holds every entry it reached. Either way the snapshot is one immutable map, so
	 * the readings taken from it cannot contradict each other - the property the callers took a snapshot for.
	 *
	 * @param map the live map to snapshot
	 * @param <K> key type
	 * @param <V> value type
	 * @return an immutable snapshot of the map
	 */
	@Nonnull
	public static <K, V> ChampMap<K, V> snapshotOf(@Nonnull PersistentTransactionalMap<K, V> map) {
		for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
			try {
				return map.snapshot();
			} catch (ConcurrentModificationException ex) {
				// a warm-up write landed in the copy - start over, see the method javadoc
			}
		}
		final ChampMap.Builder<K, V> builder = ChampMap.builder();
		try {
			map.forEach((key, value) -> {
				// a node published by a racing writer may not show its value yet - it holds nothing to copy
				if (key != null && value != null) {
					builder.add(key, value);
				}
			});
		} catch (ConcurrentModificationException ex) {
			// `forEach` checks `modCount` only after the whole table, so the builder holds every entry the walk reached
		}
		return builder.build();
	}

}
