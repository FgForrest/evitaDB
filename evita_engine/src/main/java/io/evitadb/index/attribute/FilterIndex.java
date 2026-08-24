/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Range;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.IndexHeapSize;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.invertedIndex.InvertedIndexSubSet;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPageRemoval;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Currency;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import static io.evitadb.core.transaction.Transaction.isTransactionAvailable;
import static io.evitadb.utils.Assert.isTrue;
import static io.evitadb.utils.StringUtils.unknownToString;

/**
 * Filter index maintains information about single filterable attribute - its value to record id relation.
 * It uses several data structures to allow filtration - see fields description.
 *
 * This is the abstract, sealed base of the owner/view hierarchy. It holds the shared read/write query algebra
 * over the {@link #invertedIndex} (and optional {@link #rangeIndex}) but owns no transactional lifecycle of its
 * own. Two concrete shapes exist:
 *
 * - {@link OwnerFilterIndex} — owns its {@link InvertedIndex} and participates in the commit cycle as a
 * {@link io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer}. Used by the histogram subsystem and
 * any standalone owner.
 * - {@link FilterIndexView} — a stateless flyweight wrapping an {@link AttributeIndex}-owned shared
 * {@link InvertedIndex}. It is NOT a transactional producer; its dirtiness, persistence and transactional id are
 * all derived from the shared tree it wraps.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public abstract sealed class FilterIndex implements IndexDataStructure, Serializable
	permits OwnerFilterIndex, FilterIndexView {
	public static final Function<Object, Serializable> NO_NORMALIZATION = Serializable.class::cast;
	static final Comparator<Comparable> DEFAULT_COMPARATOR = Comparator.naturalOrder();
	/**
	 * Empty inline-bucket array carried by a bucket-`PAGED` {@link FilterIndexStoragePart} root (its buckets live in
	 * individual leaf pages instead).
	 */
	private static final ValueToRecordBitmap[] EMPTY_HISTOGRAM_POINTS = new ValueToRecordBitmap[0];
	@Serial private static final long serialVersionUID = -6813305126746774103L;
	private static final String ERROR_RANGE_TYPE_NOT_SUPPORTED = "This filter index doesn't handle Range type!";
	/**
	 * Aggregation lambda used by {@link #getRangeHistogramOfAllRecords(Class, int)} when producing the subset's
	 * {@link InvertedIndexSubSet#getFormula()}. Range histogram buckets overlap by design (a single record may
	 * span multiple thresholds and therefore appear in multiple buckets), so an `OR` over each bucket's
	 * `recordIds` is the correct distinct-union aggregator. Empty / single-bucket inputs short-circuit to avoid
	 * spurious formula tree allocations.
	 */
	private static final BiFunction<long[], ValueToRecord[], Formula> RANGE_HISTOGRAM_AGGREGATION_LAMBDA =
		(indexTransactionIds, histogramBuckets) -> {
			if (histogramBuckets.length == 0) {
				return EmptyFormula.INSTANCE;
			}
			final Bitmap[] bitmaps = new Bitmap[histogramBuckets.length];
			for (int i = 0; i < histogramBuckets.length; i++) {
				bitmaps[i] = histogramBuckets[i].getRecordIds();
			}
			if (bitmaps.length == 1) {
				return bitmaps[0].isEmpty()
					? EmptyFormula.INSTANCE
					: new ConstantFormula(bitmaps[0]);
			}
			return new OrFormula(indexTransactionIds, bitmaps);
		};
	/**
	 * Prices one bucket of the memoized {@link #memoizedRangeHistogramSubSet}, which — unlike a slice off the value
	 * tree — this index owns outright: {@link #getRangeHistogramOfAllRecords(Class, int)} materializes a fresh
	 * {@link ValueToRecordBitmap} per range point, carrying a bucket key built from the threshold and a `clone()` of
	 * the running active set. Both are charged in full.
	 *
	 * A bucket of any other shape **throws**: the range sweep builds only this one, so meeting another means the
	 * memo was populated by a path that does not exist — and a zero would hide that behind a plausible total.
	 */
	private static final ToLongFunction<ValueToRecord> RANGE_HISTOGRAM_BUCKET_SIZER = bucket -> {
		if (bucket instanceof final ValueToRecordBitmap bitmapBucket) {
			final VMLayout layout = VMLayout.current();
			// the value / recordIds slots, then the materialized bucket key and the cloned active set
			return layout.sizeOfObject(2L * layout.referenceSize())
				+ IndexHeapSize.OWNED_KEY_SIZER.applyAsLong(bitmapBucket.getValue())
				+ bitmapBucket.getRecordIds().getHeapSizeInBytes();
		}
		throw new GenericEvitaInternalError(
			"Range histogram bucket of type `" + bucket.getClass().getName() + "` is not one the range sweep " +
				"builds - its heap footprint cannot be priced."
		);
	};

	/**
	 * Contains key identifying the attribute.
	 */
	@Getter private final AttributeIndexKey attributeIndexKey;
	/**
	 * Histogram is the main data structure that holds the information about value to record ids relation.
	 */
	@Nonnull @Getter private final InvertedIndex invertedIndex;
	/**
	 * Range index is used only for attribute types that are assignable to {@link Range} and can answer questions like:
	 *
	 * - what records are valid at precise moment
	 * - what records are valid until certain moment
	 * - what records are valid after certain moment
	 */
	@Nullable @Getter private final RangeIndex rangeIndex;
	/**
	 * Contains information about the type of the value held in this filter index (the type of {@link #attributeIndexKey} values).
	 */
	private final Class<?> attributeType;
	/**
	 * Decimal-places scale used to encode `BigDecimal` values to an order-preserving scaled `int` before they enter the
	 * value tree (and to decode them back on the read boundary). Sourced from the attribute schema's
	 * `indexedDecimalPlaces` at construction and re-resolved from the schema on load (it is not persisted in the
	 * {@link FilterIndexStoragePart}). `0` for non-`BigDecimal` attributes.
	 */
	@Getter private final int indexedDecimalPlaces;
	/**
	 * Instance of conversion function that converts the value before it is placed into internal index or looked up
	 * in it by {@link Comparator} interface.
	 */
	@Nonnull private final Function<Object, Serializable> normalizer;
	/**
	 * Instance of comparator that should be used for sorting values in this filter index.
	 */
	@Nonnull private final Comparator<? extends Comparable> comparator;
	/**
	 * This field speeds up all requests for all data in this index (which happens quite often). This formula can be
	 * computed anytime by calling `((InvertedIndex) this.histogram).getSortedRecords(null, null)`. Original operation
	 * needs to perform costly join of all internally held bitmaps and that's why we memoize the result.
	 */
	@Nullable private transient Formula memoizedAllRecordsFormula;
	/**
	 * Memoized result of {@link #getRangeHistogramOfAllRecords(Class, int)}. The cached subset is keyed implicitly
	 * by the leaf's {@link RangeIndex} state — the steady-state query path against an unchanged leaf pays zero
	 * allocation. Set to `null` whenever the index is mutated outside a transaction (mirrors
	 * {@link #memoizedAllRecordsFormula}); the merged-transactional copy starts fresh.
	 *
	 * The inner numeric type passed by callers is invariant for a given leaf — it is derived from
	 * {@link #attributeType} via {@link EvitaDataTypes#resolveRangeInnerNumericType(Class)} — so it does not
	 * need to be tracked alongside the cached subset; a fail-fast assertion in
	 * {@link #getRangeHistogramOfAllRecords(Class, int)} guards against schema/index drift.
	 */
	@Nullable private transient InvertedIndexSubSet memoizedRangeHistogramSubSet;

	/**
	 * Fails fast when an index built at one `BigDecimal` scale is about to be modified under a schema that now declares a
	 * different {@link io.evitadb.api.requestResponse.schema.AttributeSchemaContract#getIndexedDecimalPlaces()}. The scale
	 * an index encodes its values at is frozen when the index is created and the index is never re-scaled in place; once
	 * the schema's indexed decimal places change, the index must be fully rebuilt before it may be modified again.
	 * Encoding a new value at the changed scale and merging it with values still stored at the original scale would
	 * silently corrupt equality, range and ordering, so a mismatch must surface immediately rather than mangle the index
	 * further. For non-`BigDecimal` attributes both scales are `0`, so the check is a no-op.
	 *
	 * @param frozenIndexedDecimalPlaces  the scale frozen into the index when it was created
	 * @param currentIndexedDecimalPlaces the scale the attribute schema currently declares
	 * @param indexName                   the attribute / histogram name, used only to build a comprehensible message
	 */
	public static void assertIndexedDecimalPlacesUnchanged(
		int frozenIndexedDecimalPlaces,
		int currentIndexedDecimalPlaces,
		@Nonnull String indexName
	) {
		Assert.isPremiseValid(
			frozenIndexedDecimalPlaces == currentIndexedDecimalPlaces,
			() -> "Attribute `" + indexName + "` is indexed with " + frozenIndexedDecimalPlaces +
				" decimal place(s), but its schema now declares " + currentIndexedDecimalPlaces +
				"; the index must be rebuilt before it can be modified."
		);
	}

	/**
	 * Returns the appropriate normalizer function for particular attribute type and key.
	 *
	 * `BigDecimal` values are normalized to an order-preserving scaled `int` (respecting the schema's
	 * `indexedDecimalPlaces`) so the value tree stores them in the compact `IntValueColumn`. Temporal values
	 * (`OffsetDateTime` and `LocalDateTime`, the latter anchored at UTC) are normalized to an `Instant` so the tree
	 * stores them in the parallel-array `InstantValueColumn` rather than boxing them. The normalizer is
	 * idempotent: an already-normalized value (and `null`) passes through unchanged, so a value may be normalized
	 * more than once on a probe→lookup path without a `ClassCastException`.
	 *
	 * @param attributeType        type of the attribute
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values to a scaled `int`; ignored
	 *                             for every other attribute type
	 * @return appropriate comparator
	 */
	@Nonnull
	public static Function<Object, Serializable> getNormalizer(
		@Nonnull Class<?> attributeType,
		int indexedDecimalPlaces
	) {
		if (OffsetDateTime.class.isAssignableFrom(attributeType)) {
			return comparable -> comparable instanceof OffsetDateTime offsetDateTime
				? offsetDateTime.toInstant()
				: (Serializable) comparable;
		} else if (LocalDateTime.class.isAssignableFrom(attributeType)) {
			// a local date time has no offset of its own, so it is anchored at UTC - a *constant* offset, which makes
			// the mapping a lossless bijection AND monotonic with `LocalDateTime`'s natural order, so bucket lookup and
			// ordered iteration are unaffected. This is purely the index encoding: the schema keeps declaring
			// `LocalDateTime`, and the value handed back to the client comes from `AttributesStoragePart`, not here
			return comparable -> comparable instanceof LocalDateTime localDateTime
				? localDateTime.toInstant(ZoneOffset.UTC)
				: (Serializable) comparable;
		} else if (Currency.class.isAssignableFrom(attributeType)) {
			return comparable -> comparable instanceof Currency currency
				? new ComparableCurrency(currency)
				: (Serializable) comparable;
		} else if (Locale.class.isAssignableFrom(attributeType)) {
			return comparable -> comparable instanceof Locale locale
				? new ComparableLocale(locale)
				: (Serializable) comparable;
		} else if (BigDecimal.class.isAssignableFrom(attributeType)) {
			// scale a real BigDecimal to its order-preserving int; an already-scaled Integer (or null) passes through
			// so a probe value normalized twice along a lookup path never throws (idempotent contract)
			return value -> value instanceof BigDecimal bd
				? Integer.valueOf(NumberUtils.convertToInt(bd, indexedDecimalPlaces))
				: (Serializable) value;
		} else if (BigDecimalNumberRange.class.isAssignableFrom(attributeType)) {
			// a BigDecimal-backed range canonicalizes to the schema's `indexedDecimalPlaces`, mirroring the
			// plain-BigDecimal branch above: its `RangeIndex` thresholds (`getFrom()` / `getTo()`) must be computed
			// at the very scale the source filter index stores, so the bucket keys reconstructed from those
			// thresholds at histogram query time agree. Idempotent: a range already at that scale rebuilds to an
			// equal range; a non-range value (or null) passes through untouched
			return value -> value instanceof BigDecimalNumberRange bdr
				? rescaleBigDecimalRange(bdr, indexedDecimalPlaces)
				: (Serializable) value;
		} else if (String.class.isAssignableFrom(attributeType)) {
			// String keys are normalized to Unicode NFD so the shared value tree holds one canonical form across the
			// unique / filter / sort role-views. This matches SortIndex.createNormalizerFor's NFD
			// form verbatim, so a both-flagged attribute reads and writes the very same key bytes on every path. ASCII
			// (`code`, `ean`) is unaffected; non-ASCII filter `=` gains canonical equivalence (accepted release-note).
			return text -> text == null
				? null
				: Normalizer.normalize(String.valueOf(text), Normalizer.Form.NFD);
		} else if (Comparable.class.isAssignableFrom(attributeType)) {
			return NO_NORMALIZATION;
		} else {
			throw new EvitaInvalidUsageException(
				"Unsupported attribute type `" + attributeType + "`! The type is not comparable!");
		}
	}

	/**
	 * Returns the appropriate comparator for particular attribute type and key.
	 *
	 * @param attributeIndexKey key containing information about used locale
	 * @param attributeType     type of the attribute
	 * @return appropriate comparator
	 */
	@Nonnull
	public static Comparator<? extends Comparable> getComparator(
		@Nonnull AttributeIndexKey attributeIndexKey, @Nonnull Class<?> attributeType) {
		final Locale locale = attributeIndexKey.locale();
		if (String.class.isAssignableFrom(attributeType) && locale != null) {
			return new LocalizedStringComparator(locale);
		} else {
			return DEFAULT_COMPARATOR;
		}
	}

	/**
	 * Converts a histogram boundary value back into its `long` threshold for `RangeIndex` lookups.
	 * This is the inverse of {@link #toBucketKey(long, Class, int)}: any value previously emitted
	 * by the bucket-key encoder will round-trip through this method to recover the original
	 * `long` threshold that fed it.
	 *
	 * Encoding contract for `BigDecimal`: bucket-keys emitted as
	 * `BigDecimal.valueOf(threshold, retainedDecimalPlaces)` carry an intrinsic scale equal to
	 * `retainedDecimalPlaces`, so reversing the scale via `scaleByPowerOfTen` recovers the
	 * original `long` without needing the decimal-places parameter explicitly.
	 *
	 * @param value the boundary value emitted by the histogram sweep — one of Byte, Short,
	 *              Integer, Long, BigDecimal
	 * @return the comparable long-encoded threshold
	 */
	public static long fromBucketKey(@Nonnull Serializable value) {
		if (value instanceof BigDecimal bd) {
			// inverse of `BigDecimal.valueOf(threshold, retainedDecimalPlaces)` in toBucketKey;
			// round-trip keys always carry scale >= 0, so the Math.max is a no-op there — it is a
			// belt-and-suspenders guard for direct callers passing externally-built negative-scale
			// BigDecimals (e.g. `1E+2`, scale -2) so those still decode to the correct long
			final int scale = Math.max(bd.scale(), 0);
			return bd.scaleByPowerOfTen(scale).longValueExact();
		}
		if (value instanceof Number n) {
			return n.longValue();
		}
		throw new GenericEvitaInternalError(
			"Cannot convert histogram boundary value `" + value + "` of type `" +
				value.getClass().getName() + "` to a long threshold — only numeric scalar types are supported."
		);
	}

	/**
	 * Resolves the plain (array-unwrapped) attribute type that drives the comparator / normalizer / range decisions.
	 *
	 * @param attributeType the declared (possibly array) attribute type
	 * @return the array component type for array attributes, otherwise the type itself
	 */
	@Nonnull
	static Class<?> plainTypeOf(@Nonnull Class<?> attributeType) {
		return attributeType.isArray() ? attributeType.getComponentType() : attributeType;
	}

	/**
	 * Converts a `RangeIndex` `long` threshold back into the source attribute's natural numeric type. The
	 * threshold originated from a `NumberRange` subtype's `getFrom()` / `getTo()` and is therefore guaranteed
	 * to fit into the destination type for `Byte`, `Short`, `Integer`, `Long`. For `BigDecimal`, the encoding
	 * is `value.setScale(places, HALF_UP).scaleByPowerOfTen(places).longValueExact()` (see
	 * {@link BigDecimalNumberRange#toComparableLong(BigDecimal, int, long)}), and the inverse is
	 * `BigDecimal.valueOf(threshold, places)` — same magnitude, restored scale.
	 *
	 * @param threshold             the `long` value sourced from `TransactionalRangePoint.getThreshold()`
	 * @param innerNumericType      the inner numeric type to materialize
	 * @param retainedDecimalPlaces decimal-places scale used by `BigDecimalNumberRange` to encode the
	 *                              threshold; ignored for non-`BigDecimal` inner types
	 * @return the bucket key value as a `Serializable` instance of `innerNumericType`
	 */
	@Nonnull
	static Serializable toBucketKey(
		long threshold,
		@Nonnull Class<? extends Number> innerNumericType,
		int retainedDecimalPlaces
	) {
		if (innerNumericType == Byte.class) {
			return (byte) threshold;
		} else if (innerNumericType == Short.class) {
			return (short) threshold;
		} else if (innerNumericType == Integer.class) {
			return (int) threshold;
		} else if (innerNumericType == Long.class) {
			return threshold;
		} else if (innerNumericType == BigDecimal.class) {
			return BigDecimal.valueOf(threshold, retainedDecimalPlaces);
		} else {
			throw new GenericEvitaInternalError(
				"Unsupported inner numeric type for range histogram: " + innerNumericType.getName() +
					" — only Byte, Short, Integer, Long, BigDecimal are valid Range source types."
			);
		}
	}

	/**
	 * Verifies that the provided value is an array of Serializable objects and
	 * returns it as an array of Comparable objects. If the elements in the value array
	 * are not Comparable, they are converted to a String representation and
	 * returned as a String array.
	 *
	 * @param value the object to be verified and converted
	 * @return an array of Comparable objects or a String array if elements are not Comparable
	 */
	@Nonnull
	private static Comparable[] verifyValueArray(@Nonnull Object value) {
		isTrue(
			Serializable.class.isAssignableFrom(value.getClass().getComponentType()),
			"Value `" + unknownToString(value) + "` is expected to be Serializable, but it is not!"
		);
		if (Comparable.class.isAssignableFrom(value.getClass().getComponentType())) {
			return (Comparable[]) value;
		} else {
			final int arraySize = Array.getLength(value);
			final String[] valuesAsString = new String[arraySize];
			for (int i = 0; i < arraySize; i++) {
				valuesAsString[i] = String.valueOf(Array.get(value, i));
			}
			return valuesAsString;
		}
	}

	/**
	 * Returns the remaining ranges after subtracting the subtractedRanges from the existingRanges.
	 *
	 * @param subtractedRanges an array of ranges to be subtracted
	 * @param existingRanges   an array of existing ranges
	 * @return the remaining ranges after the subtraction
	 */
	@Nonnull
	private static Range[] getRemainingRanges(@Nonnull Range[] subtractedRanges, @Nonnull Range[] existingRanges) {
		final Range[] remainingRanges = new Range[existingRanges.length - subtractedRanges.length];
		int remainingRangesIndex = 0;
		final BitSet foundRanges = new BitSet(subtractedRanges.length);
		nextRange:
		for (Range existingRange : existingRanges) {
			for (int i = 0; i < subtractedRanges.length; i++) {
				final Range range = subtractedRanges[i];
				if (existingRange.equals(range)) {
					Assert.isPremiseValid(!foundRanges.get(i), "Sanity check - range already found!");
					foundRanges.set(i);
					continue nextRange;
				}
			}
			Assert.isTrue(
				remainingRangesIndex < remainingRanges.length, "Sanity check - remaining ranges index out of bounds!");
			remainingRanges[remainingRangesIndex++] = existingRange;
		}
		Assert.isPremiseValid(
			foundRanges.cardinality() == subtractedRanges.length, "Sanity check - not all ranges found!");
		return remainingRanges;
	}

	/**
	 * Rebuilds a `BigDecimalNumberRange` so its comparable `long` thresholds (`getFrom()` / `getTo()`) are computed
	 * at the supplied `indexedDecimalPlaces` rather than the value's intrinsic scale. This matches the scale the
	 * source attribute's filter / range index stores (the main attribute path coerces range values the same way via
	 * `EvitaDataTypes.toTargetType`), so the histogram bucket keys reconstructed from those thresholds at query time
	 * use the same magnitude. The operation is idempotent — a range already at the target scale rebuilds to an equal
	 * range — and preserves open-ended bounds.
	 *
	 * @param range                the source range whose precise `BigDecimal` bounds are rescaled
	 * @param indexedDecimalPlaces decimal-places scale to encode the range thresholds at
	 * @return a `BigDecimalNumberRange` whose thresholds are computed at `indexedDecimalPlaces`
	 */
	@Nonnull
	private static BigDecimalNumberRange rescaleBigDecimalRange(
		@Nonnull BigDecimalNumberRange range,
		int indexedDecimalPlaces
	) {
		final BigDecimal preciseFrom = range.getPreciseFrom();
		final BigDecimal preciseTo = range.getPreciseTo();
		if (preciseFrom == null && preciseTo == null) {
			// fully open (infinite) range — there are no bounds to rescale
			return range;
		} else if (preciseFrom != null && preciseTo != null) {
			return BigDecimalNumberRange.between(preciseFrom, preciseTo, indexedDecimalPlaces);
		} else if (preciseFrom != null) {
			return BigDecimalNumberRange.from(preciseFrom, indexedDecimalPlaces);
		} else {
			return BigDecimalNumberRange.to(preciseTo, indexedDecimalPlaces);
		}
	}

	/**
	 * Shared base constructor wiring the immutable fields common to every owner / view. Concrete subclasses are
	 * responsible for sourcing the {@link InvertedIndex} (owned vs shared), the comparator / normalizer (derived from
	 * the attribute type vs delegated to the wrapped tree) and the transactional lifecycle.
	 *
	 * @param attributeIndexKey    key identifying the attribute
	 * @param attributeType        the declared attribute type (array-aware)
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 * @param invertedIndex        the value→ValueToRecord tree backing this index (owned or shared)
	 * @param rangeIndex           the range structure for range-typed attributes, or `null`
	 * @param comparator           the value comparator
	 * @param normalizer           the value normalizer
	 */
	protected FilterIndex(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<?> attributeType,
		int indexedDecimalPlaces,
		@Nonnull InvertedIndex invertedIndex,
		@Nullable RangeIndex rangeIndex,
		@Nonnull Comparator<? extends Comparable> comparator,
		@Nonnull Function<Object, Serializable> normalizer
	) {
		this.attributeIndexKey = attributeIndexKey;
		this.attributeType = attributeType;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.invertedIndex = invertedIndex;
		this.rangeIndex = rangeIndex;
		this.comparator = comparator;
		this.normalizer = normalizer;
	}

	/**
	 * Returns a stable transactional id for this index. Owners mint their own sequence id; views derive it from the
	 * wrapped shared {@link InvertedIndex} so it stays stable across commits that did not touch the tree (keeping the
	 * query-planner formula cache warm).
	 */
	public abstract long getId();

	/**
	 * Returns `true` if the index contents have been modified and need persistence. Owners track their own dirty flag;
	 * views delegate to the shared tree's dirty flag.
	 */
	public abstract boolean isDirty();

	/**
	 * Returns the heap this index occupies, in bytes.
	 *
	 * The two variants answer differently by design: an {@link OwnerFilterIndex} owns its value tree and range
	 * companion and charges both, while a {@link FilterIndexView} charges neither — those belong to the
	 * {@link AttributeIndex} that maintains them, which charges each exactly once, and a collection has one view per
	 * filterable attribute pointing at them.
	 *
	 * Like every walk over a value tree this is `O(distinct values)` rather than `O(1)`, so it belongs to
	 * the index detail call and must never be called from a query path.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public abstract long getHeapSizeInBytes();

	/**
	 * Prices everything both variants hold, given how many bytes of fields the concrete subclass adds.
	 *
	 * A subclass's fields live in the **same allocation** as the base's — one header, one round of padding — so the
	 * subclass passes its field bytes in rather than sizing a second object, which would charge a phantom header and
	 * round twice.
	 *
	 * Of the base's own references only the two query memos carry anything. {@link #attributeIndexKey} belongs to the
	 * enclosing {@link AttributeIndex}, {@link #attributeType} is a `Class` the JVM owns for the lifetime of its class
	 * loader, and {@link #normalizer} / {@link #comparator} are scaffolding chosen by the attribute type and shared by
	 * every index of it — all four contribute their slot alone, the same call {@code SortIndex} and
	 * {@code UniqueIndex} make. {@link #invertedIndex} and {@link #rangeIndex} are the subclass's decision and are
	 * deliberately left to it.
	 *
	 * The memos are charged where they hold something nothing else does:
	 *
	 * - {@link #memoizedAllRecordsFormula} contributes its scaffolding (see
	 *   {@link IndexHeapSize#memoizedFormulaSizeInBytes}) plus the union it wraps — but only once the value tree
	 *   holds **more than one** bucket. With exactly one, the aggregation short-circuits and the union IS that
	 *   bucket's own bitmap, charged already by the tree; with more, it is a bitmap this index materialized and
	 *   nothing else holds. Leaving it out would be a shortfall that grows with the data, which is the one shape a
	 *   deliberate divergence must never have.
	 * - {@link #memoizedRangeHistogramSubSet} contributes **in full**, buckets included. Unlike a slice off the value
	 *   tree, the range histogram materializes a fresh {@link ValueToRecordBitmap} per range point, each carrying a
	 *   `clone()` of the running active-set bitmap, and nothing else in the catalog holds those. On a range
	 *   attribute that has answered one histogram query this is the largest thing a filter index carries.
	 *
	 * @param ownFieldBytes the field bytes the concrete subclass adds to the base's own
	 * @return the heap footprint in bytes of everything both variants share, including alignment padding
	 */
	protected final long getSharedHeapSizeInBytes(long ownFieldBytes) {
		final VMLayout layout = VMLayout.current();
		// the attributeIndexKey / invertedIndex / rangeIndex / attributeType / normalizer / comparator /
		// memoizedAllRecordsFormula / memoizedRangeHistogramSubSet slots, then the indexedDecimalPlaces int
		long size = layout.sizeOfObject(
			8L * layout.referenceSize() + Integer.BYTES + ownFieldBytes
		);
		size += IndexHeapSize.memoizedFormulaSizeInBytes(this.memoizedAllRecordsFormula);
		if (this.memoizedAllRecordsFormula instanceof final ConstantFormula unionFormula
			&& this.invertedIndex.getBucketCount() > 1) {
			// more than one bucket, so the memoized union was computed rather than short-circuited to a bucket's own
			// bitmap - this index materialized it and nothing else in the catalog holds it
			size += unionFormula.getDelegate().getHeapSizeInBytes();
		}
		if (this.memoizedRangeHistogramSubSet != null) {
			size += this.memoizedRangeHistogramSubSet.getHeapSizeInBytes(RANGE_HISTOGRAM_BUCKET_SIZER);
		}
		return size;
	}

	/**
	 * Returns the declared attribute type backing this filter index (array-aware). Exposed so {@link AttributeIndex} can
	 * rebuild the stateless filter views and produce the {@link FilterIndexStoragePart} from the shared tree.
	 */
	@Nonnull
	public Class<?> getAttributeType() {
		return this.attributeType;
	}

	/**
	 * Returns count of records in this index.
	 *
	 * Unlike {@link #getDistinctValueCount()} this walks a bucket cursor over the whole tree summing per-bucket record
	 * counts, and is therefore `O(distinct values)` rather than a single counter read - the only cardinality reading
	 * of the whole statistics surface that is not `O(1)`.
	 *
	 * **How expensive that is depends entirely on the attribute.** For a low-cardinality filterable attribute it is a
	 * handful of steps. For a *unique* attribute it is one step per record, because uniqueness makes distinct values
	 * and records the same number - and {@link UniqueIndexView#size()} routes here, so reading a unique index's
	 * covered-record count on a collection of two million entities is a two-million-step walk. That is why
	 * {@link io.evitadb.api.statistics.CatalogStatisticsComponent#INDEX_CARDINALITY} is declared expensive and is
	 * never part of a polled refresh.
	 */
	public int size() {
		return this.invertedIndex.getLength();
	}

	/**
	 * Returns the number of distinct values this index holds - one per bucket of the underlying inverted index, read
	 * from the tree's cached bucket counter and therefore `O(1)`.
	 *
	 * Read against {@link #size()} this is what says whether the index discriminates: three distinct values over two
	 * million records is an index that cannot narrow anything down.
	 *
	 * @return number of distinct indexed values
	 */
	public int getDistinctValueCount() {
		return this.invertedIndex.getBucketCount();
	}

	/**
	 * Returns formula of record ids whose String attribute starts with particular prefix.
	 *
	 * The prefix is canonicalized through the index normalizer (Unicode NFD for String attributes) so a
	 * caller-supplied precomposed (NFC) term matches the decomposed keys actually stored in the index - the same
	 * canonical equivalence the `=` operator already provides.
	 *
	 * When the index uses the natural codepoint comparator, any value that starts with the normalized prefix sorts
	 * greater than or equal to it and no non-matching value can sort between the prefix and a matching value, so the
	 * matching buckets form a single contiguous run beginning at the first bucket `>= prefix`. We then anchor a
	 * bounded forward iteration at that bucket and stream until the first non-matching value (early break), with no
	 * whole-array materialization and no backward scan. Under a localized (collation) comparator that contiguity
	 * assumption does not hold - codepoint-`startsWith` matches may sort before the anchor or be interleaved with
	 * non-matches - so we fall back to a full predicate scan that visits every bucket.
	 */
	@Nonnull
	public Formula getRecordsWhoseValuesStartWith(@Nonnull String prefix) {
		/* TOBEDONE JNO naive and slow - use RadixTree */
		final String normalizedPrefix = (String) this.normalizer.apply(prefix);
		if (this.comparator != DEFAULT_COMPARATOR) {
			// collation ordering does not guarantee a contiguous prefix run - scan every bucket without early break
			return this.invertedIndex.getRecordsMatchingFormula(value -> ((String) value).startsWith(normalizedPrefix));
		}
		// natural codepoint order: matches form one contiguous run from the anchor, so the index walks the run off its
		// cursor and early-breaks at the first miss (no flyweight / iterator / per-bucket node allocation)
		return this.invertedIndex.getRecordsStartingFromWhile(
			normalizedPrefix, value -> ((String) value).startsWith(normalizedPrefix)
		);
	}

	/**
	 * Returns formula of record ids whose String attribute ends with particular suffix.
	 *
	 * The suffix is canonicalized through the index normalizer (Unicode NFD for String attributes) so a
	 * caller-supplied precomposed (NFC) term matches the decomposed keys stored in the index.
	 */
	@Nonnull
	public Formula getRecordsWhoseValuesEndsWith(@Nonnull String suffix) {
		/* TOBEDONE JNO naive and slow - use RadixTree */
		final String normalizedSuffix = (String) this.normalizer.apply(suffix);
		return this.invertedIndex.getRecordsMatchingFormula(value -> ((String) value).endsWith(normalizedSuffix));
	}

	/**
	 * Returns formula of record ids whose String attribute contains particular text.
	 *
	 * The text is canonicalized through the index normalizer (Unicode NFD for String attributes) so a
	 * caller-supplied precomposed (NFC) term matches the decomposed keys stored in the index.
	 */
	@Nonnull
	public Formula getRecordsWhoseValuesContains(@Nonnull String text) {
		/* TOBEDONE JNO naive and slow - use RadixTree */
		final String normalizedText = (String) this.normalizer.apply(text);
		return this.invertedIndex.getRecordsMatchingFormula(value -> ((String) value).contains(normalizedText));
	}

	/**
	 * Adds a record with the given record ID and value to the filter index. Index expects that the record doesn't
	 * exist in the index yet. If it does, you need to call {@link #removeRecord(int, Object)} first and then re-add
	 * it by calling this method.
	 *
	 * @param recordId the ID of the record to add
	 * @param value    the value of the record to add
	 * @param <T>      the type of the value, must implement Comparable<T>
	 * @throws EvitaInvalidUsageException when the value is not of type Range in case of range index
	 */
	public <T extends Serializable> void addRecord(
		int recordId, @Nonnull Object value) throws EvitaInvalidUsageException {
		// if current attribute is Range based assign record also to range index
		if (this.rangeIndex != null) {
			if (value instanceof Range[] valueArray) {
				addRange(recordId, valueArray);
			} else {
				isTrue(
					value instanceof Range,
					() -> new EvitaInvalidUsageException(
						"Value `" + unknownToString(value) + "` is expected to be Range but it is not!")
				);
				// canonicalize the range to the index scale before deriving the range-index thresholds, so a
				// `BigDecimalNumberRange` supplied at its intrinsic scale yields the same `getFrom()` / `getTo()`
				// the schema-scale probe is coerced to (no-op for non-`BigDecimal` ranges via `NO_NORMALIZATION`)
				final Range range = (Range) this.normalizer.apply(value);
				this.rangeIndex.addRecord(range.getFrom(), range.getTo(), recordId);
			}
		}

		if (value instanceof final Object[] valueArray) {
			for (Object valueItem : verifyValueArray(valueArray)) {
				this.invertedIndex.addRecord((T) valueItem, recordId);
			}
		} else {
			this.invertedIndex.addRecord((T) value, recordId);
		}

		if (!isTransactionAvailable()) {
			recordWarmUpSavepointTouch();
			this.memoizedAllRecordsFormula = null;
			this.memoizedRangeHistogramSubSet = null;
		}
		markDirty();
	}

	/**
	 * Registers new record id for the passed attribute value. The difference between this method and {@link #addRecord(int, Object)}
	 * is that this method expects the record with certain value already exists in the index and that the passed value
	 * should be only added on top of the existing value. This method makes sense only for attributes that are of the
	 * array type.
	 *
	 * @param recordId the unique identifier of the record
	 * @param value    the attribute value
	 * @param <T>      the type of the attribute value
	 * @throws EvitaInvalidUsageException when the value is not of type Range in case of range index
	 */
	public <T extends Serializable> void addRecordDelta(
		int recordId, @Nonnull Object[] value) throws EvitaInvalidUsageException {
		// if current attribute is Range based assign record also to range index
		//noinspection VariableNotUsedInsideIf
		if (this.rangeIndex != null) {
			if (value instanceof Range[] valueArray) {
				// this is quite expensive operation, but we need to do it to be able to remove and add the record;
				// the existing ranges read back from the inverted index are already canonicalized to the index
				// scale, so the raw delta ranges are canonicalized too before the merge so both sides share one
				// form and consolidation collapses scale-equal duplicates (no-op for non-`BigDecimal` ranges)
				final Range[] existingRanges = this.invertedIndex.getValuesForRecord(recordId, Range.class);
				final Range[] aggregatedRanges = ArrayUtils.mergeArrays(existingRanges, normalizeRanges(valueArray));

				removeRange(recordId, existingRanges);
				addRange(recordId, aggregatedRanges);
			} else {
				throw new EvitaInvalidUsageException(
					"Value `" + unknownToString(value) + "` is expected to be Range but it is not!");
			}
		}

		for (Object valueItem : verifyValueArray(value)) {
			this.invertedIndex.addRecord((T) valueItem, recordId);
		}

		if (!isTransactionAvailable()) {
			recordWarmUpSavepointTouch();
			this.memoizedAllRecordsFormula = null;
			this.memoizedRangeHistogramSubSet = null;
		}
		markDirty();
	}

	/**
	 * Removes the specified record from the index for the given attribute value.
	 *
	 * @param recordId the unique identifier of the record
	 * @param value    the attribute value
	 * @param <T>      the type of the attribute value
	 * @throws EvitaInvalidUsageException when the removed record is not actually registered for the attribute or
	 *                                    when the value is not of type Range in case of range index
	 */
	public <T extends Serializable> void removeRecord(
		int recordId, @Nonnull Object value) throws EvitaInvalidUsageException {
		// if current attribute is Range based assign record also to range index
		if (this.rangeIndex != null) {
			if (value instanceof Object[]) {
				isTrue(
					Range.class.isAssignableFrom(value.getClass().getComponentType()),
					() -> new EvitaInvalidUsageException(
						"Value `" + unknownToString(value) + "` is expected to be Range but it is not!")
				);
				removeRange(recordId, (Range[]) value);
			} else {
				isTrue(
					value instanceof Range,
					() -> new EvitaInvalidUsageException(
						"Value `" + unknownToString(value) + "` is expected to be Range but it is not!")
				);
				// canonicalize to the index scale so the thresholds being removed match the ones `addRecord`
				// inserted at the schema scale (no-op for non-`BigDecimal` ranges via `NO_NORMALIZATION`)
				final Range range = (Range) this.normalizer.apply(value);
				this.rangeIndex.removeRecord(range.getFrom(), range.getTo(), recordId);
			}
		}

		if (value instanceof final Object[] valueArray) {
			for (Object valueItem : verifyValueArray(valueArray)) {
				removeRecordFromHistogramAndValueIndex(recordId, (T) valueItem);
			}
		} else {
			removeRecordFromHistogramAndValueIndex(recordId, (T) value);
		}

		if (!isTransactionAvailable()) {
			recordWarmUpSavepointTouch();
			this.memoizedAllRecordsFormula = null;
			this.memoizedRangeHistogramSubSet = null;
		}
		markDirty();
	}

	/**
	 * Removes the specified record from the index for the given attribute value array. The difference between this
	 * method and {@link #removeRecord(int, Object)} is that this method removes the value contents partially, while
	 * {@link #removeRecord(int, Object)} removes the whole value. This method makes sense only for attributes that
	 * are of the array type.
	 *
	 * @param recordId the unique identifier of the record
	 * @param value    the attribute value array
	 * @param <T>      the type of the attribute value
	 * @throws EvitaInvalidUsageException when the value is not of type Range in case of range index
	 */
	public <T extends Serializable> void removeRecordDelta(int recordId, @Nonnull Object[] value) {
		// if current attribute is Range based assign record also to range index
		//noinspection VariableNotUsedInsideIf
		if (this.rangeIndex != null) {
			if (value instanceof Range[] valueArray) {
				// this is quite expensive operation, but we need to do it to be able to remove and add the record;
				// the existing ranges read back from the inverted index are already canonicalized to the index
				// scale, so the raw delta ranges must be canonicalized too before the set subtraction compares them
				// by equality (no-op for non-`BigDecimal` ranges)
				final Range[] existingRanges = this.invertedIndex.getValuesForRecord(recordId, Range.class);
				final Range[] remainingRanges = getRemainingRanges(normalizeRanges(valueArray), existingRanges);

				removeRange(recordId, existingRanges);
				addRange(recordId, remainingRanges);
			} else {
				throw new EvitaInvalidUsageException(
					"Value `" + unknownToString(value) + "` is expected to be Range but it is not!");
			}
		}

		verifyValueArray(value);
		for (Object valueItem : value) {
			removeRecordFromHistogramAndValueIndex(recordId, (T) valueItem);
		}

		if (!isTransactionAvailable()) {
			recordWarmUpSavepointTouch();
			this.memoizedAllRecordsFormula = null;
			this.memoizedRangeHistogramSubSet = null;
		}
		markDirty();
	}

	/**
	 * Returns true if filter index contains no records.
	 */
	public boolean isEmpty() {
		return this.invertedIndex.isEmpty();
	}

	/**
	 * Returns bitmap of all record ids connected with the value in the argument. The lookup value is funneled
	 * through the same canonicalization the write path uses — `BigDecimal` scale stripping via
	 * {@link NumberUtils#normalizeIfBigDecimal} followed by {@link #normalizer} — so the probe key matches the
	 * form the bucket was stored under (e.g. NFD-folded strings, instant-normalized `OffsetDateTime`).
	 */
	@Nonnull
	public <T extends Serializable> Bitmap getRecordsEqualTo(@Nonnull T attributeValue) {
		final T normalized = (T) NumberUtils.normalizeIfBigDecimal(attributeValue);
		return this.invertedIndex.getRecordsEqualTo(this.normalizer.apply(normalized));
	}

	/**
	 * Formula-returning counterpart of {@link #getRecordsEqualTo(Serializable)} for the query-planner: yields a
	 * {@link ConstantFormula} over the matching record ids, or {@link EmptyFormula#INSTANCE} when none match. The
	 * lookup value is canonicalized identically (see {@link #getRecordsEqualTo(Serializable)}).
	 */
	@Nonnull
	public <T extends Serializable> Formula getRecordsEqualToFormula(@Nonnull T attributeValue) {
		final T normalized = (T) NumberUtils.normalizeIfBigDecimal(attributeValue);
		// use direct binary search to avoid stale valueIndex cache positions when
		// the inverted index is accessed through a transactional layer on another thread
		final Bitmap records = this.invertedIndex.getRecordsEqualTo(this.normalizer.apply(normalized));
		return records.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(records);
	}

	/**
	 * Returns all records present in filter index in the form of {@link InvertedIndexSubSet}.
	 */
	public InvertedIndexSubSet getHistogramOfAllRecords() {
		return this.invertedIndex.getSortedRecords(null, null);
	}

	/**
	 * Returns a histogram subset built from this filter index's {@link RangeIndex} companion by performing
	 * a forward sweep over the sorted ranges. The emitted buckets are keyed by the source attribute's natural
	 * numeric type (`Byte`, `Short`, `Integer`, `Long`, `BigDecimal`) and follow closed-interval semantics:
	 * a record whose range is `[a, b]` participates in every bucket `V` such that `a <= V <= b`.
	 *
	 * The sentinel thresholds `Long.MIN_VALUE` and `Long.MAX_VALUE` that {@link RangeIndex} always carries do
	 * not emit a bucket of their own (they cannot be materialized as the inner numeric type), but their
	 * `starts` / `ends` bitmaps are still applied to the rolling active set so that records with open-ended
	 * ranges (`from == null` / `to == null`) participate in / exit the appropriate buckets. The result is
	 * memoized — outside transactions, repeated calls return the cached subset; on mutation the cache is
	 * invalidated alongside {@link #memoizedAllRecordsFormula}.
	 *
	 * Throws {@link GenericEvitaInternalError} when invoked on a filter index that has no range companion.
	 *
	 * @param innerNumericType      the inner numeric type of the source `NumberRange` (e.g. `Integer` for
	 *                              `IntegerNumberRange`); used to materialize bucket keys
	 * @param retainedDecimalPlaces decimal-places scale used by `BigDecimalNumberRange` to encode its
	 *                              `long` threshold; ignored for all other inner numeric types
	 * @return histogram subset whose `ValueToRecordBitmap[]` is sorted ascending by threshold
	 */
	@Nonnull
	public InvertedIndexSubSet getRangeHistogramOfAllRecords(
		@Nonnull Class<? extends Number> innerNumericType,
		int retainedDecimalPlaces
	) {
		if (this.rangeIndex == null) {
			throw new GenericEvitaInternalError(
				"getRangeHistogramOfAllRecords called on a FilterIndex without a RangeIndex companion " +
					"(attribute `" + this.attributeIndexKey + "`)."
			);
		}
		// the inner numeric type is fully determined by the leaf's attribute type — a mismatch
		// here can only originate from schema/index drift or a caller bug, so fail fast instead
		// of silently recomputing under a divergent key
		final Class<? extends Number> expectedInnerType =
			EvitaDataTypes.resolveRangeInnerNumericType(this.attributeType);
		if (expectedInnerType != innerNumericType) {
			throw new GenericEvitaInternalError(
				"getRangeHistogramOfAllRecords called with innerNumericType `" + innerNumericType +
					"` but FilterIndex attributeType `" + this.attributeType.getName() +
					"` resolves to inner type `" + expectedInnerType +
					"` (attribute `" + this.attributeIndexKey + "`) — schema/index drift."
			);
		}
		// memoization fast-path: only outside transactions; mutation invalidates the cache
		if (!isTransactionAvailable() && this.memoizedRangeHistogramSubSet != null) {
			return this.memoizedRangeHistogramSubSet;
		}
		// pre-size the buffer to the exact bucket count to avoid grow-copies during the sweep;
		// outside transactions `getRangePointCount()` is O(1) and yields a tight upper bound
		// (point count minus the two skipped sentinels). Inside transactions the call would
		// force a merge, so fall back to a constant hint there.
		final int capacityHint = isTransactionAvailable()
			? 16
			: Math.max(16, this.rangeIndex.getRangePointCount() - 2);
		final List<ValueToRecordBitmap> buckets = new ArrayList<>(capacityHint);
		// the active set is mutated in place; we snapshot via clone() into each emitted bucket
		final BaseBitmap activeSet = new BaseBitmap();
		final Iterator<TransactionalRangePoint> iterator = this.rangeIndex.rangesIterator();
		while (iterator.hasNext()) {
			final TransactionalRangePoint point = iterator.next();
			final long threshold = point.getThreshold();
			final Bitmap starts = point.getStarts();
			if (!starts.isEmpty()) {
				activeSet.addAll(starts);
			}
			// sentinel thresholds (Long.MIN_VALUE / Long.MAX_VALUE) cannot be materialized as
			// bucket keys — they would overflow the inner numeric type. Their `starts` / `ends`
			// bitmaps MUST still mutate the active set, however: records with open-ended ranges
			// (`from == null` -> `Long.MIN_VALUE`; `to == null` -> `Long.MAX_VALUE`) are
			// registered exclusively on the sentinel points, and skipping them entirely would
			// drop unbounded-from records from every bucket
			if (threshold != Long.MIN_VALUE && threshold != Long.MAX_VALUE) {
				// snapshot BEFORE removing ends to honor closed-interval semantics — a single-point range
				// (from == to) carries the record in both `starts` and `ends`, so the bucket emitted here
				// must include it before the upcoming `removeAll(ends)` strips it
				buckets.add(
					new ValueToRecordBitmap(
						toBucketKey(threshold, innerNumericType, retainedDecimalPlaces),
						new BaseBitmap(activeSet)
					)
				);
			}
			final Bitmap ends = point.getEnds();
			if (!ends.isEmpty()) {
				activeSet.removeAll(ends);
			}
		}
		final ValueToRecordBitmap[] bucketArray = buckets.toArray(new ValueToRecordBitmap[0]);
		// range-histogram staleness stays whole-index coarse: the buckets are assembled from the RangeIndex point
		// sweep above (not from InvertedIndex leaf pages), so there are no leaf-version tokens to key on here.
		// getId() already mints a fresh id on any mutation, which invalidates any cached formula over this subset.
		final InvertedIndexSubSet result = new InvertedIndexSubSet(
			new long[]{getId()},
			bucketArray,
			RANGE_HISTOGRAM_AGGREGATION_LAMBDA
		);
		if (!isTransactionAvailable()) {
			this.memoizedRangeHistogramSubSet = result;
		}
		return result;
	}

	/**
	 * Returns all records present in filter index as {@link Bitmap}.
	 */
	@Nonnull
	public Bitmap getAllRecords() {
		return getAllRecordsFormula().compute();
	}

	/**
	 * Returns all records present in filter index as {@link AbstractFormula}.
	 *
	 * The returned formula is an opaque {@link ConstantFormula} wrapping the materialized bitmap (or
	 * {@link EmptyFormula#INSTANCE} when the index has no records). Returning a flat constant rather than the
	 * raw OR-of-buckets tree from {@link InvertedIndexSubSet#getFormula()} prevents query-planner rewrites that
	 * would otherwise distribute surrounding {@code NOT(OR(b₁..b_N), U)} via De Morgan into a wide
	 * {@code AND(NOT b₁ ... NOT b_N)} — a transformation that explodes cost for high-cardinality indexes.
	 */
	public Formula getAllRecordsFormula() {
		// if there is transaction open, there might be changes in the histogram data, and we can't easily use cache
		if (isTransactionAvailable() && isDirty()) {
			final Bitmap allRecords = getHistogramOfAllRecords().getFormula().compute();
			return allRecords.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(allRecords);
		} else {
			if (this.memoizedAllRecordsFormula == null) {
				final Bitmap allRecords = getHistogramOfAllRecords().getFormula().compute();
				this.memoizedAllRecordsFormula = allRecords.isEmpty() ?
					EmptyFormula.INSTANCE : new ConstantFormula(allRecords);
			}
			return this.memoizedAllRecordsFormula;
		}
	}

	/**
	 * Returns all records lesser than or equals attribute value passed in the argument in the form of {@link InvertedIndexSubSet}.
	 */
	public InvertedIndexSubSet getHistogramOfRecordsLesserThanEq(@Nonnull Serializable to) {
		return this.invertedIndex.getSortedRecords(null, to);
	}

	/**
	 * Returns all records lesser than or equals attribute value passed in the argument in the form of {@link Bitmap}.
	 */
	@Nonnull
	public Bitmap getRecordsLesserThanEq(@Nonnull Serializable to) {
		final Formula recordsLesserThanEqFormula = getRecordsLesserThanEqFormula(to);
		return recordsLesserThanEqFormula.compute();
	}

	/**
	 * Returns all records lesser than or equals attribute value passed in the argument in the form of {@link Bitmap}.
	 */
	public Formula getRecordsLesserThanEqFormula(@Nonnull Serializable to) {
		return getHistogramOfRecordsLesserThanEq(to).getFormula();
	}

	/**
	 * Returns all records greater than or equals attribute value passed in the argument in the form of {@link InvertedIndexSubSet}.
	 */
	public InvertedIndexSubSet getHistogramOfRecordsGreaterThanEq(@Nonnull Serializable from) {
		return this.invertedIndex.getSortedRecords(from, null);
	}

	/**
	 * Returns all records greater than or equals attribute value passed in the argument in the form of {@link Bitmap}.
	 */
	@Nonnull
	public Bitmap getRecordsGreaterThanEq(@Nonnull Serializable from) {
		final Formula recordsGreaterThanEqFormula = getRecordsGreaterThanEqFormula(from);
		return recordsGreaterThanEqFormula.compute();
	}

	/**
	 * Returns all records greater than or equals attribute value passed in the argument in the form of {@link AbstractFormula}.
	 */
	public Formula getRecordsGreaterThanEqFormula(@Nonnull Serializable to) {
		return getHistogramOfRecordsGreaterThanEq(to).getFormula();
	}

	/**
	 * Returns all records lesser than attribute value passed in the argument in the form of {@link InvertedIndexSubSet}.
	 */
	public InvertedIndexSubSet getHistogramOfRecordsLesserThan(@Nonnull Serializable to) {
		return this.invertedIndex.getSortedRecordsExclusive(null, to);
	}

	/**
	 * Returns all records lesser than attribute value passed in the argument in the form of {@link Bitmap}.
	 */
	@Nonnull
	public Bitmap getRecordsLesserThan(@Nonnull Serializable to) {
		final Formula recordsLesserThanFormula = getRecordsLesserThanFormula(to);
		return recordsLesserThanFormula.compute();
	}

	/**
	 * Returns all records lesser than attribute value passed in the argument in the form of {@link AbstractFormula}.
	 */
	public Formula getRecordsLesserThanFormula(@Nonnull Serializable from) {
		return getHistogramOfRecordsLesserThan(from).getFormula();
	}

	/**
	 * Returns all records greater than attribute value passed in the argument in the form of {@link InvertedIndexSubSet}.
	 */
	public InvertedIndexSubSet getHistogramOfRecordsGreaterThan(@Nonnull Serializable from) {
		return this.invertedIndex.getSortedRecordsExclusive(from, null);
	}

	/**
	 * Returns all records greater than attribute value passed in the argument in the form of {@link Bitmap}.
	 */
	@Nonnull
	public Bitmap getRecordsGreaterThan(@Nonnull Serializable from) {
		final Formula recordsGreaterThanFormula = getRecordsGreaterThanFormula(from);
		return recordsGreaterThanFormula.compute();
	}

	/**
	 * Returns all records greater than attribute value passed in the argument in the form of {@link AbstractFormula}.
	 */
	public Formula getRecordsGreaterThanFormula(@Nonnull Serializable from) {
		return getHistogramOfRecordsGreaterThan(this.normalizer.apply(from)).getFormula();
	}

	/**
	 * Returns all records with attribute values between `from` and `to` (inclusive) passed in the argument
	 * in the form of {@link InvertedIndexSubSet}.
	 */
	public InvertedIndexSubSet getHistogramOfRecordsBetween(@Nonnull Serializable from, @Nonnull Serializable to) {
		return this.invertedIndex.getSortedRecords(from, to);
	}

	/**
	 * Returns all records with attribute values between `from` and `to` (inclusive) passed in the argument
	 * in the form of {@link Bitmap}.
	 */
	@Nonnull
	public Bitmap getRecordsBetween(@Nonnull Serializable from, @Nonnull Serializable to) {
		final Formula recordsBetweenFormula = getRecordsBetweenFormula(from, to);
		return recordsBetweenFormula.compute();
	}

	/**
	 * Returns all records with attribute values between `from` and `to` (inclusive) passed in the argument
	 * in the form of {@link AbstractFormula}.
	 */
	public Formula getRecordsBetweenFormula(@Nonnull Serializable from, @Nonnull Serializable to) {
		return getHistogramOfRecordsBetween(from, to).getFormula();
	}

	/**
	 * Returns all records valid at the moment in the form of {@link Bitmap}.
	 * This method can be used only when the attribute type is of the {@link Range} type.
	 */
	@Nonnull
	public Bitmap getRecordsValidIn(long thePoint) {
		Assert.notNull(this.rangeIndex, ERROR_RANGE_TYPE_NOT_SUPPORTED);
		final Formula recordsValidInFormula = getRecordsValidInFormula(thePoint);
		return recordsValidInFormula.compute();
	}

	/**
	 * Returns all records valid at the moment in the form of {@link AbstractFormula}.
	 * This method can be used only when the attribute type is of the {@link Range} type.
	 */
	public Formula getRecordsValidInFormula(long thePoint) {
		Assert.notNull(this.rangeIndex, ERROR_RANGE_TYPE_NOT_SUPPORTED);
		return this.rangeIndex.getRecordsEnvelopingInclusive(thePoint);
	}

	/**
	 * Cache-aware variant of {@link #getRecordsValidInFormula(long)} intended for the
	 * {@code attributeInRangeNow} flow. Delegates to {@link RangeIndex#getRecordsValidNowFormula(long)}
	 * which memoizes the materialized bitmap for the interval of {@code now} values that yield the same result.
	 */
	public Formula getRecordsValidNowFormula(long thePoint) {
		Assert.notNull(this.rangeIndex, ERROR_RANGE_TYPE_NOT_SUPPORTED);
		return this.rangeIndex.getRecordsValidNowFormula(thePoint);
	}

	/**
	 * Returns all records which range overlaps the passed range in the form of {@link Bitmap}.
	 * This method can be used only when the attribute type is of the {@link Range} type.
	 *
	 * @param from the inclusive lower bound of the overlap query, as a `RangeIndex` long threshold
	 * @param to   the inclusive upper bound of the overlap query, as a `RangeIndex` long threshold
	 */
	@Nonnull
	public Bitmap getRecordsOverlapping(long from, long to) {
		Assert.notNull(this.rangeIndex, ERROR_RANGE_TYPE_NOT_SUPPORTED);
		final Formula recordsOverlappingFormula = getRecordsOverlappingFormula(from, to);
		return recordsOverlappingFormula.compute();
	}

	/**
	 * Returns all records which range overlaps the passed range in the form of {@link AbstractFormula}.
	 * This method can be used only when the attribute type is of the {@link Range} type.
	 */
	@Nonnull
	public Formula getRecordsOverlappingFormula(long from, long to) {
		Assert.notNull(this.rangeIndex, ERROR_RANGE_TYPE_NOT_SUPPORTED);
		return this.rangeIndex.getRecordsWithRangesOverlapping(from, to);
	}

	/**
	 * Emits this filter index's modified storage parts into `sink`. A clean index emits nothing. A
	 * dirty index whose bucket tree spans a single leaf emits the inline `SINGLE` root (today's whole-index part); a
	 * dirty index whose tree spans multiple leaves emits the granular `PAGED` shape: one {@link FilterIndexLeafPagePart}
	 * per CHANGED leaf plus the fused `PAGED` root carrying each axis's high-water and ordered live leaf-page list —
	 * re-emitted only when an axis's page list changed (or a non-paged axis carries varying inline data); when both
	 * axes are page-stable the root is byte-identical to disk and skipped. The leaf pages
	 * carry the sub-index identity so their stream id (and primary key) is resolved store-side at write time.
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param sink                  the trapped-changes accumulator for this commit
	 */
	public void appendStorageParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges sink) {
		if (!isDirty()) {
			return;
		}
		final AttributeKeyWithIndexType streamKey =
			new AttributeKeyWithIndexType(this.attributeIndexKey, AttributeIndexType.FILTER);

		// the two sub-indexes are paged independently of each other; emit the range axis first (preserving the prior
		// emission order), then the bucket axis, then fuse both descriptors into the single FilterIndexStoragePart root
		final RangeAxis range = appendRangeAxis(entityIndexPrimaryKey, streamKey, sink);
		final BucketAxis bucket = appendBucketAxis(entityIndexPrimaryKey, streamKey, sink);

		// the fused root carries per-commit-varying inline data only for an axis that is NOT paged (an inline histogram
		// for a SINGLE bucket tree, an inline RangeIndex for a SINGLE range). When BOTH axes are externalized to leaf
		// pages the root is nothing but two page-lists + immutable schema, so it can be skipped whenever NEITHER list
		// changed this commit (a leaf allocated/freed) — collapsing the steady-state root cost to O(1) instead of
		// O(live pages). An absent range (no companion) contributes nothing that varies, so it is root-stable too.
		final boolean bucketRootStable = bucket.paged() && !bucket.listChanged();
		final boolean rangeRootStable = range.rangePaged()
			? !range.listChanged()
			: range.inlineRangeIndex() == null;
		if (bucketRootStable && rangeRootStable) {
			return;
		}

		sink.addChangeToStore(
			new FilterIndexStoragePart(
				entityIndexPrimaryKey, this.attributeIndexKey, this.attributeType,
				bucket.histogramPoints(), range.inlineRangeIndex(), this.indexedDecimalPlaces,
				bucket.paged(), bucket.highWaterPageSequence(), bucket.leafPageSequences(),
				range.rangePaged(), range.rangeHighWaterPageSequence(), range.rangeLeafPageSequences(), null
			)
		);
	}

	/**
	 * Emits the BUCKET (value) axis of this commit into `sink` and returns how it maps onto the
	 * {@link FilterIndexStoragePart} root. A `PAGED` bucket tree emits one {@link FilterIndexLeafPagePart} per CHANGED
	 * leaf plus a {@link FilterIndexLeafPageRemoval} per freed leaf and carries empty inline buckets; a `SINGLE` tree
	 * that just collapsed from `PAGED` removes its prior leaf pages, forgets the page stream, and carries every bucket
	 * inline.
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param streamKey             the sub-index identity carried by each leaf page (resolves its store-side stream id)
	 * @param sink                  the trapped-changes accumulator for this commit
	 * @return the bucket-axis descriptor to fold into the root part
	 */
	@Nonnull
	private BucketAxis appendBucketAxis(
		int entityIndexPrimaryKey, @Nonnull AttributeKeyWithIndexType streamKey, @Nonnull TrappedChanges sink
	) {
		if (this.invertedIndex.isPaged()) {
			final PageEmission<InvertedIndex.LeafPage> emission = this.invertedIndex.collectChangedPages();
			for (final InvertedIndex.LeafPage page : emission.changedPages()) {
				sink.addChangeToStore(
					new FilterIndexLeafPagePart(entityIndexPrimaryKey, streamKey, page.pageSequence(), page.buckets())
				);
			}
			// remove the leaf pages a merge dropped this commit so they don't leak (the OffsetIndex never reclaims an
			// unreferenced-but-never-removed record — page ids are advance-only and never re-keyed)
			for (final int freedPageSequence : emission.freedPageSequences()) {
				sink.addChangeToStore(new FilterIndexLeafPageRemoval(entityIndexPrimaryKey, streamKey, freedPageSequence));
			}
			return new BucketAxis(
				EMPTY_HISTOGRAM_POINTS, true, emission.highWaterPageSequence(), emission.orderedPageSequences(),
				emission.pageListChanged()
			);
		}
		// SINGLE shape: the index collapsed back to a single leaf. Remove every leaf page from its prior PAGED life
		// (the SINGLE root no longer references them) BEFORE dropping the page bookkeeping, then forget the stream so a
		// later regrow into PAGED starts from a clean baseline and re-emits every leaf.
		// Reclaim against what the previous flush left ON DISK: its staged set while still unpublished (a warm-up
		// flush never reaches the commit-merge that publishes), else the published set. The published set alone lags a
		// whole flush behind, so every page of the collapsed stream would leak — the append-only OffsetIndex never
		// reclaims a record that is neither superseded nor explicitly removed.
		for (final int freedPageSequence : this.invertedIndex.currentLeafPageSequences()) {
			sink.addChangeToStore(new FilterIndexLeafPageRemoval(entityIndexPrimaryKey, streamKey, freedPageSequence));
		}
		this.invertedIndex.forgetPageStream();
		// SINGLE: the inline histogram rides the root and can change every commit, so force the root re-emit
		// (listChanged=true)
		return new BucketAxis(
			this.invertedIndex.getValueToRecordBitmap(), false, -1, ArrayUtils.EMPTY_INT_ARRAY, true
		);
	}

	/**
	 * Emits the RANGE axis of this commit into `sink` and returns how it maps onto the {@link FilterIndexStoragePart}
	 * root, mirroring {@link #appendBucketAxis}. A `PAGED` range emits one {@link RangeIndexLeafPagePart} per CHANGED
	 * leaf plus a {@link RangeIndexLeafPageRemoval} per freed leaf and carries a `null` inline range; a `SINGLE` range
	 * (or none at all) carries the whole {@link RangeIndex} inline and, if it just collapsed from `PAGED`, removes its
	 * prior leaf pages and forgets the range stream.
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param streamKey             the sub-index identity carried by each leaf page (resolves its store-side stream id)
	 * @param sink                  the trapped-changes accumulator for this commit
	 * @return the range-axis descriptor to fold into the root part
	 */
	@Nonnull
	private RangeAxis appendRangeAxis(
		int entityIndexPrimaryKey, @Nonnull AttributeKeyWithIndexType streamKey, @Nonnull TrappedChanges sink
	) {
		if (this.rangeIndex != null && this.rangeIndex.isPaged()) {
			final PageEmission<RangeIndex.RangePage> emission = this.rangeIndex.collectChangedPages();
			for (final RangeIndex.RangePage page : emission.changedPages()) {
				sink.addChangeToStore(
					new RangeIndexLeafPagePart(entityIndexPrimaryKey, streamKey, page.pageSequence(), page.points())
				);
			}
			for (final int freedPageSequence : emission.freedPageSequences()) {
				sink.addChangeToStore(new RangeIndexLeafPageRemoval(entityIndexPrimaryKey, streamKey, freedPageSequence));
			}
			return new RangeAxis(
				null, true, emission.highWaterPageSequence(), emission.orderedPageSequences(),
				emission.pageListChanged()
			);
		}
		if (this.rangeIndex != null) {
			// SINGLE range that may have just collapsed from PAGED: remove every prior leaf page (the inline root no
			// longer references them) BEFORE dropping the bookkeeping, then forget the stream so a later regrow starts
			// from a clean baseline and re-emits every leaf
			// Reclaim against what the previous flush left ON DISK: its staged set while still unpublished (a warm-up
			// flush never reaches the commit-merge that publishes), else the published set. The published set alone lags a
			// whole flush behind, so every page of the collapsed stream would leak — the append-only OffsetIndex never
			// reclaims a record that is neither superseded nor explicitly removed.
			for (final int freedPageSequence : this.rangeIndex.currentLeafPageSequences()) {
				sink.addChangeToStore(new RangeIndexLeafPageRemoval(entityIndexPrimaryKey, streamKey, freedPageSequence));
			}
			this.rangeIndex.forgetPageStream();
		}
		// non-paged range: either absent (`null`) or carried inline; the skip test keys off `inlineRangeIndex == null`,
		// so `listChanged` is irrelevant here — pass `true` to avoid implying the (unwritten) page-list is stable
		return new RangeAxis(this.rangeIndex, false, -1, ArrayUtils.EMPTY_INT_ARRAY, true);
	}

	/**
	 * The bucket-axis outcome of one commit: how the value bucket tree maps onto the {@link FilterIndexStoragePart}
	 * root. When `paged`, `histogramPoints` is empty and the buckets live in {@link FilterIndexLeafPagePart} leaf pages;
	 * when `SINGLE`, `histogramPoints` carries every bucket inline and the page metadata is the empty / `-1` sentinel.
	 *
	 * @param histogramPoints  inline buckets for a `SINGLE` part; empty for a `PAGED` part
	 * @param paged            true when the bucket tree is persisted as leaf pages
	 * @param highWaterPageSequence the bucket stream high-water for a `PAGED` part; `-1` otherwise
	 * @param leafPageSequences     the ordered live leaf-page sequences for a `PAGED` part; empty otherwise
	 * @param listChanged      for a `PAGED` part, whether the live leaf-page list changed this commit (a leaf was
	 *                         allocated or freed); meaningless (and forced `true`) for a `SINGLE` part whose inline
	 *                         histogram always rides the root
	 */
	private record BucketAxis(
		@Nonnull ValueToRecordBitmap[] histogramPoints,
		boolean paged,
		int highWaterPageSequence,
		@Nonnull int[] leafPageSequences,
		boolean listChanged
	) {
	}

	/**
	 * The range-axis outcome of one commit, mirroring {@link BucketAxis} for the optional range companion. When
	 * `rangePaged`, `inlineRangeIndex` is `null` and the range points live in {@link RangeIndexLeafPagePart} leaf pages;
	 * otherwise the whole {@link RangeIndex} is carried inline (or `null` when the attribute has no range companion).
	 *
	 * @param inlineRangeIndex      the inline range for a non-paged part; `null` when paged or absent
	 * @param rangePaged            true when the range tree is persisted as leaf pages
	 * @param rangeHighWaterPageSequence the range stream high-water for a `PAGED` range; `-1` otherwise
	 * @param rangeLeafPageSequences     the ordered live range leaf-page sequences for a `PAGED` range; empty otherwise
	 * @param listChanged           for a `PAGED` range, whether the live leaf-page list changed this commit (a leaf was
	 *                              allocated or freed); meaningless for a non-paged range (the skip test uses the
	 *                              `null`-inline check instead)
	 */
	private record RangeAxis(
		@Nullable RangeIndex inlineRangeIndex,
		boolean rangePaged,
		int rangeHighWaterPageSequence,
		@Nonnull int[] rangeLeafPageSequences,
		boolean listChanged
	) {
	}

	/**
	 * Records, for the warm-up savepoint bracketing the current root entity mutation if one is open, that this index's
	 * memoized formulas have to be left INVALIDATED should the mutation be rolled back (see {@link WarmUpSavepoint}).
	 *
	 * The forward mutators already null both memos, so the state a rollback finds them in would be correct — were it
	 * not for reads. A query executed later within the same root entity mutation (uniqueness checks and reference
	 * cascades routinely run one) repopulates them from the HALF-MUTATED index, and that value would then survive the
	 * rollback of the data underneath it. Re-nulling on restore is what closes that window.
	 *
	 * The memos are re-invalidated rather than restored to their captured pre-images on purpose: an absolute restore of
	 * the underlying inverted index costs the memos nothing but a recomputation, whereas a captured formula would have
	 * to be trusted to have been valid, which nothing here can establish.
	 *
	 * The touch is recorded once per savepoint - the whole cached state is these two slots, so a single re-invalidation
	 * covers every write - and only from the non-transactional branch, since inside a transaction no warm-up savepoint
	 * is ever open. Outside a savepoint it costs one {@link ThreadLocal} read returning `null`.
	 */
	private void recordWarmUpSavepointTouch() {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint != null && savepoint.claimFirstTouch(this)) {
			savepoint.push(() -> {
				this.memoizedAllRecordsFormula = null;
				this.memoizedRangeHistogramSubSet = null;
			});
		}
	}

	/**
	 * Marks the index as dirty after a mutation. Owners flip their own transactional dirty flag; views are no-ops
	 * (the shared tree they wrap tracks its own dirtiness — a view never participates in a commit, so engaging a
	 * private dirty flag would leak an undischarged transactional layer).
	 */
	protected abstract void markDirty();

	/**
	 * Returns the normalizer applied to values before they enter the inverted index. Exposed so subclasses can wire
	 * the merged-transactional copy without re-deriving it.
	 */
	@Nonnull
	protected Function<Object, Serializable> getNormalizer() {
		return this.normalizer;
	}

	/**
	 * Returns the comparator used to order values inside the inverted index. Exposed so subclasses can wire the
	 * merged-transactional copy without re-deriving it.
	 */
	@Nonnull
	protected Comparator<? extends Comparable> getComparator() {
		return this.comparator;
	}

	/**
	 * Adds the given ranges to the range index for the specified record ID.
	 *
	 * @param recordId The ID of the record.
	 * @param ranges   The ranges to add.
	 */
	private void addRange(int recordId, @Nonnull Range[] ranges) {
		// canonicalize every range to the index scale before consolidation so consolidation, the range-index
		// thresholds and the schema-scale probe all agree (no-op for non-`BigDecimal` ranges)
		final Range[] consolidatedRangesToAdd = Range.consolidateRange(normalizeRanges(ranges));
		for (Range consolidatedRange : consolidatedRangesToAdd) {
			Objects.requireNonNull(this.rangeIndex).addRecord(
				consolidatedRange.getFrom(), consolidatedRange.getTo(), recordId);
		}
	}

	/**
	 * Removes the specified ranges from the range index for a given record ID.
	 *
	 * @param recordId The ID of the record from which ranges are to be removed.
	 * @param ranges   An array of ranges to be removed.
	 */
	private void removeRange(int recordId, @Nonnull Range[] ranges) {
		// canonicalize every range to the index scale before consolidation so the thresholds being removed
		// match those `addRange` inserted at the schema scale (no-op for non-`BigDecimal` ranges)
		final Range[] consolidatedRangesToRemove = Range.consolidateRange(normalizeRanges(ranges));
		for (Range consolidatedRange : consolidatedRangesToRemove) {
			Objects.requireNonNull(this.rangeIndex).removeRecord(
				consolidatedRange.getFrom(), consolidatedRange.getTo(), recordId);
		}
	}

	/**
	 * Canonicalizes each element of the supplied range array via {@link #normalizer} so its `getFrom()` /
	 * `getTo()` thresholds are computed at the index scale rather than at the value's intrinsic scale. For
	 * `BigDecimalNumberRange` attributes this rescales the range to the schema's `indexedDecimalPlaces`
	 * (matching the form the probe is coerced to and the form the {@link #invertedIndex} already stores);
	 * for every other range type the normalizer is the identity {@link #NO_NORMALIZATION}, so the array is
	 * returned with its elements unchanged.
	 *
	 * A fresh array is always returned — the caller may freely consolidate / merge it without mutating the
	 * source array (the inverted-index path still reads the original values).
	 *
	 * @param ranges the raw ranges to canonicalize
	 * @return a new array holding the canonicalized ranges
	 */
	@Nonnull
	private Range[] normalizeRanges(@Nonnull Range[] ranges) {
		final Range[] normalized = new Range[ranges.length];
		for (int i = 0; i < ranges.length; i++) {
			normalized[i] = (Range) this.normalizer.apply(ranges[i]);
		}
		return normalized;
	}

	/**
	 * Drops a single value→record association from the backing {@link #invertedIndex}. This is the shared
	 * removal primitive behind both {@link #removeRecord(int, Object)} (whole value) and
	 * {@link #removeRecordDelta(int, Object[])} (partial array contents), invoked once per scalar value item.
	 *
	 * Before touching the tree it asserts the record really is registered for this value's bucket: the value is
	 * run through {@link #normalizer} (so the lookup key matches the canonical form the bucket was stored under,
	 * e.g. NFD-folded strings) and {@link InvertedIndex#getRecordsEqualTo} must contain `recordId`. The actual
	 * {@link InvertedIndex#removeRecord} call then uses the RAW (un-normalized) value, because the tree applies
	 * its own comparator-driven normalization during removal — normalizing here as well would double-fold the key
	 * and miss the bucket. The normalizer's output therefore feeds the sanity check only, never the write.
	 *
	 * @param recordId the record id to detach from the value's bucket
	 * @param value    the raw attribute value whose association is removed
	 * @param <T>      the attribute value type
	 * @throws EvitaInvalidUsageException when the record is not registered for the (normalized) value — signals a
	 *                                    mismatch between the mutation being applied and the index state
	 */
	private <T extends Serializable> void removeRecordFromHistogramAndValueIndex(int recordId, @Nonnull T value) {
		// sanity check first - the record must currently be assigned to this value's bucket
		final Serializable normalizedValue = this.normalizer.apply(value);
		isTrue(
			this.invertedIndex.getRecordsEqualTo(normalizedValue).contains(recordId),
			"Sanity check - record not found!"
		);
		this.invertedIndex.removeRecord(value, recordId);
	}

}
