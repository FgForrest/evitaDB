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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.comparator.NullsFirstComparatorWrapper;
import io.evitadb.comparator.NullsLastComparatorWrapper;
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
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.text.Collator;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * The array contains the descriptor allowing to create {@link #normalizer} and {@link #comparator} instances.
	 */
	@Nonnull
	final ComparatorSource[] comparatorBase;
	/**
	 * Contains key identifying the attribute.
	 */
	@Getter private final AttributeKey attributeKey;
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	private final TransactionalBoolean dirty;
	/**
	 * In unicode, some characters can be represented in multiple ways. Some has their own character as well as
	 * a combination of other unicode characters that can represent them. When characters can be represented in multiple
	 * ways, sorting them becomes harder. Therefore you should normalize the text before you sort it, or search in it
	 * for that matter. Normalizing the text makes sure that a given string of unicde characters is always represented
	 * in the same way - a way which is search and sort friendly.
	 *
	 * (source: <a href="https://jenkov.com/tutorials/java-internationalization/collator.html">Jenkov.com</a>)
	 */
	private final UnaryOperator<Object> normalizer;
	/**
	 * Comparator is used to execute insertion sort on the sorted records values.
	 */
	private final Comparator<?> comparator;
	/**
	 * Temporary data structure that should be NULL and should exist only when {@link Catalog} is in
	 * bulk insertion or read only state where transactions are not used.
	 */
	private SortIndexChanges sortIndexChanges;

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
	 * Method creates a comparator that compares {@link ComparableArray} respecting the comparator base requirements
	 * and the localization specific for {@link String} type.
	 *
	 * @param locale locale to use for sorting
	 * @param comparatorBase the descriptor that needs to be respected by the comparator
	 * @return the comparator that respects the given descriptor
	 */
	@SuppressWarnings("rawtypes")
	@Nonnull
	private static Comparator<?> createCombinedComparatorFor(@Nonnull Locale locale, @Nonnull ComparatorSource[] comparatorBase) {
		final Comparator[] result = new Comparator[comparatorBase.length];
		for (int i = 0; i < comparatorBase.length; i++) {
			final ComparatorSource comparatorSource = comparatorBase[i];
			final Comparator theComparator = createComparatorFor(locale, comparatorSource);
			result[i] = theComparator;
		}
		return new ComparableArrayComparator(result);
	}

	/**
	 * Method creates a comparator that respect the localization specific for {@link String} type.
	 * @param locale locale to use for sorting
	 * @param comparatorSource the descriptor that needs to be respected by the comparator
	 * @return the comparator that respects the given descriptor
	 */
	@SuppressWarnings("rawtypes")
	@Nonnull
	private static Comparator createComparatorFor(@Nullable Locale locale, @Nonnull ComparatorSource comparatorSource) {
		final Comparator nextComparator = String.class.isAssignableFrom(comparatorSource.type()) ?
			ofNullable(locale)
				.map(it -> {
					final Collator collator = Collator.getInstance(it);
					return (Comparator) new LocalizedStringComparator(collator);
				})
				.orElse(Comparator.naturalOrder()) :
			Comparator.naturalOrder();

		final Comparator theComparator;
		if (comparatorSource.orderBehaviour() == OrderBehaviour.NULLS_LAST) {
			//noinspection unchecked
			theComparator = new NullsLastComparatorWrapper(
				comparatorSource.orderDirection() == OrderDirection.ASC ?
					nextComparator : nextComparator.reversed()
			);
		} else {
			//noinspection unchecked
			theComparator = new NullsFirstComparatorWrapper(
				comparatorSource.orderDirection() == OrderDirection.ASC ?
					nextComparator : nextComparator.reversed()
			);
		}
		return theComparator;
	}

	/**
	 * Creates a normalizer if any part of the comparator base is of type {@link String}.
	 *
	 * @see #normalizer
	 */
	@Nonnull
	private static <T> UnaryOperator<T> createNormalizerFor(@Nonnull ComparatorSource[] comparatorBase) {
		@SuppressWarnings("unchecked")
		final UnaryOperator<T>[] normalizers = new UnaryOperator[comparatorBase.length];
		boolean atLeastOneStringFound = false;
		for (int i = 0; i < comparatorBase.length; i++) {
			if (String.class.isAssignableFrom(comparatorBase[i].type())) {
				atLeastOneStringFound = true;
				normalizers[i] = createStringNormalizer(comparatorBase[i]);
			}
		}
		return atLeastOneStringFound ?
			new ComparableArrayNormalizer<>(normalizers) : UnaryOperator.identity();
	}

	/**
	 * Creates a normalizer if any part of the comparator base is of type {@link String}.
	 *
	 * @see #normalizer
	 */
	@SuppressWarnings("unchecked")
	@Nonnull
	private static <T> UnaryOperator<T> createStringNormalizer(@Nonnull ComparatorSource comparatorBase) {
		if (String.class.isAssignableFrom(comparatorBase.type())) {
			return text -> text == null ? null : (T) Normalizer.normalize(String.valueOf(text), Normalizer.Form.NFD);
		} else {
			return UnaryOperator.identity();
		}
	}

	@SuppressWarnings("unchecked")
	public <T extends Comparable<T>> SortIndex(
		@Nonnull Class<?> attributeType,
		@Nonnull AttributeKey attributeKey
	) {
		assertComparable(attributeType);
		this.dirty = new TransactionalBoolean();
		this.comparatorBase = new ComparatorSource[] {
			new ComparatorSource(
				attributeType,
				OrderDirection.ASC,
				OrderBehaviour.NULLS_LAST
			)
		};
		this.attributeKey = attributeKey;
		this.normalizer = createStringNormalizer(this.comparatorBase[0]);
		this.comparator = createComparatorFor(this.attributeKey.locale(), this.comparatorBase[0]);
		this.sortedRecords = new TransactionalUnorderedIntArray();
		//noinspection rawtypes
		this.sortedRecordsValues = new TransactionalObjArray<>((T[]) Array.newInstance(attributeType, 0), (Comparator) this.comparator);
		this.valueCardinalities = new TransactionalMap<>(new HashMap<>());
	}

	@SuppressWarnings("unchecked")
	public SortIndex(
		@Nonnull ComparatorSource[] comparatorSources,
		@Nonnull AttributeKey attributeKey
	) {
		isTrue(
			comparatorSources.length > 1,
			"At least two comparators are required to create a SortIndex by this constructor!"
		);
		this.dirty = new TransactionalBoolean();
		this.comparatorBase = comparatorSources;
		this.attributeKey = attributeKey;
		this.normalizer = createNormalizerFor(this.comparatorBase);
		this.comparator = createCombinedComparatorFor(this.attributeKey.locale(), this.comparatorBase);
		this.sortedRecords = new TransactionalUnorderedIntArray();
		//noinspection rawtypes
		this.sortedRecordsValues = new TransactionalObjArray<>(new ComparableArray[0], (Comparator) this.comparator);
		this.valueCardinalities = new TransactionalMap<>(new HashMap<>());
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public SortIndex(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nonnull AttributeKey attributeKey,
		@Nonnull int[] sortedRecords,
		@Nonnull Comparable<?>[] sortedRecordValues,
		@Nonnull Map<Comparable<?>, Integer> cardinalities
	) {
		this.dirty = new TransactionalBoolean();
		this.comparatorBase = comparatorBase;
		for (ComparatorSource comparatorSource : comparatorBase) {
			assertComparable(comparatorSource.type());
		}
		this.attributeKey = attributeKey;
		if (this.comparatorBase.length == 1) {
			this.normalizer = createStringNormalizer(this.comparatorBase[0]);
			this.comparator = createComparatorFor(this.attributeKey.locale(), this.comparatorBase[0]);
		} else {
			this.normalizer = createNormalizerFor(this.comparatorBase);
			this.comparator = createCombinedComparatorFor(this.attributeKey.locale(), this.comparatorBase);
		}
		this.sortedRecords = new TransactionalUnorderedIntArray(sortedRecords);
		this.sortedRecordsValues = new TransactionalObjArray(sortedRecordValues, this.comparator);
		this.valueCardinalities = new TransactionalMap<>(cardinalities);
	}

	/**
	 * Registers new record for passed comparable value. Record id must be present in array only once.
	 */
	public void addRecord(@Nonnull Object[] value, int recordId) {
		final Object[] normalizedValue = (Object[]) normalizer.apply(value);
		isTrue(
			this.sortedRecords.indexOf(recordId) < 0,
			"Record id `" + recordId + "` is already present in the sort index!"
		);
		addRecordInternal(new ComparableArray(this.comparatorBase, normalizedValue), recordId);
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
		isTrue(
			!value.getClass().isArray(),
			"Value must not be an array!"
		);
		isTrue(
			this.comparatorBase[0].type().isInstance(value),
			"Value must be of type `" + this.comparatorBase[0].type().getName() + "`!"
		);
		addRecordInternal((T) normalizedValue, recordId);
	}

	/**
	 * Shared internal implementation of the record insertion.
	 */
	private <T extends Comparable<T>> void addRecordInternal(@Nonnull T normalizedValue, int recordId) {
		@SuppressWarnings("unchecked")
		final TransactionalObjArray<T> theSortedRecordsValues = (TransactionalObjArray<T>) this.sortedRecordsValues;
		final SortIndexChanges sortIndexChanges = getOrCreateSortIndexChanges();

		// prepare internal datastructures
		sortIndexChanges.prepare();

		// add record id on the computed position
		final int previousRecordId = sortIndexChanges.computePreviousRecord(normalizedValue, recordId, comparator);
		this.sortedRecords.add(previousRecordId, recordId);

		// is the value already known?
		final int index = theSortedRecordsValues.indexOf(normalizedValue);
		if (index >= 0) {
			// value is already present - just update cardinality
			this.valueCardinalities.compute(
				normalizedValue,
				(it, existingCardinality) ->
					ofNullable(existingCardinality)
						.map(crd -> crd + 1)
						.orElse(2)
			);
			// update help data structure
			sortIndexChanges.valueCardinalityIncreased(normalizedValue, comparator);
		} else {
			// insert new value into the sorted value array
			theSortedRecordsValues.add(normalizedValue);
			// update help data structure
			sortIndexChanges.valueAdded(normalizedValue, comparator);
		}

		this.dirty.setToTrue();
	}

	/**
	 * Unregisters existing record for passed comparable value. Single record must be linked to only single value.
	 *
	 * @throws IllegalArgumentException if value is not linked to passed record id
	 */
	public void removeRecord(@Nonnull Object[] value, int recordId) {
		final Object[] normalizedValue = (Object[]) normalizer.apply(value);
		final ComparableArray normalizedValueArray = new ComparableArray(this.comparatorBase, normalizedValue);
		@SuppressWarnings("unchecked")
		final TransactionalObjArray<ComparableArray> theSortedRecordsValues = (TransactionalObjArray<ComparableArray>) this.sortedRecordsValues;
		final SortIndexChanges sortIndexChanges = getOrCreateSortIndexChanges();
		final int index = theSortedRecordsValues.indexOf(normalizedValueArray);
		isTrue(
			index >= 0,
			"Value `" + Arrays.toString(value) + "` is not present in the sort index!"
		);
		removeRecordInternal(normalizedValueArray, recordId, theSortedRecordsValues, this.valueCardinalities, sortIndexChanges);
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
		final SortIndexChanges sortIndexChanges = getOrCreateSortIndexChanges();
		final int index = theSortedRecordsValues.indexOf((T) normalizedValue);
		isTrue(
			index >= 0,
			"Value `" + value + "` is not present in the sort index!"
		);
		removeRecordInternal((T) normalizedValue, recordId, theSortedRecordsValues, this.valueCardinalities, sortIndexChanges);
	}

	/**
	 * Shared internal implementation of the record removal.
	 */
	private <T extends Comparable<T>> void removeRecordInternal(
		@Nonnull T normalizedValue,
		int recordId,
		@Nonnull TransactionalObjArray<T> theSortedRecordsValues,
		@Nonnull TransactionalMap<Comparable<?>, Integer> theValueCardinalities,
		@Nonnull SortIndexChanges sortIndexChanges
	) {
		// prepare internal datastructures
		sortIndexChanges.prepare();

		// remove record id from the array
		this.sortedRecords.remove(recordId);
		// had the value cardinality >= 2?
		final Integer cardinality = theValueCardinalities.get(normalizedValue);
		if (cardinality != null) {
			// update help data structure first
			sortIndexChanges.valueCardinalityDecreased(normalizedValue, comparator);
			if (cardinality > 2) {
				// decrease cardinality
				theValueCardinalities.computeIfPresent(normalizedValue, (t, crd) -> crd - 1);
			} else if (cardinality == 2) {
				// remove cardinality altogether - cardinality = 1 is not maintained to save memory
				theValueCardinalities.remove(normalizedValue);
			} else {
				throw new EvitaInternalError("Unexpected cardinality: " + cardinality);
			}
		} else {
			// remove the entire value - there is no more record ids for it
			theSortedRecordsValues.remove(normalizedValue);
			// update help data structure
			sortIndexChanges.valueRemoved(normalizedValue, comparator);
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
		@SuppressWarnings("unchecked") final TransactionalObjArray<T> theSortedRecordsValues = (TransactionalObjArray<T>) this.sortedRecordsValues;
		@SuppressWarnings("unchecked") final int index = theSortedRecordsValues.indexOf((T) normalizedValue);
		isTrue(
			index >= 0,
			"Value `" + value + "` is not present in the sort index!"
		);

		//noinspection unchecked
		return getRecordsEqualToInternal((T) normalizedValue);
	}

	/**
	 * Returns bitmap of all record ids connected with the value in the argument
	 */
	@Nonnull
	public Bitmap getRecordsEqualTo(@Nonnull Object[] value) {
		final ComparableArray normalizedValue = new ComparableArray(this.comparatorBase, (Object[]) normalizer.apply(value));
		@SuppressWarnings("unchecked") final TransactionalObjArray<ComparableArray> theSortedRecordsValues = (TransactionalObjArray<ComparableArray>) this.sortedRecordsValues;
		final int index = theSortedRecordsValues.indexOf(normalizedValue);
		isTrue(
			index >= 0,
			"Value `" + Arrays.toString(value) + "` is not present in the sort index!"
		);

		return getRecordsEqualToInternal(normalizedValue);
	}

	/**
	 * Returns bitmap of all record ids connected with the value in the argument.
	 */
	@Nonnull
	private <T extends Comparable<T>> BaseBitmap getRecordsEqualToInternal(T normalizedValue) {
		// add record id from the array
		final ValueStartIndex[] valueIndex = getOrCreateSortIndexChanges()
			.getValueIndex(this.sortedRecordsValues, this.valueCardinalities);

		@SuppressWarnings({"rawtypes", "unchecked"}) final int theValueIndex = ArrayUtils.binarySearch(
			valueIndex, normalizedValue,
			(valueStartIndex, theValue) -> ((Comparator)comparator).compare(valueStartIndex.getValue(), theValue)
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
	public StoragePart createStoragePart(int entityIndexPrimaryKey) {
		if (this.dirty.isTrue()) {
			// all data are persisted to disk - we may get rid of temporary, modification only helper container
			this.sortIndexChanges = null;
			return new SortIndexStoragePart(
				entityIndexPrimaryKey, attributeKey, comparatorBase,
				getSortedRecords(), getSortedRecordValues(), valueCardinalities
			);
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
			this.comparatorBase,
			this.attributeKey,
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
			return ofNullable(this.sortIndexChanges).orElseGet(() -> {
				this.sortIndexChanges = new SortIndexChanges(this);
				return this.sortIndexChanges;
			});
		} else {
			return layer;
		}
	}

	/**
	 * Description of the single attribute element sorting properties. If the sort index is created for the single
	 * attribute, then the {@link ComparatorSource} is created for the attribute type. If the sort index is created
	 * for the multiple attributes, then the {@link ComparatorSource} is created for each of
	 * the {@link SortableAttributeCompoundSchemaContract#getAttributeElements()} element.
	 *
	 * @param type           contains type of the attribute
	 * @param orderDirection contains the direction of sorted values
	 * @param orderBehaviour contains instruction for sorting NULL values
	 */
	@SuppressWarnings("rawtypes")
	public record ComparatorSource(
		@Nonnull Class type,
		@Nonnull OrderDirection orderDirection,
		@Nonnull OrderBehaviour orderBehaviour

	) implements Serializable {

		public ComparatorSource {
			assertComparable(type);
		}

	}

	/**
	 * The record wraps the array of {@link Comparable} values into an {@link Comparable} object so that it could
	 * be sorted using {@link ComparableArrayComparator} instance.
	 *
	 * @param array object array to be wrapped
	 */
	public record ComparableArray(
		@Nonnull Comparable<?>[] array
	) implements Comparable<ComparableArray> {

		public ComparableArray(@Nonnull ComparatorSource[] comparatorBase, @Nonnull Object[] value) {
			this(
				Arrays.stream(value)
					.map(v -> (Comparable<?>)v)
					.toArray(Comparable[]::new)
			);
			for (int i = 0; i < comparatorBase.length; i++) {
				isTrue(
					value[i] == null || comparatorBase[i].type().isInstance(value[i]),
					"Value on index `" + i + "` must be of type `" + comparatorBase[i].type().getName() + "` but is `" + value[i] + "`!"
				);
			}
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ComparableArray that = (ComparableArray) o;
			return Arrays.equals(array, that.array);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(array);
		}

		@Override
		public String toString() {
			return Arrays.toString(array);
		}

		@Override
		public int compareTo(@Nonnull ComparableArray o) {
			for (int i = 0; i < array.length; i++) {
				@SuppressWarnings({"unchecked", "rawtypes"})
				final int result = ((Comparable)array[i]).compareTo(o.array[i]);
				if (result != 0) {
					return result;
				}
			}
			return 0;
		}

	}

	/**
	 * The comparator is used to compare two {@link ComparableArray} instances. Both instances must contain the same
	 * number of elements in the same order (elements on the same index must share same type / class).
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static class ComparableArrayComparator implements Comparator<ComparableArray>, Serializable {
		@Serial private static final long serialVersionUID = 8384226900454891700L;
		private final Comparator[] result;

		public ComparableArrayComparator(@Nonnull Comparator[] result) {
			this.result = result;
		}

		@SuppressWarnings("unchecked")
		@Override
		public int compare(ComparableArray o1, ComparableArray o2) {
			final Comparable<?>[] array1 = o1.array();
			final Comparable<?>[] array2 = o2.array();
			isTrue(array1.length == array2.length, "Arrays must have the same length!");
			for (int i = 0; i < array1.length; i++) {
				final int compare = result[i].compare(array1[i], array2[i]);
				if (compare != 0) {
					return compare;
				}
			}
			return 0;
		}
	}

	/**
	 * Implementation of the {@link #normalizer} for {@link ComparableArray} instances.
	 *
	 * @see #normalizer
	 */
	@RequiredArgsConstructor
	private static class ComparableArrayNormalizer<T> implements UnaryOperator<T> {
		private final UnaryOperator<T>[] normalizers;

		@SuppressWarnings({"unchecked"})
		@Override
		public T apply(T t) {
			final Object[] array = (Object[]) t;
			for (int i = 0; i < array.length; i++) {
				final Object originalValue = array[i];
				array[i] = ofNullable(normalizers[i])
					.map(it -> it.apply((T) originalValue))
					.orElse((T) originalValue);
			}
			return (T) array;
		}
	}
}
