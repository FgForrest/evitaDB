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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.comparator.NullsFirstComparatorWrapper;
import io.evitadb.comparator.NullsLastComparatorWrapper;
import io.evitadb.core.Catalog;
import io.evitadb.core.Transaction;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.array.TransactionalObjArray;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.attribute.SortIndexChanges.ValueStartIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.SortIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

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
	final TransactionalObjArray<Serializable> sortedRecordsValues;
	/**
	 * Map contains only values with cardinalities greater than one. It is expected that records will have scarce values
	 * with low cardinality so this should save a lot of memory.
	 */
	final TransactionalMap<Serializable, Integer> valueCardinalities;
	/**
	 * The array contains the descriptor allowing to create {@link #normalizer} and {@link #comparator} instances.
	 */
	@Nonnull final ComparatorSource[] comparatorBase;
	/**
	 * Reference key (discriminator) of the {@link ReducedEntityIndex} this index belongs to. Or null if this index
	 * is part of the global {@link GlobalEntityIndex}.
	 */
	@Getter @Nullable private final RepresentativeReferenceKey referenceKey;
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
	private final UnaryOperator<Serializable> normalizer;
	/**
	 * Comparator is used to execute insertion sort on the sorted records values.
	 */
	private final Comparator<?> comparator;
	/**
	 * Temporary data structure that should be NULL and should exist only when {@link Catalog} is in
	 * bulk insertion or read only state where transaction are not used.
	 */
	@Nullable private SortIndexChanges sortIndexChanges;

	/**
	 * Method creates a comparator that compares {@link ComparableArray} respecting the comparator base requirements
	 * and the localization specific for {@link String} type.
	 *
	 * @param locale         locale to use for sorting
	 * @param comparatorBase the descriptor that needs to be respected by the comparator
	 * @return the comparator that respects the given descriptor
	 */
	@SuppressWarnings("rawtypes")
	@Nonnull
	public static Comparator<ComparableArray> createCombinedComparatorFor(@Nullable Locale locale, @Nonnull ComparatorSource[] comparatorBase) {
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
	 *
	 * @param locale           locale to use for sorting
	 * @param comparatorSource the descriptor that needs to be respected by the comparator
	 * @return the comparator that respects the given descriptor
	 */
	@SuppressWarnings("rawtypes")
	@Nonnull
	public static Comparator createComparatorFor(@Nullable Locale locale, @Nonnull ComparatorSource comparatorSource) {
		final Comparator nextComparator = String.class.isAssignableFrom(comparatorSource.type()) ?
			ofNullable(locale)
				.map(it -> (Comparator) new LocalizedStringComparator(it))
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
	public static UnaryOperator<Serializable> createNormalizerFor(@Nonnull ComparatorSource[] comparatorBase) {
		//noinspection unchecked
		final UnaryOperator<Serializable>[] normalizers = new UnaryOperator[comparatorBase.length];
		boolean atLeastOneNormalizerFound = false;
		for (int i = 0; i < comparatorBase.length; i++) {
			final Optional<UnaryOperator<Serializable>> normalizer = createNormalizerFor(comparatorBase[i]);
			normalizers[i] = normalizer.orElseGet(UnaryOperator::identity);
			atLeastOneNormalizerFound = atLeastOneNormalizerFound || normalizer.isPresent();
		}
		return atLeastOneNormalizerFound ?
			new ComparableArrayNormalizer<>(normalizers) : UnaryOperator.identity();
	}

	/**
	 * Creates a normalizer if any part of the comparator base is of type {@link String}.
	 *
	 * @see #normalizer
	 */
	@Nonnull
	public static Optional<UnaryOperator<Serializable>> createNormalizerFor(@Nonnull ComparatorSource comparatorBase) {
		if (String.class.isAssignableFrom(comparatorBase.type())) {
			return Optional.of(text -> text == null ? null : Normalizer.normalize(String.valueOf(text), Normalizer.Form.NFD));
		} else if (BigDecimal.class.isAssignableFrom(comparatorBase.type())) {
			return Optional.of(value -> value == null ? null : NumberUtils.normalize((BigDecimal) value));
		} else if (Locale.class.isAssignableFrom(comparatorBase.type())) {
			return Optional.of(value -> value == null ? null : new ComparableLocale((Locale) value));
		} else if (Currency.class.isAssignableFrom(comparatorBase.type())) {
			return Optional.of(value -> value == null ? null : new ComparableCurrency((Currency) value));
		} else {
			return Optional.empty();
		}
	}

	/**
	 * Inverts positions by subtracting from the largest value.
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
	private static Class<?> assertComparable(@Nonnull Class<?> attributeType) {
		if (Currency.class.isAssignableFrom(attributeType)) {
			return ComparableCurrency.class;
		} else if (Locale.class.isAssignableFrom(attributeType)) {
			return ComparableLocale.class;
		} else {
			isTrue(
				Comparable.class.isAssignableFrom(attributeType) || attributeType.isPrimitive(),
				"Type `" + attributeType + "` is expected to be Comparable, but it is not!"
			);
			return attributeType;
		}
	}

	public SortIndex(
		@Nonnull Class<?> attributeType,
		@Nonnull AttributeKey attributeKey
	) {
		this(attributeType, null, attributeKey);
	}

	@SuppressWarnings("unchecked")
	public SortIndex(
		@Nonnull Class<?> attributeType,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeKey attributeKey
	) {
		final Class<?> normalizedAttributeType = assertComparable(attributeType);
		this.dirty = new TransactionalBoolean();
		this.comparatorBase = new ComparatorSource[]{
			new ComparatorSource(
				attributeType,
				OrderDirection.ASC,
				OrderBehaviour.NULLS_LAST
			)
		};
		this.referenceKey = referenceKey;
		this.attributeKey = attributeKey;
		this.normalizer = createNormalizerFor(this.comparatorBase[0]).orElseGet(UnaryOperator::identity);
		this.comparator = createComparatorFor(this.attributeKey.locale(), this.comparatorBase[0]);
		this.sortedRecords = new TransactionalUnorderedIntArray();
		//noinspection rawtypes
		this.sortedRecordsValues = new TransactionalObjArray<>((Serializable[]) Array.newInstance(normalizedAttributeType, 0), (Comparator) this.comparator);
		this.valueCardinalities = new TransactionalMap<>(new HashMap<>());
	}

	public SortIndex(
		@Nonnull ComparatorSource[] comparatorSources,
		@Nonnull AttributeKey attributeKey
	) {
		this(comparatorSources, null, attributeKey);
	}

	public SortIndex(
		@Nonnull ComparatorSource[] comparatorSources,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeKey attributeKey
	) {
		isTrue(
			comparatorSources.length > 1,
			"At least two comparators are required to create a SortIndex by this constructor!"
		);
		this.dirty = new TransactionalBoolean();
		this.comparatorBase = comparatorSources;
		this.referenceKey = referenceKey;
		this.attributeKey = attributeKey;
		this.normalizer = createNormalizerFor(this.comparatorBase);
		this.comparator = createCombinedComparatorFor(this.attributeKey.locale(), this.comparatorBase);
		this.sortedRecords = new TransactionalUnorderedIntArray();
		//noinspection rawtypes,unchecked
		this.sortedRecordsValues = new TransactionalObjArray<>(new ComparableArray[0], (Comparator) this.comparator);
		this.valueCardinalities = new TransactionalMap<>(new HashMap<>());
	}

	public SortIndex(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeKey attributeKey,
		@Nonnull int[] sortedRecords,
		@Nonnull Serializable[] sortedRecordValues,
		@Nonnull Map<Serializable, Integer> cardinalities
	) {
		this.dirty = new TransactionalBoolean();
		this.comparatorBase = comparatorBase;
		for (ComparatorSource comparatorSource : comparatorBase) {
			assertComparable(comparatorSource.type());
		}
		this.referenceKey = referenceKey;
		this.attributeKey = attributeKey;
		if (this.comparatorBase.length == 1) {
			this.normalizer = createNormalizerFor(this.comparatorBase[0]).orElseGet(UnaryOperator::identity);
			this.comparator = createComparatorFor(this.attributeKey.locale(), this.comparatorBase[0]);
		} else {
			this.normalizer = createNormalizerFor(this.comparatorBase);
			this.comparator = createCombinedComparatorFor(this.attributeKey.locale(), this.comparatorBase);
		}
		this.sortedRecords = new TransactionalUnorderedIntArray(sortedRecords);
		//noinspection unchecked,rawtypes
		this.sortedRecordsValues = new TransactionalObjArray(sortedRecordValues, this.comparator);
		this.valueCardinalities = new TransactionalMap<>(cardinalities);
	}

	/**
	 * Registers new record for passed comparable value. Record id must be present in array only once.
	 */
	public void addRecord(@Nonnull Serializable[] value, int recordId) {
		final Serializable[] normalizedValue = (Serializable[]) this.normalizer.apply(value);
		isTrue(
			this.sortedRecords.indexOf(recordId) < 0,
			"Record id `" + recordId + "` is already present in the sort index!"
		);
		addRecordInternal(new ComparableArray(this.comparatorBase, normalizedValue), recordId);
	}

	/**
	 * Registers new record for passed comparable value. Record id must be present in array only once.
	 */
	public void addRecord(@Nonnull Serializable value, int recordId) {
		final Serializable normalizedValue = this.normalizer.apply(value);
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
		addRecordInternal(normalizedValue, recordId);
	}

	/**
	 * Unregisters existing record for passed comparable value. Single record must be linked to only single value.
	 *
	 * @throws IllegalArgumentException if value is not linked to passed record id
	 */
	public void removeRecord(@Nonnull Serializable[] value, int recordId) {
		final Serializable[] normalizedValue = (Serializable[]) this.normalizer.apply(value);
		final ComparableArray normalizedValueArray = new ComparableArray(this.comparatorBase, normalizedValue);
		final SortIndexChanges sortIndexChanges = getOrCreateSortIndexChanges();
		final int index = this.sortedRecordsValues.indexOf(normalizedValueArray);
		isTrue(
			index >= 0,
			"Value `" + Arrays.toString(value) + "` is not present in the sort index of attribute `" + this.attributeKey + "`!"
		);
		removeRecordInternal(normalizedValueArray, recordId, this.sortedRecordsValues, this.valueCardinalities, sortIndexChanges);
	}

	/**
	 * Unregisters existing record for passed comparable value. Single record must be linked to only single value.
	 *
	 * @throws IllegalArgumentException if value is not linked to passed record id
	 */
	public void removeRecord(@Nonnull Serializable value, int recordId) {
		final Serializable normalizedValue = this.normalizer.apply(value);
		final SortIndexChanges sortIndexChanges = getOrCreateSortIndexChanges();
		final int index = this.sortedRecordsValues.indexOf(normalizedValue);
		isTrue(
			index >= 0,
			"Value `" + value + "` is not present in the sort index of attribute `" + this.attributeKey + "`!"
		);
		removeRecordInternal(normalizedValue, recordId, this.sortedRecordsValues, this.valueCardinalities, sortIndexChanges);
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
	public Serializable[] getSortedRecordValues() {
		return this.sortedRecordsValues.getArray();
	}

	/**
	 * Returns bitmap of all record ids connected with the value in the argument
	 */
	@Nonnull
	public Bitmap getRecordsEqualTo(@Nonnull Serializable value) {
		final Serializable normalizedValue = this.normalizer.apply(value);
		final int index = this.sortedRecordsValues.indexOf(normalizedValue);
		if (index >= 0) {
			return getRecordsEqualToInternal(normalizedValue);
		} else {
			return EmptyBitmap.INSTANCE;
		}
	}

	/**
	 * Returns bitmap of all record ids connected with the value in the argument
	 */
	@Nonnull
	public Bitmap getRecordsEqualTo(@Nonnull Serializable[] value) {
		final ComparableArray normalizedValue = new ComparableArray(this.comparatorBase, (Serializable[]) this.normalizer.apply(value));
		final int index = this.sortedRecordsValues.indexOf(normalizedValue);
		if (index >= 0) {
			return getRecordsEqualToInternal(normalizedValue);
		} else {
			return EmptyBitmap.INSTANCE;
		}
	}

	/**
	 * Returns true if {@link SortIndex} contains no data.
	 */
	public boolean isEmpty() {
		return this.sortedRecords.isEmpty();
	}

	/**
	 * Returns number of record ids in this {@link SortIndex}.
	 */
	public int size() {
		return this.sortedRecords.getLength();
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
				entityIndexPrimaryKey, this.attributeKey, this.comparatorBase,
				getSortedRecords(), getSortedRecordValues(), this.valueCardinalities
			);
		} else {
			return null;
		}
	}

	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	@Override
	public SortIndexChanges createLayer() {
		return new SortIndexChanges(this, this.comparator);
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
	public SortIndex createCopyWithMergedTransactionalMemory(@Nullable SortIndexChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// we can safely throw away dirty flag now
		final Boolean isDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		if (isDirty) {
			return new SortIndex(
				this.comparatorBase,
				this.referenceKey,
				this.attributeKey,
				transactionalLayer.getStateCopyWithCommittedChanges(this.sortedRecords),
				transactionalLayer.getStateCopyWithCommittedChanges(this.sortedRecordsValues),
				transactionalLayer.getStateCopyWithCommittedChanges(this.valueCardinalities)
			);
		} else {
			return this;
		}
	}

	/**
	 * Creates and returns an instance of {@link SortedRecordsSupplierFactory.SortedComparableForwardSeeker}.
	 * The created seeker facilitates efficient forward traversal of sorted comparable records,
	 * ensuring alignment with the inherent sorting order.
	 *
	 * @return an instance of {@link SortedRecordsSupplierFactory.SortedComparableForwardSeeker}, initialized with the
	 *         necessary record values and cardinalities for efficient forward traversal.
	 */
	@Nonnull
	public SortedRecordsSupplierFactory.SortedComparableForwardSeeker createSortedComparableForwardSeeker() {
		return new SortedComparableForwardSeeker(
			this.sortedRecordsValues, this.valueCardinalities, this.size()
		);
	}

	/**
	 * Creates and returns an instance of {@link SortedRecordsSupplierFactory.SortedComparableForwardSeeker}.
	 * The created seeker facilitates efficient reverse traversal of sorted comparable records,
	 * ensuring alignment with the inherent sorting order.
	 *
	 * @return an instance of {@link SortedRecordsSupplierFactory.SortedComparableForwardSeeker}, initialized with the
	 *         necessary record values and cardinalities for efficient reverse traversal.
	 */
	@Nonnull
	public SortedRecordsSupplierFactory.SortedComparableForwardSeeker createReversedSortedComparableForwardSeeker() {
		return new ReversedSortedComparableForwardSeeker(
			this.sortedRecordsValues, this.valueCardinalities, this.size()
		);
	}

	/**
	 * Shared internal implementation of the record insertion.
	 */
	private void addRecordInternal(@Nonnull Serializable normalizedValue, int recordId) {
		final SortIndexChanges sortIndexChanges = getOrCreateSortIndexChanges();

		// prepare internal datastructures
		sortIndexChanges.prepare();

		// add record id on the computed position
		final int previousRecordId = sortIndexChanges.computePreviousRecord(normalizedValue, recordId);
		this.sortedRecords.add(previousRecordId, recordId);

		// is the value already known?
		final int index = this.sortedRecordsValues.indexOf(normalizedValue);
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
			sortIndexChanges.valueCardinalityIncreased(normalizedValue);
		} else {
			// insert new value into the sorted value array
			this.sortedRecordsValues.add(normalizedValue);
			// update help data structure
			sortIndexChanges.valueAdded(normalizedValue);
		}

		this.dirty.setToTrue();
	}

	/**
	 * Shared internal implementation of the record removal.
	 */
	private <T extends Serializable> void removeRecordInternal(
		@Nonnull T normalizedValue,
		int recordId,
		@Nonnull TransactionalObjArray<T> theSortedRecordsValues,
		@Nonnull TransactionalMap<Serializable, Integer> theValueCardinalities,
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
			sortIndexChanges.valueCardinalityDecreased(normalizedValue);
			if (cardinality > 2) {
				// decrease cardinality
				theValueCardinalities.computeIfPresent(normalizedValue, (t, crd) -> crd - 1);
			} else if (cardinality == 2) {
				// remove cardinality altogether - cardinality = 1 is not maintained to save memory
				theValueCardinalities.remove(normalizedValue);
			} else {
				throw new GenericEvitaInternalError("Unexpected cardinality: " + cardinality);
			}
		} else {
			// remove the entire value - there is no more record ids for it
			theSortedRecordsValues.remove(normalizedValue);
			// update help data structure
			sortIndexChanges.valueRemoved(normalizedValue);
		}

		this.dirty.setToTrue();
	}

	/**
	 * Returns bitmap of all record ids connected with the value in the argument.
	 */
	@Nonnull
	private <T extends Serializable> BaseBitmap getRecordsEqualToInternal(@Nonnull T normalizedValue) {
		// add record id from the array
		final ValueStartIndex[] valueIndex = getOrCreateSortIndexChanges()
			.getValueIndex(this.sortedRecordsValues, this.valueCardinalities);

		@SuppressWarnings({"rawtypes", "unchecked"}) final int theValueIndex = ArrayUtils.binarySearch(
			valueIndex, normalizedValue,
			(valueStartIndex, theValue) -> ((Comparator) this.comparator).compare(valueStartIndex.getValue(), theValue)
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
	 * Retrieves or creates temporary data structure. When transaction exists it's created in the transactional memory
	 * space so that other threads are not affected by the changes in the {@link SortIndex}.
	 */
	@Nonnull
	private SortIndexChanges getOrCreateSortIndexChanges() {
		final SortIndexChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return ofNullable(this.sortIndexChanges).orElseGet(() -> {
				this.sortIndexChanges = new SortIndexChanges(this, this.comparator);
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
		@Nonnull Serializable[] array
	) implements Serializable {

		public ComparableArray(@Nonnull ComparatorSource[] comparatorBase, @Nonnull Serializable[] value) {
			this(value);
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
			return Arrays.equals(this.array, that.array);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(this.array);
		}

		@Nonnull
		@Override
		public String toString() {
			return Arrays.toString(this.array);
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
			final Serializable[] array1 = o1.array();
			final Serializable[] array2 = o2.array();
			isTrue(array1.length == array2.length, "Arrays must have the same length!");
			for (int i = 0; i < array1.length; i++) {
				final int compare = this.result[i].compare(array1[i], array2[i]);
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
				array[i] = ofNullable(this.normalizers[i])
					.map(it -> it.apply((T) originalValue))
					.orElse((T) originalValue);
			}
			return (T) array;
		}
	}

	/**
	 * A helper class that operates as a forward seeker for sorted comparable records.
	 * This implementation is designed to efficiently retrieve comparable values stored
	 * across multiple ranges in a sorted collection. It supports traversing the collection
	 * in a forward direction while maintaining consistent behavior with the sorting order.
	 *
	 * The seeker uses an array of {@code ValueStartIndex} objects where each index defines
	 * the starting position and associated value, along with the total number of records
	 * to manage bounds validation during traversal.
	 */
	/* TOBEDONE JNO #760 - this should be handled by optimized B+Tree key iterator that is tied to the value count! */
	private static class SortedComparableForwardSeeker implements SortedRecordsSupplierFactory.SortedComparableForwardSeeker {
		/**
		 * Contains comparable values sorted naturally by their {@link Comparable} characteristics.
		 */
		private final TransactionalObjArray<Serializable> sortedRecordsValues;
		/**
		 * Map contains only values with cardinalities greater than one. It is expected that records will have scarce values
		 * with low cardinality so this should save a lot of memory.
		 */
		private final TransactionalMap<Serializable, Integer> valueCardinalities;
		/**
		 * The total count of all records in the sorted collection.
		 */
		private final int totalCount;
		/**
		 * The current index in the {@code valueLocationIndex} array used for traversing forward.
		 */
		private int index = -1;
		/**
		 * Last position the comparable was retrieved for (and that match the {@link #index}).
		 */
		private int lastPosition = -1;
		/**
		 * Contains peak of the current index including cardinality.
		 */
		private int indexPeak = 0;

		public SortedComparableForwardSeeker(
			@Nonnull TransactionalObjArray<Serializable> sortedRecordsValues,
			@Nonnull TransactionalMap<Serializable, Integer> valueCardinalities,
			int totalCount
		) {
			this.sortedRecordsValues = sortedRecordsValues;
			this.valueCardinalities = valueCardinalities;
			this.totalCount = totalCount;
		}

		@Override
		public void reset() {
			this.index = -1;
			this.indexPeak = 0;
			this.lastPosition = -1;
		}

		@Nonnull
		@Override
		public Serializable getValueToCompareOn(int position) throws ArrayIndexOutOfBoundsException {
			if (position < 0 || position > this.totalCount) {
				throw new ArrayIndexOutOfBoundsException("Position " + position + " is out of bounds for value index!");
			}
			Assert.isPremiseValid(
				position >= this.lastPosition,
				"Position " + position + " must be greater than or equal to the last position " + this.lastPosition + "!"
			);

			boolean exhausted = false;
			if (this.indexPeak <= position) {
				int currentIndexCardinality = this.valueCardinalities.getOrDefault(this.sortedRecordsValues.get(this.index + 1), 1);
				while (this.indexPeak <= position && this.indexPeak < this.totalCount) {
					this.indexPeak += currentIndexCardinality;
					this.index++;
					if (this.index + 1 < this.sortedRecordsValues.getLength()) {
						currentIndexCardinality = this.valueCardinalities.getOrDefault(this.sortedRecordsValues.get(this.index + 1), 1);
					} else {
						exhausted = true;
						break;
					}
				}
				if (exhausted && position > this.indexPeak) {
					throw new ArrayIndexOutOfBoundsException("Position " + position + " is out of bounds for value index!");
				}
			}

			this.lastPosition = position;
			return this.sortedRecordsValues.get(this.index);
		}
	}

	/**
	 * ReversedSortedComparableForwardSeeker provides an implementation of
	 * SortedRecordsSupplierFactory.SortedComparableForwardSeeker that enables
	 * forward traversal of a collection of comparable values in reversed sorted order.
	 * This implementation operates on an array of ValueStartIndex objects and retrieves
	 * the Comparable value for a specified position.
	 *
	 * The seeker's index is initialized to the last position in the valueLocationIndex array,
	 * and it moves backwards through the array as values are requested. Each value can be
	 * fetched using its position in the sorted collection, with validation to ensure the
	 * position is within bounds.
	 *
	 * This class is specifically designed to support sorted collections where the elements
	 * are traversed from the end towards the beginning, following reverse sorted order.
	 */
	/* TOBEDONE JNO #760 - this should be handled by optimized B+Tree key iterator that is tied to the value count! */
	private static class ReversedSortedComparableForwardSeeker implements SortedRecordsSupplierFactory.SortedComparableForwardSeeker {
		/**
		 * Contains comparable values sorted naturally by their {@link Comparable} characteristics.
		 */
		private final TransactionalObjArray<Serializable> sortedRecordsValues;
		/**
		 * Map contains only values with cardinalities greater than one. It is expected that records will have scarce values
		 * with low cardinality so this should save a lot of memory.
		 */
		private final TransactionalMap<Serializable, Integer> valueCardinalities;
		/**
		 * The total count of all records in the sorted collection.
		 */
		private final int totalCount;
		/**
		 * The current index of the ValueStartIndex array used while traversing backward.
		 */
		private int index;
		/**
		 * Last position the comparable was retrieved for (and that match the {@link #index}).
		 */
		private int lastPosition;
		/**
		 * Contains peak of the current index including cardinality.
		 */
		private int indexPeak;

		public ReversedSortedComparableForwardSeeker(
			@Nonnull TransactionalObjArray<Serializable> sortedRecordsValues,
			@Nonnull TransactionalMap<Serializable, Integer> valueCardinalities,
			int totalCount
		) {
			this.sortedRecordsValues = sortedRecordsValues;
			this.valueCardinalities = valueCardinalities;
			this.totalCount = totalCount;
			this.lastPosition = totalCount;
			this.index = this.sortedRecordsValues.getLength();
			this.indexPeak = totalCount;
		}

		@Override
		public void reset() {
			this.lastPosition = this.totalCount;
			this.indexPeak = this.totalCount;
			this.index = this.sortedRecordsValues.getLength();
		}

		@Nonnull
		@Override
		public Serializable getValueToCompareOn(int invertedPosition) throws ArrayIndexOutOfBoundsException {
			int position = this.totalCount - invertedPosition - 1;
			if (position < 0 || position > this.totalCount) {
				throw new ArrayIndexOutOfBoundsException("Position " + position + " is out of bounds for value index!");
			}

			Assert.isPremiseValid(
				position <= this.lastPosition,
				"Position " + position + " must be lesser than or equal to the last position " + this.lastPosition + "!"
			);

			boolean exhausted = false;
			if (this.indexPeak > position) {
				int currentIndexCardinality = this.valueCardinalities.getOrDefault(this.sortedRecordsValues.get(this.index - 1), 1);
				while (this.indexPeak > position) {
					this.indexPeak -= currentIndexCardinality;
					this.index--;
					if (this.index > 0) {
						currentIndexCardinality = this.valueCardinalities.getOrDefault(this.sortedRecordsValues.get(this.index - 1), 1);
					} else {
						exhausted = true;
						break;
					}
				}
				if (exhausted && position > this.indexPeak + currentIndexCardinality) {
					throw new ArrayIndexOutOfBoundsException("Position " + position + " is out of bounds for value index!");
				}
			}

			this.lastPosition = position;
			return this.sortedRecordsValues.get(this.index);
		}
	}

}
