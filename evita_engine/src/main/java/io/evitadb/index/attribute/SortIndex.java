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
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import io.evitadb.utils.VMLayout;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.Iterator;
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
 * The class is an `abstract sealed` base of a two-variant family. Both variants back the value side with an
 * {@link InvertedIndex} (value → records bitmap) exposed through {@link #valueTreeOrNull()}, so the value-side READS
 * (ordered value/cardinality cursors, presence, distinct-value reconstruction) are shared in this base:
 *
 * - {@link OwnerSortIndex} OWNS its own {@link InvertedIndex} and is the full source of truth for value ordering and
 * cardinality. It backs sort-only single attributes and ALL sortable attribute compounds.
 * - {@link SortIndexView} owns ONLY its sort-specific {@link #sortedRecords} ordering and sources the value ordering /
 * cardinality / comparator / normalizer from the shared {@link InvertedIndex} owned by {@link AttributeIndex} (a
 * both-filterable-and-sortable single attribute). It is still a producer (it commits {@link #sortedRecords}).
 *
 * The base orchestrates the {@link #sortedRecords} façade and the {@link SortIndexChanges} help structure that both
 * variants share, and reads the value side through {@link #valueTreeOrNull()}; the few value-side operations that still
 * differ between owner and view (the write hooks, {@link #getValueCardinality(Serializable)}, the persisted columns and
 * {@link #effectiveNormalizer()}) are expressed as the abstract hooks below instead of a runtime flag.
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
	 * view mode adopts the shared tree's comparator and normalizer.
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
	 * Memoized ascending-direction supplier arrays for THIS committed snapshot — lazily built by
	 * {@link #getAscendingOrderRecordsSupplier()} whenever no transactional layer for this index exists on the current
	 * thread (a read-only / no-transaction query, or a write transaction that has not yet written to this index), and
	 * shared, unsynchronized, across every reader thread until it is either dropped or superseded by a new
	 * {@code SortIndex} instance (see {@link #createCopyWithMergedTransactionalMemory}). This single cache backs BOTH
	 * sort directions: a descending supplier reads these same ascending arrays and applies the reverse/invert transform
	 * lazily (see {@link SortDirectionBacking#DESCENDING_MIRRORS_ASCENDING}), so no reversed/inverted arrays are cached.
	 *
	 * Two invalidation regimes keep it correct:
	 * - In the transactional (`ALIVE`) world, writes are always routed through a per-transaction {@link SortIndexChanges}
	 *   layer and never mutate this instance's own {@link #sortedRecords} in place (see the class javadoc); the next
	 *   commit that actually changes this index produces a brand-new {@code SortIndex} instance whose cache field starts
	 *   `null` again — so nothing to invalidate here.
	 * - In the non-transactional (bulk-load / warm-up) path, {@link #sortedRecords} CAN be mutated in place and no new
	 *   instance is minted, so {@link #invalidateCommittedSnapshotCacheIfNonTransactional()} explicitly clears this
	 *   field on every in-place add/remove, forcing the next read to rebuild.
	 *
	 * `volatile` for cross-thread visibility only; concurrent first-touch races are tolerated (both racing threads
	 * compute an equal, pure value and the record's `final` fields guarantee safe publication regardless of which
	 * write wins) — the same pattern as {@code PersistentTransactionalMap#state}.
	 */
	@Nullable private transient volatile SortIndexChanges.MaterializedSortRecords cachedAscendingArrays;

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
				sortedRecords, sortedRecordValues, cardinalities
			);
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
	 * Returns array of naturally sorted comparable values, reconstructed from the value-side {@link InvertedIndex}
	 * ({@link #valueTreeOrNull()}) by walking its buckets in comparator order and reading each bucket's value. Owner mode
	 * walks its owned tree; view mode the shared one (an empty array when the tree is transiently absent). Method is
	 * targeted to be used in SERIALIZATION and nowhere else.
	 */
	@Nonnull
	public Serializable[] getSortedRecordValues() {
		final InvertedIndex tree = valueTreeOrNull();
		if (tree == null) {
			return ArrayUtils.EMPTY_SERIALIZABLE_ARRAY;
		}
		final Serializable[] result = new Serializable[tree.getBucketCount()];
		final Iterator<ValueToRecord> it = tree.getValueIterator();
		int index = 0;
		while (it.hasNext()) {
			result[index++] = it.next().getValue();
		}
		return result;
	}

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

	/**
	 * Returns the number of distinct values this index orders by - the public form of {@link #valueCount()}, read from
	 * the value tree's cached bucket counter and therefore `O(1)`.
	 *
	 * Read against {@link #size()} it says how large the blocks of ties are: a sort index with few distinct values
	 * imposes almost no ordering.
	 *
	 * @return number of distinct ordering values
	 */
	public int getDistinctValueCount() {
		return valueCount();
	}

	/**
	 * Returns {@link SortedRecordsSupplier} that contains record ids sorted by value in ascending order.
	 *
	 * A plain query opens no transaction at all (read-only sessions never do, and even a read-write session only binds
	 * a {@link Transaction} while a write is executing), so the per-transaction {@link SortIndexChanges} layer (see
	 * {@link #getOrCreateSortIndexChanges()}) never survives long enough for its array memoization to pay off. The fast
	 * path below therefore keys off the ONLY thing that matters for correctness: whether a transactional layer for THIS
	 * index exists on the current thread. When none does — a read-only / no-transaction query, OR a write transaction
	 * that has not yet written to this index — the committed base state ({@link #sortedRecords}) is guaranteed untouched
	 * (an `ALIVE` commit mints a brand-new instance; the only in-place mutation is the non-transactional warm-up / bulk
	 * path, which drops the cache via {@link #invalidateCommittedSnapshotCacheIfNonTransactional()}), so the request is
	 * served from this instance's own long-lived cache ({@link #cachedAscendingArrays} et al.), reused across every
	 * query against this snapshot. As soon as a write actually touches this index within a transaction, its layer
	 * exists and the per-transaction path below takes over again, preserving read-your-own-writes.
	 */
	@Nonnull
	@Override
	public SortedRecordsSupplier getAscendingOrderRecordsSupplier() {
		if (Transaction.getTransactionalMemoryLayerIfExists(this) == null) {
			return buildCachedSupplier(false, createSortedComparableForwardSeeker());
		}
		return getOrCreateSortIndexChanges().getAscendingOrderRecordsSupplier();
	}

	/**
	 * Returns {@link SortedRecordsSupplier} that contains record ids sorted by value in descending order.
	 *
	 * See {@link #getAscendingOrderRecordsSupplier()} for the full rationale of the committed-snapshot cache fast
	 * path taken below.
	 */
	@Nonnull
	@Override
	public SortedRecordsSupplier getDescendingOrderRecordsSupplier() {
		if (Transaction.getTransactionalMemoryLayerIfExists(this) == null) {
			return buildCachedSupplier(true, createReversedSortedComparableForwardSeeker());
		}
		return getOrCreateSortIndexChanges().getDescendingOrderRecordsSupplier();
	}

	/**
	 * Builds a tree-backed {@link SortedRecordsSupplier} resolving positions straight from {@link #sortedRecords},
	 * with its materialized arrays exposed LAZILY through this instance's own long-lived committed-snapshot cache
	 * ({@link #cachedAscendingArrays}) — mirrors {@code SortIndexChanges#buildSupplier}, but backed by a cache that
	 * outlives the query rather than by a per-transaction layer.
	 *
	 * Deliberately lazy, not eager: a sparse selection resolves entirely by tree probe and NEVER reads the arrays, so
	 * warming them at construction time would burn an `O(N log N)` materialization on every ORDER BY — including the
	 * churn-heavy, sparse-only workloads the streaming tree was built to keep allocation-free. Instead the supplier is
	 * flagged {@link DenseSelectionWarmup#WARM_AND_REUSE}: only when a DENSE selection actually needs the arrays does the
	 * dispatch pull them through the accessors below, materializing the long-lived cache once and reusing it for every
	 * later dense query against this snapshot (see {@link SortedRecordsSupplier#resolvePositions}).
	 *
	 * Both directions share the single ascending cache: a descending supplier is flagged
	 * {@link SortDirectionBacking#DESCENDING_MIRRORS_ASCENDING} and applies the reverse/invert transform lazily, so the descending
	 * direction never materializes or caches reversed/inverted arrays of its own.
	 *
	 * @param descending whether the descending order is requested
	 * @param seeker     the freshly built value seeker for this direction
	 * @return the lazily-cache-backed sorted-records supplier for the requested direction
	 */
	@Nonnull
	private SortedRecordsSupplier buildCachedSupplier(
		boolean descending,
		@Nonnull SortedRecordsSupplierFactory.SortedComparableForwardSeeker seeker
	) {
		final int recordCount = this.sortedRecords.getLength();
		final long transactionalId = descending ? this.id : this.sortedRecords.getId();
		// BOTH directions read the SAME ascending snapshot cache (get-or-build); a descending supplier applies the
		// reverse/invert transform lazily (recordAt mirror + merge-walk mirror + on-demand public getters), so no
		// reversed/inverted arrays are ever materialized or cached - see SortDirectionBacking#DESCENDING_MIRRORS_ASCENDING.
		// Consulted only by the dense array merge-walk, never by the sparse probe.
		final Supplier<int[]> sortedRecordIdsSupplier = () -> getCachedAscendingArrays().sortedRecordIds();
		final Supplier<int[]> recordPositionsSupplier = () -> getCachedAscendingArrays().recordPositions();
		final Supplier<Bitmap> allRecordsSupplier = () -> getCachedAscendingArrays().allRecords();
		// committed-snapshot arrays live in a long-lived cache, so a dense selection warms and reuses them
		final SortDirectionBacking directionBacking = descending
			? SortDirectionBacking.DESCENDING_MIRRORS_ASCENDING
			: SortDirectionBacking.ASCENDING;
		return SortedRecordsSupplier.createTreeBacked(
			transactionalId, this.sortedRecords, recordCount,
			sortedRecordIdsSupplier, recordPositionsSupplier, allRecordsSupplier, seeker, this.referenceKey,
			DenseSelectionWarmup.WARM_AND_REUSE, directionBacking
		);
	}

	/**
	 * Lazily materializes and caches the ascending-direction supplier arrays for this committed snapshot in a SINGLE
	 * tree walk + sort (via {@link TransactionalUnorderedIntArray#materialize()}, which yields the record-id order, the
	 * record positions and the shared record-id bitmap together); see {@link #cachedAscendingArrays} for the
	 * invalidation contract.
	 */
	@Nonnull
	private SortIndexChanges.MaterializedSortRecords getCachedAscendingArrays() {
		SortIndexChanges.MaterializedSortRecords current = this.cachedAscendingArrays;
		if (current == null) {
			final TransactionalUnorderedIntArray.MaterializedOrder order = this.sortedRecords.materialize();
			current = new SortIndexChanges.MaterializedSortRecords(
				this.sortedRecords.getId(),
				order.sortedRecordIds(),
				order.recordPositions(),
				order.allRecords()
			);
			this.cachedAscendingArrays = current;
		}
		return current;
	}

	/**
	 * Drops the committed-snapshot supplier cache ({@link #cachedAscendingArrays}, shared by both sort directions) when
	 * — and only when — the caller just mutated {@link #sortedRecords} IN PLACE, i.e. outside any transactional layer
	 * (the non-transactional warm-up / bulk-load regime). In the transactional (`ALIVE`) world a mutation is captured in
	 * a per-transaction layer and the committed base is never touched, so the cache stays valid and a real commit mints
	 * a fresh instance whose cache starts empty; only the in-place path needs explicit invalidation, because there no
	 * new instance is minted to carry a clean cache. Detected by the absence of a transactional layer for this index on
	 * the current thread — the same predicate the read fast path gates on.
	 */
	private void invalidateCommittedSnapshotCacheIfNonTransactional() {
		if (Transaction.getTransactionalMemoryLayerIfExists(this) == null) {
			this.cachedAscendingArrays = null;
		}
	}

	/**
	 * Emits this sort index's modified storage part into `sink` on the commit/flush path — the single persistence
	 * entry point for a sort index, mirroring the UNIQUE/FILTER indexes (see
	 * {@link AttributeIndex#getModifiedStorageParts}). A clean index emits nothing. A dirty index emits one
	 * {@link SortIndexStoragePart}: owner mode persists the full distinct values + cardinalities, view mode persists a
	 * slim part whose values/cardinalities are re-derivable from the shared FILTER part on load (see
	 * {@link #storagePartSortedValues()} / {@link #storagePartCardinalities()}).
	 *
	 * @param entityIndexPrimaryKey the owning entity index primary key
	 * @param sink                  the trapped-changes accumulator for this commit
	 */
	public void appendStorageParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges sink) {
		if (this.dirty.isTrue()) {
			// all data are persisted to disk - we may get rid of temporary, modification only helper container
			this.sortIndexChanges = null;
			// mode-specific emission: a view emits the slim SINGLE part, an owner emits the granular PAGED shape (one leaf
			// page per changed leaf + a PAGED root) for a multi-leaf value tree and the inline SINGLE part otherwise
			doAppendStorageParts(entityIndexPrimaryKey, sink);
		}
	}

	/**
	 * Returns the leaf-page sequences this sort index WILL have on disk once the in-flight commit is durable, or an
	 * empty array. A VIEW (which reuses the FILTER family's shared tree and owns no pages of its own) and a SINGLE /
	 * never-paged owner return empty; a PAGED OWNER overrides this to return its current on-disk page set so the owning
	 * {@link AttributeIndex} can reclaim those pages if the whole sub-index is later emptied and dropped from its map —
	 * after which this index's own flush never runs again — instead of leaking them forever.
	 *
	 * @return the current on-disk leaf-page sequences, or an empty array when the index owns no leaf pages
	 */
	@Nonnull
	public int[] currentLeafPageSequences() {
		return ArrayUtils.EMPTY_INT_ARRAY;
	}

	/**
	 * Clears the dirty flag once the current state has been flushed via
	 * {@link #appendStorageParts(int, TrappedChanges)}, so the index is no longer reported as needing persistence.
	 */
	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	/**
	 * Returns the heap this index occupies, in bytes.
	 *
	 * Each variant adds its own value side — {@code OwnerSortIndex} the inverted index it owns, {@code SortIndexView}
	 * only a slot, because the tree it points at belongs to the enclosing {@code AttributeIndex}. Everything the two
	 * have in common is priced by {@link #getSharedHeapSizeInBytes}.
	 *
	 * Like every walk over a tree or an ordered array this is `O(records)` rather than `O(1)`, so it belongs to
	 * the index detail call and must never be called from a query path.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public abstract long getHeapSizeInBytes();

	/**
	 * Prices everything both variants hold, given how many bytes of fields the concrete subclass adds.
	 *
	 * A subclass's fields live in the **same allocation** as the base's — one header, one round of padding — so the
	 * subclass passes its field bytes in rather than sizing a second object and adding it, which would charge a
	 * phantom header and round twice.
	 *
	 * # What is charged, and what is not
	 *
	 * - {@link #sortedRecords} and {@link #dirty} in full, and {@link #comparatorBase} with every
	 *   {@link ComparatorSource} record in it. The descriptor array is handed **by reference** to the successor
	 *   instance on every commit-merge, so it is shared with a version this one supersedes — charged in full on both
	 *   sides, since the predecessor is garbage the moment the commit completes.
	 * - {@link #sortIndexChanges} when present, which is whenever the catalog is in bulk-insert or read-only state.
	 *   It prices only its own fields; the back-reference to this index is never followed.
	 * - {@link #cachedAscendingArrays} when built, bitmap included — an independent `materialize()` produced it, so
	 *   it shares nothing with the layer's own memos. Being lazily built it makes the figure **jump on the first
	 *   sorted read**: an index that has never served a sort reports less than the identical index that has.
	 * - {@link #normalizer} and {@link #comparator} contribute their **slot alone**, despite being built here rather
	 *   than injected. They are fixed scaffolding chosen by the attribute schema — a wrapper around a natural-order
	 *   or collating comparator, one per compound element — whose size is a few hundred bytes at most and does not
	 *   grow with the indexed data. Pricing them exactly would mean a heap method on every comparator implementation
	 *   in the codebase, and a collating one additionally drags the ~30 KB per-locale collation tables that belong to
	 *   the JVM rather than to any index.
	 * - {@link #referenceKey} and {@link #attributeIndexKey} contribute their slot: both are the enclosing index's,
	 *   handed to every sub-index under it.
	 *
	 * @param ownFieldBytes the field bytes the concrete subclass adds to the base's own
	 * @return the heap footprint in bytes of everything both variants share, including alignment padding
	 */
	protected final long getSharedHeapSizeInBytes(long ownFieldBytes) {
		final VMLayout layout = VMLayout.current();
		// id + indexedDecimalPlaces, then the sortedRecords / comparatorBase / normalizer / comparator / referenceKey
		// / attributeIndexKey / dirty / sortIndexChanges / cachedAscendingArrays slots
		long size = layout.sizeOfObject(
			Long.BYTES + Integer.BYTES + 9L * layout.referenceSize() + ownFieldBytes
		)
			+ this.dirty.getHeapSizeInBytes()
			+ this.sortedRecords.getHeapSizeInBytes()
			// every ComparatorSource component addresses a Class or an enum constant, both JVM-owned - the records
			// themselves are this index's, their contents are nobody's
			+ layout.sizeOfArray(this.comparatorBase.length, layout.referenceSize())
			+ this.comparatorBase.length * layout.sizeOfObject(3L * layout.referenceSize());
		if (this.sortIndexChanges != null) {
			size += this.sortIndexChanges.getHeapSizeInBytes();
		}
		// read the volatile field ONCE: a concurrent reader can publish or drop the cache between two reads
		final SortIndexChanges.MaterializedSortRecords ascending = this.cachedAscendingArrays;
		if (ascending != null) {
			size += SortIndexChanges.sizeOfMaterializedSortRecords(ascending, true);
		}
		return size;
	}

	/**
	 * Creates the per-transaction change buffer ({@link SortIndexChanges}) that captures modifications without
	 * touching the shared index, seeded with this index's value-space comparator so newly inserted values keep sort
	 * order (the shared tree's comparator in view mode).
	 */
	@Override
	public SortIndexChanges createLayer() {
		return new SortIndexChanges(this);
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
	 * Builds and emits the inline SINGLE {@link SortIndexStoragePart} root from this index's mode-specific persisted
	 * columns. Owner mode produces the full distinct values + sparse cardinality columns directly from its value tree (no
	 * intermediate map); view mode emits empty columns (re-derived from the shared FILTER part on load), yielding the slim
	 * part. Shared by both the view {@link #doAppendStorageParts} and the owner's SINGLE branch.
	 *
	 * @param entityIndexPrimaryKey the owning entity index primary key
	 * @param sink                  the trapped-changes accumulator for this commit
	 */
	protected final void appendSingleStoragePart(int entityIndexPrimaryKey, @Nonnull TrappedChanges sink) {
		final CardinalityColumns cardinalityColumns = storagePartCardinalities();
		sink.addChangeToStore(
			new SortIndexStoragePart(
				entityIndexPrimaryKey, this.attributeIndexKey, this.comparatorBase,
				storagePartSortedRecords(), storagePartSortedValues(),
				cardinalityColumns.values(), cardinalityColumns.cardinalities(),
				this.indexedDecimalPlaces,
				null
			)
		);
	}

	/**
	 * Emits this (dirty) index's modified storage parts into `sink`. View mode delegates to
	 * {@link #appendSingleStoragePart(int, TrappedChanges)} (the slim part); owner mode chooses between the granular PAGED
	 * shape (a multi-leaf value tree → one {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPagePart}
	 * per changed leaf + a PAGED root re-emitted only when the live leaf-page list changed) and the inline SINGLE
	 * shape (a single-leaf tree, possibly just collapsed from
	 * PAGED). Invoked by {@link #appendStorageParts(int, TrappedChanges)} only after the dirty gate passes.
	 *
	 * @param entityIndexPrimaryKey the owning entity index primary key
	 * @param sink                  the trapped-changes accumulator for this commit
	 */
	protected abstract void doAppendStorageParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges sink);

	/**
	 * Returns the value-side {@link InvertedIndex} (value → records bitmap) this index reads its ordered values and
	 * per-value cardinalities from. Owner mode returns its OWNED tree (always non-null); view mode returns the shared tree
	 * owned by {@link AttributeIndex}, which may be transiently `null` (a brand-new key whose tree the FILTER block has not
	 * created yet, or a both-flagged remove that dropped the key mid-transaction). The base value-side reads below all
	 * tolerate a `null` tree by yielding the empty result.
	 */
	@Nullable
	protected abstract InvertedIndex valueTreeOrNull();

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
	 * Returns `true` when the (already-normalized) value is currently present in this index — i.e. the value-side
	 * {@link InvertedIndex} ({@link #valueTreeOrNull()}) holds a non-empty bucket for it. Used by the `getRecordsEqualTo`
	 * and `addRecord` callers, which read the tree in its pre-mutation state.
	 */
	protected boolean valuePresent(@Nonnull Serializable normalizedValue) {
		final InvertedIndex tree = valueTreeOrNull();
		return tree != null && tree.cardinalityOf(normalizedValue) > 0;
	}

	/**
	 * Presence check for the `removeRecord` precondition, mode-specific because the two modes own the value side
	 * differently. Owner mode validates the VALUE against its owned tree (fail-before-mutate: removing a value the tree
	 * never held is an illegal argument); view mode validates the RECORD's own presence against {@link #sortedRecords}
	 * (the structure the sort block is about to mutate), because the value's cardinality lives in the shared FILTER tree
	 * which may legitimately hold many records for the value.
	 */
	protected abstract boolean valuePresentForRemoval(@Nonnull Serializable normalizedValue, int recordId);

	/**
	 * Returns the positional `sortedRecords` array to persist into the {@link SortIndexStoragePart}. Owner mode emits its
	 * full positional array (its own source of truth for the sort order); view mode emits an EMPTY array — the slim part
	 * omits it and the loader rebuilds it byte-for-byte from the shared FILTER tree's buckets (see
	 * {@link SortIndexView#reconstructSortedRecords}), eliminating the whole-array rewrite on every both-flagged commit.
	 */
	@Nonnull
	protected abstract int[] storagePartSortedRecords();

	/**
	 * Returns the value-side distinct values to persist into the {@link SortIndexStoragePart}. Owner mode emits its full
	 * ordered values; view mode emits an empty array (the slim part re-derives them from the shared FILTER part on load).
	 */
	@Nonnull
	protected abstract Serializable[] storagePartSortedValues();

	/**
	 * Returns the value-side sparse cardinality columns to persist into the {@link SortIndexStoragePart}. Owner mode walks
	 * its owned tree once, emitting only the `cardinality > 1` values in ascending order (no intermediate map); view mode
	 * emits empty columns (the slim part re-derives them from the shared FILTER part on load).
	 */
	@Nonnull
	protected abstract CardinalityColumns storagePartCardinalities();

	/**
	 * Pre-removal cardinality of a value whose presence is guaranteed by the public `removeRecord` callers (always
	 * `>= 1`). Reads the bucket cardinality from the value-side {@link InvertedIndex} ({@link #valueTreeOrNull()}) in its
	 * pre-removal state (the SORT block runs before the FILTER block removes the value). A `0` (absent value / transiently
	 * null tree) is surfaced by {@link #removeRecordInternal}, which throws on `cardinality < 1` — preserving the owner's
	 * broken-invariant guard.
	 */
	protected int preRemovalCardinality(@Nonnull Serializable normalizedValue) {
		final InvertedIndex tree = valueTreeOrNull();
		return tree == null ? 0 : tree.cardinalityOf(normalizedValue);
	}

	/**
	 * Value-side maintenance when a value's FIRST record is inserted. Owner mode adds the record to its owned tree
	 * (creating the bucket); view mode is a no-op (the shared tree is mutated by the FILTER block).
	 */
	protected abstract void onFirstRecordForValue(@Nonnull Serializable normalizedValue, int recordId);

	/**
	 * Value-side maintenance when an already-present value gains another record. Owner mode adds the record to its owned
	 * tree bucket (growing its cardinality); view mode is a no-op.
	 */
	protected abstract void onValueCardinalityIncreased(@Nonnull Serializable normalizedValue, int recordId);

	/**
	 * Value-side maintenance when an already-present value (cardinality `> 1`) loses one record. Owner mode removes the
	 * record from its owned tree bucket (shrinking its cardinality); view mode is a no-op.
	 */
	protected abstract void onValueCardinalityDecreased(@Nonnull Serializable normalizedValue, int recordId);

	/**
	 * Value-side maintenance when a value's LAST record is removed. Owner mode removes the record from its owned tree,
	 * draining and dropping the bucket; view mode is a no-op.
	 */
	protected abstract void onLastRecordForValueRemoved(@Nonnull Serializable normalizedValue, int recordId);

	/**
	 * Discards the value-side transactional layer (owner mode removes its owned tree's layer; view mode is a no-op).
	 */
	protected abstract void removeValueSideLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer);

	/**
	 * Builds the committed copy of the right concrete variant, merging the value side. The base has already merged the
	 * shared {@link #sortedRecords} façade and passes it in. Owner mode merges its owned tree into a new
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
	 * Owner mode reads its owned tree's bucket cardinality — where every present value is stored explicitly, so an absent
	 * value is a broken invariant and throws {@link io.evitadb.exception.GenericEvitaInternalError}. View mode reads the
	 * shared inverted index in its pre-mutation state (the SORT block runs before the FILTER block updates the shared
	 * tree), so the value may legitimately not be reflected there yet and the cardinality is floored to `1`.
	 *
	 * @throws GenericEvitaInternalError in owner mode when the value is absent from the owned tree (a broken invariant —
	 *                                   the caller must have proven presence first)
	 */
	abstract int getValueCardinality(@Nonnull Serializable value);

	/**
	 * Returns the number of distinct values held by this index — the value-side {@link InvertedIndex}
	 * ({@link #valueTreeOrNull()}) distinct-bucket count (`0` when the tree is transiently absent). Used by
	 * {@link SortIndexChanges#getValueTree}.
	 */
	int valueCount() {
		final InvertedIndex tree = valueTreeOrNull();
		return tree == null ? 0 : tree.getBucketCount();
	}

	/**
	 * Returns an ordered ascending `(value, cardinality)` cursor over this index's distinct values, backed by the
	 * value-side {@link InvertedIndex} ({@link #valueTreeOrNull()}) buckets (an empty cursor when the tree is transiently
	 * absent). The tree is read in its pre-mutation state (SORT runs before FILTER).
	 */
	@Nonnull
	ValueCardinalityCursor valueCursor() {
		final InvertedIndex tree = valueTreeOrNull();
		final Iterator<ValueToRecord> it = tree == null
			? Collections.emptyIterator()
			: tree.getValueIterator();
		return new InvertedIndexValueCursor(it);
	}

	/**
	 * Returns an ordered descending `(value, cardinality)` cursor — reverse counterpart of {@link #valueCursor()} used by
	 * the reversed seeker.
	 */
	@Nonnull
	ValueCardinalityCursor valueReverseCursor() {
		final InvertedIndex tree = valueTreeOrNull();
		final Iterator<ValueToRecord> it = tree == null
			? Collections.emptyIterator()
			: tree.getValueReverseIterator();
		return new InvertedIndexValueCursor(it);
	}

	/**
	 * Shared internal implementation of the record insertion. The value-side maintenance (owner tree mutation, no-op for
	 * views) is delegated to the {@link #onFirstRecordForValue} / {@link #onValueCardinalityIncreased} hooks, preserving
	 * the original "value-side first, then help structure" ordering.
	 */
	private void addRecordInternal(@Nonnull Serializable normalizedValue, int recordId) {
		Assert.isPremiseValid(
			recordId != EvitaDataTypes.RESERVED_PRIMARY_KEY,
			"Primary key `" + EvitaDataTypes.RESERVED_PRIMARY_KEY + "` is reserved by evitaDB and must never enter " +
				"an index - it is the sort index's no-predecessor sentinel, so storing it would make the anchor of " +
				"the first record ambiguous!"
		);
		final SortIndexChanges sortIndexChanges = getOrCreateSortIndexChanges();

		// add record id on the computed position - the anchor is answered bucket-locally by the value tree in one
		// descent (no rank computation, no derived structure); a transiently absent shared tree holds no values, so
		// the record belongs first
		final InvertedIndex valueTree = valueTreeOrNull();
		final int previousRecordId = valueTree == null
			? EvitaDataTypes.RESERVED_PRIMARY_KEY
			: valueTree.computePreviousRecord(normalizedValue, recordId);
		this.sortedRecords.add(previousRecordId, recordId);

		// determine whether the value already existed BEFORE this record. In view mode the shared tree is read in
		// its pre-insert state (the SORT block runs before the FILTER block writes the value), so a positive
		// cardinality means the value was already present; owner mode consults its own value tree.
		final boolean valueAlreadyPresent = valuePresent(normalizedValue);
		if (valueAlreadyPresent) {
			// value is already present - owner mode adds the record to its owned tree bucket (view mode shares the tree)
			onValueCardinalityIncreased(normalizedValue, recordId);
		} else {
			// the value's first record - owner mode creates the owned tree bucket (view mode shares the tree)
			onFirstRecordForValue(normalizedValue, recordId);
		}
		// drop the memoized supplier arrays - the sorted record order changed
		sortIndexChanges.sortOrderChanged();

		// a non-transactional (warm-up / bulk) add mutates sortedRecords in place; drop the committed-snapshot cache
		invalidateCommittedSnapshotCacheIfNonTransactional();
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
		// remove record id from the array
		this.sortedRecords.remove(recordId);
		// pre-removal cardinality of the value (always >= 1). In view mode the shared tree is read in its
		// pre-removal state (the SORT block runs before the FILTER block removes the value), so it still counts
		// this record; owner mode reads its own value tree.
		final int cardinality = preRemovalCardinality(normalizedValue);
		if (cardinality < 1) {
			throw new GenericEvitaInternalError("Unexpected cardinality: " + cardinality);
		} else if (cardinality > 1) {
			// more than one record shares the value - owner mode drops the record from its owned tree bucket
			onValueCardinalityDecreased(normalizedValue, recordId);
		} else {
			// last record for the value - owner mode drains and drops the owned tree bucket entirely
			onLastRecordForValueRemoved(normalizedValue, recordId);
		}
		// drop the memoized supplier arrays - the sorted record order changed
		sortIndexChanges.sortOrderChanged();

		// a non-transactional (warm-up / bulk) remove mutates sortedRecords in place; drop the committed-snapshot cache
		invalidateCommittedSnapshotCacheIfNonTransactional();
		this.dirty.setToTrue();
	}

	/**
	 * Returns bitmap of all record ids connected with the value in the argument.
	 */
	@Nonnull
	private <T extends Serializable> BaseBitmap getRecordsEqualToInternal(@Nonnull T normalizedValue) {
		// the records equal to the value are exactly the value's bucket in the value tree, in identical (ascending id)
		// order - served bucket-locally without any rank computation; the array is copied to preserve the historical
		// detached-result contract
		final InvertedIndex valueTree = valueTreeOrNull();
		return valueTree == null
			? new BaseBitmap()
			: new BaseBitmap(valueTree.getRecordsEqualTo(normalizedValue).getArray());
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
				this.sortIndexChanges = new SortIndexChanges(this);
				return this.sortIndexChanges;
			});
		} else {
			return layer;
		}
	}

	/**
	 * Mode-agnostic ordered cursor over `(value, cardinality)` pairs of a {@link SortIndex}'s distinct values. Owner mode
	 * is backed by the {@link OwnerSortIndex}'s owned {@link InvertedIndex} buckets; view mode by the shared
	 * {@link InvertedIndex}'s buckets — both read the same structure, differing only in ownership. Lets
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
	 * {@link ValueCardinalityCursor} backed by a value-side {@link InvertedIndex}'s bucket iterator (the owner's owned
	 * tree or the view's shared tree). Because the SORT block runs before the FILTER block mutates the tree, the tree is
	 * read in its pre-mutation state, so the raw `(value, size())` bucket stream directly yields the ordered cardinalities
	 * the prefix-sum needs — no in-flight compensation. Cardinality is read allocation-free via {@link ValueToRecord#size()}.
	 */
	private static final class InvertedIndexValueCursor implements ValueCardinalityCursor {
		private final Iterator<ValueToRecord> bucketIterator;
		private int cardinality;

		InvertedIndexValueCursor(@Nonnull Iterator<ValueToRecord> bucketIterator) {
			this.bucketIterator = bucketIterator;
		}

		@Override
		public boolean hasNext() {
			return this.bucketIterator.hasNext();
		}

		@Nonnull
		@Override
		public Serializable next() {
			final ValueToRecord bucket = this.bucketIterator.next();
			this.cardinality = bucket.size();
			return bucket.getValue();
		}

		@Override
		public int cardinality() {
			return this.cardinality;
		}
	}

	/**
	 * The value-side sparse cardinality columns handed to {@link SortIndexStoragePart}: positionally-aligned distinct
	 * values and their cardinalities, holding only entries whose `cardinality > 1` (cardinality `1` is implied). Owner
	 * mode fills these in ascending value order directly from its value tree; view mode hands back empty columns.
	 *
	 * @param values        the distinct values with cardinality `> 1`, in ascending order
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
		 * {@link ComparableArray} carries no *domain* ordering of its own: the owning {@link SortIndex} always orders
		 * it through the configured {@link ComparableArrayComparator} (per-element comparators honouring direction and
		 * NULL handling), which the index constructor wires unconditionally. This method deliberately does NOT
		 * reproduce that ordering — it supplies only the element-wise natural order the {@link Comparable} contract
		 * requires, and callers that need the index's ordering must keep using the comparator.
		 *
		 * It must never *refuse* to answer. {@link java.util.HashMap} promotes a bin holding at least eight entries
		 * into a red-black tree and then navigates it by the key's natural order on every hash tie — it accepts
		 * any class declaring `Comparable` of itself, which this record does. A `ComparableArray` used as an ordinary
		 * map key (the sparse `value → cardinality` map of a sort index storage part, for one) therefore reaches this
		 * method from inside the JDK, with no comparator in sight; refusing to answer there turns a plain lookup — or
		 * even the `put` that triggers the promotion — into a hard failure, and only on datasets large enough to
		 * treeify a bin.
		 *
		 * The one case that still propagates is a {@link ClassCastException} from comparing two elements of different
		 * types at the same position. That is deliberate and must not be swallowed: within a single compound every
		 * value shares the element types declared by its {@link ComparatorSource} array — the write path enforces it
		 * through the validating constructor — so mixed types mean corrupted data or a schema whose compound element
		 * types changed without a reindex. Masking it with a fallback tie-break would trade a loud failure for a
		 * silently wrong order. Note this differs from the refusal above: the refusal fired on perfectly valid data,
		 * whereas this fires only on data that is genuinely broken. Arrays of *differing length* are not affected —
		 * they never reach an element comparison past their shared prefix.
		 *
		 * Ordering is element-wise with `null` first, falling back to array length. That is a total order across the
		 * values of any single compound, because they all share the element types declared by the comparator base. It
		 * is intentionally NOT consistent with {@link #equals(Object)} for element types whose own `compareTo`
		 * disagrees with `equals` ({@link java.math.BigDecimal} scale, for one); {@link java.util.HashMap} tolerates
		 * that, resolving identity through `equals` and ties through its own fallback ordering.
		 */
		@Override
		public int compareTo(@Nonnull ComparableArray o) {
			final Serializable[] left = this.array;
			final Serializable[] right = o.array;
			final int sharedLength = Math.min(left.length, right.length);
			for (int i = 0; i < sharedLength; i++) {
				final Serializable leftValue = left[i];
				final Serializable rightValue = right[i];
				if (leftValue == null || rightValue == null) {
					// nulls sort first; two nulls are equal on this element and the comparison moves on
					if (leftValue != null) {
						return 1;
					}
					if (rightValue != null) {
						return -1;
					}
					continue;
				}
				//noinspection unchecked,rawtypes
				final int result = ((Comparable) leftValue).compareTo(rightValue);
				if (result != 0) {
					return result;
				}
			}
			return Integer.compare(left.length, right.length);
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
		 * (mode-agnostic — owner mode walks its owned inverted index, view mode the shared one). Recreated on {@link #reset()}.
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
