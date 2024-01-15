/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.attribute;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.dataType.Range;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.InvertedIndexSubSet;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.index.transactionalMemory.TransactionalObjectVersion;
import io.evitadb.index.transactionalMemory.VoidTransactionMemoryProducer;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.FilterIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.BitSet;
import java.util.Collection;
import java.util.Map;

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
	@Serial private static final long serialVersionUID = -6813305126746774103L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Contains key identifying the attribute.
	 */
	@Getter private final AttributeKey attributeKey;
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * Histogram is the main data structure that holds the information about value to record ids relation.
	 */
	@Nonnull @Getter private final InvertedIndex<? extends Comparable<?>> invertedIndex;
	/**
	 * Range index is used only for attribute types that are assignable to {@link Range} and can answer questions like:
	 * <p>
	 * - what records are valid at precise moment
	 * - what records are valid until certain moment
	 * - what records are valid after certain moment
	 */
	@Nullable @Getter private final RangeIndex rangeIndex;
	/**
	 * Value index is auxiliary data structure that allows fast O(1) access to the records of specified value.
	 * It is created on demand on first use.
	 */
	@Nullable private transient Map<? extends Comparable<?>, Integer> valueIndex;
	/**
	 * This field speeds up all requests for all data in this index (which happens quite often). This formula can be
	 * computed anytime by calling `((InvertedIndex) this.histogram).getSortedRecords(null, null)`. Original operation
	 * needs to perform costly join of all internally held bitmaps and that's why we memoize the result.
	 */
	@Nullable private transient Formula memoizedAllRecordsFormula;

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

	private static Comparable verifyValue(@Nonnull Object value) {
		isTrue(value instanceof Serializable, "Value `" + unknownToString(value) + "` is expected to be Serializable, but it is not!");
		if (value instanceof Comparable) {
			return (Comparable) value;
		} else {
			return value.toString();
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

	public FilterIndex(@Nonnull AttributeKey attributeKey, @Nonnull Class<?> attributeType) {
		this.attributeKey = attributeKey;
		this.dirty = new TransactionalBoolean();
		this.invertedIndex = new InvertedIndex<>();
		this.rangeIndex = Range.class.isAssignableFrom(attributeType) ? new RangeIndex() : null;
	}

	public <T extends Comparable<T>> FilterIndex(@Nonnull AttributeKey attributeKey, @Nonnull InvertedIndex<T> invertedIndex, @Nullable RangeIndex rangeIndex) {
		this.attributeKey = attributeKey;
		this.dirty = new TransactionalBoolean();
		this.invertedIndex = invertedIndex;
		this.rangeIndex = rangeIndex;
	}

	/**
	 * Returns count of records in this index.
	 */
	public int size() {
		return invertedIndex.getLength();
	}

	/**
	 * Returns true if this {@link FilterIndex} instance contains range index.
	 */
	public boolean hasRangeIndex() {
		return this.rangeIndex != null;
	}

	/**
	 * Returns sorted (ascending, natural) collection of all distinct values present in the filter index.
	 */
	@Nonnull
	public <T extends Comparable<T>> Collection<T> getValues() {
		//noinspection unchecked
		return (Collection<T>) getValueIndex().keySet();
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
	public <T extends Comparable<T>> void addRecord(int recordId, @Nonnull Object value) throws EvitaInvalidUsageException {
		// if current attribute is Range based assign record also to range index
		if (rangeIndex != null) {
			if (value instanceof Range[] valueArray) {
				addRange(recordId, valueArray);
			} else {
				isTrue(
					value instanceof Range,
					() -> new EvitaInvalidUsageException("Value `" + unknownToString(value) + "` is expected to be Range but it is not!"));
				final Range range = (Range) value;
				rangeIndex.addRecord(range.getFrom(), range.getTo(), recordId);
			}
		}

		if (value instanceof final Object[] valueArray) {
			for (Object valueItem : verifyValueArray(valueArray)) {
				addRecordToHistogramAndValueIndex(recordId, (T) valueItem);
			}
		} else {
			addRecordToHistogramAndValueIndex(recordId, (T) verifyValue(value));
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
	public <T extends Comparable<T>> void addRecordDelta(int recordId, @Nonnull Object[] value) throws EvitaInvalidUsageException {
		// if current attribute is Range based assign record also to range index
		if (rangeIndex != null) {
			if (value instanceof Range[] valueArray) {
				// this is quite expensive operation, but we need to do it to be able to remove and add the record
				@SuppressWarnings("SuspiciousArrayCast") final Range[] existingRanges = (Range[]) ((InvertedIndex) this.invertedIndex).getValuesForRecord(recordId, Range.class);
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
	public <T extends Comparable<T>> void removeRecord(int recordId, @Nonnull Object value) throws EvitaInvalidUsageException {
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
			verifyValueArray(value);
			for (Object valueItem : valueArray) {
				removeRecordFromHistogramAndValueIndex(recordId, (T) valueItem);
			}
		} else {
			verifyValue(value);
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
	public <T extends Comparable<T>> void removeRecordDelta(int recordId, @Nonnull Object[] value) {
		// if current attribute is Range based assign record also to range index
		if (this.rangeIndex != null) {
			if (value instanceof Range[] valueArray) {
				// this is quite expensive operation, but we need to do it to be able to remove and add the record
				@SuppressWarnings("SuspiciousArrayCast") final Range[] existingRanges = (Range[]) ((InvertedIndex) this.invertedIndex).getValuesForRecord(recordId, Range.class);
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
	public <T extends Comparable<T>> Bitmap getRecordsEqualTo(@Nonnull T attributeValue) {
		return ofNullable(getValueIndex().get(attributeValue))
			.map(invertedIndex::getRecordsAtIndex)
			.orElse(EmptyBitmap.INSTANCE);
	}

	/**
	 * Returns bitmap of all record ids connected with the value in the argument
	 */
	@Nonnull
	public <T extends Comparable<T>> Formula getRecordsEqualToFormula(@Nonnull T attributeValue) {
		return ofNullable(getValueIndex().get(attributeValue))
			.map(it -> (Formula) new ConstantFormula(invertedIndex.getRecordsAtIndex(it)))
			.orElse(EmptyFormula.INSTANCE);
	}

	/**
	 * Returns all records present in filter index in the form of {@link InvertedIndexSubSet}.
	 */
	public <T extends Comparable<T>> InvertedIndexSubSet<T> getHistogramOfAllRecords() {
		return ((InvertedIndex) this.invertedIndex).getSortedRecords(null, null);
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
	public <T extends Comparable<T>> InvertedIndexSubSet<T> getHistogramOfRecordsLesserThanEq(@Nonnull T comparable) {
		return ((InvertedIndex) this.invertedIndex).getSortedRecords(null, comparable);
	}

	/**
	 * Returns all records lesser than or equals attribute value passed in the argument in the form of {@link Bitmap}.
	 */
	@Nonnull
	public <T extends Comparable<T>> Bitmap getRecordsLesserThanEq(@Nonnull T comparable) {
		return getRecordsLesserThanEqFormula(comparable).compute();
	}

	/**
	 * Returns all records lesser than or equals attribute value passed in the argument in the form of {@link Bitmap}.
	 */
	public <T extends Comparable<T>> Formula getRecordsLesserThanEqFormula(@Nonnull T comparable) {
		return getHistogramOfRecordsLesserThanEq(comparable).getFormula();
	}

	/**
	 * Returns all records greater than or equals attribute value passed in the argument in the form of {@link InvertedIndexSubSet}.
	 */
	public <T extends Comparable<T>> InvertedIndexSubSet<T> getHistogramOfRecordsGreaterThanEq(@Nonnull T comparable) {
		return ((InvertedIndex) this.invertedIndex).getSortedRecords(comparable, null);
	}

	/**
	 * Returns all records greater than or equals attribute value passed in the argument in the form of {@link Bitmap}.
	 */
	@Nonnull
	public <T extends Comparable<T>> Bitmap getRecordsGreaterThanEq(@Nonnull T comparable) {
		return getRecordsGreaterThanEqFormula(comparable).compute();
	}

	/**
	 * Returns all records greater than or equals attribute value passed in the argument in the form of {@link AbstractFormula}.
	 */
	public <T extends Comparable<T>> Formula getRecordsGreaterThanEqFormula(@Nonnull T comparable) {
		return getHistogramOfRecordsGreaterThanEq(comparable).getFormula();
	}

	/**
	 * Returns all records lesser than attribute value passed in the argument in the form of {@link InvertedIndexSubSet}.
	 */
	public <T extends Comparable<T>> InvertedIndexSubSet<T> getHistogramOfRecordsLesserThan(@Nonnull T comparable) {
		return ((InvertedIndex) this.invertedIndex).getSortedRecordsExclusive(null, comparable);
	}

	/**
	 * Returns all records lesser than attribute value passed in the argument in the form of {@link Bitmap}.
	 */
	@Nonnull
	public <T extends Comparable<T>> Bitmap getRecordsLesserThan(@Nonnull T comparable) {
		return getRecordsLesserThanFormula(comparable).compute();
	}

	/**
	 * Returns all records lesser than attribute value passed in the argument in the form of {@link AbstractFormula}.
	 */
	public <T extends Comparable<T>> Formula getRecordsLesserThanFormula(@Nonnull T comparable) {
		return getHistogramOfRecordsLesserThan(comparable).getFormula();
	}

	/**
	 * Returns all records greater than attribute value passed in the argument in the form of {@link InvertedIndexSubSet}.
	 */
	public <T extends Comparable<T>> InvertedIndexSubSet<T> getHistogramOfRecordsGreaterThan(@Nonnull T comparable) {
		return ((InvertedIndex) this.invertedIndex).getSortedRecordsExclusive(comparable, null);
	}

	/**
	 * Returns all records greater than attribute value passed in the argument in the form of {@link Bitmap}.
	 */
	@Nonnull
	public <T extends Comparable<T>> Bitmap getRecordsGreaterThan(@Nonnull T comparable) {
		return getRecordsGreaterThanFormula(comparable).compute();
	}

	/**
	 * Returns all records greater than attribute value passed in the argument in the form of {@link AbstractFormula}.
	 */
	public <T extends Comparable<T>> Formula getRecordsGreaterThanFormula(@Nonnull T comparable) {
		return getHistogramOfRecordsGreaterThan(comparable).getFormula();
	}

	/**
	 * Returns all records with attribute values between `from` and `to` (inclusive) passed in the argument
	 * in the form of {@link InvertedIndexSubSet}.
	 */
	public <T extends Comparable<T>> InvertedIndexSubSet<T> getHistogramOfRecordsBetween(@Nonnull T from, @Nonnull T to) {
		return ((InvertedIndex) this.invertedIndex).getSortedRecords(from, to);
	}

	/**
	 * Returns all records with attribute values between `from` and `to` (inclusive) passed in the argument
	 * in the form of {@link Bitmap}.
	 */
	@Nonnull
	public <T extends Comparable<T>> Bitmap getRecordsBetween(@Nonnull T from, @Nonnull T to) {
		return getRecordsBetweenFormula(from, to).compute();
	}

	/**
	 * Returns all records with attribute values between `from` and `to` (inclusive) passed in the argument
	 * in the form of {@link AbstractFormula}.
	 */
	public <T extends Comparable<T>> Formula getRecordsBetweenFormula(@Nonnull T from, @Nonnull T to) {
		return getHistogramOfRecordsBetween(from, to).getFormula();
	}

	/**
	 * Returns all records valid at the moment in the form of {@link Bitmap}.
	 * This method can be used only when the attribute type is of the {@link Range} type.
	 */
	@Nonnull
	public Bitmap getRecordsValidIn(long thePoint) {
		Assert.notNull(this.rangeIndex, ERROR_RANGE_TYPE_NOT_SUPPORTED);
		return getRecordsValidInFormula(thePoint).compute();
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
		return getRecordsOverlappingFormula(from, to).compute();
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
			return new FilterIndexStoragePart(entityIndexPrimaryKey, attributeKey, invertedIndex, rangeIndex);
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
			this.attributeKey,
			transactionalLayer.getStateCopyWithCommittedChanges(this.invertedIndex),
			this.rangeIndex == null ? null : transactionalLayer.getStateCopyWithCommittedChanges(this.rangeIndex)
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
			this.rangeIndex.addRecord(consolidatedRange.getFrom(), consolidatedRange.getTo(), recordId);
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
			this.rangeIndex.removeRecord(consolidatedRange.getFrom(), consolidatedRange.getTo(), recordId);
		}
	}

	@Nonnull
	private Map<? extends Comparable<?>, Integer> getValueIndex() {
		if (valueIndex == null) {
			final Map<Comparable<?>, Integer> bucketValueToPositionIndex = createHashMap(invertedIndex.getBucketCount());
			final ValueToRecordBitmap<? extends Comparable<?>>[] buckets = invertedIndex.getValueToRecordBitmap();
			for (int i = 0; i < buckets.length; i++) {
				final ValueToRecordBitmap<? extends Comparable<?>> bucket = buckets[i];
				bucketValueToPositionIndex.put(bucket.getValue(), i);
			}
			this.valueIndex = bucketValueToPositionIndex;
		}
		return valueIndex;
	}

	private <T extends Comparable<T>> void addRecordToHistogramAndValueIndex(int recordId, @Nonnull T value) {
		((InvertedIndex) this.invertedIndex).addRecord(value, recordId);
		this.valueIndex = null;
	}

	private <T extends Comparable<T>> void removeRecordFromHistogramAndValueIndex(int recordId, @Nonnull T value) {
		final int removalIndex = ((InvertedIndex) this.invertedIndex).removeRecord(value, recordId);
		isTrue(removalIndex >= 0, "Sanity check - record not found!");
		this.valueIndex = null;
	}

}
