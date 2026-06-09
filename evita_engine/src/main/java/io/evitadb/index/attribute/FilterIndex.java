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
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Range;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.InvertedIndexSubSet;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Currency;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

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
 *   {@link io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer}. Used by the histogram subsystem and
 *   any standalone owner.
 * - {@link FilterIndexView} — a stateless flyweight wrapping an {@link AttributeIndex}-owned shared
 *   {@link InvertedIndex}. It is NOT a transactional producer; its dirtiness, persistence and transactional id are
 *   all derived from the shared tree it wraps.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public abstract sealed class FilterIndex implements IndexDataStructure, Serializable
	permits OwnerFilterIndex, FilterIndexView {
	@Serial private static final long serialVersionUID = -6813305126746774103L;
	private static final String ERROR_RANGE_TYPE_NOT_SUPPORTED = "This filter index doesn't handle Range type!";
	static final Comparator<Comparable> DEFAULT_COMPARATOR = Comparator.naturalOrder();

	public static final Function<Object, Serializable> NO_NORMALIZATION = Serializable.class::cast;

	/**
	 * Aggregation lambda used by {@link #getRangeHistogramOfAllRecords(Class, int)} when producing the subset's
	 * {@link InvertedIndexSubSet#getFormula()}. Range histogram buckets overlap by design (a single record may
	 * span multiple thresholds and therefore appear in multiple buckets), so an `OR` over each bucket's
	 * `recordIds` is the correct distinct-union aggregator. Empty / single-bucket inputs short-circuit to avoid
	 * spurious formula tree allocations.
	 */
	private static final BiFunction<Long, ValueToRecord[], Formula> RANGE_HISTOGRAM_AGGREGATION_LAMBDA =
		(indexTransactionId, histogramBuckets) -> {
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
			return new OrFormula(new long[] {indexTransactionId}, bitmaps);
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
		isTrue(Serializable.class.isAssignableFrom(value.getClass().getComponentType()), "Value `" + unknownToString(value) + "` is expected to be Serializable, but it is not!");
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
			Assert.isTrue(remainingRangesIndex < remainingRanges.length, "Sanity check - remaining ranges index out of bounds!");
			remainingRanges[remainingRangesIndex++] = existingRange;
		}
		Assert.isPremiseValid(foundRanges.cardinality() == subtractedRanges.length, "Sanity check - not all ranges found!");
		return remainingRanges;
	}

	/**
	 * Returns the appropriate normalizer function for particular attribute type and key.
	 *
	 * @param attributeType type of the attribute
	 * @return appropriate comparator
	 */
	@Nonnull
	public static Function<Object, Serializable> getNormalizer(@Nonnull Class<?> attributeType) {
		if (OffsetDateTime.class.isAssignableFrom(attributeType)) {
			return comparable -> comparable instanceof OffsetDateTime offsetDateTime ? offsetDateTime.toInstant() : (Serializable) comparable;
		} else if (Currency.class.isAssignableFrom(attributeType)) {
			return comparable -> comparable instanceof Currency currency ? new ComparableCurrency(currency) : (Serializable) comparable;
		} else if (Locale.class.isAssignableFrom(attributeType)) {
			return comparable -> comparable instanceof Locale locale ? new ComparableLocale(locale) : (Serializable) comparable;
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
			throw new EvitaInvalidUsageException("Unsupported attribute type `" + attributeType + "`! The type is not comparable!");
		}
	}

	/**
	 * Returns the appropriate comparator for particular attribute type and key.
	 *
	 * @param attributeIndexKey  key containing information about used locale
	 * @param attributeType type of the attribute
	 * @return appropriate comparator
	 */
	@Nonnull
	public static Comparator<? extends Comparable> getComparator(@Nonnull AttributeIndexKey attributeIndexKey, @Nonnull Class<?> attributeType) {
		final Locale locale = attributeIndexKey.locale();
		if (String.class.isAssignableFrom(attributeType) && locale != null) {
			return new LocalizedStringComparator(locale);
		} else {
			return DEFAULT_COMPARATOR;
		}
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
	 * Shared base constructor wiring the immutable fields common to every owner / view. Concrete subclasses are
	 * responsible for sourcing the {@link InvertedIndex} (owned vs shared), the comparator / normalizer (derived from
	 * the attribute type vs delegated to the wrapped tree) and the transactional lifecycle.
	 *
	 * @param attributeIndexKey key identifying the attribute
	 * @param attributeType     the declared attribute type (array-aware)
	 * @param invertedIndex     the value→ValueToRecord tree backing this index (owned or shared)
	 * @param rangeIndex        the range structure for range-typed attributes, or `null`
	 * @param comparator        the value comparator
	 * @param normalizer        the value normalizer
	 */
	protected FilterIndex(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<?> attributeType,
		@Nonnull InvertedIndex invertedIndex,
		@Nullable RangeIndex rangeIndex,
		@Nonnull Comparator<? extends Comparable> comparator,
		@Nonnull Function<Object, Serializable> normalizer
	) {
		this.attributeIndexKey = attributeIndexKey;
		this.attributeType = attributeType;
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
	 * Marks the index as dirty after a mutation. Owners flip their own transactional dirty flag; views are no-ops
	 * (the shared tree they wrap tracks its own dirtiness — a view never participates in a commit, so engaging a
	 * private dirty flag would leak an undischarged transactional layer).
	 */
	protected abstract void markDirty();

	/**
	 * Returns the declared attribute type backing this filter index (array-aware). Exposed so {@link AttributeIndex} can
	 * rebuild the stateless filter views and produce the {@link FilterIndexStoragePart} from the shared tree.
	 */
	@Nonnull
	public Class<?> getAttributeType() {
		return this.attributeType;
	}

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
	 * Returns count of records in this index.
	 */
	public int size() {
		return this.invertedIndex.getLength();
	}

	/**
	 * Returns formula of record ids whose String attribute starts with particular prefix.
	 *
	 * Any value that starts with `prefix` sorts greater than or equal to `prefix` under the index comparator, and no
	 * non-matching value can sort between `prefix` and a matching value - so the matching buckets form a single
	 * contiguous run beginning at the first bucket `>= prefix`. We therefore anchor a bounded forward iteration at that
	 * bucket and stream until the first non-matching value (early break), with no whole-array materialization and no
	 * backward scan.
	 *
	 * TOBEDONE JNO naive and slow - use RadixTree
	 */
	@Nonnull
	public Formula getRecordsWhoseValuesStartWith(@Nonnull String prefix) {
		final LinkedList<Formula> formulas = new LinkedList<>();
		// anchor at the first bucket whose value sorts >= prefix and walk forward while the prefix still matches
		final Iterator<ValueToRecord> it = this.invertedIndex.getValueIteratorFrom(prefix);
		while (it.hasNext()) {
			final ValueToRecord bucket = it.next();
			final String value = String.valueOf(bucket.getValue());
			if (value.startsWith(prefix)) {
				formulas.add(new ConstantFormula(bucket.getRecordIds()));
			} else {
				// break immediately when the prefix is no longer valid - the run is contiguous
				break;
			}
		}
		if (formulas.isEmpty()) {
			return EmptyFormula.INSTANCE;
		}
		return FormulaFactory.or(formulas.toArray(Formula.EMPTY_FORMULA_ARRAY));
	}

	/**
	 * Returns formula of record ids whose String attribute ends with particular prefix.
	 */
	@Nonnull
	public Formula getRecordsWhoseValuesEndsWith(@Nonnull String suffix) {
		/* TOBEDONE JNO naive and slow - use RadixTree */
		return this.invertedIndex
			.getSortedRecordsMatching(value -> ((String) value).endsWith(suffix))
			.getFormula();
	}

	/**
	 * Returns formula of record ids whose String attribute contains particular text.
	 */
	@Nonnull
	public Formula getRecordsWhoseValuesContains(@Nonnull String text) {
		/* TOBEDONE JNO naive and slow - use RadixTree */
		return this.invertedIndex
			.getSortedRecordsMatching(value -> ((String) value).contains(text))
			.getFormula();
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
	public <T extends Serializable> void addRecord(int recordId, @Nonnull Object value) throws EvitaInvalidUsageException {
		// if current attribute is Range based assign record also to range index
		if (this.rangeIndex != null) {
			if (value instanceof Range[] valueArray) {
				addRange(recordId, valueArray);
			} else {
				isTrue(
					value instanceof Range,
					() -> new EvitaInvalidUsageException("Value `" + unknownToString(value) + "` is expected to be Range but it is not!"));
				final Range range = (Range) value;
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
	public <T extends Serializable> void addRecordDelta(int recordId, @Nonnull Object[] value) throws EvitaInvalidUsageException {
		// if current attribute is Range based assign record also to range index
		//noinspection VariableNotUsedInsideIf
		if (this.rangeIndex != null) {
			if (value instanceof Range[] valueArray) {
				// this is quite expensive operation, but we need to do it to be able to remove and add the record
				final Range[] existingRanges = this.invertedIndex.getValuesForRecord(recordId, Range.class);
				final Range[] aggregatedRanges = ArrayUtils.mergeArrays(existingRanges, valueArray);

				removeRange(recordId, existingRanges);
				addRange(recordId, aggregatedRanges);
			} else {
				throw new EvitaInvalidUsageException("Value `" + unknownToString(value) + "` is expected to be Range but it is not!");
			}
		}

		for (Object valueItem : verifyValueArray(value)) {
			this.invertedIndex.addRecord((T) valueItem, recordId);
		}

		if (!isTransactionAvailable()) {
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
	public <T extends Serializable> void removeRecord(int recordId, @Nonnull Object value) throws EvitaInvalidUsageException {
		// if current attribute is Range based assign record also to range index
		if (this.rangeIndex != null) {
			if (value instanceof Object[]) {
				isTrue(
					Range.class.isAssignableFrom(value.getClass().getComponentType()),
					() -> new EvitaInvalidUsageException("Value `" + unknownToString(value) + "` is expected to be Range but it is not!")
				);
				removeRange(recordId, (Range[]) value);
			} else {
				isTrue(
					value instanceof Range,
					() -> new EvitaInvalidUsageException("Value `" + unknownToString(value) + "` is expected to be Range but it is not!"));
				final Range range = (Range) value;
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
				// this is quite expensive operation, but we need to do it to be able to remove and add the record
				final Range[] existingRanges = this.invertedIndex.getValuesForRecord(recordId, Range.class);
				final Range[] remainingRanges = getRemainingRanges(valueArray, existingRanges);

				removeRange(recordId, existingRanges);
				addRange(recordId, remainingRanges);
			} else {
				throw new EvitaInvalidUsageException("Value `" + unknownToString(value) + "` is expected to be Range but it is not!");
			}
		}

		verifyValueArray(value);
		for (Object valueItem : value) {
			removeRecordFromHistogramAndValueIndex(recordId, (T) valueItem);
		}

		if (!isTransactionAvailable()) {
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
		final InvertedIndexSubSet result = new InvertedIndexSubSet(
			getId(),
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
	 * Method creates container for storing filter index from memory to the persistent storage. The dirtiness decision
	 * is delegated to the concrete subclass via {@link #isDirty()} (owner flag vs shared-tree flag).
	 */
	@Nullable
	public StoragePart createStoragePart(int entityIndexPrimaryKey) {
		if (isDirty()) {
			return new FilterIndexStoragePart(
				entityIndexPrimaryKey, this.attributeIndexKey, this.attributeType,
				this.invertedIndex.getValueToRecordBitmap(),
				this.rangeIndex
			);
		} else {
			return null;
		}
	}

	/**
	 * Adds the given ranges to the range index for the specified record ID.
	 *
	 * @param recordId The ID of the record.
	 * @param ranges   The ranges to add.
	 */
	private void addRange(int recordId, @Nonnull Range[] ranges) {
		final Range[] consolidatedRangesToAdd = Range.consolidateRange(ranges);
		for (Range consolidatedRange : consolidatedRangesToAdd) {
			Objects.requireNonNull(this.rangeIndex).addRecord(consolidatedRange.getFrom(), consolidatedRange.getTo(), recordId);
		}
	}

	/**
	 * Removes the specified ranges from the range index for a given record ID.
	 *
	 * @param recordId The ID of the record from which ranges are to be removed.
	 * @param ranges   An array of ranges to be removed.
	 */
	private void removeRange(int recordId, @Nonnull Range[] ranges) {
		final Range[] consolidatedRangesToRemove = Range.consolidateRange(ranges);
		for (Range consolidatedRange : consolidatedRangesToRemove) {
			Objects.requireNonNull(this.rangeIndex).removeRecord(consolidatedRange.getFrom(), consolidatedRange.getTo(), recordId);
		}
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
