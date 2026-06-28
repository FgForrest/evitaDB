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
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
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
 * The class is an `abstract sealed` base of a two-variant family:
 *
 * - {@link OwnerSortIndex} OWNS its own `value → cardinality` B+ tree and is the full source of truth for value
 * ordering and cardinality. It backs sort-only single attributes and ALL sortable attribute compounds.
 * - {@link SortIndexView} owns ONLY its sort-specific {@link #sortedRecords} ordering and sources the value ordering /
 * cardinality / comparator / normalizer from the shared {@link InvertedIndex} owned by {@link AttributeIndex} (a
 * both-filterable-and-sortable single attribute). It is still a producer (it commits {@link #sortedRecords}).
 *
 * The base orchestrates the {@link #sortedRecords} façade and the {@link SortIndexChanges} help structure that both
 * variants share; the value-side operations that differ between owner and view are expressed as the abstract hooks
 * below ({@link #valueCursor()}, {@link #getValueCardinality(Serializable)}, {@link #effectiveComparator()}, …) instead
 * of a runtime flag.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@ThreadSafe
public abstract sealed class SortIndex
	implements SortedRecordsSupplierFactory, TransactionalLayerProducer<SortIndexChanges, SortIndex>,
	IndexDataStructure, Serializable
	permits OwnerSortIndex, SortIndexView {
	@Serial private static final long serialVersionUID = 5862170244589598450L;
	/**
	 * Contains record ids sorted by assigned values. The array is divided in so called record ids block that respects
	 * order in the index's value ordering. Record ids within the same block are sorted naturally by their integer id.
	 */
	final TransactionalUnorderedIntArray sortedRecords;
	/**
	 * The array contains the descriptor allowing to create {@link #normalizer} and {@link #comparator} instances.
	 */
	@Nonnull final ComparatorSource[] comparatorBase;
	/**
	 * In unicode, some characters can be represented in multiple ways. Some has their own character as well as
	 * a combination of other unicode characters that can represent them. When characters can be represented in multiple
	 * ways, sorting them becomes harder. Therefore you should normalize the text before you sort it, or search in it
	 * for that matter. Normalizing the text makes sure that a given string of unicode characters is always represented
	 * in the same way - a way which is search and sort friendly.
	 *
	 * (source: <a href="https://jenkov.com/tutorials/java-internationalization/collator.html">Jenkov.com</a>)
	 *
	 * This is the index's OWN normalizer (built from {@link #comparatorBase}). View mode overrides
	 * {@link #effectiveNormalizer()} to source the shared tree's normalizer instead; this own one stays as a fallback.
	 */
	final UnaryOperator<Serializable> normalizer;
	/**
	 * Comparator is used to execute insertion sort on the sorted records values. This is the index's OWN comparator;
	 * view mode overrides {@link #effectiveComparator()} to adopt the shared tree's comparator.
	 */
	final Comparator<?> comparator;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Decimal-places scale used to encode `BigDecimal` values to an order-preserving scaled `int` so this index's OWN
	 * normalizer ({@link #normalizer}) keeps a both-flagged attribute's sort keys identical to the shared filter tree's
	 * keys. `0` for every non-`BigDecimal` attribute (and for compounds, applied uniformly to BigDecimal elements).
	 */
	@Getter private final int indexedDecimalPlaces;
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
		return createNormalizerFor(comparatorBase, 0);
	}

	/**
	 * Creates a normalizer if any element of the comparator base needs canonicalization (a {@link String},
	 * {@link Locale}, {@link Currency} or {@link java.math.BigDecimal}). `BigDecimal` elements are scaled to an
	 * order-preserving `int` using `indexedDecimalPlaces`, applied uniformly to every `BigDecimal` element.
	 *
	 * @param comparatorBase       one descriptor per compound element
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` elements (0 for other types)
	 * @see #normalizer
	 */
	@Nonnull
	public static UnaryOperator<Serializable> createNormalizerFor(
		@Nonnull ComparatorSource[] comparatorBase,
		int indexedDecimalPlaces
	) {
		//noinspection unchecked
		final UnaryOperator<Serializable>[] normalizers = new UnaryOperator[comparatorBase.length];
		boolean atLeastOneNormalizerFound = false;
		for (int i = 0; i < comparatorBase.length; i++) {
			// a sortable attribute compound is always an owner (no shared filter twin), so its `BigDecimal` elements are
			// NOT scaled to an int — they keep their exact `BigDecimal` natural order. This also matches the migration,
			// which leaves compound (`ComparableArray`) sort values untouched. A single `indexedDecimalPlaces` could not
			// represent the distinct per-element scales anyway.
			final Optional<UnaryOperator<Serializable>> normalizer =
				createNormalizerFor(comparatorBase[i], indexedDecimalPlaces, false);
			normalizers[i] = normalizer.orElseGet(UnaryOperator::identity);
			atLeastOneNormalizerFound = atLeastOneNormalizerFound || normalizer.isPresent();
		}
		return atLeastOneNormalizerFound ?
			new ComparableArrayNormalizer<>(normalizers) : UnaryOperator.identity();
	}

	/**
	 * Creates a normalizer if the comparator base is of type {@link String}, {@link Locale} or {@link Currency}.
	 * `BigDecimal` is intentionally NOT normalized here: the query-side comparators that call this overload compare raw
	 * `BigDecimal` entity values among themselves, where natural order is already correct. The index-side scaling is
	 * supplied through the {@link #createNormalizerFor(ComparatorSource, int)} overload.
	 *
	 * @see #normalizer
	 */
	@Nonnull
	public static Optional<UnaryOperator<Serializable>> createNormalizerFor(@Nonnull ComparatorSource comparatorBase) {
		return createNormalizerFor(comparatorBase, 0, false);
	}

	/**
	 * Creates a normalizer if the comparator base needs canonicalization. In addition to the {@link String} /
	 * {@link Locale} / {@link Currency} forms, a {@link java.math.BigDecimal} element is scaled to an order-preserving
	 * `int` using `indexedDecimalPlaces`, matching the shared filter value tree so a both-flagged attribute's sort keys
	 * never diverge from its filter keys. The scaled-int normalizer is idempotent (an already-scaled `Integer` and
	 * `null` pass through unchanged).
	 *
	 * @param comparatorBase       descriptor of the element
	 * @param indexedDecimalPlaces decimal-places scale used to encode a `BigDecimal` element
	 * @see #normalizer
	 */
	@Nonnull
	public static Optional<UnaryOperator<Serializable>> createNormalizerFor(
		@Nonnull ComparatorSource comparatorBase,
		int indexedDecimalPlaces
	) {
		return createNormalizerFor(comparatorBase, indexedDecimalPlaces, true);
	}

	/**
	 * Shared implementation behind the two public `createNormalizerFor(ComparatorSource, …)` overloads. `scaleBigDecimal`
	 * gates the `BigDecimal` scaled-int branch: the index path enables it (keys must match the shared filter tree); the
	 * query-comparator path leaves it off (raw `BigDecimal` natural order is already correct for sorting result rows).
	 */
	@Nonnull
	private static Optional<UnaryOperator<Serializable>> createNormalizerFor(
		@Nonnull ComparatorSource comparatorBase,
		int indexedDecimalPlaces,
		boolean scaleBigDecimal
	) {
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
		} else if (scaleBigDecimal && BigDecimal.class.isAssignableFrom(comparatorBase.type())) {
			// scale a real BigDecimal to its order-preserving int; an already-scaled Integer (or null) passes through
			// so the value may be normalized twice without a ClassCastException (idempotent contract)
			return Optional.of(
				value -> value instanceof BigDecimal bd
					? Integer.valueOf(NumberUtils.convertToInt(bd, indexedDecimalPlaces))
					: value
			);
		} else {
			return Optional.empty();
		}
	}

	/**
	 * Creates a single-attribute sort index that runs in **owner mode** when no shared tree is available, or in
	 * **view mode** when the supplier resolves a shared {@link InvertedIndex} at construction. This is the single place
	 * the owner-vs-view decision is made for freshly created single-attribute sort indexes (the value type drives the
	 * comparator; ASC/NULLS_LAST is the single-attribute default). A supplier resolving `null` at construction (sort-only
	 * attribute) yields an owner; a supplier resolving a non-null tree (both-flagged attribute) yields a view. The
	 * decision is FIXED at construction — the shared tree can be transiently removed mid-mutation but the mode does not
	 * flip.
	 *
	 * @param attributeType        the comparable type of the indexed attribute
	 * @param referenceKey         discriminator of the owning {@link AbstractReducedEntityIndex}, or `null` for the global index
	 * @param key                  identifies the indexed attribute (and its locale, if localized)
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 * @param sharedSupplier       parent-bound supplier of the shared tree (view candidate) or `null` (always owner)
	 * @return a freshly created {@link OwnerSortIndex} or {@link SortIndexView}
	 */
	@Nonnull
	public static SortIndex create(
		@Nonnull Class<?> attributeType,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey key,
		int indexedDecimalPlaces,
		@Nullable Supplier<InvertedIndex> sharedSupplier
	) {
		// resolve the shared tree ONCE at construction (sort runs before filter, so for a brand-new key the tree is still
		// absent → owner mode; a both-flagged key whose tree already exists → view mode bound directly to that instance)
		final InvertedIndex sharedTree = sharedSupplier == null ? null : sharedSupplier.get();
		return sharedTree != null
			? new SortIndexView(attributeType, referenceKey, key, indexedDecimalPlaces, sharedTree)
			: new OwnerSortIndex(attributeType, referenceKey, key, indexedDecimalPlaces);
	}

	/**
	 * Rehydrates a sort index from its persisted state in **owner** or **view** mode. When `sharedSupplier` resolves a
	 * shared tree the persisted `sortedRecordValues` / `cardinalities` are IGNORED (the slim part omits them) and a
	 * {@link SortIndexView} is built; otherwise an {@link OwnerSortIndex} is rebuilt from the persisted arrays.
	 * `sortedRecords` is always taken as-is.
	 *
	 * @param comparatorBase       one descriptor per element (a single entry for plain attributes)
	 * @param referenceKey         owning reference discriminator, or `null` for the global index
	 * @param attributeIndexKey    identifies the indexed attribute / compound
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 * @param sortedRecords        record ids ordered by their associated values, blocked per value
	 * @param sortedRecordValues   the naturally sorted distinct values (ignored in view mode)
	 * @param cardinalities        counts for values shared by more than one record (ignored in view mode)
	 * @param sharedSupplier       parent-bound supplier of the shared tree (view mode) or `null` (owner mode)
	 * @return a rehydrated {@link OwnerSortIndex} or {@link SortIndexView}
	 */
	@Nonnull
	public static SortIndex create(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		int indexedDecimalPlaces,
		@Nonnull int[] sortedRecords,
		@Nonnull Serializable[] sortedRecordValues,
		@Nonnull Map<Serializable, Integer> cardinalities,
		@Nullable Supplier<InvertedIndex> sharedSupplier
	) {
		// resolve the shared tree ONCE at load: a slim view-mode part folds onto the existing shared tree, an owner part
		// rebuilds from its persisted arrays
		final InvertedIndex sharedTree = sharedSupplier == null ? null : sharedSupplier.get();
		return sharedTree != null
			? new SortIndexView(
				comparatorBase, referenceKey, attributeIndexKey, indexedDecimalPlaces, sortedRecords, sharedTree)
			: new OwnerSortIndex(
				comparatorBase, referenceKey, attributeIndexKey, indexedDecimalPlaces,
				sortedRecords, sortedRecordValues, cardinalities);
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
	@Nonnull
	static Class<?> assertComparable(@Nonnull Class<?> attributeType) {
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
	 * Base constructor shared by both variants. Derives the {@link #comparator} / {@link #normalizer} from the supplied
	 * `comparatorBase` (single descriptor ⇒ single-attribute comparator, multiple ⇒ combined compound comparator) and
	 * adopts the supplied {@link #sortedRecords} façade as-is.
	 *
	 * @param comparatorBase    one descriptor per element (a single entry for plain attributes)
	 * @param referenceKey      owning reference discriminator, or `null` for the global index
	 * @param attributeIndexKey identifies the indexed attribute / compound
	 * @param sortedRecords     the sorted-records façade to adopt (fresh empty, rehydrated, or merged)
	 */
	protected SortIndex(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull TransactionalUnorderedIntArray sortedRecords
	) {
		this(comparatorBase, referenceKey, attributeIndexKey, 0, sortedRecords);
	}

	/**
	 * Base constructor shared by both variants, carrying the `BigDecimal` scaling decimal places so this index's OWN
	 * {@link #normalizer} scales `BigDecimal` keys identically to the shared filter value tree (keeping a both-flagged
	 * attribute's sort and filter keys in lockstep).
	 *
	 * @param comparatorBase       one descriptor per element (a single entry for plain attributes)
	 * @param referenceKey         owning reference discriminator, or `null` for the global index
	 * @param attributeIndexKey    identifies the indexed attribute / compound
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 * @param sortedRecords        the sorted-records façade to adopt (fresh empty, rehydrated, or merged)
	 */
	protected SortIndex(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		int indexedDecimalPlaces,
		@Nonnull TransactionalUnorderedIntArray sortedRecords
	) {
		this.dirty = new TransactionalBoolean();
		this.comparatorBase = comparatorBase;
		for (final ComparatorSource comparatorSource : comparatorBase) {
			assertComparable(comparatorSource.type());
		}
		this.referenceKey = referenceKey;
		this.attributeIndexKey = attributeIndexKey;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		if (this.comparatorBase.length == 1) {
			this.normalizer = createNormalizerFor(this.comparatorBase[0], indexedDecimalPlaces)
				.orElseGet(UnaryOperator::identity);
			this.comparator = createComparatorFor(this.attributeIndexKey.locale(), this.comparatorBase[0]);
		} else {
			this.normalizer = createNormalizerFor(this.comparatorBase, indexedDecimalPlaces);
			this.comparator = createCombinedComparatorFor(this.attributeIndexKey.locale(), this.comparatorBase);
		}
		this.sortedRecords = sortedRecords;
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
		// in view mode the value must live in the shared tree's normalized space (e.g. OffsetDateTime→Instant) so it
		// matches the shared bucket keys it is compared against
		final Serializable normalizedValue = effectiveNormalizer().apply(value);
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
			valuePresentForRemoval(normalizedValueArray, recordId),
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
		// view mode operates in the shared tree's normalized space
		final Serializable normalizedValue = effectiveNormalizer().apply(value);
		final SortIndexChanges sortIndexChanges = getOrCreateSortIndexChanges();
		isTrue(
			valuePresentForRemoval(normalizedValue, recordId),
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
	 * Returns array of naturally sorted comparable values. Owner mode walks its own value tree; view mode reconstructs
	 * the ordered distinct values from the shared {@link InvertedIndex}. Method is targeted to be used in SERIALIZATION
	 * and nowhere else.
	 */
	@Nonnull
	public abstract Serializable[] getSortedRecordValues();

	/**
	 * Returns bitmap of all record ids connected with the value in the argument
	 */
	@Nonnull
	public Bitmap getRecordsEqualTo(@Nonnull Serializable value) {
		// view mode operates in the shared tree's normalized space
		final Serializable normalizedValue = effectiveNormalizer().apply(value);
		if (valuePresent(normalizedValue)) {
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
		if (valuePresent(normalizedValue)) {
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
	 * Method creates container for storing sort index from memory to the persistent storage. Owner mode persists the
	 * full distinct values + cardinalities; view mode persists a slim part whose values/cardinalities are re-derivable
	 * from the shared FILTER part on load (see {@link #storagePartSortedValues()} / {@link #storagePartCardinalities()}).
	 */
	@Nullable
	public StoragePart createStoragePart(int entityIndexPrimaryKey) {
		if (this.dirty.isTrue()) {
			// all data are persisted to disk - we may get rid of temporary, modification only helper container
			this.sortIndexChanges = null;
			// owner mode produces the sparse cardinality columns directly from its value tree (no intermediate map); view
			// mode emits empty columns (re-derived from the shared FILTER part on load)
			final CardinalityColumns cardinalityColumns = storagePartCardinalities();
			return new SortIndexStoragePart(
				entityIndexPrimaryKey, this.attributeIndexKey, this.comparatorBase,
				getSortedRecords(), storagePartSortedValues(),
				cardinalityColumns.values(), cardinalityColumns.cardinalities(),
				this.indexedDecimalPlaces,
				null
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
	 * touching the shared index, seeded with this index's value-space comparator so newly inserted values keep sort
	 * order (the shared tree's comparator in view mode).
	 */
	@Override
	public SortIndexChanges createLayer() {
		return new SortIndexChanges(this, effectiveComparator());
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
		removeValueSideLayer(transactionalLayer);
	}

	/**
	 * Produces the committed copy of this index with all transactional changes merged in. When nothing changed
	 * (dirty flag is `false` after merge) the original instance is returned unchanged to avoid needless copying;
	 * otherwise a new {@code SortIndex} is built from the committed copies of each sub-structure (the merged
	 * sorted-records façade is shared by both variants; the value side is merged by {@link #copyWithMergedValueSide}).
	 */
	@Nonnull
	@Override
	public final SortIndex createCopyWithMergedTransactionalMemory(
		@Nullable SortIndexChanges layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// we can safely throw away dirty flag now
		final boolean isDirty = transactionalLayer
			.getStateCopyWithCommittedChanges(this.dirty);
		if (isDirty) {
			return copyWithMergedValueSide(
				transactionalLayer.getStateCopyWithCommittedChanges(this.sortedRecords),
				transactionalLayer
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
		return new SortedComparableForwardSeeker(this::valueCursor, this.size());
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
		return new ReversedSortedComparableForwardSeeker(this::valueReverseCursor, this.size());
	}

	/**
	 * Returns the normalizer governing this index's VALUE SPACE. Owner mode keeps its own {@link #normalizer} (built from
	 * {@link #comparatorBase}); view mode operates entirely in the shared tree's normalized space — so OffsetDateTime keys
	 * are folded to Instant, localized Strings to NFD, etc. — and therefore adopts the shared {@link InvertedIndex}'s
	 * normalizer. This reconciliation is mandatory: a value derived from the shared tree (a bucket key) and a value the
	 * SortIndex itself normalizes MUST live in one space or comparisons throw / silently mismatch.
	 */
	@Nonnull
	protected abstract UnaryOperator<Serializable> effectiveNormalizer();

	/**
	 * Returns the comparator governing this index's VALUE SPACE. Owner mode keeps its own {@link #comparator}; view mode
	 * adopts the shared tree's comparator (which orders the shared normalized keys — e.g. Instant, NFD String). Every
	 * comparison of values that originate from / are looked up in the shared tree must use this comparator, never the raw
	 * own comparator.
	 */
	@Nonnull
	@SuppressWarnings("rawtypes")
	protected abstract Comparator effectiveComparator();

	/**
	 * Returns `true` when the (already-normalized) value is currently present in this index — owner mode consults its own
	 * value tree, view mode the shared inverted index. Used by the `getRecordsEqualTo` and `addRecord` callers.
	 */
	protected abstract boolean valuePresent(@Nonnull Serializable normalizedValue);

	/**
	 * Presence check for the `removeRecord` precondition. Owner mode keeps the value-based tree check; view mode asserts
	 * the record's own presence against {@link #sortedRecords} (the structure the sort block is about to mutate) because
	 * the value's cardinality lives in the shared tree which may legitimately hold many records for the value.
	 */
	protected abstract boolean valuePresentForRemoval(@Nonnull Serializable normalizedValue, int recordId);

	/**
	 * Returns the value-side distinct values to persist into the {@link SortIndexStoragePart}. Owner mode emits its full
	 * ordered values; view mode emits an empty array (the slim part re-derives them from the shared FILTER part on load).
	 */
	@Nonnull
	protected abstract Serializable[] storagePartSortedValues();

	/**
	 * Returns the value-side sparse cardinality columns to persist into the {@link SortIndexStoragePart}. Owner mode walks
	 * its value tree once, emitting only the `cardinality > 1` values in ascending order (no intermediate map); view mode
	 * emits empty columns (the slim part re-derives them from the shared FILTER part on load).
	 */
	@Nonnull
	protected abstract CardinalityColumns storagePartCardinalities();

	/**
	 * Pre-removal cardinality of a value whose presence is guaranteed by the public `removeRecord` callers (always
	 * `>= 1`). Owner mode reads its inline tree cardinality with a fail-fast guard; view mode reads the shared inverted
	 * index in its pre-removal state (the SORT block runs before the FILTER block removes the value).
	 */
	protected abstract int preRemovalCardinality(@Nonnull Serializable normalizedValue);

	/**
	 * Value-side maintenance when a value's FIRST record is inserted. Owner mode inserts the new value into its tree with
	 * cardinality `1`; view mode is a no-op (the shared tree is mutated by the FILTER block).
	 */
	protected abstract void onFirstRecordForValue(@Nonnull Serializable normalizedValue);

	/**
	 * Value-side maintenance when an already-present value gains another record. Owner mode bumps its inline cardinality;
	 * view mode is a no-op.
	 */
	protected abstract void onValueCardinalityIncreased(@Nonnull Serializable normalizedValue);

	/**
	 * Value-side maintenance when an already-present value (cardinality `> 1`) loses one record. Owner mode decrements its
	 * inline cardinality; view mode is a no-op.
	 */
	protected abstract void onValueCardinalityDecreased(@Nonnull Serializable normalizedValue);

	/**
	 * Value-side maintenance when a value's LAST record is removed. Owner mode deletes the value from its tree; view mode
	 * is a no-op.
	 */
	protected abstract void onLastRecordForValueRemoved(@Nonnull Serializable normalizedValue);

	/**
	 * Discards the value-side transactional layer (owner mode removes its value tree's layer; view mode is a no-op).
	 */
	protected abstract void removeValueSideLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer);

	/**
	 * Builds the committed copy of the right concrete variant, merging the value side. The base has already merged the
	 * shared {@link #sortedRecords} façade and passes it in. Owner mode merges its value tree into a new
	 * {@link OwnerSortIndex}; view mode wraps the supplier into a new {@link SortIndexView} (its committed shared tree is
	 * re-bound by the parent's `createCopy`).
	 *
	 * @param mergedSortedRecords the committed copy of {@link #sortedRecords}
	 * @param transactionalLayer  the maintainer providing committed copies of value-side sub-structures
	 * @return the committed copy of this index
	 */
	@Nonnull
	protected abstract SortIndex copyWithMergedValueSide(
		@Nonnull TransactionalUnorderedIntArray mergedSortedRecords,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	);

	/**
	 * Returns a copy of this index bound to the freshly-committed shared `committedSharedTree`, so a view-mode index never
	 * references a stale pre-commit tree. The result is a NEW immutable instance when the tree differs (sharing the
	 * committed sorted-records façade), or `this` when the tree is identity-unchanged (carry-forward) — never an in-place
	 * mutation, so the returned value is safe to share across snapshot versions. Package-private — called by
	 * {@link AttributeIndex} from its `createCopy`. A no-op (`this`) for owner-mode indexes (they own their value tree).
	 *
	 * @param committedSharedTree the committed shared tree for this key (ignored in owner mode; may be `null`)
	 * @return a view bound to the committed tree, or `this` when nothing changed
	 */
	@Nonnull
	abstract SortIndex bindSharedTree(@Nullable InvertedIndex committedSharedTree);

	/**
	 * Returns the cardinality (number of records sharing the value, always `>= 1`) of a value known to be present.
	 * Owner mode reads its inline tree cardinality — where every present value is stored explicitly, so an absent value
	 * is a broken invariant and throws {@link io.evitadb.exception.GenericEvitaInternalError}. View mode reads the shared
	 * inverted index in its pre-mutation state (the SORT block runs before the FILTER block updates the shared tree), so
	 * the value may legitimately not be reflected there yet and the cardinality is floored to `1`.
	 */
	abstract int getValueCardinality(@Nonnull Serializable value);

	/**
	 * Returns the number of distinct values held by this index — owner mode the value tree size, view mode the shared
	 * inverted index's distinct buckets. Used by {@link SortIndexChanges#getValueTree}.
	 */
	abstract int valueCount();

	/**
	 * Returns an ordered ascending `(value, cardinality)` cursor over this index's distinct values — owner mode walks the
	 * value tree, view mode the shared inverted index's buckets.
	 */
	@Nonnull
	abstract ValueCardinalityCursor valueCursor();

	/**
	 * Returns an ordered descending `(value, cardinality)` cursor — reverse counterpart of {@link #valueCursor()} used by
	 * the reversed seeker.
	 */
	@Nonnull
	abstract ValueCardinalityCursor valueReverseCursor();

	/**
	 * Shared internal implementation of the record insertion. The value-side maintenance (owner tree mutation, no-op for
	 * views) is delegated to the {@link #onFirstRecordForValue} / {@link #onValueCardinalityIncreased} hooks, preserving
	 * the original "value-side first, then help structure" ordering.
	 */
	private void addRecordInternal(@Nonnull Serializable normalizedValue, int recordId) {
		final SortIndexChanges sortIndexChanges = getOrCreateSortIndexChanges();

		// prepare internal datastructures
		sortIndexChanges.prepare();

		// add record id on the computed position
		final int previousRecordId = sortIndexChanges.computePreviousRecord(normalizedValue, recordId);
		this.sortedRecords.add(previousRecordId, recordId);

		// determine whether the value already existed BEFORE this record. In view mode the shared tree is read in
		// its pre-insert state (the SORT block runs before the FILTER block writes the value), so a positive
		// cardinality means the value was already present; owner mode consults its own value tree.
		final boolean valueAlreadyPresent = valuePresent(normalizedValue);
		if (valueAlreadyPresent) {
			// value is already present - owner mode bumps its inline cardinality (view mode shares the tree)
			onValueCardinalityIncreased(normalizedValue);
			// update help data structure
			sortIndexChanges.valueCardinalityIncreased(normalizedValue);
		} else {
			// insert new value into the tree with cardinality of one (owner mode only)
			onFirstRecordForValue(normalizedValue);
			// update help data structure
			sortIndexChanges.valueAdded(normalizedValue);
		}

		this.dirty.setToTrue();
	}

	/**
	 * Shared internal implementation of the record removal. The value's presence (cardinality `>= 1`) is guaranteed by
	 * the public {@code removeRecord} callers, so the three-way legacy branch (`> 2` / `== 2` / absent) collapses to a
	 * decrement, with the value deleted once its cardinality would reach zero. The value-side maintenance is delegated to
	 * the {@link #onValueCardinalityDecreased} / {@link #onLastRecordForValueRemoved} hooks, preserving the original
	 * per-branch ordering relative to the help-structure updates.
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
		// pre-removal cardinality of the value (always >= 1). In view mode the shared tree is read in its
		// pre-removal state (the SORT block runs before the FILTER block removes the value), so it still counts
		// this record; owner mode reads its own value tree.
		final int cardinality = preRemovalCardinality(normalizedValue);
		if (cardinality < 1) {
			throw new GenericEvitaInternalError("Unexpected cardinality: " + cardinality);
		} else if (cardinality > 1) {
			// more than one record shares the value - owner mode decrements its inline cardinality
			sortIndexChanges.valueCardinalityDecreased(normalizedValue);
			onValueCardinalityDecreased(normalizedValue);
		} else {
			// last record for the value - owner mode removes the value entirely
			onLastRecordForValueRemoved(normalizedValue);
			sortIndexChanges.valueRemoved(normalizedValue);
		}

		this.dirty.setToTrue();
	}

	/**
	 * Returns bitmap of all record ids connected with the value in the argument.
	 */
	@Nonnull
	private <T extends Serializable> BaseBitmap getRecordsEqualToInternal(@Nonnull T normalizedValue) {
		// block start = cumulative weight (rank) of all strictly-smaller values, answered in O(log V) by the value tree
		// (the value space is shared in view mode and the tree carries the effective-comparator ordering)
		final int recordIdIndex = getOrCreateSortIndexChanges().computeBlockStart(normalizedValue);

		// cardinality is stored inline in the tree and is always >= 1
		final int cardinality = getValueCardinality(normalizedValue);
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
	 * Retrieves or creates temporary data structure. When transaction exists, it is created in the transactional memory
	 * space so that other threads are not affected by the changes in the {@link SortIndex}.
	 */
	@Nonnull
	private SortIndexChanges getOrCreateSortIndexChanges() {
		final SortIndexChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return ofNullable(this.sortIndexChanges).orElseGet(() -> {
				// value-space comparator (shared tree's in view mode)
				this.sortIndexChanges = new SortIndexChanges(this, effectiveComparator());
				return this.sortIndexChanges;
			});
		} else {
			return layer;
		}
	}

	/**
	 * Mode-agnostic ordered cursor over `(value, cardinality)` pairs of a {@link SortIndex}'s distinct values. Owner mode
	 * is backed by the {@link OwnerSortIndex} value B+ tree; view mode by the shared {@link InvertedIndex}'s buckets. Lets
	 * {@link SortIndexChanges} and the seekers consume the same ordered stream regardless of mode.
	 */
	interface ValueCardinalityCursor {
		/**
		 * Returns `true` if another `(value, cardinality)` pair is available.
		 */
		boolean hasNext();

		/**
		 * Advances to the next pair and returns its value. Must be paired with {@link #cardinality()} for the same pair.
		 */
		@Nonnull
		Serializable next();

		/**
		 * Returns the cardinality of the pair the cursor currently sits on (after {@link #next()}).
		 */
		int cardinality();
	}

	/**
	 * The value-side sparse cardinality columns handed to {@link SortIndexStoragePart}: positionally-aligned distinct
	 * values and their cardinalities, holding only entries whose `cardinality > 1` (cardinality `1` is implied). Owner
	 * mode fills these in ascending value order directly from its value tree; view mode hands back empty columns.
	 *
	 * @param values       the distinct values with cardinality `> 1`, in ascending order
	 * @param cardinalities the cardinalities (each `> 1`) positionally aligned with `values`
	 */
	public record CardinalityColumns(
		@Nonnull Serializable[] values,
		@Nonnull int[] cardinalities
	) {
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
		 * compare the backing array by identity and break lookups in the owner value tree.
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
		 * NULL handling). This method exists only to satisfy the {@link Comparable} key bound of the owner value tree,
		 * which never invokes it because a comparator is always supplied, so a direct call is a programming error and
		 * fails fast.
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
	 * `(value, cardinality)` entry cursor over the index's distinct values and accumulating cardinalities. Cardinality is
	 * read inline from each entry, so no separate cardinality lookup is needed.
	 *
	 * The contract is monotonic: callers request strictly non-decreasing positions (enforced by an assert), which lets
	 * a single forward cursor satisfy the whole traversal in `O(distinct values)` total.
	 */
	private static class SortedComparableForwardSeeker
		implements SortedRecordsSupplierFactory.SortedComparableForwardSeeker {
		/**
		 * Factory of forward `(value, cardinality)` cursors over the owning {@link SortIndex}'s distinct values
		 * (mode-agnostic — owner mode walks the B+ tree, view mode the shared inverted index). Recreated on {@link #reset()}.
		 */
		@Nonnull
		private final Supplier<ValueCardinalityCursor> cursorFactory;
		/**
		 * The total count of all records in the sorted collection.
		 */
		private final int totalCount;
		/**
		 * Forward `(value, cardinality)` cursor; recreated on {@link #reset()}.
		 */
		private ValueCardinalityCursor cursor;
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

		public SortedComparableForwardSeeker(@Nonnull Supplier<ValueCardinalityCursor> cursorFactory, int totalCount) {
			this.cursorFactory = cursorFactory;
			this.totalCount = totalCount;
			reset();
		}

		@Override
		public void reset() {
			this.cursor = this.cursorFactory.get();
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
				this.currentValue = this.cursor.next();
				this.indexPeak += this.cursor.cardinality();
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
	 * to its value by walking a reverse `(value, cardinality)` entry cursor over the index's distinct values from the
	 * largest key downward, subtracting cardinalities from the running total. Cardinality is read inline from each entry.
	 *
	 * The contract is monotonic in the reverse direction (positions are requested in non-increasing order, enforced by
	 * an assert), so a single reverse cursor satisfies the whole traversal.
	 */
	private static class ReversedSortedComparableForwardSeeker
		implements SortedRecordsSupplierFactory.SortedComparableForwardSeeker {
		/**
		 * Factory of reverse `(value, cardinality)` cursors over the owning {@link SortIndex}'s distinct values
		 * (mode-agnostic). Recreated on {@link #reset()}.
		 */
		@Nonnull
		private final Supplier<ValueCardinalityCursor> cursorFactory;
		/**
		 * The total count of all records in the sorted collection.
		 */
		private final int totalCount;
		/**
		 * Reverse `(value, cardinality)` cursor; recreated on {@link #reset()}.
		 */
		private ValueCardinalityCursor cursor;
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

		public ReversedSortedComparableForwardSeeker(
			@Nonnull Supplier<ValueCardinalityCursor> cursorFactory, int totalCount) {
			this.cursorFactory = cursorFactory;
			this.totalCount = totalCount;
			reset();
		}

		@Override
		public void reset() {
			this.cursor = this.cursorFactory.get();
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
				this.currentValue = this.cursor.next();
				this.indexPeak -= this.cursor.cardinality();
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
