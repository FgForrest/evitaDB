/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.index.bPlusTree;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Comparator;

/**
 * Creates a fresh empty {@link ValueColumn} of the kind chosen for a particular attribute key type, so a
 * {@link TransactionalBucketBPlusTree} leaf can pick the cheapest representation (a front-coded
 * {@link FrontCodedStringColumn} for {@link String} keys, a primitive {@link LongValueColumn} for integral / temporal
 * keys, a {@link RangeValueColumn} for range keys, otherwise the universal {@link BoxedObjectColumn}).
 *
 * The selection is made once per tree (via {@link #forKey} or {@link #forFilterKey}) and threaded into every
 * empty-leaf creation; split / merge reuse the originating column's {@link ValueColumn#allocate}, so
 * kind-consistency is guaranteed within one tree.
 *
 * **There are two entry points, and which one a caller may use is a correctness constraint rather than a
 * convenience.** They serve two different **key spaces**: {@link #forFilterKey} serves the *normalized* one a filter
 * index builds through `FilterIndex#getNormalizer`, and {@link #forKey} the *raw* one, where the tree holds the
 * values exactly as the caller handed them over. {@link #forFilterKey} is therefore the wider of the two — it is the
 * only one that applies the temporal key-class remap, and the only one that can select the range column, because it
 * is the only one whose caller has an `indexedDecimalPlaces` to give. See its javadoc, and {@link #forKey}'s, for
 * what would go silently wrong otherwise.
 *
 * @param <M> the (boxed) key type
 */
@FunctionalInterface
public interface ValueColumnFactory<M extends Comparable<M>> {

	/**
	 * Creates a new empty column of the chosen kind with the given **logical** capacity (== the leaf block size). The
	 * column allocates no backing storage until its first write — see {@link ValueColumn} for the logical / physical
	 * split.
	 *
	 * @param capacity the logical capacity
	 * @return a fresh empty column
	 */
	@Nonnull
	ValueColumn<M> create(int capacity);

	/**
	 * Selects the value-column factory for a **filter index** attribute key — the entry point for the *normalized*
	 * key space, widened by the one column kind that needs a scale, {@link RangeValueColumn}.
	 *
	 * A filter index does not store its keys as the schema declares them: `FilterIndex#getNormalizer` rewrites every
	 * value before it becomes a tree key, and this method mirrors the resulting **key class** through
	 * {@link #normalizedTypeOf} before delegating. That is what lets a declared {@code OffsetDateTime} /
	 * {@code LocalDateTime} attribute ride in the single-`long` {@link LongValueColumn} as an {@link Instant} — the
	 * tree really does hold {@link Instant}s there. The remap lives here and **only** here; {@link #forKey}'s callers
	 * store raw values and must not get a column keyed by a class their values are never converted to.
	 *
	 * The range column is chosen for the **six concrete** range subtypes ({@code DateTimeRange} and the five
	 * {@code NumberRange} implementations) under natural order, matched by exact class equality. A filter index can
	 * legitimately be built over the abstract {@code NumberRange.class} or {@code Range.class} — neither is a
	 * supported schema attribute type, but both are constructible — and there is no subtype to rebuild for them, so
	 * they fall through to {@link #forKey} and keep the universal boxed column exactly as before. Everything the
	 * range column does not claim is delegated to {@link #forKey} verbatim, under the normalized key class.
	 *
	 * @param plainType            the attribute's plain (non-array) declared type
	 * @param comparator           the tree comparator, or {@code null} for natural order
	 * @param indexedDecimalPlaces the scale this index's {@code BigDecimalNumberRange} keys are encoded at (0 for
	 *                             every other type)
	 * @return the factory producing the chosen column kind
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	static ValueColumnFactory<? extends Comparable> forFilterKey(
		@Nonnull Class<?> plainType,
		@Nullable Comparator<?> comparator,
		int indexedDecimalPlaces
	) {
		if (isNaturalOrder(comparator)) {
			final RangeKind kind = RangeKind.forType(plainType);
			if (kind != null) {
				// raw lambda → the wildcard return is closed over the kind's concrete subtype (natural order only)
				return (ValueColumnFactory) capacity -> new RangeValueColumn(kind, indexedDecimalPlaces, capacity);
			}
		}
		// the range kinds are matched on the DECLARED type (no range type is remapped); everything else is selected
		// on the class the normalizer will actually have produced by the time a key reaches the leaf
		return forKey(normalizedTypeOf(plainType), comparator);
	}

	/**
	 * Selects the value-column factory for an attribute key held **exactly as the caller stores it** — the entry
	 * point for the *raw* key space, where no normalizer stands between the value and the leaf.
	 *
	 * {@link String} keys (localized or not) select the front-coded {@link FrontCodedStringColumn} first, regardless of
	 * the comparator: front-coding is order-agnostic — the column stores values in whatever physical order the tree
	 * imposes and {@link FrontCodedStringColumn#findKeyPosition} decodes each candidate back to a {@link String} and
	 * compares it through the supplied comparator (natural codepoint order or locale collation). Under natural order,
	 * when both the stored corpus and the probe are BMP-only, the comparison instead runs directly over raw UTF-8
	 * bytes, skipping the per-candidate {@link String} allocation; see {@link FrontCodedStringColumn}'s "BMP-safe
	 * byte-compare fast path" section.
	 *
	 * A primitive column is chosen only when the comparator is natural order. Every key type with a supported
	 * {@link LongKeyCodec} selects {@link LongValueColumn} — the integral types, {@code LocalDate}, {@code LocalTime}
	 * and {@link Instant}, whose keys ride in a single {@code long} as epoch-millis because no sub-millisecond value
	 * ever reaches a tree key (see {@link LongKeyCodec#INSTANT}). {@code BigDecimal} keys (normalized upstream to a
	 * scaled {@code int}) select the 4-byte {@link IntValueColumn}. Otherwise the universal boxed
	 * {@link BoxedObjectColumn} (keyed by {@code Comparable.class}, the raw key type the tree uses today) is
	 * returned, which is behavior-identical to the universal boxed leaf.
	 *
	 * **The key type is taken literally, and that is the whole point of the split.** A declared
	 * {@code OffsetDateTime} / {@code LocalDateTime} attribute lands on the boxed column here, not on the
	 * {@link Instant}-keyed {@link LongValueColumn}: this method's callers keep values exactly as they were handed
	 * over — `OwnerUniqueIndex` and `GlobalUniqueIndex` document that they hold RAW values — so a column that
	 * {@link LongKeyCodec#INSTANT} would `(Instant)`-cast every key into is simply the wrong column, and selecting it
	 * threw a `ClassCastException` on the first write. The temporal remap belongs to {@link #forFilterKey}, whose
	 * caller really does convert its values first; keeping {@link #normalizedTypeOf} out of this method makes the
	 * mismatch structurally impossible rather than a matter of every caller remembering to normalize.
	 *
	 * **This entry point can never select the {@link RangeValueColumn} either, for the same shape of reason.** A
	 * range column rebuilding a {@code BigDecimalNumberRange} needs the index's `indexedDecimalPlaces` to reproduce
	 * the bounds at the scale the tree's keys were encoded with, and neither of this method's callers has such a
	 * scale to offer: `UniqueIndexBPlusTreeSupport.buildTree`, which serves both `OwnerUniqueIndex` and
	 * `GlobalUniqueIndex`, and `ReferenceTypeCardinalityIndex`. A `Range`-typed attribute declared `unique` would
	 * otherwise move its unique tree onto a column rebuilding every key at whatever default the parameter carried —
	 * at the wrong scale, with no error anywhere. The filter-index caller uses {@link #forFilterKey} instead.
	 *
	 * @param plainType  the plain (non-array) type of the keys the tree will actually hold
	 * @param comparator the tree comparator, or {@code null} for natural order
	 * @return the factory producing the chosen column kind
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	static ValueColumnFactory<? extends Comparable> forKey(
		@Nonnull Class<?> plainType,
		@Nullable Comparator<?> comparator
	) {
		if (String.class.isAssignableFrom(plainType)) {
			// String keys (localized or not) are prefix-compressed into a front-coded byte block. Front-coding is
			// orthogonal to the key order — the column stores values in whatever physical order the tree imposes and
			// findKeyPosition usually decodes each candidate back to a String and compares it through the supplied
			// comparator — so this column is selected regardless of the comparator (natural codepoint order vs.
			// locale collation).
			// naturalOrderSafe is captured once, here, from the same comparator every subsequent findKeyPosition
			// call on this column will receive — see FrontCodedStringColumn's BMP-safe byte-compare fast path.
			final boolean naturalOrderSafe = isNaturalOrder(comparator);
			return (ValueColumnFactory) capacity -> new FrontCodedStringColumn(capacity, naturalOrderSafe);
		}
		if (isNaturalOrder(comparator)) {
			if (plainType == BigDecimal.class) {
				// BigDecimal filter/sort keys are normalized upstream to a scaled int (indexedDecimalPlaces);
				// store them in a 4-byte int[] column. The column never sees a BigDecimal (already converted).
				return (ValueColumnFactory) capacity -> new IntValueColumn(capacity);
			}
			final LongKeyCodec codec = LongKeyCodec.forType(plainType);
			if (codec != null) {
				// raw lambda → the wildcard return is closed over the codec's monotonic encoding (natural order only)
				return (ValueColumnFactory) capacity -> new LongValueColumn(codec, capacity);
			}
		}
		// boxed fallback keyed by Comparable.class (the raw key type the tree uses today)
		return (ValueColumnFactory) capacity -> new BoxedObjectColumn(Comparable.class, capacity);
	}

	/**
	 * Maps a plain attribute type to the type a **filter index** actually stores as its tree key, mirroring the
	 * **temporal** key-class remaps performed by {@code FilterIndex.getNormalizer}: {@link OffsetDateTime} is stored
	 * as its {@link Instant}, and so is {@link LocalDateTime} (anchored at UTC — a constant offset, hence a lossless,
	 * order-preserving mapping). Those two must stay in lockstep with `FilterIndex.getNormalizer`, and this is their
	 * single source of truth.
	 *
	 * **It is reachable from {@link #forFilterKey} alone, and must stay that way.** The remap is only true of a tree
	 * whose caller runs the normalizer; applying it to the raw key space of {@link #forKey} names a class those keys
	 * are never converted to, and the column then casts a declared {@code OffsetDateTime} to {@link Instant} on its
	 * first write. That is the mismatch this method's placement — rather than any runtime check — rules out.
	 *
	 * The normalizer changes the stored class for other types too — {@code BigDecimal} to a scaled {@code Integer},
	 * {@code Currency} / {@code Locale} to their comparable wrappers — but those are **not** remapped here:
	 * {@code BigDecimal} is matched on its declared type directly in {@link #forKey} (which then selects
	 * {@link IntValueColumn}), and the wrapper types fall through to the boxed column either way. Everything else
	 * (numbers, {@code LocalDate} / {@code LocalTime}, {@code String}, …) keeps its own class. The remap exists
	 * purely to name the key class the tree really holds — {@link Instant} has a {@link LongKeyCodec} of its own, so
	 * the remapped type lands in {@link LongValueColumn} exactly as {@code LocalDate} and {@code LocalTime} do.
	 *
	 * @param plainType the plain (non-array) declared attribute type
	 * @return the normalized key type used by a filter index tree
	 */
	@Nonnull
	private static Class<?> normalizedTypeOf(@Nonnull Class<?> plainType) {
		if (OffsetDateTime.class.isAssignableFrom(plainType) || LocalDateTime.class.isAssignableFrom(plainType)) {
			return Instant.class;
		}
		return plainType;
	}

	/**
	 * Returns whether the comparator imposes natural order (the {@code null} default or the
	 * {@link Comparator#naturalOrder()} singleton). Only then is the monotonic {@link LongKeyCodec} encoding guaranteed
	 * to match the tree ordering, and only then is {@link FrontCodedStringColumn}'s BMP-safe byte-compare fast path
	 * (package-visible reuse of this same identity check) safe to take.
	 *
	 * @param comparator the comparator to test, or {@code null}
	 * @return {@code true} when the order is natural
	 */
	static boolean isNaturalOrder(@Nullable Comparator<?> comparator) {
		return comparator == null || comparator == Comparator.naturalOrder();
	}
}
