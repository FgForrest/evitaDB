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

package io.evitadb.index.attribute;

import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.ValueColumnFactory;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Function;

/**
 * Payload-agnostic building blocks shared by the two bucket-tree-backed unique indexes: {@link OwnerUniqueIndex}
 * (value → `int` record id) and {@link GlobalUniqueIndex} (value → packed-`long` entity tuple).
 *
 * The two indexes cannot share a common base class — {@link OwnerUniqueIndex} is bound into the sealed
 * {@link UniqueIndex} hierarchy (whose {@link UniqueIndexView} sibling owns no value tree at all), while
 * {@link GlobalUniqueIndex} is a standalone catalog-level structure implementing different interfaces — and their
 * per-record state diverges by payload width (a `long` packed tuple vs a plain `int` record id), which a generic base
 * could only unify by boxing on the per-record hot path. The genuinely identical key-space, value-ordering and
 * page-stream plumbing is therefore centralised here as stateless static helpers instead of through inheritance.
 *
 * # One key space with the filter index
 *
 * A unique tree keys every value exactly as the filter index keys it: through
 * {@link FilterIndex#getNormalizer(Class, int)}, at the attribute's `indexedDecimalPlaces`, in the leaf column
 * {@link ValueColumnFactory#forFilterKey} selects for that key. There is no per-type exception. Two values the filter
 * index treats as one - the same instant at two offsets, a precomposed and a decomposed Unicode spelling, two decimals
 * equal at the indexed scale - are therefore one unique value as well: a lookup by either finds the owner, and the
 * second is refused for anyone else. Every entry point converts its value through {@link #toKey}: registration,
 * removal, lookup and the restore from persisted parts. The query translators probe with the filter normalizer's
 * output, which the conversion passes through unchanged because the normalizer is idempotent.
 *
 * Storage parts do not carry the keys themselves. Their serializers read every inline value back as the DECLARED
 * attribute type, so {@link #toDeclaredValue} turns each key into a value of that type naming the same key - an
 * `OffsetDateTime` at UTC, a `BigDecimal` at the indexed scale, the bare `Currency` / `Locale` - and the restore
 * converts it to the key again. The on-disk format is the one it has always been.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class UniqueIndexBPlusTreeSupport {
	/**
	 * Block-size geometry of the value bucket tree — a 256-entry leaf with the matching minimum split thresholds,
	 * identical for both unique-index flavours.
	 */
	private static final int VALUE_BLOCK_SIZE = 256;
	private static final int MIN_VALUE_BLOCK_SIZE = VALUE_BLOCK_SIZE / 2 - 1;
	private static final int MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(MIN_VALUE_BLOCK_SIZE / 2.0) - 1);

	/**
	 * The order every unique tree keeps its keys in. Every normalized key type orders consistently with `equals` in
	 * its natural order, so one order serves every attribute type. Typed over the heterogeneous self-comparable key
	 * {@code Comparable<?>} (the tree's runtime key type is decided per attribute), so the cast bridging
	 * {@link Comparator#naturalOrder()}'s recursive `T extends Comparable<? super T>` bound to this erased face is the
	 * one unavoidable unchecked step. It is the very singleton {@link Comparator#naturalOrder()} returns, which is what
	 * lets {@link ValueColumnFactory} recognise natural order and select the primitive and range columns.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	static final Comparator<Comparable<?>> KEY_ORDER = (Comparator) Comparator.naturalOrder();

	private UniqueIndexBPlusTreeSupport() {
		throw new UnsupportedOperationException("This class is a static-helper holder and must not be instantiated.");
	}

	/**
	 * Resolves the plain (array-unwrapped) attribute type that drives the normalizer and leaf-column choice.
	 *
	 * @param attributeType the declared attribute type (possibly an array type)
	 * @return the element type for an array attribute, otherwise the attribute type itself
	 */
	@Nonnull
	static Class<? extends Serializable> plainTypeOf(@Nonnull Class<? extends Serializable> attributeType) {
		//noinspection unchecked
		return attributeType.isArray() ? (Class<? extends Serializable>) attributeType.getComponentType() : attributeType;
	}

	/**
	 * Returns the function converting a value of `plainType` to its unique-tree key - the filter index's normalizer,
	 * see the class javadoc.
	 *
	 * @param plainType            the plain (array-unwrapped) attribute type
	 * @param indexedDecimalPlaces the scale `BigDecimal` and `BigDecimalNumberRange` values are keyed at (ignored for
	 *                             every other type)
	 * @return the normalizer
	 */
	@Nonnull
	static Function<Object, Serializable> normalizerFor(@Nonnull Class<?> plainType, int indexedDecimalPlaces) {
		return FilterIndex.getNormalizer(plainType, indexedDecimalPlaces);
	}

	/**
	 * Converts a scalar value to the key the unique tree holds and checks that the key can be one: it must be
	 * {@link Serializable} and {@link Comparable}. The check runs on the key, not on the value - a `Currency` or a
	 * `Locale` is not comparable, while the `ComparableCurrency` / `ComparableLocale` it is keyed by is.
	 *
	 * The conversion is idempotent, so a probe the query translator has already normalized passes unchanged.
	 *
	 * @param normalizer the normalizer of the tree (see {@link #normalizerFor})
	 * @param value      the value (or an already normalized key)
	 * @return the tree key
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when the key is not serializable or not comparable
	 */
	@Nonnull
	static Comparable<?> toKey(@Nonnull Function<Object, Serializable> normalizer, @Nonnull Object value) {
		final Serializable key = normalizer.apply(value);
		UniqueIndex.verifyValue(key);
		return (Comparable<?>) key;
	}

	/**
	 * Array counterpart of {@link #toKey}: converts every element and folds the keys onto the distinct ones - see
	 * {@link #foldOntoDistinctValues}. Two elements the normalizer maps to one key (two spellings of one value in one
	 * array) are then one tree entry, exactly as two equal elements are.
	 *
	 * @param normalizer the normalizer of the tree (see {@link #normalizerFor})
	 * @param values     the array elements
	 * @return the distinct tree keys, in the encounter order of their first element
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when a key is not serializable or not comparable
	 */
	@Nonnull
	static Object[] toDistinctKeys(@Nonnull Function<Object, Serializable> normalizer, @Nonnull Object[] values) {
		final Object[] keys = new Object[values.length];
		for (int i = 0; i < values.length; i++) {
			keys[i] = toKey(normalizer, values[i]);
		}
		return foldOntoDistinctValues(keys);
	}

	/**
	 * Converts persisted values to the keys a restored tree holds, positionally aligned with `values`. Unlike
	 * {@link #toDistinctKeys} nothing is folded: persisted values are distinct keys of the tree that wrote them, and a
	 * pair that is not must be refused by the caller rather than silently merged.
	 *
	 * @param normalizer the normalizer of the tree (see {@link #normalizerFor})
	 * @param values     the persisted values
	 * @return the tree keys
	 */
	@Nonnull
	static Object[] toPersistedKeys(@Nonnull Function<Object, Serializable> normalizer, @Nonnull Serializable[] values) {
		final Object[] keys = new Object[values.length];
		for (int i = 0; i < values.length; i++) {
			keys[i] = toKey(normalizer, values[i]);
		}
		return keys;
	}

	/**
	 * Converts a tree key back to a value of the declared attribute type that names the same key - the inverse of
	 * {@link #toKey} up to what the key no longer carries:
	 *
	 * - an `Instant` becomes an `OffsetDateTime` at UTC, or the `LocalDateTime` it was anchored from at UTC,
	 * - a scaled `Integer` becomes the `BigDecimal` at the indexed scale,
	 * - a `ComparableCurrency` / `ComparableLocale` becomes the `Currency` / `Locale` it wraps,
	 * - every other key (an NFD `String`, a millisecond `LocalTime`, a rescaled range, ...) is a value of the declared
	 *   type already.
	 *
	 * Storage parts carry these values, because the inline serializers read every value back as the declared type;
	 * the restore converts them to keys again, and {@link #toKey} of the result is the key itself. Violation messages
	 * show them for the same reason: they name a value of the attribute's type.
	 *
	 * @param plainType            the plain (array-unwrapped) attribute type
	 * @param indexedDecimalPlaces the scale `BigDecimal` keys were encoded at
	 * @param key                  the tree key
	 * @return the value in the declared type
	 */
	@Nonnull
	static Serializable toDeclaredValue(@Nonnull Class<?> plainType, int indexedDecimalPlaces, @Nonnull Object key) {
		if (key instanceof Instant instant) {
			if (OffsetDateTime.class.isAssignableFrom(plainType)) {
				return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
			} else if (LocalDateTime.class.isAssignableFrom(plainType)) {
				return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
			}
		} else if (key instanceof Integer scaled && BigDecimal.class.isAssignableFrom(plainType)) {
			return BigDecimal.valueOf(scaled, indexedDecimalPlaces);
		} else if (key instanceof ComparableCurrency currency) {
			return currency.getCurrency();
		} else if (key instanceof ComparableLocale locale) {
			return locale.getLocale();
		}
		return (Serializable) key;
	}

	/**
	 * Folds an array-typed attribute's keys onto the DISTINCT keys the value tree can actually hold.
	 *
	 * A unique-index tree is a MAP from value to its single owner, so one value occupies one entry no matter how
	 * many times the attribute array repeats it. Both indexes apply such an array in two passes — verify every
	 * element, then mutate every element — and the mutating pass re-derives ownership from the tree as it goes.
	 * A repeated element therefore passes verification twice and is then retired twice: the first occurrence
	 * removes the sole entry and the second finds no owner, failing over data the registration side accepts
	 * (re-claiming a value the same record already owns is explicitly allowed).
	 *
	 * Distinctness is measured with the tree's own {@link #KEY_ORDER} over the KEYS rather than `equals` over the raw
	 * elements, since that is what decides whether two values are one entry.
	 *
	 * @param keys the array elements, already converted to tree keys
	 * @return `keys` itself when no element repeats (the overwhelmingly common case), otherwise a shorter array holding
	 *         the first occurrence of each distinct key, in encounter order
	 */
	@Nonnull
	static Object[] foldOntoDistinctValues(@Nonnull Object[] keys) {
		if (keys.length < 2) {
			return keys;
		}
		// the accepted elements are a prefix of `keys` itself until the first repeat forces a compacted copy
		Object[] acceptedValues = keys;
		int acceptedCount = 0;
		boolean compacted = false;
		for (Object value : keys) {
			boolean repeated = false;
			for (int i = 0; i < acceptedCount; i++) {
				if (KEY_ORDER.compare((Comparable<?>) acceptedValues[i], (Comparable<?>) value) == 0) {
					repeated = true;
					break;
				}
			}
			if (repeated) {
				if (!compacted) {
					acceptedValues = Arrays.copyOf(keys, keys.length - 1);
					compacted = true;
				}
			} else {
				if (compacted) {
					acceptedValues[acceptedCount] = value;
				}
				acceptedCount++;
			}
		}
		return compacted ? Arrays.copyOf(acceptedValues, acceptedCount) : keys;
	}

	/**
	 * Returns the position of the first key in `keys` that does not sort strictly after its predecessor, or `-1` when
	 * they are strictly ascending. A restore calls this only once a bulk load has refused a page, to tell a collision
	 * (two persisted values naming one key) from any other cause; it is never on the path of a load that succeeds.
	 *
	 * @param keys the keys in persisted order
	 * @return the position of the first non-ascending key, or `-1`
	 */
	static int findFirstNonAscendingKey(@Nonnull Object[] keys) {
		for (int i = 1; i < keys.length; i++) {
			if (KEY_ORDER.compare((Comparable<?>) keys[i - 1], (Comparable<?>) keys[i]) >= 0) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Builds the error refusing to load a persisted unique index in which two owners hold one key.
	 *
	 * The current writer cannot produce such an index - every value is converted to its key before its uniqueness is
	 * checked - but a part written before the unique indexes keyed their values like the filter index can: it kept two
	 * spellings of one value (one instant at two offsets, two Unicode spellings, two decimals equal at the indexed
	 * scale) as two keys. Such an index is refused rather than loaded with one owner silently dropped, because which
	 * owner keeps the value is a decision the index has no information to make.
	 *
	 * @param structure      the index that refuses to load, described for an operator
	 * @param key            the key both owners hold
	 * @param declaredType   the plain attribute type, to show the key as a value of it
	 * @param decimalPlaces  the scale `BigDecimal` keys are encoded at
	 * @param firstOwner     one owner, described for an operator
	 * @param secondOwner    the other owner, described for an operator
	 * @return the error to throw
	 */
	@Nonnull
	static GenericEvitaInternalError collidingKeysError(
		@Nonnull String structure,
		@Nonnull Object key,
		@Nonnull Class<?> declaredType,
		int decimalPlaces,
		@Nonnull String firstOwner,
		@Nonnull String secondOwner
	) {
		return new GenericEvitaInternalError(
			"The persisted " + structure + " holds the unique value `" + toDeclaredValue(declaredType, decimalPlaces, key) +
				"` for two owners: " + firstOwner + " and " + secondOwner + ". They were stored as two different values " +
				"before unique values were compared the way the filter index compares them (one instant at two " +
				"offsets, two Unicode spellings of one text, or two decimals equal at the indexed decimal places). " +
				"Change or remove the value of one of the two in the evitaDB version that wrote the catalog, or " +
				"rebuild the catalog from its source data, and load it again."
		);
	}

	/**
	 * Creates a fresh, empty value tree with a single-`long` payload column (used by {@link GlobalUniqueIndex} to hold
	 * the packed entity tuple), keyed like the filter index - see {@link #buildTree}.
	 *
	 * @param plainType            the plain (array-unwrapped) attribute type
	 * @param indexedDecimalPlaces the scale `BigDecimal` and `BigDecimalNumberRange` keys are encoded at
	 * @return the fresh empty long-payload bucket tree
	 */
	@Nonnull
	static TransactionalBucketBPlusTree<?> newLongPayloadTree(@Nonnull Class<?> plainType, int indexedDecimalPlaces) {
		return buildTree(plainType, indexedDecimalPlaces, true);
	}

	/**
	 * Creates a fresh, empty value tree with a single-`int` payload column (used by {@link OwnerUniqueIndex} to hold the
	 * owning record id), keyed like the filter index - see {@link #buildTree}.
	 *
	 * @param plainType            the plain (array-unwrapped) attribute type
	 * @param indexedDecimalPlaces the scale `BigDecimal` and `BigDecimalNumberRange` keys are encoded at
	 * @return the fresh empty int-payload bucket tree
	 */
	@Nonnull
	static TransactionalBucketBPlusTree<?> newIntPayloadTree(@Nonnull Class<?> plainType, int indexedDecimalPlaces) {
		return buildTree(plainType, indexedDecimalPlaces, false);
	}

	/**
	 * Builds a fresh, empty bucket value tree with the chosen payload width. The tree key is the heterogeneous
	 * self-comparable {@code Comparable} whose concrete type is fixed only at runtime, so the generic key/comparator/
	 * column-factory wiring into the parameterized tree constructor is the one place the erased key forces unchecked
	 * casts — they are confined to this single helper rather than smeared across every call site.
	 *
	 * The leaf column comes from {@link ValueColumnFactory#forFilterKey}, the *normalized* key space, because the tree
	 * holds exactly the keys a filter index over the same attribute holds: an `Instant` for a temporal attribute (the
	 * single-`long` column), a scaled `int` for a `BigDecimal` one, a range rebuilt at `indexedDecimalPlaces` for a
	 * range one.
	 *
	 * @param plainType            the plain (array-unwrapped) attribute type (selects the leaf column kind)
	 * @param indexedDecimalPlaces the scale `BigDecimal` and `BigDecimalNumberRange` keys are encoded at
	 * @param longPayload          `true` for a single-`long` payload column ({@link GlobalUniqueIndex}), `false` for
	 *                             single-`int` ({@link OwnerUniqueIndex})
	 * @return the fresh empty bucket value tree
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static TransactionalBucketBPlusTree<?> buildTree(
		@Nonnull Class<?> plainType, int indexedDecimalPlaces, boolean longPayload
	) {
		// the erased key forces every generic argument raw together: a parameterized comparator beside a raw key type
		// gives the tree's `K` two conflicting bounds, so the key type, comparator and factory are all raw and infer `K` once
		final Class keyType = Comparable.class;
		final ValueColumnFactory factory = ValueColumnFactory.forFilterKey(plainType, KEY_ORDER, indexedDecimalPlaces);
		return longPayload
			? TransactionalBucketBPlusTree.withLongPayload(
				VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
				keyType, (Comparator) KEY_ORDER, factory)
			: new TransactionalBucketBPlusTree(
				VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
				keyType, KEY_ORDER, factory);
	}

}
