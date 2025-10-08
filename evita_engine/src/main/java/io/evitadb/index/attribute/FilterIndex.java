/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.dataType.Range;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.InvertedIndex.MonotonicRowCorruptedException;
import io.evitadb.index.invertedIndex.InvertedIndexSubSet;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexKey;
import io.evitadb.store.spi.model.storageParts.index.FilterIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static io.evitadb.core.Transaction.isTransactionAvailable;
import static io.evitadb.utils.Assert.isTrue;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.StringUtils.unknownToString;
import static java.util.Optional.ofNullable;

/**
 * Filter index maintains information about single filterable attribute - its value to record id relation.
 * It uses several data structures to allow filtration - see fields description.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class FilterIndex implements VoidTransactionMemoryProducer<FilterIndex>, IndexDataStructure, Serializable {
	public static final String ERROR_RANGE_TYPE_NOT_SUPPORTED = "This filter index doesn't handle Range type!";
	public static final Function<Object, Serializable> NO_NORMALIZATION = Serializable.class::cast;
	@Serial private static final long serialVersionUID = -6813305126746774103L;
	public static final Comparator<Comparable> DEFAULT_COMPARATOR = Comparator.naturalOrder();
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Contains key identifying the attribute.
	 */
	@Getter private final AttributeIndexKey attributeIndexKey;
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * Histogram is the main data structure that holds the information about value to record ids relation.
	 */
	@Nonnull @Getter private final InvertedIndex invertedIndex;
	/**
	 * Range index is used only for attribute types that are assignable to {@link Range} and can answer questions like:
	 * <p>
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
	 * Instance of conversion function that converts the value before it's placed into internal index or looked up
	 * in it by {@link Comparator} interface.
	 */
	@Nonnull private final Function<Object, Serializable> normalizer;
	/**
	 * Instance of comparator that should be used for sorting values in this filter index.
	 */
	@Nonnull private final Comparator<? extends Comparable> comparator;
	/**
	 * Value index is auxiliary data structure that allows fast O(1) access to the records of specified value.
	 * It is created on demand on first use.
	 */
	@Nullable private transient Map<Serializable, Integer> valueIndex;
	/**
	 * This field speeds up all requests for all data in this index (which happens quite often). This formula can be
	 * computed anytime by calling `((InvertedIndex) this.histogram).getSortedRecords(null, null)`. Original operation
	 * needs to perform costly join of all internally held bitmaps and that's why we memoize the result.
	 */
	@Nullable private transient Formula memoizedAllRecordsFormula;

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
		} else if (BigDecimal.class.isAssignableFrom(attributeType)) {
			return comparable -> comparable instanceof BigDecimal bigDecimal ? bigDecimal.stripTrailingZeros() : (Serializable) comparable;
		} else if (Currency.class.isAssignableFrom(attributeType)) {
			return comparable -> comparable instanceof Currency currency ? new ComparableCurrency(currency) : (Serializable) comparable;
		} else if (Locale.class.isAssignableFrom(attributeType)) {
			return comparable -> comparable instanceof Locale locale ? new ComparableLocale(locale) : (Serializable) comparable;
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

	public FilterIndex(@Nonnull AttributeIndexKey attributeIndexKey, @Nonnull Class<?> attributeType) {
		this.attributeIndexKey = attributeIndexKey;
		this.attributeType = attributeType;
		this.dirty = new TransactionalBoolean();
		final Class<?> plainType = attributeType.isArray() ? attributeType.getComponentType() : attributeType;
		this.rangeIndex = Range.class.isAssignableFrom(plainType) ? new RangeIndex() : null;
		this.comparator = getComparator(attributeIndexKey, plainType);
		this.normalizer = getNormalizer(plainType);
		this.invertedIndex = new InvertedIndex(this.normalizer, this.comparator);
	}

	public FilterIndex(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull ValueToRecordBitmap[] valueToRecords,
		@Nullable RangeIndex rangeIndex,
		@Nonnull Class<?> attributeType
	) {
		this.attributeIndexKey = attributeIndexKey;
		this.attributeType = attributeType;
		this.dirty = new TransactionalBoolean();
		this.rangeIndex = rangeIndex;
		final Class<?> plainType = attributeType.isArray() ? attributeType.getComponentType() : attributeType;
		this.comparator = getComparator(attributeIndexKey, plainType);
		this.normalizer = getNormalizer(plainType);
		this.invertedIndex = new InvertedIndex(valueToRecords, this.normalizer, this.comparator);
	}

	//	TOBEDONE #538 - remove unnecessary constructor
	public FilterIndex(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull ValueToRecordBitmap[] valueToRecords,
		@Nullable RangeIndex rangeIndex,
		@Nonnull Class<?> attributeType,
		boolean updateSortedValues
	) {
		this.attributeIndexKey = attributeIndexKey;
		this.attributeType = attributeType;
		this.dirty = new TransactionalBoolean();
		this.rangeIndex = rangeIndex;
		final Class<?> plainType = attributeType.isArray() ? attributeType.getComponentType() : attributeType;
		this.comparator = getComparator(attributeIndexKey, plainType);
		this.normalizer = getNormalizer(plainType);
		if (updateSortedValues) {
			if (this.normalizer != NO_NORMALIZATION) {
				for (int i = 0; i < valueToRecords.length; i++) {
					final ValueToRecordBitmap valueToRecord = valueToRecords[i];
					valueToRecords[i] = new ValueToRecordBitmap(this.normalizer.apply(valueToRecord.getValue()), valueToRecord.getRecordIds());
				}
			}
			if (this.comparator != DEFAULT_COMPARATOR) {
				ArrayUtils.sortArray((o1, o2) -> ((Comparator) this.comparator).compare(o1.getValue(), o2.getValue()), valueToRecords);
			}
		}
		InvertedIndex theInvertedIndex;
		try {
			theInvertedIndex = new InvertedIndex(valueToRecords, this.normalizer, this.comparator);
		} catch (MonotonicRowCorruptedException ex) {
			if (this.comparator != DEFAULT_COMPARATOR) {
				ArrayUtils.sortArray((o1, o2) -> ((Comparator) this.comparator).compare(o1.getValue(), o2.getValue()), valueToRecords);
				theInvertedIndex = new InvertedIndex(valueToRecords, this.normalizer, this.comparator);
			} else {
				throw ex;
			}
		}
		this.invertedIndex = theInvertedIndex;
	}

	private FilterIndex(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<?> attributeType,
		@Nonnull InvertedIndex invertedIndex,
		@Nullable RangeIndex rangeIndex,
		@Nonnull Comparator<? extends Comparable> comparator,
		@Nonnull Function<Object, Serializable> normalizer
	) {
		this.attributeIndexKey = attributeIndexKey;
		this.attributeType = attributeType;
		this.dirty = new TransactionalBoolean();
		this.invertedIndex = invertedIndex;
		this.rangeIndex = rangeIndex;
		this.comparator = comparator;
		this.normalizer = normalizer;
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
	 * TOBEDONE JNO naive and slow - use RadixTree
	 */
	@Nonnull
	public Formula getRecordsWhoseValuesStartWith(@Nonnull String prefix) {
		final ValueToRecordBitmap[] buckets = this.invertedIndex.getValueToRecordBitmap();
		final int matchIndex = ArrayUtils.binarySearch(
			buckets,
			prefix,
			(valueToRecordBitmap, textToSearch) -> {
				final String valueA = String.valueOf(valueToRecordBitmap.getValue());
				final String shortenedA = valueA.substring(0, Math.min(valueA.length(), textToSearch.length()));
				return ((Comparator<String>)this.comparator).compare(shortenedA, textToSearch);
			}
		);
		if (matchIndex < 0) {
			return EmptyFormula.INSTANCE;
		} else {
			final LinkedList<Formula> formulas = new LinkedList<>();
			// find all matching values to the end of the bucket list
			for (int i = matchIndex; i < buckets.length; i++) {
				final ValueToRecordBitmap bucket = buckets[i];
				final String value = String.valueOf(bucket.getValue());
				if (value.startsWith(prefix)) {
					formulas.add(new ConstantFormula(bucket.getRecordIds()));
				} else {
					// break immediately when the prefix is no longer valid
					break;
				}
			}
			// find all matching values to the start of the bucket list
			for (int i = matchIndex - 1; i >= 0; i--) {
				final ValueToRecordBitmap bucket = buckets[i];
				final String value = String.valueOf(bucket.getValue());
				if (value.startsWith(prefix)) {
					formulas.add(new ConstantFormula(bucket.getRecordIds()));
				} else {
					// break immediately when the prefix is no longer valid
					break;
				}
			}
			return FormulaFactory.or(formulas.toArray(Formula.EMPTY_FORMULA_ARRAY));
		}
	}

	/**
	 * Returns formula of record ids whose String attribute ends with particular prefix.
	 */
	@Nonnull
	public Formula getRecordsWhoseValuesEndsWith(@Nonnull String suffix) {
		/* TOBEDONE JNO naive and slow - use RadixTree */
		final Formula[] foundRecords = getValueIndex().keySet()
			.stream()
			.map(String.class::cast)
			.filter(it -> it.endsWith(suffix))
			.map(this::getRecordsEqualToFormula)
			.toArray(Formula[]::new);
		return ArrayUtils.isEmpty(foundRecords) ?
			EmptyFormula.INSTANCE : FormulaFactory.or(foundRecords);
	}

	/**
	 * Returns formula of record ids whose String attribute contains particular text.
	 */
	@Nonnull
	public Formula getRecordsWhoseValuesContains(@Nonnull String text) {
		/* TOBEDONE JNO naive and slow - use RadixTree */
		final Formula[] foundRecords = getValueIndex().keySet()
			.stream()
			.map(String.class::cast)
			.filter(it -> it.contains(text))
			.map(this::getRecordsEqualToFormula)
			.toArray(Formula[]::new);
		return ArrayUtils.isEmpty(foundRecords) ?
			EmptyFormula.INSTANCE : FormulaFactory.or(foundRecords);
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
				addRecordToHistogramAndValueIndex(recordId, (T) valueItem);
			}
		} else {
			addRecordToHistogramAndValueIndex(recordId, (T) value);
		}

		if (!isTransactionAvailable()) {
			this.memoizedAllRecordsFormula = null;
		}
		this.dirty.setToTrue();
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
			addRecordToHistogramAndValueIndex(recordId, (T) valueItem);
		}

		if (!isTransactionAvailable()) {
			this.memoizedAllRecordsFormula = null;
		}
		this.dirty.setToTrue();
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
		}
		this.dirty.setToTrue();
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
		}
		this.dirty.setToTrue();
	}

	/**
	 * Returns true if filter index contains no records.
	 */
	public boolean isEmpty() {
		return this.invertedIndex.isEmpty();
	}

	/**
	 * Returns bitmap of all record ids connected with the value in the argument
	 */
	@Nonnull
	public <T> Bitmap getRecordsEqualTo(@Nonnull T attributeValue) {
		return ofNullable(getValueIndex().get(this.normalizer.apply(attributeValue)))
			.map(this.invertedIndex::getRecordsAtIndex)
			.orElse(EmptyBitmap.INSTANCE);
	}

	/**
	 * Returns bitmap of all record ids connected with the value in the argument
	 */
	@Nonnull
	public <T> Formula getRecordsEqualToFormula(@Nonnull T attributeValue) {
		return ofNullable(getValueIndex().get(this.normalizer.apply(attributeValue)))
			.map(it -> (Formula) new ConstantFormula(this.invertedIndex.getRecordsAtIndex(it)))
			.orElse(EmptyFormula.INSTANCE);
	}

	/**
	 * Returns all records present in filter index in the form of {@link InvertedIndexSubSet}.
	 */
	public InvertedIndexSubSet getHistogramOfAllRecords() {
		return this.invertedIndex.getSortedRecords(null, null);
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
	 */
	public Formula getAllRecordsFormula() {
		// if there is transaction open, there might be changes in the histogram data, and we can't easily use cache
		if (isTransactionAvailable() && this.dirty.isTrue()) {
			return getHistogramOfAllRecords().getFormula();
		} else {
			if (this.memoizedAllRecordsFormula == null) {
				this.memoizedAllRecordsFormula = getHistogramOfAllRecords().getFormula();
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
	 * Returns all records which range overlaps the passed range in the form of {@link Bitmap}.
	 * This method can be used only when the attribute type is of the {@link Range} type.
	 *
	 * @param from * @param to
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
	 * Method creates container for storing filter index from memory to the persistent storage.
	 */
	@Nullable
	public StoragePart createStoragePart(int entityIndexPrimaryKey) {
		if (this.dirty.isTrue()) {
			return new FilterIndexStoragePart(
				entityIndexPrimaryKey, this.attributeIndexKey, this.attributeType,
				this.invertedIndex.getValueToRecordBitmap(),
				this.rangeIndex
			);
		} else {
			return null;
		}
	}

	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	@Nonnull
	@Override
	public FilterIndex createCopyWithMergedTransactionalMemory(@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// we can safely throw away dirty flag now
		transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		return new FilterIndex(
			this.attributeIndexKey,
			this.attributeType,
			transactionalLayer.getStateCopyWithCommittedChanges(this.invertedIndex),
			this.rangeIndex == null ? null : transactionalLayer.getStateCopyWithCommittedChanges(this.rangeIndex),
			this.comparator,
			this.normalizer
		);
	}

	/*
		TransactionalLayerProducer implementation
	 */

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.invertedIndex.removeLayer(transactionalLayer);
		ofNullable(this.rangeIndex).ifPresent(it -> it.removeLayer(transactionalLayer));
		this.dirty.removeLayer(transactionalLayer);
	}

	/*
		PRIVATE METHODS
	 */

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

	@Nonnull
	private Map<Serializable, Integer> getValueIndex() {
		if (this.valueIndex == null) {
			final Map<Serializable, Integer> bucketValueToPositionIndex = createHashMap(this.invertedIndex.getBucketCount());
			final ValueToRecordBitmap[] buckets = this.invertedIndex.getValueToRecordBitmap();
			for (int i = 0; i < buckets.length; i++) {
				final ValueToRecordBitmap bucket = buckets[i];
				bucketValueToPositionIndex.put(bucket.getValue(), i);
			}
			this.valueIndex = bucketValueToPositionIndex;
		}
		return this.valueIndex;
	}

	private <T extends Serializable> void addRecordToHistogramAndValueIndex(int recordId, @Nonnull T value) {
		this.invertedIndex.addRecord(value, recordId);
		this.valueIndex = null;
	}

	private <T extends Serializable> void removeRecordFromHistogramAndValueIndex(int recordId, @Nonnull T value) {
		final int removalIndex = this.invertedIndex.removeRecord(value, recordId);
		isTrue(removalIndex >= 0, "Sanity check - record not found!");
		this.valueIndex = null;
	}

}
