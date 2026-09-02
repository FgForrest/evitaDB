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

package io.evitadb.index;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.BucketBPlusTree;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.Instant;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * The sizers the index classes share when pricing what their sub-structures hold.
 *
 * The heap walks themselves live on the indexes, because ownership is a property of the owning class and nothing else
 * can decide it. What collects here is the small amount of *pricing* several of them would otherwise each restate —
 * and restate slightly differently, which is how one of them ends up silently reporting a zero.
 *
 * @author Claude (heap-size accounting), FG Forrest a.s. (c) 2026
 */
public final class IndexHeapSize {

	/**
	 * Prices one boxed key a {@link BucketBPlusTree} owns outright.
	 *
	 * Shared by every index built on such a tree — the inverted index, both unique indexes and the reference-type
	 * cardinality index — so that all of them answer identically for the same key.
	 *
	 * # Where a tree holds a boxed key
	 *
	 * Two places, and only the second depends on the column kind:
	 *
	 * - **Every separator key in an internal node**, always. That array is `M[]`, so a tree whose leaves keep their
	 *   keys inline as `long`s still boxes one key per separator, and nothing but the internal node holds it. Leaving
	 *   these at zero under-reports by one box per separator — a shortfall that **grows with the tree**, which is why
	 *   {@link BucketBPlusTree} has no sizer-less overload to fall into.
	 * - **The leaf keys**, but only when the leaves chose {@link io.evitadb.index.bPlusTree.BoxedObjectColumn}. The
	 *   front-coded and primitive columns store their keys as values and ignore the sizer entirely.
	 *
	 * # The three keys that are not evitaDB data types
	 *
	 * A tree does not store the attribute value the client supplied — it stores the **normalized** form
	 * {@link io.evitadb.index.attribute.FilterIndex#getNormalizer} produced, and for three attribute types that form
	 * is a class {@link EvitaDataTypes} has never heard of: an `OffsetDateTime` normalizes to {@link Instant}, a
	 * `Currency` to {@link ComparableCurrency} and a `Locale` to {@link ComparableLocale}, each so that natural order
	 * over the tree agrees with the order the query layer promises. They are priced here rather than delegated,
	 * because `estimateSize` throws for a type outside evitaDB's own set and would take a real catalog's statistics
	 * request down with it. Both wrappers hold a JVM-interned instance and are charged for the wrapper alone.
	 *
	 * A key that is not {@link Serializable} **throws** rather than being priced at zero. Every value entering these
	 * trees is verified `Serializable` on the way in, so reaching this branch means the invariant broke somewhere
	 * upstream — and a zero would hide that behind a plausible-looking total instead of surfacing it.
	 * {@link EvitaDataTypes#estimateSize} throws in the same spirit for a type evitaDB does not support.
	 */
	public static final ToLongFunction<Object> OWNED_KEY_SIZER = key -> {
		if (key instanceof Instant) {
			// a seconds `long` and a nanos `int`
			final VMLayout layout = VMLayout.current();
			return layout.sizeOfObject(Long.BYTES + Integer.BYTES);
		} else if (key instanceof ComparableCurrency || key instanceof ComparableLocale) {
			// a wrapper over an instance the JVM interns per currency code / language tag
			final VMLayout layout = VMLayout.current();
			return layout.sizeOfObject(layout.referenceSize());
		} else if (key instanceof final Serializable serializable) {
			return EvitaDataTypes.estimateSize(serializable);
		}
		throw new GenericEvitaInternalError(
			"Indexed key of type `" + key.getClass().getName() + "` is not Serializable, which every value entering " +
				"an index tree is verified to be - its heap footprint cannot be priced."
		);
	};

	/**
	 * Prices a {@link Formula} an index memoized, as **scaffolding alone** — never the bitmaps it holds, and never
	 * its inner formulas.
	 *
	 * # Why a memoized formula is charged this shallowly
	 *
	 * The sole remaining caller is {@link io.evitadb.index.invertedIndex.InvertedIndexSubSet}. The index structures
	 * that used to memoize a formula — a filter index's all-records union, an owner unique index's record ids, a
	 * hierarchy index's node set — memoize the **bitmap** instead and build a fresh wrapper per call, because a
	 * formula node retains the execution context of the first query to initialize it and would pin that query's
	 * session and catalog generation for the lifetime of the index. A subset cannot follow them: its aggregation
	 * lambda may return a lazy `DeferredFormula`, so materializing eagerly would change behaviour.
	 *
	 * A memoized formula is a query answer a structure kept, and every bitmap reachable from one is in exactly one of
	 * two states, neither of which this figure may follow:
	 *
	 * - **an alias of index data already charged** — a formula's `memoizedResult` resolves to its own delegate, and a
	 *   single-bucket aggregation short-circuits to that bucket's own bitmap. Following either would charge one
	 *   bitmap twice for a structure that has answered a single query, which rule 1 forbids outright.
	 * - **a recomputable union**, dropped the moment the index is mutated and rebuilt on the next read.
	 *
	 * Following an arbitrary formula tree would additionally need a heap API across the whole query algebra — a query
	 * concern, not an index one. What an index owns here is the cache slot and the node, and that is what is charged.
	 * Data a memo materializes that nothing else holds — the cloned bucket bitmaps of a range histogram, for one —
	 * is NOT a formula and is charged in full by whoever holds it.
	 *
	 * The node is priced at its **upper bound**: twelve reference fields (the widest shape in play, an
	 * {@link io.evitadb.core.query.algebra.base.OrFormula} — nine inherited plus a computation callback, a bitmap
	 * array and a transactional-id array), all five boxed `Long` memos whether or not they have been computed, and a
	 * one-element transactional-id array. Which of them are populated cannot be read from outside, so the higher of
	 * the defensible figures is the answer — a fixed handful of bytes per memo, never a term that grows.
	 *
	 * {@link EmptyFormula#INSTANCE} is a JVM-wide constant every empty index resolves to and contributes nothing.
	 *
	 * @param formula the memoized formula, or `null` when the cache is cold
	 * @return the owned heap footprint of the formula's own scaffolding in bytes
	 */
	public static long memoizedFormulaSizeInBytes(@Nullable Formula formula) {
		if (formula == null || formula == EmptyFormula.INSTANCE) {
			return 0L;
		}
		final VMLayout layout = VMLayout.current();
		return layout.sizeOfObject(12L * layout.referenceSize())
			+ 5L * layout.sizeOfObject(Long.BYTES)
			+ layout.sizeOfArray(1, Long.BYTES);
	}

	/**
	 * Prices an immutable {@link java.util.Set#copyOf} result — the shape every persisted-baseline manifest an
	 * {@link EntityIndex} keeps between flushes takes.
	 *
	 * # Why the shape has to be modelled rather than counted
	 *
	 * `Set.copyOf` picks one of three implementations by element count, and they differ by more than a constant: an
	 * empty copy is a JVM-wide singleton owned by nobody, one or two elements are held in **fields** with no array at
	 * all, and three or more allocate an open-addressed table of **twice** the element count. Charging a flat
	 * per-entry cost would read a two-element set high and a large one low by half.
	 *
	 * An empty set is charged nothing for the same reason {@link java.util.Collections#emptySet()} is: a fresh index
	 * parks all four of its baselines on singletons, and billing them would give every never-flushed index a
	 * footprint it does not have.
	 *
	 * @param set          the immutable set to price
	 * @param elementSizer prices one element's owned payload; returns `0` for elements the set borrows
	 * @return the owned heap footprint of the set in bytes, including alignment padding
	 */
	public static <T> long immutableSetSizeInBytes(
		@Nonnull Set<T> set,
		@Nonnull ToLongFunction<? super T> elementSizer
	) {
		final int size = set.size();
		if (size == 0) {
			return 0L;
		}
		final VMLayout layout = VMLayout.current();
		// `Set12` keeps its one or two elements in fields; `SetN` keeps a size and a table twice as wide as its
		// content, which is what keeps its probe sequences short
		long result = size <= 2 ?
			layout.sizeOfObject(2L * layout.referenceSize()) :
			layout.sizeOfObject(Integer.BYTES + layout.referenceSize())
				+ layout.sizeOfArray(2 * size, layout.referenceSize());
		for (final T element : set) {
			result += elementSizer.applyAsLong(element);
		}
		return result;
	}

	private IndexHeapSize() {
		// utility class, never instantiated
	}

}
