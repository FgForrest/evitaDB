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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.comparator.NullsFirstComparatorWrapper;
import io.evitadb.comparator.NullsLastComparatorWrapper;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.attribute.SortIndexChanges.ValueStartIndex;
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree.EntryCursor;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.Iterator;
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
 * This class is thread-safe in transactional environment - it means, that the sort index can be updated
 * by multiple writers and also multiple readers can read from its original index without spotting the changes made
 * in transactional access. Each transaction is bound to the same thread and different threads don't see changes in
 * other threads.
 *
 * If no transaction is opened, changes are applied directly to the delegate data structures. In such case the class is
 * not thread safe for multiple writers!
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@ThreadSafe
public class SortIndex implements SortedRecordsSupplierFactory, TransactionalLayerProducer<SortIndexChanges, SortIndex>,
	IndexDataStructure, Serializable {
	@Serial private static final long serialVersionUID = 5862170244589598450L;
	/**
	 * Leaf block size of the {@link #sortedValues} tree. ORDER BY drives a full ordered cursor sweep over this tree,
	 * which is cache-miss bound from chasing scattered leaf nodes; larger leaves mean fewer, longer, more sequential
	 * runs and far fewer cold-leaf first-touch misses. Benchmarking (`SortIndexArrayVsBPlusTreeBenchmark`; results and
	 * analysis under `documentation/performance/individual/SortIndexArrayVsBPlusTreeBenchmark/`) puts the knee at `256`
	 * — it roughly halves the high-distinct sweep latency versus the tree default `64`, while `512`+ gives diminishing
	 * returns and costs more per write (a commit path-copies a whole leaf, so write cost scales with this size). It is
	 * a runtime-only parameter — it does not affect the persisted form, which is rebuilt into the tree on load.
	 */
	private static final int VALUE_BLOCK_SIZE = 256;
	private static final int MIN_VALUE_BLOCK_SIZE = VALUE_BLOCK_SIZE / 2 - 1;
	private static final int MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(MIN_VALUE_BLOCK_SIZE / 2.0) - 1);
	/**
	 * Contains record ids sorted by assigned values. The array is divided in so called record ids block that respects
	 * order in {@link #sortedValues}. Record ids within the same block are sorted naturally by their integer id.
	 */
	final TransactionalUnorderedIntArray sortedRecords;
	/**
	 * Comparator-ordered B+ tree holding every distinct value as a key (ordered by {@link #comparator}) mapped to its
	 * cardinality (the number of records sharing that value, always `>= 1`). It replaces the former parallel pair of a
	 * `sortedRecordsValues` array plus a sparse `valueCardinalities` map: the sorted distinct values are the tree keys,
	 * and the cardinality is stored inline as the value (so cardinality `1` is now stored explicitly rather than being
	 * implied to save memory). Commit derives the next snapshot by path-copying only the `O(log N)` nodes on each
	 * mutated key's root→leaf path - the same structural-sharing win already used by
	 * {@link io.evitadb.index.invertedIndex.InvertedIndex} - instead of rebuilding a full contiguous `Serializable[]`
	 * on every committed transaction.
	 *
	 * The key type is the raw {@link Comparable} because compound values ({@link ComparableArray}) are ordered solely
	 * by {@link #comparator} and do not implement {@link Comparable} themselves; the tree always uses the supplied
	 * comparator and never the keys' natural order, so the raw key is safe. The unchecked key casts are confined to the
	 * private `tree*` helper methods.
	 */
	@SuppressWarnings("rawtypes") final TransactionalObjectBPlusTree sortedValues;
	/**
	 * The array contains the descriptor allowing to create {@link #normalizer} and {@link #comparator} instances.
	 */
	@Nonnull final ComparatorSource[] comparatorBase;

	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Reference key (discriminator) of the {@link AbstractReducedEntityIndex} this index belongs to. Or null if
	 * this index is part of the global {@link GlobalEntityIndex}.
	 */
	@Getter @Nullable private final RepresentativeReferenceKey referenceKey;
	/**
	 * Contains key identifying the attribute.
	 */
	@Getter private final AttributeIndexKey attributeIndexKey;
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	private final TransactionalBoolean dirty;
	/**
	 * In unicode, some characters can be represented in multiple ways. Some has their own character as well as
	 * a combination of other unicode characters that can represent them. When characters can be represented in multiple
	 * ways, sorting them becomes harder. Therefore you should normalize the text before you sort it, or search in it
	 * for that matter. Normalizing the text makes sure that a given string of unicode characters is always represented
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
	public static Comparator<ComparableArray> createCombinedComparatorFor(
		@Nullable Locale locale, @Nonnull ComparatorSource[] comparatorBase
	) {
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
			return Optional.of(
				text -> text == null
					? null
					: Normalizer.normalize(String.valueOf(text), Normalizer.Form.NFD)
			);
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
	 * Creates a fresh, empty {@link #sortedValues} tree ordered by the passed comparator (cardinality values are plain
	 * {@link Integer}s). The cardinality value is an immutable {@link Integer} that never needs transactional wrapping,
	 * so no value wrapper is supplied. See {@link #VALUE_BLOCK_SIZE} for the read-vs-write block-size trade-off.
	 *
	 * @param comparator the comparator that defines the key (value) ordering
	 * @return a new empty comparator-ordered value → cardinality tree
	 */
	@Nonnull
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static TransactionalObjectBPlusTree createEmptyTree(@Nonnull Comparator comparator) {
		return new TransactionalObjectBPlusTree<>(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			Comparable.class, Integer.class, comparator
		);
	}

	/**
	 * Builds a {@link #sortedValues} tree from the persisted arrays: every value from `sortedRecordValues` becomes a
	 * key, with its cardinality taken from `cardinalities` (defaulting to `1` for values absent from the sparse map,
	 * matching the legacy "cardinality 1 is implied" storage convention). The values are already sorted by `comparator`.
	 *
	 * @param sortedRecordValues the naturally sorted distinct values
	 * @param cardinalities      counts for values shared by more than one record (cardinality `1` is implicit)
	 * @param comparator         the comparator that defines the key (value) ordering
	 * @return a populated comparator-ordered value → cardinality tree
	 */
	@Nonnull
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static TransactionalObjectBPlusTree buildTree(
		@Nonnull Serializable[] sortedRecordValues,
		@Nonnull Map<Serializable, Integer> cardinalities,
		@Nonnull Comparator comparator
	) {
		final TransactionalObjectBPlusTree tree = createEmptyTree(comparator);
		for (final Serializable value : sortedRecordValues) {
			tree.insert((Comparable) value, cardinalities.getOrDefault(value, 1));
		}
		return tree;
	}

	/**
	 * Verifies that the given attribute type is comparable.
	 */
	@Nonnull
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

	/**
	 * Creates an empty sort index for a single attribute that belongs to the global
	 * {@link GlobalEntityIndex} (no discriminating reference key). Convenience overload that defaults the
	 * sort order to ascending with NULLs last.
	 *
	 * @param attributeType     the comparable type of the indexed attribute
	 * @param attributeIndexKey identifies the indexed attribute (and its locale, if localized)
	 */
	public SortIndex(
		@Nonnull Class<?> attributeType,
		@Nonnull AttributeIndexKey attributeIndexKey
	) {
		this(attributeType, null, attributeIndexKey);
	}

	/**
	 * Creates an empty sort index for a single attribute, defaulting the sort order to ascending with NULLs
	 * last. The internal {@link ComparatorSource} is derived from `attributeType`.
	 *
	 * @param attributeType     the comparable type of the indexed attribute
	 * @param referenceKey      discriminator of the owning {@link AbstractReducedEntityIndex}, or `null` when
	 *                          this index lives in the global {@link GlobalEntityIndex}
	 * @param attributeIndexKey identifies the indexed attribute (and its locale, if localized)
	 */
	public SortIndex(
		@Nonnull Class<?> attributeType,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey
	) {
		assertComparable(attributeType);
		this.dirty = new TransactionalBoolean();
		this.comparatorBase = new ComparatorSource[]{
			new ComparatorSource(
				attributeType,
				OrderDirection.ASC,
				OrderBehaviour.NULLS_LAST
			)
		};
		this.referenceKey = referenceKey;
		this.attributeIndexKey = attributeIndexKey;
		this.normalizer = createNormalizerFor(this.comparatorBase[0]).orElseGet(UnaryOperator::identity);
		this.comparator = createComparatorFor(this.attributeIndexKey.locale(), this.comparatorBase[0]);
		this.sortedRecords = new TransactionalUnorderedIntArray();
		this.sortedValues = createEmptyTree(this.comparator);
	}

	/**
	 * Creates an empty sort index for a sortable attribute compound (multiple attribute elements) that belongs
	 * to the global {@link GlobalEntityIndex}. Convenience overload of the reference-key aware constructor.
	 *
	 * @param comparatorSources one descriptor per compound element, in element order; at least two are required
	 * @param attributeIndexKey identifies the indexed compound (and its locale, if localized)
	 */
	public SortIndex(
		@Nonnull ComparatorSource[] comparatorSources,
		@Nonnull AttributeIndexKey attributeIndexKey
	) {
		this(comparatorSources, null, attributeIndexKey);
	}

	/**
	 * Creates an empty sort index for a sortable attribute compound. Each element of `comparatorSources`
	 * describes one compound element (type, direction, NULL handling); the combined comparator orders records
	 * lexicographically across those elements.
	 *
	 * @param comparatorSources one descriptor per compound element, in element order; at least two are required
	 * @param referenceKey      discriminator of the owning {@link AbstractReducedEntityIndex}, or `null` when
	 *                          this index lives in the global {@link GlobalEntityIndex}
	 * @param attributeIndexKey identifies the indexed compound (and its locale, if localized)
	 * @throws IllegalArgumentException if fewer than two comparator sources are supplied
	 */
	public SortIndex(
		@Nonnull ComparatorSource[] comparatorSources,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey
	) {
		isTrue(
			comparatorSources.length > 1,
			"At least two comparators are required to create a SortIndex by this constructor!"
		);
		this.dirty = new TransactionalBoolean();
		this.comparatorBase = comparatorSources;
		this.referenceKey = referenceKey;
		this.attributeIndexKey = attributeIndexKey;
		this.normalizer = createNormalizerFor(this.comparatorBase);
		this.comparator = createCombinedComparatorFor(this.attributeIndexKey.locale(), this.comparatorBase);
		this.sortedRecords = new TransactionalUnorderedIntArray();
		this.sortedValues = createEmptyTree(this.comparator);
	}

	/**
	 * Rehydrates a sort index from its persisted state (typically a {@link SortIndexStoragePart} read back from
	 * disk). Works for both single-attribute and compound indexes: the comparator/normalizer is chosen by the
	 * length of `comparatorBase`. The presorted arrays are taken as-is, so the caller is responsible for their
	 * mutual consistency (`sortedRecords` aligned with `sortedRecordValues`, and `cardinalities` holding only
	 * values whose cardinality is greater than one).
	 *
	 * @param comparatorBase     one descriptor per element (a single entry for plain attributes)
	 * @param referenceKey       owning reference discriminator, or `null` for the global index
	 * @param attributeIndexKey  identifies the indexed attribute / compound
	 * @param sortedRecords      record ids ordered by their associated values, blocked per value
	 * @param sortedRecordValues the naturally sorted distinct values backing `sortedRecords`
	 * @param cardinalities      counts for values shared by more than one record (cardinality 1 is implicit)
	 */
	public SortIndex(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
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
		this.attributeIndexKey = attributeIndexKey;
		if (this.comparatorBase.length == 1) {
			this.normalizer = createNormalizerFor(this.comparatorBase[0]).orElseGet(UnaryOperator::identity);
			this.comparator = createComparatorFor(this.attributeIndexKey.locale(), this.comparatorBase[0]);
		} else {
			this.normalizer = createNormalizerFor(this.comparatorBase);
			this.comparator = createCombinedComparatorFor(this.attributeIndexKey.locale(), this.comparatorBase);
		}
		this.sortedRecords = new TransactionalUnorderedIntArray(sortedRecords);
		this.sortedValues = buildTree(sortedRecordValues, cardinalities, this.comparator);
	}

	/**
	 * Internal constructor used by {@link #createCopyWithMergedTransactionalMemory} to wrap the already-merged
	 * (committed) sorted-records façade and value tree directly, instead of rebuilding them from arrays (preserves the
	 * structural sharing of the underlying two-tree backing and the B+ tree across commits).
	 */
	private SortIndex(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull TransactionalUnorderedIntArray sortedRecords,
		@SuppressWarnings("rawtypes") @Nonnull TransactionalObjectBPlusTree sortedValues
	) {
		this.dirty = new TransactionalBoolean();
		this.comparatorBase = comparatorBase;
		for (ComparatorSource comparatorSource : comparatorBase) {
			assertComparable(comparatorSource.type());
		}
		this.referenceKey = referenceKey;
		this.attributeIndexKey = attributeIndexKey;
		if (this.comparatorBase.length == 1) {
			this.normalizer = createNormalizerFor(this.comparatorBase[0]).orElseGet(UnaryOperator::identity);
			this.comparator = createComparatorFor(this.attributeIndexKey.locale(), this.comparatorBase[0]);
		} else {
			this.normalizer = createNormalizerFor(this.comparatorBase);
			this.comparator = createCombinedComparatorFor(this.attributeIndexKey.locale(), this.comparatorBase);
		}
		this.sortedRecords = sortedRecords;
		this.sortedValues = sortedValues;
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
		isTrue(
			treeSearch(normalizedValueArray) != null,
			"Value `" + Arrays.toString(value) + "` is not present in the sort index of attribute `" +
				this.attributeIndexKey + "`!"
		);
		removeRecordInternal(normalizedValueArray, recordId, sortIndexChanges);
	}

	/**
	 * Unregisters existing record for passed comparable value. Single record must be linked to only single value.
	 *
	 * @throws IllegalArgumentException if value is not linked to passed record id
	 */
	public void removeRecord(@Nonnull Serializable value, int recordId) {
		final Serializable normalizedValue = this.normalizer.apply(value);
		final SortIndexChanges sortIndexChanges = getOrCreateSortIndexChanges();
		isTrue(
			treeSearch(normalizedValue) != null,
			"Value `" + value + "` is not present in the sort index of attribute `" +
				this.attributeIndexKey + "`!"
		);
		removeRecordInternal(normalizedValue, recordId, sortIndexChanges);
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
		final Serializable[] result = new Serializable[this.sortedValues.size()];
		final Iterator<?> it = this.sortedValues.keyIterator();
		int index = 0;
		while (it.hasNext()) {
			result[index++] = (Serializable) it.next();
		}
		return result;
	}

	/**
	 * Returns bitmap of all record ids connected with the value in the argument
	 */
	@Nonnull
	public Bitmap getRecordsEqualTo(@Nonnull Serializable value) {
		final Serializable normalizedValue = this.normalizer.apply(value);
		if (treeSearch(normalizedValue) != null) {
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
		final ComparableArray normalizedValue = new ComparableArray(
			this.comparatorBase,
			(Serializable[]) this.normalizer.apply(value)
		);
		if (treeSearch(normalizedValue) != null) {
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
				entityIndexPrimaryKey, this.attributeIndexKey, this.comparatorBase,
				getSortedRecords(), getSortedRecordValues(), materializeCardinalities()
			);
		} else {
			return null;
		}
	}

	/**
	 * Clears the dirty flag once the current state has been flushed via {@link #createStoragePart(int)}, so the
	 * index is no longer reported as needing persistence.
	 */
	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	/**
	 * Creates the per-transaction change buffer ({@link SortIndexChanges}) that captures modifications without
	 * touching the shared index, seeded with this index's comparator so newly inserted values keep sort order.
	 */
	@Override
	public SortIndexChanges createLayer() {
		return new SortIndexChanges(this, this.comparator);
	}

	/**
	 * Discards this index's transactional layer together with the layers of every transactional sub-structure
	 * it owns, so an aborted (or fully committed) transaction leaves no orphaned change buffers behind.
	 */
	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.dirty.removeLayer(transactionalLayer);
		this.sortedRecords.removeLayer(transactionalLayer);
		this.sortedValues.removeLayer(transactionalLayer);
	}

	/**
	 * Produces the committed copy of this index with all transactional changes merged in. When nothing changed
	 * (dirty flag is `false` after merge) the original instance is returned unchanged to avoid needless copying;
	 * otherwise a new {@code SortIndex} is built from the committed copies of each sub-structure. Both the merged
	 * sorted-records façade and the merged value tree are passed through the private constructor so their structural
	 * sharing is kept across commits rather than being rebuilt from flat arrays.
	 */
	@Nonnull
	@Override
	public SortIndex createCopyWithMergedTransactionalMemory(
		@Nullable SortIndexChanges layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// we can safely throw away dirty flag now
		final boolean isDirty = transactionalLayer
			.getStateCopyWithCommittedChanges(this.dirty);
		if (isDirty) {
			@SuppressWarnings({"rawtypes", "unchecked"}) final TransactionalObjectBPlusTree committedValues =
				(TransactionalObjectBPlusTree) transactionalLayer.getStateCopyWithCommittedChanges(this.sortedValues);
			return new SortIndex(
				this.comparatorBase,
				this.referenceKey,
				this.attributeIndexKey,
				transactionalLayer.getStateCopyWithCommittedChanges(this.sortedRecords),
				committedValues
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
	 * necessary record values and cardinalities for efficient forward traversal.
	 */
	@Nonnull
	public SortedRecordsSupplierFactory.SortedComparableForwardSeeker createSortedComparableForwardSeeker() {
		return new SortedComparableForwardSeeker(this.sortedValues, this.size());
	}

	/**
	 * Creates and returns an instance of {@link SortedRecordsSupplierFactory.SortedComparableForwardSeeker}.
	 * The created seeker facilitates efficient reverse traversal of sorted comparable records,
	 * ensuring alignment with the inherent sorting order.
	 *
	 * @return an instance of {@link SortedRecordsSupplierFactory.SortedComparableForwardSeeker}, initialized with the
	 * necessary record values and cardinalities for efficient reverse traversal.
	 */
	@Nonnull
	public SortedRecordsSupplierFactory.SortedComparableForwardSeeker createReversedSortedComparableForwardSeeker() {
		return new ReversedSortedComparableForwardSeeker(this.sortedValues, this.size());
	}

	/**
	 * Returns the inline cardinality (number of records sharing the value, always `>= 1`) of a value known to be
	 * present in {@link #sortedValues}; falls back to `1` for a value that is unexpectedly absent.
	 */
	int getValueCardinality(@Nonnull Serializable value) {
		final Integer cardinality = treeSearch(value);
		return cardinality == null ? 1 : cardinality;
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
		if (treeSearch(normalizedValue) != null) {
			// value is already present - just increment its inline cardinality
			treeUpsert(normalizedValue, crd -> crd + 1);
			// update help data structure
			sortIndexChanges.valueCardinalityIncreased(normalizedValue);
		} else {
			// insert new value into the tree with cardinality of one
			treeInsert(normalizedValue);
			// update help data structure
			sortIndexChanges.valueAdded(normalizedValue);
		}

		this.dirty.setToTrue();
	}

	/**
	 * Shared internal implementation of the record removal. The value's presence (cardinality `>= 1`) is guaranteed by
	 * the public {@code removeRecord} callers, so the three-way legacy branch (`> 2` / `== 2` / absent) collapses to a
	 * decrement, with the value deleted once its cardinality would reach zero.
	 */
	private void removeRecordInternal(
		@Nonnull Serializable normalizedValue,
		int recordId,
		@Nonnull SortIndexChanges sortIndexChanges
	) {
		// prepare internal datastructures
		sortIndexChanges.prepare();

		// remove record id from the array
		this.sortedRecords.remove(recordId);
		// the value is always present here with cardinality >= 1 (cardinality is stored inline)
		final Integer cardinality = treeSearch(normalizedValue);
		if (cardinality == null || cardinality < 1) {
			throw new GenericEvitaInternalError("Unexpected cardinality: " + cardinality);
		} else if (cardinality > 1) {
			// more than one record shares the value - just decrement its inline cardinality
			sortIndexChanges.valueCardinalityDecreased(normalizedValue);
			treeUpsert(normalizedValue, crd -> crd - 1);
		} else {
			// last record for the value - remove the value entirely
			treeDelete(normalizedValue);
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
			.getValueIndex(this.sortedValues);

		@SuppressWarnings({"rawtypes", "unchecked"}) final int theValueIndex = ArrayUtils.binarySearch(
			valueIndex, normalizedValue,
			(valueStartIndex, theValue) -> ((Comparator) this.comparator).compare(valueStartIndex.getValue(), theValue)
		);

		// cardinality is stored inline in the tree and is always >= 1
		final int cardinality = getValueCardinality(normalizedValue);
		final int recordIdIndex = valueIndex[theValueIndex].getIndex();
		if (cardinality > 1) {
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
	 * Inserts a brand-new value into {@link #sortedValues} with an initial cardinality of `1`. A value reaches this
	 * method only via its very first record (the {@link #treeSearch} miss branch in {@link #addRecordInternal}); every
	 * later record sharing the value bumps the inline cardinality through {@link #treeUpsert}, so the initial count is
	 * always `1`. Confines the unchecked cast of the value to the raw {@link Comparable} key type (safe because the tree
	 * orders by {@link #comparator}).
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private void treeInsert(@Nonnull Serializable value) {
		this.sortedValues.insert((Comparable) value, 1);
	}

	/**
	 * Updates the inline cardinality of an existing value in {@link #sortedValues}. Confines the unchecked cast of the
	 * value to the raw {@link Comparable} key type.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private void treeUpsert(@Nonnull Serializable value, @Nonnull UnaryOperator<Integer> updater) {
		this.sortedValues.upsert((Comparable) value, updater);
	}

	/**
	 * Returns the inline cardinality of the passed value, or `null` when the value is not present in
	 * {@link #sortedValues}. Confines the unchecked cast of the value to the raw {@link Comparable} key type.
	 */
	@Nullable
	@SuppressWarnings({"unchecked", "rawtypes"})
	private Integer treeSearch(@Nonnull Serializable value) {
		return (Integer) this.sortedValues.search((Comparable) value).orElse(null);
	}

	/**
	 * Deletes the passed value (and its inline cardinality) from {@link #sortedValues}. Confines the unchecked cast of
	 * the value to the raw {@link Comparable} key type.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private void treeDelete(@Nonnull Serializable value) {
		this.sortedValues.delete((Comparable) value);
	}

	/**
	 * Materialises the sparse cardinality map for serialization: only values shared by more than one record are
	 * emitted (cardinality `1` is implied on load), reproducing the exact byte layout the storage format expects.
	 */
	@Nonnull
	@SuppressWarnings("rawtypes")
	private Map<Serializable, Integer> materializeCardinalities() {
		final Map<Serializable, Integer> result = CollectionUtils.createHashMap(this.sortedValues.size());
		final EntryCursor cursor = this.sortedValues.entryCursor();
		while (cursor.hasNext()) {
			final Serializable value = (Serializable) cursor.next();
			final int cardinality = (Integer) cursor.value();
			if (cardinality > 1) {
				result.put(value, cardinality);
			}
		}
		return result;
	}

	/**
	 * Retrieves or creates temporary data structure. When transaction exists, it is created in the transactional memory
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
	) implements Comparable<ComparableArray>, Serializable {

		/**
		 * Validating constructor that asserts each value matches the type declared by the corresponding
		 * {@link ComparatorSource} element before wrapping the array (null values are permitted).
		 *
		 * @param comparatorBase the per-element type/order descriptors of the owning compound
		 * @param value          the values to wrap, aligned positionally with `comparatorBase`
		 * @throws IllegalArgumentException if any non-null value is not an instance of its declared element type
		 */
		public ComparableArray(@Nonnull ComparatorSource[] comparatorBase, @Nonnull Serializable[] value) {
			this(value);
			for (int i = 0; i < comparatorBase.length; i++) {
				isTrue(
					value[i] == null || comparatorBase[i].type().isInstance(value[i]),
					"Value on index `" + i + "` must be of type `" + comparatorBase[i].type()
						.getName() + "` but is `" + value[i] + "`!"
				);
			}
		}

		/**
		 * Uses element-wise array equality ({@link Arrays#equals}) instead of the record default, which would
		 * compare the backing array by identity and break lookups in {@link #sortedValues}.
		 */
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ComparableArray that = (ComparableArray) o;
			return Arrays.equals(this.array, that.array);
		}

		/**
		 * Hashes the array contents ({@link Arrays#hashCode}) so it stays consistent with {@link #equals(Object)};
		 * the record default would hash the array reference and violate the equals/hashCode contract.
		 */
		@Override
		public int hashCode() {
			return Arrays.hashCode(this.array);
		}

		@Nonnull
		@Override
		public String toString() {
			return Arrays.toString(this.array);
		}

		/**
		 * {@link ComparableArray} carries no ordering of its own: it is always ordered through the owning
		 * {@link SortIndex}'s configured {@link ComparableArrayComparator} (per-element comparators with direction and
		 * NULL handling). This method exists only to satisfy the {@link Comparable} key bound of
		 * {@link TransactionalObjectBPlusTree}, which never invokes it because a comparator is always supplied, so a
		 * direct call is a programming error and fails fast.
		 */
		@Override
		public int compareTo(@Nonnull ComparableArray o) {
			throw new GenericEvitaInternalError(
				"ComparableArray must be ordered through the SortIndex comparator, never its natural order!"
			);
		}

	}

	/**
	 * The comparator is used to compare two {@link ComparableArray} instances. Both instances must contain the same
	 * number of elements in the same order (elements on the same index must share same type / class).
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static class ComparableArrayComparator implements Comparator<ComparableArray>, Serializable {
		@Serial private static final long serialVersionUID = 8384226900454891700L;
		/**
		 * Per-element comparators, one per compound element, applied left-to-right until one breaks the tie.
		 */
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
		/**
		 * Per-element normalizers applied positionally; elements that need no normalization hold an identity op.
		 */
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
	 * A helper class that operates as a forward seeker for sorted comparable records. It maps an absolute record
	 * position (in ascending value order) to the value occupying that position by walking a forward
	 * `(value, cardinality)` entry cursor over {@link #sortedValues} and accumulating cardinalities. Cardinality is
	 * read inline from each entry, so no separate cardinality lookup is needed.
	 *
	 * The contract is monotonic: callers request strictly non-decreasing positions (enforced by an assert), which lets
	 * a single forward cursor satisfy the whole traversal in `O(distinct values)` total.
	 */
	private static class SortedComparableForwardSeeker
		implements SortedRecordsSupplierFactory.SortedComparableForwardSeeker {
		/**
		 * Comparator-ordered value → cardinality tree shared with the owning {@link SortIndex}.
		 */
		@SuppressWarnings("rawtypes")
		private final TransactionalObjectBPlusTree sortedValues;
		/**
		 * The total count of all records in the sorted collection.
		 */
		private final int totalCount;
		/**
		 * Forward `(value, cardinality)` cursor over {@link #sortedValues}; recreated on {@link #reset()}.
		 */
		@SuppressWarnings("rawtypes")
		private EntryCursor cursor;
		/**
		 * The value of the entry the cursor currently sits on (the block containing {@link #lastPosition}).
		 */
		@Nullable
		private Serializable currentValue;
		/**
		 * Cumulative cardinality through {@link #currentValue} (exclusive upper bound of its record block).
		 */
		private int indexPeak;
		/**
		 * Last position the comparable was retrieved for.
		 */
		private int lastPosition = -1;

		@SuppressWarnings("rawtypes")
		public SortedComparableForwardSeeker(@Nonnull TransactionalObjectBPlusTree sortedValues, int totalCount) {
			this.sortedValues = sortedValues;
			this.totalCount = totalCount;
			reset();
		}

		@Override
		public void reset() {
			this.cursor = this.sortedValues.entryCursor();
			this.currentValue = null;
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

			// advance the cursor until the current value's block (exclusive end = indexPeak) covers the position
			while (this.indexPeak <= position && this.cursor.hasNext()) {
				this.currentValue = (Serializable) this.cursor.next();
				this.indexPeak += (Integer) this.cursor.value();
			}
			// the cursor is exhausted and still does not reach the requested position
			if (this.indexPeak < position || this.currentValue == null) {
				throw new ArrayIndexOutOfBoundsException("Position " + position + " is out of bounds for value index!");
			}

			this.lastPosition = position;
			return this.currentValue;
		}
	}

	/**
	 * Reverse counterpart of {@link SortedComparableForwardSeeker}: it maps an inverted position (largest value first)
	 * to its value by walking a reverse `(value, cardinality)` entry cursor over {@link #sortedValues} from the largest
	 * key downward, subtracting cardinalities from the running total. Cardinality is read inline from each entry.
	 *
	 * The contract is monotonic in the reverse direction (positions are requested in non-increasing order, enforced by
	 * an assert), so a single reverse cursor satisfies the whole traversal.
	 */
	private static class ReversedSortedComparableForwardSeeker
		implements SortedRecordsSupplierFactory.SortedComparableForwardSeeker {
		/**
		 * Comparator-ordered value → cardinality tree shared with the owning {@link SortIndex}.
		 */
		@SuppressWarnings("rawtypes")
		private final TransactionalObjectBPlusTree sortedValues;
		/**
		 * The total count of all records in the sorted collection.
		 */
		private final int totalCount;
		/**
		 * Reverse `(value, cardinality)` cursor over {@link #sortedValues}; recreated on {@link #reset()}.
		 */
		@SuppressWarnings("rawtypes")
		private EntryCursor cursor;
		/**
		 * The value of the entry the cursor currently sits on (the block containing {@link #lastPosition}).
		 */
		@Nullable
		private Serializable currentValue;
		/**
		 * Start offset (inclusive lower bound) of {@link #currentValue}'s record block, descending from the total.
		 */
		private int indexPeak;
		/**
		 * Last position the comparable was retrieved for.
		 */
		private int lastPosition;

		@SuppressWarnings("rawtypes")
		public ReversedSortedComparableForwardSeeker(
			@Nonnull TransactionalObjectBPlusTree sortedValues, int totalCount) {
			this.sortedValues = sortedValues;
			this.totalCount = totalCount;
			reset();
		}

		@Override
		public void reset() {
			this.cursor = this.sortedValues.entryReverseCursor();
			this.currentValue = null;
			this.indexPeak = this.totalCount;
			this.lastPosition = this.totalCount;
		}

		@Nonnull
		@Override
		public Serializable getValueToCompareOn(int invertedPosition) throws ArrayIndexOutOfBoundsException {
			final int position = this.totalCount - invertedPosition - 1;
			if (position < 0 || position > this.totalCount) {
				throw new ArrayIndexOutOfBoundsException("Position " + position + " is out of bounds for value index!");
			}
			Assert.isPremiseValid(
				position <= this.lastPosition,
				"Position " + position + " must be lesser than or equal to the last position " + this.lastPosition + "!"
			);

			// descend through values (largest first) until the current value's block start (indexPeak) covers position
			while (this.indexPeak > position && this.cursor.hasNext()) {
				this.currentValue = (Serializable) this.cursor.next();
				this.indexPeak -= (Integer) this.cursor.value();
			}
			// the cursor is exhausted and still does not reach down to the requested position
			if (position < this.indexPeak || this.currentValue == null) {
				throw new ArrayIndexOutOfBoundsException("Position " + position + " is out of bounds for value index!");
			}

			this.lastPosition = position;
			return this.currentValue;
		}
	}

}
