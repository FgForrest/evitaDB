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
import java.time.OffsetDateTime;
import java.util.Comparator;

/**
 * Creates a fresh empty {@link ValueColumn} of the kind chosen for a particular attribute key type, so a
 * {@link TransactionalBucketBPlusTree} leaf can pick the cheapest representation (a front-coded
 * {@link FrontCodedStringColumn} for {@link String} keys, a primitive {@link LongValueColumn} for integral / temporal
 * keys, otherwise the universal {@link BoxedObjectColumn}).
 *
 * The selection is made once per tree (via {@link #forKey}) and threaded into every empty-leaf creation; split / merge
 * reuse the originating column's {@link ValueColumn#allocate}, so kind-consistency is guaranteed within one tree.
 *
 * @param <M> the (boxed) key type
 */
@FunctionalInterface
public interface ValueColumnFactory<M extends Comparable<M>> {

	/**
	 * Creates a new empty column of the chosen kind with the given backing capacity (== the leaf block size).
	 *
	 * @param capacity the backing capacity
	 * @return a fresh empty column
	 */
	@Nonnull
	ValueColumn<M> create(int capacity);

	/**
	 * Selects the value-column factory for an attribute key.
	 *
	 * {@link String} keys (localized or not) select the front-coded {@link FrontCodedStringColumn} first, regardless of
	 * the comparator: front-coding is order-agnostic — the column stores values in whatever physical order the tree
	 * imposes and {@link FrontCodedStringColumn#findKeyPosition} decodes each candidate back to a {@link String} and
	 * compares it through the supplied comparator (natural codepoint order or locale collation).
	 *
	 * A primitive column is chosen only when the comparator is natural order. Temporal keys (normalized type
	 * {@link Instant}, i.e. declared {@code OffsetDateTime} / {@code Instant}) select the parallel-array
	 * {@link InstantValueColumn}; integral keys with a supported {@link LongKeyCodec} select {@link LongValueColumn}.
	 * {@code BigDecimal} keys (normalized upstream to a scaled {@code int}) select the 4-byte {@link IntValueColumn}.
	 * Otherwise the universal boxed {@link BoxedObjectColumn} (keyed by {@code Comparable.class}, the raw key type the
	 * tree uses today) is returned, which is behavior-identical to the universal boxed leaf.
	 *
	 * @param plainType  the attribute's plain (non-array) declared type
	 * @param comparator the tree comparator, or {@code null} for natural order
	 * @return the factory producing the chosen column kind
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	static ValueColumnFactory<? extends Comparable> forKey(
		@Nonnull Class<?> plainType,
		@Nullable Comparator<?> comparator
	) {
		final Class<?> normalizedType = normalizedTypeOf(plainType);
		if (String.class.isAssignableFrom(plainType)) {
			// String keys (localized or not) are prefix-compressed into a front-coded byte block. Front-coding is
			// orthogonal to the key order — the column stores values in whatever physical order the tree imposes and
			// findKeyPosition decodes each candidate back to a String and compares it through the supplied comparator —
			// so this column is selected regardless of the comparator (natural codepoint order vs. locale collation).
			return (ValueColumnFactory) FrontCodedStringColumn::new;
		}
		if (isNaturalOrder(comparator)) {
			if (normalizedType == Instant.class) {
				// temporal keys (OffsetDateTime / Instant) decompose losslessly into a (seconds, nanos) parallel-array
				// column whose lexicographic order matches natural Instant order (see InstantValueColumn)
				return (ValueColumnFactory) capacity -> new InstantValueColumn(new long[capacity], new int[capacity]);
			}
			if (plainType == BigDecimal.class) {
				// BigDecimal filter/sort keys are normalized upstream to a scaled int (indexedDecimalPlaces);
				// store them in a 4-byte int[] column. The column never sees a BigDecimal (already converted).
				return (ValueColumnFactory) capacity -> new IntValueColumn(new int[capacity]);
			}
			final LongKeyCodec codec = LongKeyCodec.forType(normalizedType);
			if (codec != null) {
				// raw lambda → the wildcard return is closed over the codec's monotonic encoding (natural order only)
				return (ValueColumnFactory) capacity -> new LongValueColumn(codec, new long[capacity]);
			}
		}
		// boxed fallback keyed by Comparable.class (the raw key type the tree uses today)
		return (ValueColumnFactory) capacity -> new BoxedObjectColumn(Comparable.class, capacity);
	}

	/**
	 * Maps a plain attribute type to the type actually stored as the tree key, mirroring the only normalization in
	 * {@code FilterIndex.getNormalizer} that changes the stored class: {@link OffsetDateTime} is stored as its
	 * {@link Instant}. All other types (numbers, {@code LocalDate} / {@code LocalTime}, {@code String},
	 * {@code Currency}, {@code Locale}, …) keep their own class. The single source of truth for this remap lives here.
	 *
	 * @param plainType the plain (non-array) declared attribute type
	 * @return the normalized key type used by the tree
	 */
	@Nonnull
	private static Class<?> normalizedTypeOf(@Nonnull Class<?> plainType) {
		if (OffsetDateTime.class.isAssignableFrom(plainType)) {
			return Instant.class;
		}
		return plainType;
	}

	/**
	 * Returns whether the comparator imposes natural order (the {@code null} default or the
	 * {@link Comparator#naturalOrder()} singleton). Only then is the monotonic {@link LongKeyCodec} encoding guaranteed
	 * to match the tree ordering.
	 *
	 * @param comparator the comparator to test, or {@code null}
	 * @return {@code true} when the order is natural
	 */
	private static boolean isNaturalOrder(@Nullable Comparator<?> comparator) {
		return comparator == null || comparator == Comparator.naturalOrder();
	}
}
