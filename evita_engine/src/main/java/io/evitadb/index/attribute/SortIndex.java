/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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
import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.core.Catalog;
import io.evitadb.core.Transaction;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.array.TransactionalObjArray;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.attribute.SortIndexChanges.ValueStartIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.index.transactionalMemory.TransactionalLayerProducer;
import io.evitadb.index.transactionalMemory.TransactionalObjectVersion;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.SortIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.text.Collator;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static io.evitadb.core.Transaction.getTransactionalMemoryLayer;
import static io.evitadb.utils.Assert.isTrue;
import static java.util.Optional.ofNullable;

/**
 * Sort index contains presorted bitmaps/arrays that allows 10x faster sorting result than sorting the records by quicksort
 * on real attribute values.
 *
 * This class is tread safe in transactional environment - it means, that the sort index can be updated
 * by multiple writers and also multiple readers can read from it's original index without spotting the changes made
 * in transactional access. Each transaction is bound to the same thread and different threads doesn't see changes in
 * another threads.
 *
 * If no transaction is opened, changes are applied directly to the delegate data structures. In such case the class is
 * not thread safe for multiple writers!
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@ThreadSafe
public class SortIndex implements SortedRecordsSupplierFactory, TransactionalLayerProducer<SortIndexChanges, SortIndex>, IndexDataStructure, Serializable {
	@Serial private static final long serialVersionUID = 5862170244589598450L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Contains record ids sorted by assigned values. The array is divided in so called record ids block that respects
	 * order in {@link #sortedRecordsValues}. Record ids within the same block are sorted naturally by their integer id.
	 */
	final TransactionalUnorderedIntArray sortedRecords;
	/**
	 * Contains comparable values sorted naturally by their {@link Comparable} characteristics.
	 */
	final TransactionalObjArray<? extends Comparable<?>> sortedRecordsValues;
	/**
	 * Map contains only values with cardinalities greater than one. It is expected that records will have scarce values
	 * with low cardinality so this should save a lot of memory.
	 */
	final TransactionalMap<Comparable<?>, Integer> valueCardinalities;
	/**
	 * Contains type of the attribute.
	 */
	@Nonnull
	@Getter private final Class<? extends Comparable<?>> type;
	/**
	 * Contains locale of the index that affects the sorting algorithm.
	 * May be NULL for non-localized attributes.
	 */
	@Nullable
	@Getter private final Locale locale;
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	private final TransactionalBoolean dirty;
	private final UnaryOperator<Object> normalizer;
	private final Comparator<Object> comparator;
	/**
	 * Temporary data structure that should be NULL and should exist only when {@link Catalog} is in
	 * bulk insertion state where transactions are not used.
	 */
	private SortIndexChanges valueLocations;

	/**
	 * Inverts positions by subtracting from largest value.
	 */
	@Nonnull
	static int[] invert(@Nonnull int[] positions) {
		final int lastPosition = positions.length - 1;
		final int[] inverted = new int[positions.length];
		for (int i = 0; i < positions.length; i++) {
			inverted[i] = lastPosition - positions[i];
		}
		return inverted;
	}

	/**
	 * Verifies that the given attribute type is comparable.
	 */
	private static void assertComparable(@Nonnull Class<?> attributeType) {
		isTrue(
			Comparable.class.isAssignableFrom(attributeType) || attributeType.isPrimitive(),
			"Type `" + attributeType + "` is expected to be Comparable, but it is not!"
		);
	}

	/**
	 * Method creates a comparator that respect the localization specific for {@link String} type.
	 */
	@SuppressWarnings("rawtypes")
	@Nonnull
	private static Comparator createComparatorFor(@Nonnull Locale locale, @Nonnull Class<?> type) {
		return String.class.isAssignableFrom(type) ?
			Optional.ofNullable(locale)
				.map(it -> {
					final Collator collator = Collator.getInstance(it);
					return (Comparator) new LocalizedStringComparator(collator);
				})
				.orElse(Comparator.naturalOrder()) :
			Comparator.naturalOrder();
	}

	/**
	 * In unicode, some characters can be represented in multiple ways. Some has their own character as well as
	 * a combination of other unicode characters that can represent them. When characters can be represented in multiple
	 * ways, sorting them becomes harder. Therefore you should normalize the text before you sort it, or search in it
	 * for that matter. Normalizing the text makes sure that a given string of unicde characters is always represented
	 * in the same way - a way which is search and sort friendly.
	 *
	 * (source: <a href="https://jenkov.com/tutorials/java-internationalization/collator.html">Jenkov.com</a>)
	 */
	@SuppressWarnings("unchecked")
	@Nonnull
	private static <T> UnaryOperator<T> createNormalizerFor(@Nonnull Class<?> type) {
		return String.class.isAssignableFrom(type) ?
			text -> text == null ? null : (T) Normalizer.normalize(String.valueOf(text), Normalizer.Form.NFD)
			: UnaryOperator.identity();
	}

	@SuppressWarnings("unchecked")
	public <T extends Comparable<T>> SortIndex(@Nonnull Class<?> attributeType, @Nullable Locale locale) {
		assertComparable(attributeType);
		this.dirty = new TransactionalBoolean();
		this.type = (Class<? extends Comparable<?>>) attributeType;
		this.locale = locale;
		this.normalizer = createNormalizerFor(attributeType);
		this.comparator = createComparatorFor(locale, attributeType);
		this.sortedRecords = new TransactionalUnorderedIntArray();
		//noinspection rawtypes
		this.sortedRecordsValues = new TransactionalObjArray<>((T[]) Array.newInstance(attributeType, 0), (Comparator)this.comparator);
		this.valueCardinalities = new TransactionalMap<>(new HashMap<>());
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public SortIndex(@Nonnull Class<?> attributeType, @Nullable Locale locale, @Nonnull int[] sortedRecords, @Nonnull Comparable<?>[] sortedRecordValues, @Nonnull Map<Comparable<?>, Integer> cardinalities) {
		assertComparable(attributeType);
		this.dirty = new TransactionalBoolean();
		this.type = (Class<? extends Comparable<?>>) attributeType;
		this.locale = locale;
		this.normalizer = createNormalizerFor(attributeType);
		this.comparator = createComparatorFor(locale, attributeType);
		this.sortedRecords = new TransactionalUnorderedIntArray(sortedRecords);
		this.sortedRecordsValues = new TransactionalObjArray(sortedRecordValues, this.comparator);
		this.valueCardinalities = new TransactionalMap<>(cardinalities);
	}

	/**
	 * Registers new record for passed comparable value. Record id must be present in array only once.
	 */
	@SuppressWarnings("unchecked")
	public <T extends Comparable<T>> void addRecord(@Nonnull Object value, int recordId) {
		final Object normalizedValue = normalizer.apply(value);
		isTrue(
			this.sortedRecords.indexOf(recordId) < 0,
			"Record id `" + recordId + "` is already present in the sort index!"
		);
		final TransactionalObjArray<T> theSortedRecordsValues = (TransactionalObjArray<T>) this.sortedRecordsValues;
		final SortIndexChanges sortIndexChanges = getOrCreateSortIndexChanges();

		// prepare internal datastructures
		sortIndexChanges.prepare();

		// add record id on the computed position
		final int previousRecordId = sortIndexChanges.computePreviousRecord((Comparable<?>) normalizedValue, recordId, comparator);
		this.sortedRecords.add(previousRecordId, recordId);

		// is the value already known?
		final int index = theSortedRecordsValues.indexOf((T) normalizedValue);
		if (index >= 0) {
			// value is already present - just update cardinality
			this.valueCardinalities.compute(
				(T) normalizedValue,
				(it, existingCardinality) ->
					ofNullable(existingCardinality)
						.map(crd -> crd + 1)
						.orElse(2)
			);
			// update help data structure
			sortIndexChanges.valueCardinalityIncreased((Comparable<?>) normalizedValue, comparator);
		} else {
			// insert new value into the sorted value array
			theSortedRecordsValues.add((T) normalizedValue);
			// update help data structure
			sortIndexChanges.valueAdded((Comparable<?>) normalizedValue, comparator);
		}

		this.dirty.setToTrue();
	}

	/**
	 * Unregisters existing record for passed comparable value. Single record must be linked to only single value.
	 *
	 * @throws IllegalArgumentException if value is not linked to passed record id
	 */
	@SuppressWarnings("unchecked")
	public <T extends Comparable<T>> void removeRecord(@Nonnull Object value, int recordId) {
		final Object normalizedValue = normalizer.apply(value);
		final TransactionalObjArray<T> theSortedRecordsValues = (TransactionalObjArray<T>) this.sortedRecordsValues;
		final TransactionalMap<Comparable<?>, Integer> theValueCardinalities = this.valueCardinalities;
		final SortIndexChanges sortIndexChanges = getOrCreateSortIndexChanges();
		final int index = theSortedRecordsValues.indexOf((T) normalizedValue);
		isTrue(
			index >= 0,
			"Value `" + value + "` is not present in the sort index!"
		);
		// prepare internal datastructures
		sortIndexChanges.prepare();

		// remove record id from the array
		this.sortedRecords.remove(recordId);
		// had the value cardinality >= 2?
		final Integer cardinality = theValueCardinalities.get(normalizedValue);
		if (cardinality != null) {
			// update help data structure first
			sortIndexChanges.valueCardinalityDecreased((Comparable<?>) normalizedValue, comparator);
			if (cardinality > 2) {
				// decrease cardinality
				theValueCardinalities.computeIfPresent((T) normalizedValue, (t, crd) -> crd - 1);
			} else if (cardinality == 2) {
				// remove cardinality altogether - cardinality = 1 is not maintained to save memory
				theValueCardinalities.remove(normalizedValue);
			} else {
				throw new EvitaInternalError("Unexpected cardinality: " + cardinality);
			}
		} else {
			// remove the entire value - there is no more record ids for it
			theSortedRecordsValues.remove((T) normalizedValue);
			// update help data structure
			sortIndexChanges.valueRemoved((Comparable<?>) normalizedValue, comparator);
		}

		this.dirty.setToTrue();
	}

	/**
	 * Returns array of sorted record ids according to {@link #getSortedRecordValues()}.
	 * Method is targeted to be used in SERIALIZATION and nowhere else.
	 */
	@Nonnull
	public int[] getSortedRecords() {
		return this.sortedRecords.getArray();
	}

	/**
	 * Returns array of naturally sorted comparable values.
	 * Method is targeted to be used in SERIALIZATION and nowhere else.
	 */
	@Nonnull
	public Comparable<?>[] getSortedRecordValues() {
		return this.sortedRecordsValues.getArray();
	}

	/**
	 * Returns bitmap of all record ids connected with the value in the argument
	 */
	@Nonnull
	public <T extends Comparable<T>> Bitmap getRecordsEqualTo(@Nonnull T value) {
		final Object normalizedValue = normalizer.apply(value);
		@SuppressWarnings("unchecked")
		final TransactionalObjArray<T> theSortedRecordsValues = (TransactionalObjArray<T>) this.sortedRecordsValues;
		@SuppressWarnings("unchecked")
		final int index = theSortedRecordsValues.indexOf((T) normalizedValue);
		isTrue(
			index >= 0,
			"Value `" + value + "` is not present in the sort index!"
		);

		// add record id from the array
		final ValueStartIndex[] valueIndex = getOrCreateSortIndexChanges()
			.getValueIndex(this.sortedRecordsValues, this.valueCardinalities);

		@SuppressWarnings({"rawtypes", "unchecked"})
		final int theValueIndex = ArrayUtils.binarySearch(
			valueIndex, normalizedValue,
			(valueStartIndex, theValue) -> ((Comparable) valueStartIndex.getValue()).compareTo(theValue)
		);

		// had the value cardinality >= 2?
		final Integer cardinality = this.valueCardinalities.get(normalizedValue);
		final int recordIdIndex = valueIndex[theValueIndex].getIndex();
		if (cardinality != null) {
			return new BaseBitmap(
				this.sortedRecords.getSubArray(recordIdIndex, recordIdIndex + cardinality)
			);
		} else {
			return new BaseBitmap(
				this.sortedRecords.get(recordIdIndex)
			);
		}
	}

	/**
	 * Returns true if {@link SortIndex} contains no data.
	 */
	public boolean isEmpty() {
		return sortedRecords.isEmpty();
	}

	/**
	 * Returns number of record ids in this {@link SortIndex}.
	 */
	public int size() {
		return sortedRecords.getLength();
	}

	@Nonnull
	@Override
	public SortedRecordsSupplier getAscendingOrderRecordsSupplier() {
		return getOrCreateSortIndexChanges().getAscendingOrderRecordsSupplier();
	}

	@Nonnull
	@Override
	public SortedRecordsSupplier getDescendingOrderRecordsSupplier() {
		return getOrCreateSortIndexChanges().getDescendingOrderRecordsSupplier();
	}

	/**
	 * Method creates container for storing sort index from memory to the persistent storage.
	 */
	@Nullable
	public StoragePart createStoragePart(int entityIndexPrimaryKey, AttributeKey attribute) {
		if (this.dirty.isTrue()) {
			// all data are persisted to disk - we may get rid of temporary, modification only helper container
			this.valueLocations = null;
			return new SortIndexStoragePart(entityIndexPrimaryKey, attribute, type, getSortedRecords(), getSortedRecordValues(), valueCardinalities);
		} else {
			return null;
		}
	}

	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	/*
		Implementation of TransactionalLayerProducer
	 */

	@Override
	public SortIndexChanges createLayer() {
		return new SortIndexChanges(this);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.dirty.removeLayer(transactionalLayer);
		this.sortedRecords.removeLayer(transactionalLayer);
		this.sortedRecordsValues.removeLayer(transactionalLayer);
		this.valueCardinalities.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public SortIndex createCopyWithMergedTransactionalMemory(@Nullable SortIndexChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Transaction transaction) {
		// we can safely throw away dirty flag now
		transactionalLayer.getStateCopyWithCommittedChanges(this.dirty, transaction);
		return new SortIndex(
			this.type,
			this.locale,
			transactionalLayer.getStateCopyWithCommittedChanges(this.sortedRecords, transaction),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sortedRecordsValues, transaction),
			transactionalLayer.getStateCopyWithCommittedChanges(this.valueCardinalities, transaction)
		);
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Retrieves or creates temporary data structure. When transaction exists it's created in the transactional memory
	 * space so that other threads are not affected by the changes in the {@link SortIndex}.
	 */
	@Nonnull
	private SortIndexChanges getOrCreateSortIndexChanges() {
		final SortIndexChanges layer = getTransactionalMemoryLayer(this);
		if (layer == null) {
			return ofNullable(this.valueLocations).orElseGet(() -> {
				this.valueLocations = new SortIndexChanges(this);
				return this.valueLocations;
			});
		} else {
			return layer;
		}
	}

	/**
	 * Presorted array supplier. Allows really quickly provide information about record id at certain "presorted" position
	 * and relatively quickly (much faster than binary search O(log n)) compute position of record with passed id.
	 */
	public static class SortedRecordsSupplier implements SortedRecordsProvider, Serializable {
		@Serial private static final long serialVersionUID = 6606884166778706442L;
		@Getter private final int[] sortedRecordIds;
		@Getter private final int[] recordPositions;
		@Getter private final Bitmap allRecords;

		public SortedRecordsSupplier(int[] sortedRecordIds, Bitmap allRecords, int[] recordPositions) {
			this.sortedRecordIds = sortedRecordIds;
			this.allRecords = allRecords;
			this.recordPositions = recordPositions;
		}

		@Override
		public int getRecordCount() {
			return sortedRecordIds.length;
		}

	}
}
