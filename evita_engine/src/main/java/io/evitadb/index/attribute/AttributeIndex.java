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

import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges.ContainerChangesMemento;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Range;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.attribute.AttributeIndex.AttributeIndexChanges;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueLifecycleSink;
import io.evitadb.index.map.MapHeapSize;
import io.evitadb.index.map.PersistentTransactionalProducerMap;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPageRemoval;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

import static io.evitadb.core.transaction.Transaction.isTransactionAvailable;
import static io.evitadb.utils.Assert.isTrue;
import static io.evitadb.utils.Assert.notNull;
import static io.evitadb.utils.StringUtils.unknownToString;
import static java.util.Optional.ofNullable;

/**
 * Attribute index maintains search look-up indexes for {@link Entity#getAttributeValues()} - i.e. the unique, filter,
 * sort and chain sub-indexes. {@link AttributeIndex} handles all attribute indexes for the {@link Entity#getType()}.
 *
 * Filterable and sortable attributes share a single value→ValueToRecord tree ({@link #sharedValueIndex}) to halve
 * memory: the {@link FilterIndex} is a stateless view over it and a both-flagged {@link SortIndex} reads its
 * cardinality from it. Range-typed filterable attributes keep a sibling {@link RangeIndex} in {@link #sharedRangeIndex}.
 * Most unique attributes are folded into the shared tree and represented by views in {@link #uniqueViewIndex}; only
 * global-unique-localized attributes keep a standalone {@link UniqueIndex} in {@link #uniqueIndex}. Predecessor-typed
 * sort attributes are tracked by a {@link ChainIndex} in {@link #chainIndex}.
 *
 * All sub-index maps support transactional memory: under an open transaction multiple writers and readers operate on
 * their own isolated snapshot, and a writer's uncommitted changes are invisible to concurrent readers. Outside a
 * transaction changes are applied in place and the index is not safe for multiple concurrent writers.
 *
 * # Lazy sub-index maps
 *
 * All seven sub-index maps are **allocated on first write, not at construction**. A catalog carries one attribute
 * index per entity index, and most of them never see most of the seven families: on a production e-commerce catalog
 * (564,187 entity indexes) 64.4 % of the observable family slots were allocated and empty, and 16,104 indexes held
 * nothing at all. Each empty family costs a map decorator plus an empty {@link java.util.HashMap} delegate, so the
 * scaffolding alone was a floor of hundreds of megabytes before a single value was indexed.
 *
 * Every read therefore treats a `null` family as an absent one, and every write goes through the family's
 * `getOrCreate…Map()`. Three properties make that safe, and none of them may be dropped:
 *
 * - **The fields are `volatile`.** {@link TransactionalMap} holds only final fields, but the state of a
 *   {@link PersistentTransactionalProducerMap} is swapped between its sealed and thawed forms and is therefore NOT
 *   final. Without the volatile write a concurrent reader could observe a published map whose backing state is still
 *   `null`.
 * - **Creation is double-checked under `synchronized (this)`.** Two concurrent write transactions can reach the same
 *   index; if both built a map, the loser's diff layer would be keyed on an orphan instance while every later read
 *   re-read the field and found the winner — losing that transaction's writes silently. Exactly one instance wins,
 *   and nothing is written into a fresh map before it is published.
 * - **The from-maps constructor re-nulls an empty family.** Both the commit merge and the cold load run through it,
 *   so a family that committed nothing goes back to being absent instead of being resurrected empty for the lifetime
 *   of the next snapshot.
 *
 * A first write that is later rolled back leaves an empty map behind on the pre-commit instance. That is bounded (at
 * most seven per index, i.e. what construction used to cost unconditionally) and transient on the trunk, because the
 * committed copy is rebuilt from the merged maps and materialises only the families that actually hold something.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@ThreadSafe
public abstract sealed class AttributeIndex implements AttributeIndexContract,
	AttributeIndexScopeSpecificContract,
	TransactionalLayerProducer<AttributeIndexChanges, AttributeIndex>,
	IndexDataStructure,
	Serializable
	permits EntityAttributeIndex, ReferenceAttributeIndex {
	@Serial private static final long serialVersionUID = 479979988960202298L;
	/**
	 * Unique identity of this transactional producer instance, used by the transactional layer to track changes to
	 * this index. Assigned from a process-wide monotonic sequence at construction.
	 */
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Type of the entity this index belongs to.
	 */
	@Getter private final String entityType;
	/**
	 * Reference key (discriminator) of the {@link AbstractReducedEntityIndex} this index belongs to. Or null if
	 * this index is part of the global {@link GlobalEntityIndex}.
	 */
	@Nullable private final RepresentativeReferenceKey referenceKey;
	/**
	 * This transactional map (index) contains, for each NON-foldable unique attribute, a standalone OWNER
	 * {@link UniqueIndex} keyed by `createUniqueAttributeKey`. Only global-unique-localized attributes live here: their
	 * uniqueness is locale-less, so it cannot be modelled by the per-locale {@link #sharedValueIndex} tree (which is
	 * keyed by the per-locale FILTER key). Every other unique attribute (any non-localized one, or a localized one that
	 * is unique within locale) is folded into the shared tree and represented by a VIEW in {@link #uniqueViewIndex}.
	 * Filter and Sort also share the {@link #sharedValueIndex} tree.
	 *
	 * `null` until the first standalone-unique write reaches this index — see {@link #getOrCreateUniqueIndexMap()}
	 * and the "lazy sub-index maps" section of the class javadoc.
	 */
	@Nullable private volatile PersistentTransactionalProducerMap<AttributeIndexKey, UniqueIndex> uniqueIndex;
	/**
	 * Derived cache of {@link FilterIndex} VIEWS over {@link #sharedValueIndex} — one per filterable attribute key. The
	 * views own no transactional state of their own (their reads/writes dispatch through the wrapped shared
	 * {@link InvertedIndex}), so the cache carries them as plain non-producer values; what it DOES need is MVCC
	 * isolation, which is why it is a {@link TransactionalMap} rather than a {@link ConcurrentHashMap}.
	 *
	 * A {@link ConcurrentHashMap} gives structural thread-safety but NOT isolation: a writer transaction's uncommitted
	 * {@link #removeSharedIfEmpty} would delete a key from the shared baseline map while a concurrent reader still sees
	 * that key committed in {@link #sharedValueIndex} (itself a {@link TransactionalMap}) — {@link #resolveFilterView}
	 * then finds the shared tree present but the cache entry gone and trips its premise. The {@link TransactionalMap}
	 * keeps each transaction's view of the cache in its own diff layer (baseline shared immutably across query threads),
	 * so a writer's in-flight put/remove is invisible to readers and the cache never diverges from the reader's
	 * isolated view of {@link #sharedValueIndex}.
	 *
	 * `null` until the first filter write reaches this index — see {@link #getOrCreateFilterIndexMap()} and the
	 * "lazy sub-index maps" section of the class javadoc.
	 *
	 * The cached views WRAP {@link InvertedIndex} instances, and a touched key receives a fresh tree instance at commit;
	 * a carried-forward view would wrap a stale tree. So the map's transactional role is in-tx ISOLATION + committed
	 * KEY-SET only — the VALUES are re-derived fresh over the committed shared trees in the from-maps constructor
	 * ({@link #buildFilterViews}). The map is maintained in lockstep with {@link #sharedValueIndex} (every key created
	 * by {@link #getOrCreateFilterView} and dropped by {@link #removeSharedIfEmpty} is mirrored here), so the read path
	 * resolves a committed key without ever mutating the baseline.
	 */
	@Nullable private volatile TransactionalMap<AttributeIndexKey, FilterIndex> filterIndex;
	/**
	 * Derived map of folded (view-mode) {@link UniqueIndex} instances over {@link #sharedValueIndex} — one per FOLDABLE
	 * unique attribute key (any non-localized attribute, or a localized one unique within locale). Mirrors
	 * {@link #filterIndex}: the views own no transactional state, each holds a direct reference to the shared
	 * {@link FilterIndex} view it folds onto and is carried forward O(Δ) (or rebound over the committed filter view) by
	 * the from-maps constructor ({@link #buildUniqueViews}). This is a pure READ-side structure: it serves folded unique
	 * reads. It does NOT gate enforcement and is NOT consulted for control flow — the folded write self-registers the
	 * view ({@link #registerFoldedUniqueView}, bound to the live filter view) and the decision to do so is reported
	 * explicitly by {@link #insertUniqueAttribute} (its {@link UniquenessEnforcement} return) and passed to
	 * {@link #insertFilterAttribute}, so a sub-index that suppresses unique maintenance (e.g. a group index) composes
	 * correctly.
	 *
	 * A {@link TransactionalMap} for the same reason as {@link #filterIndex}: a folded read resolves the view through the
	 * writer's own diff layer (so read-your-writes resolve against the current shared tree) while staying invisible to
	 * concurrent readers — proven MVCC instead of a hand-rolled overlay.
	 *
	 * `null` until the first folded-unique write reaches this index — see {@link #getOrCreateUniqueViewIndexMap()} and
	 * the "lazy sub-index maps" section of the class javadoc.
	 */
	@Nullable private volatile TransactionalMap<AttributeIndexKey, UniqueIndex> uniqueViewIndex;
	/**
	 * This transactional map (index) contains for each attribute single instance of {@link SortIndex}
	 * (respective single instance for each attribute-locale combination in case of language specific attribute).
	 *
	 * `null` until the first sort write reaches this index — see {@link #getOrCreateSortIndexMap()} and the "lazy
	 * sub-index maps" section of the class javadoc.
	 */
	@Nullable private volatile PersistentTransactionalProducerMap<AttributeIndexKey, SortIndex> sortIndex;
	/**
	 * This transactional map (index) contains for each attribute single instance of {@link ChainIndex}
	 * (respective single instance for each attribute-locale combination in case of language specific attribute).
	 *
	 * `null` until the first chain (predecessor-ordering) write reaches this index — see
	 * {@link #getOrCreateChainIndexMap()} and the "lazy sub-index maps" section of the class javadoc.
	 */
	@Nullable private volatile PersistentTransactionalProducerMap<AttributeIndexKey, ChainIndex> chainIndex;
	/**
	 * OWNED shared comparator-ordered value→ValueToRecord tree, one per single FILTERABLE attribute key (keyed by the
	 * filter {@link #createAttributeKey} shape). The {@link FilterIndex} is a non-producing view over this tree; a
	 * both-flagged {@link SortIndex} reads its cardinality from it. This map is a {@link TransactionalLayerProducer} for
	 * the FILTER data. The {@link UniqueIndex} is a separate standalone structure. See {@link #sharedRangeIndex} for the
	 * sibling range structure of range-typed attributes.
	 *
	 * `null` until the first filter write reaches this index — see {@link #getOrCreateSharedValueIndexMap()} and the
	 * "lazy sub-index maps" section of the class javadoc.
	 */
	@Nullable private volatile PersistentTransactionalProducerMap<AttributeIndexKey, InvertedIndex> sharedValueIndex;
	/**
	 * OWNED sibling range structure of range-typed filterable attributes (keyed by the same filter
	 * {@link #createAttributeKey} shape as {@link #sharedValueIndex}). Only present for attributes whose plain type is
	 * assignable to {@link Range}. Kept beside {@link #sharedValueIndex} so the {@link FilterIndex}
	 * view stays stateless and the range structure commits independently.
	 *
	 * `null` until the first write to a range-typed filterable attribute reaches this index — see
	 * {@link #getOrCreateSharedRangeIndexMap()} and the "lazy sub-index maps" section of the class javadoc.
	 */
	@Nullable private volatile PersistentTransactionalProducerMap<AttributeIndexKey, RangeIndex> sharedRangeIndex;
	/**
	 * Owner-resident snapshot of the leaf-page sequences each PAGED {@link ChainIndex} holds on disk (empty for a SINGLE
	 * / never-paged chain, absent for a key with no chain). It is (re)built from the current chains at two points, both
	 * on the single-writer flush/commit path: fresh from the committed chain sub-index map every time this index is built
	 * from committed maps (the merge-copy of {@link #createCopyWithMergedTransactionalMemory} and cold load both go
	 * through the from-maps constructor), and again at the end of every {@link #getModifiedStorageParts} so a reused
	 * instance (warm-up flushes, or the same instance flushed repeatedly) stays current without a rebuild-from-copy.
	 *
	 * It exists solely to close the empty-drop leaf-page leak: when an emptied chain is dropped from {@link #chainIndex}, the dropped
	 * {@link ChainIndex}'s own {@link ChainIndex#appendStorageParts} never runs again, so this snapshot is the only
	 * remaining record of its live leaf pages. {@link #getModifiedStorageParts} diffs it against the surviving chain key
	 * set and emits a {@link ChainIndexLeafPageRemoval} for every page of a vanished key, or those pages would leak
	 * forever in the append-only OffsetIndex. It lives OUTSIDE transactional memory (touched only by the single catalog
	 * writer during flush/commit), mirroring the residency of the per-chain page-stream registry.
	 */
	@Nonnull private Map<AttributeIndexKey, int[]> persistedChainLeafPages;

	/**
	 * Empty-drop-reclaim on-disk leaf-page snapshot for the FILTER value (inverted) sub-index — the twin of
	 * {@link #persistedChainLeafPages} for {@link #sharedValueIndex}. When a filter empties and its shared value tree is
	 * dropped from the map, that tree's own flush never runs again, so this is the only remaining record of its live leaf
	 * pages: {@link #getModifiedStorageParts} diffs it against the surviving keys and emits a
	 * {@link FilterIndexLeafPageRemoval} for every orphaned page. Maintained at the same two points, with the same
	 * residency, as {@link #persistedChainLeafPages}.
	 */
	@Nonnull private Map<AttributeIndexKey, int[]> persistedFilterInvertedLeafPages;

	/**
	 * Empty-drop-reclaim on-disk leaf-page snapshot for the FILTER range companion ({@link #sharedRangeIndex}); the range twin of
	 * {@link #persistedFilterInvertedLeafPages}, reclaimed via a {@link RangeIndexLeafPageRemoval} per orphaned page.
	 */
	@Nonnull private Map<AttributeIndexKey, int[]> persistedFilterRangeLeafPages;

	/**
	 * Empty-drop-reclaim on-disk leaf-page snapshot for the owner UNIQUE sub-index ({@link #uniqueIndex}); reclaimed via a
	 * {@link UniqueIndexLeafPageRemoval} per orphaned page when an emptied owner unique index is dropped. Folded VIEW
	 * unique indexes own no pages and never appear here.
	 */
	@Nonnull private Map<AttributeIndexKey, int[]> persistedUniqueLeafPages;

	/**
	 * Empty-drop-reclaim on-disk leaf-page snapshot for the owner SORT sub-index ({@link #sortIndex}); reclaimed via a
	 * {@link SortIndexLeafPageRemoval} per orphaned page when an emptied owner sort index is dropped. VIEW-mode sort
	 * indexes own no pages (they reuse the FILTER tree) and never appear here.
	 */
	@Nonnull private Map<AttributeIndexKey, int[]> persistedSortLeafPages;

	/**
	 * Verifies that a value of a localized attribute carries a locale and that the locale is among those permitted by
	 * the entity schema.
	 *
	 * @param attributeName  the attribute name, for the error message
	 * @param allowedLocales the locales permitted by the entity schema
	 * @param locale         the locale of the value, must be non-null and allowed
	 * @param value          the attribute value, for the error message
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when the locale is missing or not allowed
	 */
	public static void verifyLocalizedAttribute(
		@Nonnull String attributeName,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value
	) {
		notNull(
			locale,
			"Attribute `" + attributeName + "` is marked as localized. Value " + unknownToString(
				value) + " is expected to be localized but is not!"
		);
		isTrue(
			allowedLocales.contains(locale),
			"Attribute `" + attributeName + "` is in locale `" + locale + "` that is not among allowed locales for this entity: " + allowedLocales.stream()
				.map(it -> "`" + it.toString() + "`")
				.collect(Collectors.joining(", ")) + "!"
		);
	}

	/**
	 * Method creates an attribute key based on the given attribute schema, allowed locales, locale, and value.
	 * If the attribute schema is localized, it verifies whether the provided locale is allowed and returns
	 * a new AttributeKey object with the attribute name and locale. If the attribute schema is not localized,
	 * it returns a new AttributeKey object with only the attribute name.
	 *
	 * @param referenceSchema The reference schema contract that is envelope for attribute schema contract.
	 *                        Can be null when attribute is defined on entity level.
	 * @param attributeSchema The attribute schema contract.
	 * @param allowedLocales  The set of allowed locales for the entity.
	 * @param locale          The locale to be checked against the allowed locales.
	 * @param value           The value of the attribute.
	 * @return An AttributeKey object with the attribute name and optional locale.
	 */
	@Nonnull
	public static AttributeIndexKey createAttributeKey(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value
	) {
		if (attributeSchema.isLocalized()) {
			verifyLocalizedAttribute(attributeSchema.getName(), allowedLocales, locale, value);
		}
		return createAttributeKey(referenceSchema, attributeSchema, locale);
	}

	/**
	 * Creates and returns an instance of AttributeIndexKey based on the provided reference schema,
	 * attribute schema, and locale.
	 *
	 * @param referenceSchema the reference schema contract, or null if the attribute is not associated with a reference schema
	 * @param attributeSchema the attribute schema contract, must not be null
	 * @param locale          the locale associated with the attribute, or null if the attribute is not localized
	 * @return a new instance of AttributeIndexKey constructed using the given parameters
	 */
	@Nonnull
	public static AttributeIndexKey createAttributeKey(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale
	) {
		// entity-level attributes can also appear inside reference-scoped indexes via reference
		// fan-out — EntityAttributeSchemaContract always nulls out the reference name regardless
		// of the surrounding index, keeping the storage key shape uniform across both fan-outs
		return new AttributeIndexKey(
			attributeSchema instanceof EntityAttributeSchemaContract || referenceSchema == null ?
				null : referenceSchema.getName(),
			attributeSchema.getName(),
			attributeSchema.isLocalized() ? locale : null
		);
	}

	/**
	 * Creates the attribute key for a sortable attribute compound. The key carries the locale only when the compound is
	 * localized, which is itself derived from the localization of its element attributes (resolved from the entity or
	 * reference schema depending on where the compound is defined).
	 *
	 * @param entitySchema    the entity schema, used to resolve element-attribute localization for entity-level compounds
	 * @param referenceSchema the reference schema enveloping the compound, or null when the compound is entity-level
	 * @param compoundSchema  the sortable attribute compound schema
	 * @param locale          the locale carried into the key when the compound is localized
	 * @return the attribute key with the compound name and optional locale
	 */
	@Nonnull
	private static AttributeIndexKey createAttributeKey(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nullable Locale locale
	) {
		final boolean entityCompound = compoundSchema instanceof EntitySortableAttributeCompoundSchemaContract;
		return new AttributeIndexKey(
			entityCompound ? null : Objects.requireNonNull(referenceSchema).getName(),
			compoundSchema.getName(),
			((SortableAttributeCompoundSchema) compoundSchema).isLocalized(
				entityCompound ?
					attributeName -> entitySchema.getAttribute(attributeName).orElse(null) :
					attributeName -> referenceSchema.getAttribute(attributeName).orElse(null)
			) ? locale : null
		);
	}

	/**
	 * Method creates and verifies validity of attribute key from passed arguments.
	 * This method can be used only for unique indexes and differs from {@link #createAttributeKey(ReferenceSchemaContract, AttributeSchemaContract, Set, Locale, Object)}
	 * in the sense that it creates locale specific key only if {@link AttributeSchemaContract#isUniqueWithinLocale()} is true.
	 *
	 * @param referenceSchema The reference schema contract that is envelope for attribute schema contract,
	 *                        can be null when attribute is defined on entity level.
	 * @param attributeSchema The attribute schema contract.
	 * @param allowedLocales  The set of allowed locales.
	 * @param scope           The scope in which the uniqueness is enforced.
	 * @param locale          The locale (can be null).
	 * @param value           The attribute value.
	 * @return An AttributeKey object with the attribute name and optional locale.
	 */
	@Nonnull
	private static AttributeIndexKey createUniqueAttributeKey(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Object value
	) {
		if (attributeSchema.isLocalized()) {
			verifyLocalizedAttribute(attributeSchema.getName(), allowedLocales, locale, value);
		}
		return createUniqueAttributeKey(referenceSchema, attributeSchema, scope, locale);
	}

	/**
	 * Creates a unique attribute key based on the provided reference schema, attribute schema,
	 * scope, and locale. This method generates a key to uniquely identify an attribute
	 * considering its uniqueness rules.
	 *
	 * @param referenceSchema the reference schema associated with the attribute, may be null
	 * @param attributeSchema the attribute schema that defines the attribute's properties, must not be null
	 * @param scope           the scope in which the attribute's uniqueness is determined, must not be null
	 * @param locale          the locale for the attribute, may be null if not applicable for uniqueness
	 * @return a unique key for the attribute based on the given parameters
	 */
	@Nonnull
	private static AttributeIndexKey createUniqueAttributeKey(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Scope scope,
		@Nullable Locale locale
	) {
		return new AttributeIndexKey(
			attributeSchema instanceof EntityAttributeSchemaContract || referenceSchema == null ?
				null : referenceSchema.getName(),
			attributeSchema.getName(),
			attributeSchema.isUniqueWithinLocaleInScope(scope) ? locale : null
		);
	}

	/**
	 * Snapshots the on-disk leaf-page sequences of every PAGED sub-index in `index` into a fresh map — the baseline
	 * {@link #getModifiedStorageParts} diffs to reclaim the leaf pages of an empty-dropped sub-index. The
	 * per-value `pageAccessor` yields each sub-index's current on-disk page set (empty for a SINGLE / never-paged one, so
	 * the map holds only keys that actually own leaf pages on disk). Shared across every paged family — CHAIN, owner
	 * UNIQUE, owner SORT and both FILTER axes — by supplying the family's `currentLeafPageSequences` accessor. The
	 * returned map is a plain, non-transactional {@link java.util.HashMap} (single-writer flush use).
	 *
	 * @param index        the committed sub-index map (already published or restored from disk), or `null` when the
	 *                     family was never allocated — which is the same answer as an empty one, no pages on disk
	 * @param pageAccessor yields the on-disk leaf-page sequences of one sub-index (empty when it is not paged)
	 * @param <V>          the sub-index type
	 * @return the per-key on-disk leaf-page snapshot, or an empty map when nothing is paged
	 */
	@Nonnull
	private static <V> Map<AttributeIndexKey, int[]> snapshotLeafPages(
		@Nullable Map<AttributeIndexKey, V> index, @Nonnull Function<V, int[]> pageAccessor
	) {
		if (index == null || index.isEmpty()) {
			return Map.of();
		}
		// `forEach` rather than `entrySet()`: this runs against the LIVE sub-index maps on every flush, and an entry
		// set asked for here would stay cached in each of them - see `collectKeys`. The buffer is therefore allocated
		// up front and discarded when nothing paged, rather than lazily on the first paged key; the returned value is
		// identical either way, and mirrors what `HistogramIndexMapComponent` does for the histogram families
		final Map<AttributeIndexKey, int[]> snapshot = CollectionUtils.createHashMap(index.size());
		index.forEach((key, subIndex) -> {
			final int[] pages = pageAccessor.apply(subIndex);
			if (pages.length > 0) {
				snapshot.put(key, pages);
			}
		});
		return snapshot.isEmpty() ? Map.of() : snapshot;
	}

	/**
	 * Empty-drop reclaim for one paged family: emits a leaf-page removal for every on-disk page of a sub-index that was
	 * emptied and dropped from its map this commit. The dropped sub-index's own {@code appendStorageParts} never runs
	 * again, so its last leaf pages would otherwise be copied forward forever by the append-only OffsetIndex; this diffs
	 * the pre-commit on-disk `snapshot` against the keys that survived (`stillPresent`) and removes the orphaned pages of
	 * every vanished key. Surviving sub-indexes reclaim their own split/merge-freed pages through their own flush — this
	 * only covers the whole-index drop the child can no longer see.
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param snapshot              the per-key on-disk leaf-page snapshot captured before this commit
	 * @param stillPresent          whether a snapshot key still has a live sub-index (its own flush handled its pages)
	 * @param indexType             the sub-index discriminator carried by each removal's stream key
	 * @param trappedChanges        the trapped-changes accumulator for this commit
	 * @param removalFactory        builds the family-specific removal part for one freed page
	 */
	private static void emitDroppedLeafPageRemovals(
		int entityIndexPrimaryKey,
		@Nonnull Map<AttributeIndexKey, int[]> snapshot,
		@Nonnull Predicate<AttributeIndexKey> stillPresent,
		@Nonnull AttributeIndexType indexType,
		@Nonnull TrappedChanges trappedChanges,
		@Nonnull LeafPageRemovalFactory removalFactory
	) {
		if (snapshot.isEmpty()) {
			return;
		}
		for (final Entry<AttributeIndexKey, int[]> entry : snapshot.entrySet()) {
			if (!stillPresent.test(entry.getKey())) {
				final AttributeKeyWithIndexType streamKey = new AttributeKeyWithIndexType(entry.getKey(), indexType);
				for (final int freedPageSequence : entry.getValue()) {
					trappedChanges.addChangeToStore(
						removalFactory.create(entityIndexPrimaryKey, streamKey, freedPageSequence)
					);
				}
			}
		}
	}

	/**
	 * Factory for a family-specific leaf-page removal part, letting {@link #emitDroppedLeafPageRemovals} stay generic over
	 * the CHAIN / FILTER / range / UNIQUE / SORT removal types (all share the same `(pk, streamKey, pageSequence)` shape).
	 */
	@FunctionalInterface
	private interface LeafPageRemovalFactory {

		/**
		 * Builds the removal part for a single freed leaf page.
		 *
		 * @param entityIndexPrimaryKey the owning entity index pk
		 * @param streamKey             the dropped sub-index's stream identity (attribute key + index type)
		 * @param pageSequence          the freed leaf-page sequence to remove from storage
		 * @return the family-specific removal storage part
		 */
		@Nonnull
		StoragePart create(
			int entityIndexPrimaryKey, @Nonnull AttributeKeyWithIndexType streamKey, int pageSequence
		);
	}

	/**
	 * Rebuilds the {@link FilterIndex} VIEW map fresh over the committed shared trees. The view's `attributeType` is taken
	 * from the matching entry of `previousViews` (the previous snapshot's / loaded views), which is the only carrier
	 * of the attribute type once {@link FilterIndexStoragePart} has been consumed.
	 *
	 * @param sharedValueIndex the committed value→ValueToRecord trees keyed by filter key
	 * @param sharedRangeIndex the committed range structures keyed by filter key (range-typed attributes only)
	 * @param previousViews    the previous/loaded views supplying each key's attribute type
	 * @return a fresh non-transactional map of filter views
	 */
	@Nonnull
	private static Map<AttributeIndexKey, FilterIndex> buildFilterViews(
		@Nonnull Map<AttributeIndexKey, InvertedIndex> sharedValueIndex,
		@Nonnull Map<AttributeIndexKey, RangeIndex> sharedRangeIndex,
		@Nonnull Map<AttributeIndexKey, FilterIndex> previousViews
	) {
		// plain delegate: the caller wraps this in a TransactionalMap, which provides the MVCC isolation between
		// concurrent query threads and an in-flight writer (see the filterIndex field javadoc)
		final Map<AttributeIndexKey, FilterIndex> views = CollectionUtils.createHashMap(
			Math.max(8, sharedValueIndex.size()));
		for (final Entry<AttributeIndexKey, InvertedIndex> entry : sharedValueIndex.entrySet()) {
			final AttributeIndexKey key = entry.getKey();
			final InvertedIndex committedTree = entry.getValue();
			final RangeIndex committedRange = sharedRangeIndex.get(key);
			final FilterIndex previous = previousViews.get(key);
			Assert.isPremiseValid(
				previous != null,
				() -> new GenericEvitaInternalError(
					"Missing previous filter view for shared value index key `" + key + "`!")
			);
			// O(Δ) carry-forward: a FilterIndexView is an IMMUTABLE façade over its shared tree(s). Since the producer-map
			// merge keeps an untouched InvertedIndex/RangeIndex identity-stable across commit (createCopy returns `this`), a previous view
			// whose wrapped instances are reference-equal to the just-committed ones is still exactly correct — reuse it by
			// reference (also sharing it with the older snapshot, which is safe precisely because it is immutable). Only a
			// key whose tree was created/replaced this commit needs a freshly wrapped view. Never mutate a published view.
			if (previous.getInvertedIndex() == committedTree && previous.getRangeIndex() == committedRange) {
				views.put(key, previous);
			} else {
				views.put(
					key,
					new FilterIndexView(
						key, committedTree, committedRange, previous.getAttributeType(),
						previous.getIndexedDecimalPlaces()
					)
				);
			}
		}
		return views;
	}

	/**
	 * Derives the committed SORT view map by binding every view-mode {@link SortIndex} to its committed shared tree
	 * (resolved from `sharedValueIndex`). Each binding is {@link SortIndex#bindSharedTree}: an O(Δ) carry-forward (returns
	 * the same instance) when the tree is identity-unchanged, or a fresh immutable view-copy sharing the committed
	 * sorted-records façade when the tree was replaced. Owner-mode indexes carry forward unchanged. No view is ever
	 * mutated in place, so a carried-forward view is safe to share with the older snapshot: binding never touches a
	 * potentially-shared committed instance, which would otherwise risk an MVCC snapshot-isolation leak.
	 *
	 * @param sharedValueIndex   the committed value→ValueToRecord trees keyed by filter key (the bind targets)
	 * @param committedSortIndex the merged sort sub-index map (values already committed by the producer-map merge)
	 * @return a plain map of sort indexes bound to the committed shared trees, ready to seed the new `sortIndex` map
	 */
	@Nonnull
	private static Map<AttributeIndexKey, SortIndex> deriveSortViews(
		@Nonnull Map<AttributeIndexKey, InvertedIndex> sharedValueIndex,
		@Nonnull Map<AttributeIndexKey, SortIndex> committedSortIndex
	) {
		final Map<AttributeIndexKey, SortIndex> bound = CollectionUtils.createHashMap(
			Math.max(8, committedSortIndex.size()));
		for (final Entry<AttributeIndexKey, SortIndex> entry : committedSortIndex.entrySet()) {
			final AttributeIndexKey key = entry.getKey();
			// owner mode ignores the argument; a view-mode index binds to the committed shared tree (null for an owner)
			bound.put(key, entry.getValue().bindSharedTree(sharedValueIndex.get(key)));
		}
		return bound;
	}

	/**
	 * A unique attribute is FOLDABLE into the shared filter tree iff its unique key shape matches its filter key shape:
	 * either it is not localized (both keys are locale-less), or it is unique within locale (both keys carry the same
	 * locale). The only non-foldable case is a localized attribute unique across locales (global-unique-localized),
	 * whose locale-less unique key cannot be modelled by the per-locale filter tree.
	 */
	private static boolean isUniqueFoldable(@Nonnull AttributeSchemaContract attributeSchema, @Nonnull Scope scope) {
		return !attributeSchema.isLocalized() || attributeSchema.isUniqueWithinLocaleInScope(scope);
	}

	/**
	 * Creates an empty attribute index for a fresh entity / reference index. **No sub-index map is allocated here**:
	 * each of the seven stays `null` until the first write to its family, for the reasons the "lazy sub-index maps"
	 * section of the class javadoc sets out. A freshly built index therefore costs its own object and nothing else.
	 *
	 * @param entityType   the entity type this index belongs to
	 * @param referenceKey the reference discriminator when this index backs a reference-scoped index, or `null` for
	 *                     the global entity index
	 */
	protected AttributeIndex(@Nonnull String entityType, @Nullable RepresentativeReferenceKey referenceKey) {
		this.entityType = entityType;
		this.referenceKey = referenceKey;
		// every sub-index map is left null - the first write to a family allocates it through its getOrCreate...Map()
		//
		// a fresh index has nothing on disk yet; the snapshots are rebuilt from the committed sub-indexes at the next
		// commit / load
		this.persistedChainLeafPages = Map.of();
		this.persistedFilterInvertedLeafPages = Map.of();
		this.persistedFilterRangeLeafPages = Map.of();
		this.persistedUniqueLeafPages = Map.of();
		this.persistedSortLeafPages = Map.of();
	}

	/**
	 * Rebuilds an attribute index from already-committed sub-index maps. Used both when loading from persistent storage
	 * and at transaction commit ({@link #createCopyWithMergedTransactionalMemory}). The shared trees are assigned first
	 * because the derived filter / sort / unique VIEWS bind directly to THIS index's committed shared trees; the supplied
	 * view maps contribute only their key set and each key's attribute type, never live view values (those are re-derived
	 * fresh over the committed trees).
	 *
	 * @param entityType       the entity type this index belongs to
	 * @param referenceKey     the reference discriminator, or `null` for the global entity index
	 * @param uniqueIndex      the standalone (owner) unique sub-index map
	 * @param filterIndex      the previous filter VIEW map (sources each key's attribute type for the rebuilt views)
	 * @param uniqueViewIndex  the previous folded-unique VIEW map (supplies which keys are foldable unique attributes)
	 * @param sortIndex        the sort sub-index map
	 * @param chainIndex       the chain sub-index map
	 * @param sharedValueIndex the shared value→ValueToRecord tree map
	 * @param sharedRangeIndex the shared range-structure map for range-typed attributes
	 */
	protected AttributeIndex(
		@Nonnull String entityType,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull Map<AttributeIndexKey, UniqueIndex> uniqueIndex,
		@Nonnull Map<AttributeIndexKey, FilterIndex> filterIndex,
		@Nonnull Map<AttributeIndexKey, UniqueIndex> uniqueViewIndex,
		@Nonnull Map<AttributeIndexKey, SortIndex> sortIndex,
		@Nonnull Map<AttributeIndexKey, ChainIndex> chainIndex,
		@Nonnull Map<AttributeIndexKey, InvertedIndex> sharedValueIndex,
		@Nonnull Map<AttributeIndexKey, RangeIndex> sharedRangeIndex
	) {
		this.entityType = entityType;
		this.referenceKey = referenceKey;
		// an empty committed family is left ABSENT rather than resurrected as an empty map: this constructor runs on
		// both the commit merge and the cold load, so it is the point at which a family that holds nothing gets to
		// cost nothing for the whole life of the snapshot being built (see "lazy sub-index maps" on the class)
		//
		// the shared trees must be built FIRST: the sort views below bind directly to THIS index's committed shared trees
		this.sharedValueIndex = sharedValueIndex.isEmpty() ? null : new PersistentTransactionalProducerMap<>(
			sharedValueIndex, InvertedIndex.class, Function.identity());
		this.sharedRangeIndex = sharedRangeIndex.isEmpty() ? null : new PersistentTransactionalProducerMap<>(
			sharedRangeIndex, RangeIndex.class, Function.identity());
		this.uniqueIndex = uniqueIndex.isEmpty() ? null : new PersistentTransactionalProducerMap<>(
			uniqueIndex, UniqueIndex.class, Function.identity());
		this.chainIndex = chainIndex.isEmpty() ?
			null : new PersistentTransactionalProducerMap<>(chainIndex, ChainIndex.class, Function.identity());
		// snapshot each committed PAGED sub-index's on-disk leaf pages BEFORE any mutation, so a later empty-drop of that
		// sub-index can still reclaim its now-orphaned leaf pages (see the field javadoc). The committed
		// sub-indexes have already published their page streams (merge-copy) or been restored from disk (cold load), so
		// their live-page sets mirror what is on disk at this instant. Applied symmetrically to every paged family:
		// CHAIN, owner UNIQUE, owner SORT and both FILTER axes (shared value tree + range companion).
		this.persistedChainLeafPages =
			snapshotLeafPages(chainIndex, ChainIndex::currentLeafPageSequences);
		this.persistedFilterInvertedLeafPages =
			snapshotLeafPages(sharedValueIndex, InvertedIndex::currentLeafPageSequences);
		this.persistedFilterRangeLeafPages =
			snapshotLeafPages(sharedRangeIndex, RangeIndex::currentLeafPageSequences);
		this.persistedUniqueLeafPages =
			snapshotLeafPages(uniqueIndex, UniqueIndex::currentLeafPageSequences);
		this.persistedSortLeafPages =
			snapshotLeafPages(sortIndex, SortIndex::currentLeafPageSequences);
		// derive the SORT views bound to the committed shared trees: each view-mode index is carried forward by reference
		// when its wrapped tree is identity-unchanged, or replaced by a fresh immutable copy (sharing the committed
		// sorted-records façade) when the tree was replaced. Owner-mode indexes carry forward unchanged. Never mutated in
		// place, so a carried-forward view is safe to share with the older snapshot (no isolation hazard).
		final Map<AttributeIndexKey, SortIndex> boundSortViews = deriveSortViews(sharedValueIndex, sortIndex);
		this.sortIndex = boundSortViews.isEmpty() ? null : new PersistentTransactionalProducerMap<>(
			boundSortViews, SortIndex.class, Function.identity());
		// derive the FILTER views over the just-committed shared trees: carry each previous view forward by reference when
		// its wrapped tree(s) are identity-unchanged (the common case — the producer-map merge keeps untouched trees
		// identity-stable), and freshly wrap only the keys whose tree was created/replaced this commit. A view is never mutated in place, so
		// sharing a carried-forward view with the older snapshot is safe; the invariant "no committed view wraps a stale
		// tree" is preserved because a replaced tree always fails the identity check and is rewrapped.
		final Map<AttributeIndexKey, FilterIndex> rebuiltFilterViews = buildFilterViews(
			sharedValueIndex, sharedRangeIndex, filterIndex);
		this.filterIndex = rebuiltFilterViews.isEmpty() ? null : new TransactionalMap<>(rebuiltFilterViews);
		// rebuild the folded UNIQUE views fresh over the same committed shared trees (mirrors the FILTER views); the
		// source map's key set tells us which keys are foldable unique attributes.
		final Map<AttributeIndexKey, UniqueIndex> rebuiltUniqueViews = buildUniqueViews(
			sharedValueIndex, uniqueViewIndex);
		this.uniqueViewIndex = rebuiltUniqueViews.isEmpty() ? null : new TransactionalMap<>(
			rebuiltUniqueViews, UniqueIndex.class, Function.identity());
	}

	/**
	 * Returns the standalone (owner) UNIQUE sub-index map, allocating it on the first write that needs it.
	 *
	 * Never call this from a read path — a read resolves a missing family to "absent" through {@link #entryOf} and
	 * friends, and materialising a map in order to look nothing up in it would give back exactly the scaffolding cost
	 * the laziness exists to avoid. The double-checked publication is explained once on the class javadoc.
	 *
	 * @return the map, freshly created when this is the first write to the family
	 */
	@Nonnull
	private PersistentTransactionalProducerMap<AttributeIndexKey, UniqueIndex> getOrCreateUniqueIndexMap() {
		final PersistentTransactionalProducerMap<AttributeIndexKey, UniqueIndex> existing = this.uniqueIndex;
		if (existing != null) {
			return existing;
		}
		synchronized (this) {
			PersistentTransactionalProducerMap<AttributeIndexKey, UniqueIndex> theIndex = this.uniqueIndex;
			if (theIndex == null) {
				theIndex =  new PersistentTransactionalProducerMap<>(
					CollectionUtils.createHashMap(32),
					UniqueIndex.class,
					Function.identity()
				);
				this.uniqueIndex = theIndex;
			}
			return theIndex;
		}
	}

	/**
	 * Returns the SORT sub-index map, allocating it on the first write that needs it. See
	 * {@link #getOrCreateUniqueIndexMap()} for why this is a write-path-only accessor.
	 *
	 * @return the map, freshly created when this is the first write to the family
	 */
	@Nonnull
	private PersistentTransactionalProducerMap<AttributeIndexKey, SortIndex> getOrCreateSortIndexMap() {
		final PersistentTransactionalProducerMap<AttributeIndexKey, SortIndex> existing = this.sortIndex;
		if (existing != null) {
			return existing;
		}
		synchronized (this) {
			PersistentTransactionalProducerMap<AttributeIndexKey, SortIndex> theIndex = this.sortIndex;
			if (theIndex == null) {
				theIndex = new PersistentTransactionalProducerMap<>(
					CollectionUtils.createHashMap(32),
					SortIndex.class,
					Function.identity()
				);
				this.sortIndex = theIndex;
			}
			return theIndex;
		}
	}

	/**
	 * Returns the CHAIN sub-index map, allocating it on the first write that needs it. See
	 * {@link #getOrCreateUniqueIndexMap()} for why this is a write-path-only accessor.
	 *
	 * @return the map, freshly created when this is the first write to the family
	 */
	@Nonnull
	private PersistentTransactionalProducerMap<AttributeIndexKey, ChainIndex> getOrCreateChainIndexMap() {
		final PersistentTransactionalProducerMap<AttributeIndexKey, ChainIndex> existing = this.chainIndex;
		if (existing != null) {
			return existing;
		}
		synchronized (this) {
			PersistentTransactionalProducerMap<AttributeIndexKey, ChainIndex> theIndex = this.chainIndex;
			if (theIndex == null) {
				theIndex = new PersistentTransactionalProducerMap<>(
					CollectionUtils.createHashMap(32),
					ChainIndex.class,
					Function.identity()
				);
				this.chainIndex = theIndex;
			}
			return theIndex;
		}
	}

	/**
	 * Returns the shared value→ValueToRecord tree map that owns the FILTER data, allocating it on the first write that
	 * needs it. See {@link #getOrCreateUniqueIndexMap()} for why this is a write-path-only accessor.
	 *
	 * @return the map, freshly created when this is the first write to the family
	 */
	@Nonnull
	private PersistentTransactionalProducerMap<AttributeIndexKey, InvertedIndex> getOrCreateSharedValueIndexMap() {
		final PersistentTransactionalProducerMap<AttributeIndexKey, InvertedIndex> existing = this.sharedValueIndex;
		if (existing != null) {
			return existing;
		}
		synchronized (this) {
			PersistentTransactionalProducerMap<AttributeIndexKey, InvertedIndex> theIndex = this.sharedValueIndex;
			if (theIndex == null) {
				theIndex = new PersistentTransactionalProducerMap<>(
					CollectionUtils.createHashMap(32),
					InvertedIndex.class,
					Function.identity()
				);
				this.sharedValueIndex = theIndex;
			}
			return theIndex;
		}
	}

	/**
	 * Returns the sibling range-structure map of range-typed filterable attributes, allocating it on the first write
	 * that needs it. See {@link #getOrCreateUniqueIndexMap()} for why this is a write-path-only accessor.
	 *
	 * @return the map, freshly created when this is the first write to the family
	 */
	@Nonnull
	private PersistentTransactionalProducerMap<AttributeIndexKey, RangeIndex> getOrCreateSharedRangeIndexMap() {
		final PersistentTransactionalProducerMap<AttributeIndexKey, RangeIndex> existing = this.sharedRangeIndex;
		if (existing != null) {
			return existing;
		}
		synchronized (this) {
			PersistentTransactionalProducerMap<AttributeIndexKey, RangeIndex> theIndex = this.sharedRangeIndex;
			if (theIndex == null) {
				theIndex = new PersistentTransactionalProducerMap<>(
					CollectionUtils.createHashMap(32),
					RangeIndex.class,
					Function.identity()
				);
				this.sharedRangeIndex = theIndex;
			}
			return theIndex;
		}
	}

	/**
	 * Returns the derived FILTER view cache, allocating it on the first write that needs it. Transactional for MVCC
	 * isolation between concurrent readers and an in-flight writer — see the {@link #filterIndex} javadoc; a
	 * {@link FilterIndexView} is a non-producer value carried by reference, so the plain-valued constructor is the
	 * right one here. See {@link #getOrCreateUniqueIndexMap()} for why this is a write-path-only accessor.
	 *
	 * @return the map, freshly created when this is the first write to the family
	 */
	@Nonnull
	private TransactionalMap<AttributeIndexKey, FilterIndex> getOrCreateFilterIndexMap() {
		final TransactionalMap<AttributeIndexKey, FilterIndex> existing = this.filterIndex;
		if (existing != null) {
			return existing;
		}
		synchronized (this) {
			TransactionalMap<AttributeIndexKey, FilterIndex> theIndex = this.filterIndex;
			if (theIndex == null) {
				theIndex = new TransactionalMap<>(CollectionUtils.createHashMap(32));
				this.filterIndex = theIndex;
			}
			return theIndex;
		}
	}

	/**
	 * Returns the derived folded-UNIQUE view cache, allocating it on the first write that needs it. A
	 * {@link UniqueIndexView} extends the producer {@link UniqueIndex} (its commit methods are no-ops), so it needs the
	 * producer-valued constructor to avoid the plain-value commit path's null-wrapper NPE. See
	 * {@link #getOrCreateUniqueIndexMap()} for why this is a write-path-only accessor.
	 *
	 * @return the map, freshly created when this is the first write to the family
	 */
	@Nonnull
	private TransactionalMap<AttributeIndexKey, UniqueIndex> getOrCreateUniqueViewIndexMap() {
		final TransactionalMap<AttributeIndexKey, UniqueIndex> existing = this.uniqueViewIndex;
		if (existing != null) {
			return existing;
		}
		synchronized (this) {
			TransactionalMap<AttributeIndexKey, UniqueIndex> theIndex = this.uniqueViewIndex;
			if (theIndex == null) {
				theIndex = new TransactionalMap<>(
					CollectionUtils.createHashMap(32),
					UniqueIndex.class,
					Function.identity()
				);
				this.uniqueViewIndex = theIndex;
			}
			return theIndex;
		}
	}

	/**
	 * Looks `key` up in a sub-index family that may not have been allocated yet.
	 *
	 * An absent family and a family that simply does not hold the key are the same answer to the caller — there is no
	 * sub-index either way — so both give back `null` rather than making every read site branch twice.
	 *
	 * @param family the sub-index map, or `null` when nothing has ever written to this family
	 * @param key    the attribute-index key to resolve
	 * @param <V>    the sub-index type held by the family
	 * @return the sub-index filed under `key`, or `null` when the family is absent or does not hold it
	 */
	@Nullable
	private static <V> V entryOf(@Nullable Map<AttributeIndexKey, V> family, @Nonnull AttributeIndexKey key) {
		return family == null ? null : family.get(key);
	}

	/**
	 * Tells whether a sub-index family that may not have been allocated yet holds `key`.
	 *
	 * @param family the sub-index map, or `null` when nothing has ever written to this family
	 * @param key    the attribute-index key to test
	 * @return true only when the family exists and holds the key
	 */
	private static boolean familyHoldsKey(
		@Nullable Map<AttributeIndexKey, ?> family, @Nonnull AttributeIndexKey key
	) {
		return family != null && family.containsKey(key);
	}

	/**
	 * Returns the keys of a sub-index family that may not have been allocated yet. An absent family yields
	 * {@link Set#of()} — the JVM-wide empty set, so answering "nothing" costs nothing.
	 *
	 * @param family the sub-index map, or `null` when nothing has ever written to this family
	 * @return the family's key set, or an empty set when it is absent
	 */
	@Nonnull
	private static Set<AttributeIndexKey> keysOfFamily(@Nullable Map<AttributeIndexKey, ?> family) {
		return family == null ? Set.of() : family.keySet();
	}

	/**
	 * Returns how many sub-indexes a family that may not have been allocated yet holds.
	 *
	 * @param family the sub-index map, or `null` when nothing has ever written to this family
	 * @return the entry count, or `0` when the family is absent
	 */
	private static int familySize(@Nullable Map<AttributeIndexKey, ?> family) {
		return family == null ? 0 : family.size();
	}

	/**
	 * Tells whether a sub-index family holds nothing — either because it was never allocated, or because everything
	 * put into it has since been removed.
	 *
	 * @param family the sub-index map, or `null` when nothing has ever written to this family
	 * @return true when the family contributes no sub-index
	 */
	private static boolean familyIsEmpty(@Nullable Map<AttributeIndexKey, ?> family) {
		return family == null || family.isEmpty();
	}

	/**
	 * Walks a sub-index family that may not have been allocated yet, visiting nothing when it is absent.
	 *
	 * It is a `forEach` for the reason spelled out on {@link #collectKeys}: an `entrySet`/`keySet` asked for on a walk
	 * that runs from a constructor or a flush leaves a view object cached on the map for the lifetime of the index.
	 *
	 * @param family   the sub-index map, or `null` when nothing has ever written to this family
	 * @param consumer receives each key and its sub-index
	 * @param <V>      the sub-index type held by the family
	 */
	private static <V> void forEachInFamily(
		@Nullable Map<AttributeIndexKey, V> family, @Nonnull BiConsumer<AttributeIndexKey, V> consumer
	) {
		if (family != null) {
			family.forEach(consumer);
		}
	}

	/**
	 * Takes `key` out of a sub-index family that may not have been allocated yet.
	 *
	 * @param family the sub-index map, or `null` when nothing has ever written to this family
	 * @param key    the attribute-index key to drop
	 * @param <V>    the sub-index type held by the family
	 * @return the sub-index that was filed under `key`, or `null` when the family is absent or did not hold it
	 */
	@Nullable
	private static <V> V removeFromFamily(
		@Nullable Map<AttributeIndexKey, V> family, @Nonnull AttributeIndexKey key
	) {
		return family == null ? null : family.remove(key);
	}

	/**
	 * Discharges the diff layer of a sub-index family that may not have been allocated yet. A family that was never
	 * allocated cannot own a layer, so there is nothing to release for it.
	 *
	 * @param family             the sub-index map, or `null` when nothing has ever written to this family
	 * @param transactionalLayer the maintainer holding this transaction's diff layers
	 */
	private static void removeFamilyLayer(
		@Nullable TransactionalStateProducer<?> family, @Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		if (family != null) {
			family.removeLayer(transactionalLayer);
		}
	}

	/**
	 * Returns the committed state of a sub-index family, discharging its diff layer on the way. An absent family has no
	 * layer to sweep and no state to merge, so it commits to {@link Map#of()} — which the from-maps constructor then
	 * recognises and leaves absent in the copy, instead of resurrecting an empty map on every snapshot.
	 *
	 * @param transactionalLayer the maintainer holding this transaction's diff layers
	 * @param family             the sub-index map, or `null` when nothing has ever written to this family
	 * @param <V>                the sub-index type held by the family
	 * @return the merged committed map, or an empty map when the family is absent
	 */
	@Nonnull
	private static <V> Map<AttributeIndexKey, V> committedFamilyCopy(
		@Nonnull TransactionalLayerMaintainer transactionalLayer,
		@Nullable TransactionalStateProducer<Map<AttributeIndexKey, V>> family
	) {
		return family == null ? Map.of() : transactionalLayer.getStateCopyWithCommittedChanges(family);
	}

	/**
	 * Accounts for the uniqueness of `value` for `recordId` and reports where it is enforced (see
	 * {@link UniquenessEnforcement}). A foldable unique attribute has no separate unique store — its value lives in the
	 * shared filter tree — so this method does nothing and returns {@link UniquenessEnforcement#BY_FILTER_WRITE}; the
	 * paired filter write enforces uniqueness and registers the folded read-view. A non-foldable
	 * (global-unique-localized) attribute is stored and enforced in a standalone {@link UniqueIndex} here and returns
	 * {@link UniquenessEnforcement#BY_OWNER_INDEX}.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the attribute being inserted
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param scope           the scope deciding how the unique key is shaped
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the attribute value to insert
	 * @param recordId        the primary key the value is attributed to
	 * @return where this value's uniqueness is enforced
	 */
	public UniquenessEnforcement insertUniqueAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		if (isUniqueFoldable(attributeSchema, scope)) {
			// FOLDED: there is no separate unique store — the value lives in the shared filter tree. The filter write
			// (insertFilterAttribute) both enforces per-value uniqueness and registers the folded read-view, so nothing
			// is done here; we only report that enforcement belongs to the filter write.
			return UniquenessEnforcement.BY_FILTER_WRITE;
		}
		// STANDALONE OWNER: global-unique-localized uniqueness is locale-less and enforced across locales here, which the
		// per-locale shared filter tree cannot model.
		final AttributeIndexKey lookupKey = createUniqueAttributeKey(
			referenceSchema, attributeSchema, allowedLocales, scope, locale, value
		);
		final PersistentTransactionalProducerMap<AttributeIndexKey, UniqueIndex> uniqueIndexes =
			getOrCreateUniqueIndexMap();
		final UniqueIndex theUniqueIndex = uniqueIndexes.computeIfAbsent(
			lookupKey,
			ownerKey -> {
				final UniqueIndex newUniqueIndex = new OwnerUniqueIndex(
					this.entityType,
					ownerKey,
					attributeSchema.getType()
				);
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addCreatedItem(newUniqueIndex));
				return newUniqueIndex;
			}
		);
		// registerUniqueKey mutates the standalone unique index in place — declare it for the O(Δ) commit walk (the
		// folded case writes through the shared filter tree, which the filter-insert path already marks)
		uniqueIndexes.markValueMutated(lookupKey);
		theUniqueIndex.registerUniqueKey(value, recordId);
		return UniquenessEnforcement.BY_OWNER_INDEX;
	}

	/**
	 * Drops `value` for `recordId` from the unique structure of `attributeSchema`. For a foldable unique attribute this
	 * is a no-op — the shared tree owns the data and the filter-remove path drops the value (and the view once the tree
	 * empties). For a standalone unique attribute the key is unregistered and the now-empty index is removed.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the attribute being removed
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param scope           the scope deciding how the unique key is shaped
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the attribute value to remove
	 * @param recordId        the primary key the value was attributed to
	 */
	public void removeUniqueAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		if (isUniqueFoldable(attributeSchema, scope)) {
			// FOLDED: the shared filter tree owns the data; the value is removed by the filter-remove path and the view
			// entry is dropped by removeSharedIfEmpty once the tree empties. Nothing to do here.
			return;
		}
		final AttributeIndexKey lookupKey = createUniqueAttributeKey(
			referenceSchema,
			attributeSchema,
			allowedLocales,
			scope,
			locale,
			value
		);
		// an absent family means an absent index, which the notNull below reports as the caller's error
		final PersistentTransactionalProducerMap<AttributeIndexKey, UniqueIndex> uniqueIndexes = this.uniqueIndex;
		final UniqueIndex theUniqueIndex = entryOf(uniqueIndexes, lookupKey);
		notNull(theUniqueIndex, "Unique index for attribute `" + attributeSchema.getName() + "` not found!");
		// unregisterUniqueKey mutates the standalone unique index in place — declare it for the O(Δ) commit walk
		uniqueIndexes.markValueMutated(lookupKey);
		theUniqueIndex.unregisterUniqueKey(value, recordId);

		if (theUniqueIndex.isEmpty()) {
			uniqueIndexes.remove(lookupKey);
			ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
				.ifPresent(it -> it.addRemovedItem(theUniqueIndex));
		}
	}

	/**
	 * Adds `value` for `recordId` to the shared value tree through the filter view of `attributeSchema`, creating the
	 * shared structures on first use. When `foldedUnique` is set, this write IS the folded unique attribute's store, so
	 * it both enforces per-value uniqueness against the shared tree (before the write) and lazily registers the folded
	 * read-view bound to the live filter view. It is self-contained — the folded handling does not depend on
	 * {@link #insertUniqueAttribute} having run first (the caller derives the flag from that method's return, but the
	 * view registration and enforcement both happen here against the live tree).
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the attribute being inserted
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the attribute value to insert
	 * @param recordId        the primary key the value is attributed to
	 * @param foldedUnique    `true` when this is a folded unique attribute write (enforce uniqueness + register the view)
	 * @throws UniqueValueViolationException when `foldedUnique` is set and the value is already owned by another record
	 */
	public void insertFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId,
		boolean foldedUnique
	) {
		insertFilterAttribute(
			referenceSchema, attributeSchema, allowedLocales, locale, value, recordId, foldedUnique, null);
	}

	/**
	 * Value-lifecycle-reporting variant of {@link #insertFilterAttribute(ReferenceSchemaContract,
	 * AttributeSchemaContract, Set, Locale, Serializable, int, boolean)}: `sink` learns about every distinct value
	 * this write brings into existence in the shared value tree.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the attribute being inserted
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the attribute value to insert
	 * @param recordId        the primary key the value is attributed to
	 * @param foldedUnique    `true` when this is a folded unique attribute write
	 * @param sink            learns about the values born by this write, or `null` when nobody is interested
	 * @throws UniqueValueViolationException when `foldedUnique` is set and the value is already owned by another record
	 */
	public void insertFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId,
		boolean foldedUnique,
		@Nullable ValueLifecycleSink sink
	) {
		final AttributeIndexKey lookupKey = createAttributeKey(
			referenceSchema, attributeSchema, allowedLocales, locale, value);
		final FilterIndex theFilterIndex = getOrCreateFilterView(lookupKey, attributeSchema);
		if (foldedUnique) {
			// this write owns the folded unique attribute: enforce uniqueness against the shared tree BEFORE the write,
			// then register the folded read-view bound to the live filter view (the tree now exists, so no rebind needed)
			enforceFoldedUniqueness(lookupKey, attributeSchema, theFilterIndex, value, recordId);
			registerFoldedUniqueView(lookupKey, attributeSchema, theFilterIndex);
		}
		theFilterIndex.addRecord(recordId, value, sink);
	}

	/**
	 * Removes `value` for `recordId` from the shared value tree through the filter view of `attributeSchema`, dropping
	 * the shared structures (and any folded unique view) once the tree empties.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the attribute being removed
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the attribute value to remove
	 * @param recordId        the primary key the value was attributed to
	 */
	public void removeFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		removeFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId, null);
	}

	/**
	 * Value-lifecycle-reporting variant of {@link #removeFilterAttribute(ReferenceSchemaContract,
	 * AttributeSchemaContract, Set, Locale, Serializable, int)}: `sink` learns about every distinct value this
	 * write takes out of existence in the shared value tree.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the attribute being removed
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the attribute value to remove
	 * @param recordId        the primary key the value was attributed to
	 * @param sink            learns about the values that died in this write, or `null` when nobody is interested
	 */
	public void removeFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId,
		@Nullable ValueLifecycleSink sink
	) {
		final AttributeIndexKey lookupKey = createAttributeKey(
			referenceSchema, attributeSchema, allowedLocales, locale, value);
		final FilterIndex theFilterIndex = resolveFilterViewForMutation(lookupKey, attributeSchema);
		theFilterIndex.removeRecord(recordId, value, sink);
		removeSharedIfEmpty(lookupKey, theFilterIndex);
	}

	/**
	 * Adds the array elements `value` for `recordId` to the shared value tree through the filter view of
	 * `attributeSchema`. Used for array attributes where only the added elements are known. When `foldedUnique` is set,
	 * EVERY new element is verified before any is added (so a violation on a late element leaves none half-applied) and
	 * the folded read-view is registered. The flag is derived by the caller from {@link #insertUniqueAttribute}'s return.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the array attribute being modified
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the array elements to add
	 * @param recordId        the primary key the values are attributed to
	 * @param foldedUnique    `true` when this is a folded unique attribute write (enforce uniqueness + register the view)
	 * @throws UniqueValueViolationException when `foldedUnique` is set and any element is already owned by another record
	 */
	public void addDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId,
		boolean foldedUnique
	) {
		addDeltaFilterAttribute(
			referenceSchema, attributeSchema, allowedLocales, locale, value, recordId, foldedUnique, null);
	}

	/**
	 * Value-lifecycle-reporting variant of {@link #addDeltaFilterAttribute(ReferenceSchemaContract,
	 * AttributeSchemaContract, Set, Locale, Serializable[], int, boolean)} - see
	 * {@link #insertFilterAttribute(ReferenceSchemaContract, AttributeSchemaContract, Set, Locale, Serializable,
	 * int, boolean, ValueLifecycleSink)} for what the sink is told.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the array attribute being modified
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the array elements to add
	 * @param recordId        the primary key the values are attributed to
	 * @param foldedUnique    `true` when this is a folded unique attribute write
	 * @param sink            learns about the values born by this write, or `null` when nobody is interested
	 * @throws UniqueValueViolationException when `foldedUnique` is set and any element is already owned by
	 *                                       another record
	 */
	public void addDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId,
		boolean foldedUnique,
		@Nullable ValueLifecycleSink sink
	) {
		final AttributeIndexKey lookupKey = createAttributeKey(
			referenceSchema, attributeSchema, allowedLocales, locale, value);
		final FilterIndex theFilterIndex = getOrCreateFilterView(lookupKey, attributeSchema);
		if (foldedUnique) {
			// this write owns the folded unique array: verify EVERY new element before any is added (atomic over the whole
			// array), then register the folded read-view bound to the live filter view
			enforceFoldedUniqueness(lookupKey, attributeSchema, theFilterIndex, value, recordId);
			registerFoldedUniqueView(lookupKey, attributeSchema, theFilterIndex);
		}
		theFilterIndex.addRecordDelta(recordId, value, sink);
	}

	/**
	 * Removes the array elements `value` for `recordId` from the shared value tree through the filter view of
	 * `attributeSchema`, dropping the shared structures once the tree empties. Used for array attributes where only the
	 * removed elements are known.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the array attribute being modified
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the array elements to remove
	 * @param recordId        the primary key the values were attributed to
	 */
	public void removeDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		removeDeltaFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId, null);
	}

	/**
	 * Value-lifecycle-reporting variant of {@link #removeDeltaFilterAttribute(ReferenceSchemaContract,
	 * AttributeSchemaContract, Set, Locale, Serializable[], int)} - see
	 * {@link #removeFilterAttribute(ReferenceSchemaContract, AttributeSchemaContract, Set, Locale, Serializable,
	 * int, ValueLifecycleSink)} for what the sink is told.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the array attribute being modified
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the array elements to remove
	 * @param recordId        the primary key the values were attributed to
	 * @param sink            learns about the values that died in this write, or `null` when nobody is interested
	 */
	public void removeDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId,
		@Nullable ValueLifecycleSink sink
	) {
		final AttributeIndexKey lookupKey = createAttributeKey(
			referenceSchema, attributeSchema, allowedLocales, locale, value);
		final FilterIndex theFilterIndex = resolveFilterViewForMutation(lookupKey, attributeSchema);
		theFilterIndex.removeRecordDelta(recordId, value, sink);
		removeSharedIfEmpty(lookupKey, theFilterIndex);
	}

	/**
	 * Makes sure the shared value tree of `lookupKey` exists and carries stable value ids for `consumerName`,
	 * creating the tree when the attribute has never been written to.
	 *
	 * This is how a subsystem that maintains a value-id-keyed structure of its own switches the id column on. It has
	 * to happen on the WRITE path rather than when the capability is declared, because a shared value tree is created
	 * lazily on the first write to its attribute — and it has to happen BEFORE that write, or the first value would
	 * be stamped after the fact or not at all. Both are single-writer moments, which is the obligation
	 * {@link InvertedIndex#attachValueIdConsumer(String)} states.
	 *
	 * Idempotent, but not free: resolving the view declares the attribute's filter key mutated for the commit walk,
	 * and every call allocates a {@link io.evitadb.index.invertedIndex.ValueIdAllocator} that a tree already carrying
	 * ids immediately discards. It belongs on the path that creates the structure needing the ids — once per
	 * attribute — and never on the per-write path.
	 *
	 * @param lookupKey       the attribute and locale whose shared value tree is to carry value ids
	 * @param attributeSchema the schema of that attribute, used to shape the tree when it is created here
	 * @param consumerName    the stable name of the subsystem needing the ids
	 */
	public void attachSharedValueIdConsumer(
		@Nonnull AttributeIndexKey lookupKey,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull String consumerName
	) {
		getOrCreateFilterView(lookupKey, attributeSchema).getInvertedIndex().attachValueIdConsumer(consumerName);
	}

	/**
	 * Tells the shared value tree of `lookupKey` that `consumerName` no longer needs its stable value ids — the drop
	 * half of {@link #attachSharedValueIdConsumer}, performed by the write that observes the capability behind the
	 * consumer withdrawn.
	 *
	 * Resolves the view read-only and does nothing when the attribute has no shared value tree: a withdrawal reaching
	 * an attribute that was never written to has nothing to detach from, and creating the tree in order to unregister
	 * from it would be absurd. Unlike the attach it costs nothing to call on a tree that carries no ids at all.
	 *
	 * What the tree does with the last consumer's departure — and why a populated one keeps its id column —
	 * is stated on {@link InvertedIndex#detachValueIdConsumer(String)}.
	 *
	 * @param lookupKey    the attribute and locale whose shared value tree carried the ids
	 * @param consumerName the stable name of the subsystem that no longer needs them
	 */
	public void detachSharedValueIdConsumer(
		@Nonnull AttributeIndexKey lookupKey,
		@Nonnull String consumerName
	) {
		final FilterIndex filterIndex = resolveFilterView(lookupKey);
		if (filterIndex != null) {
			filterIndex.getInvertedIndex().detachValueIdConsumer(consumerName);
		}
	}

	/**
	 * Registers `value` for `recordId` in the sort structure of `attributeSchema`. A {@link Predecessor} /
	 * {@link ReferencedEntityPredecessor} value goes into a {@link ChainIndex} (predecessor ordering); any other value
	 * goes into a {@link SortIndex}, which is a view over the shared tree for both-flagged attributes or a standalone
	 * owner for sort-only attributes.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the attribute being inserted
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the attribute value to insert
	 * @param recordId        the primary key the value is attributed to
	 */
	public void insertSortAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		final AttributeIndexKey attributeKey = createAttributeKey(
			referenceSchema, attributeSchema, allowedLocales, locale, value);
		if (value instanceof Predecessor predecessor) {
			final ChainIndex theSortIndex = getOrCreateChainIndex(attributeKey);
			theSortIndex.upsertPredecessor(predecessor, recordId);
		} else if (value instanceof ReferencedEntityPredecessor referencedEntityPredecessor) {
			final ChainIndex theSortIndex = getOrCreateChainIndex(attributeKey);
			theSortIndex.upsertPredecessor(referencedEntityPredecessor, recordId);
		} else {
			final PersistentTransactionalProducerMap<AttributeIndexKey, SortIndex> sortIndexes =
				getOrCreateSortIndexMap();
			final SortIndex theSortIndex = sortIndexes.computeIfAbsent(
				attributeKey,
				lookupKey -> {
					// pass a parent-bound supplier: when a shared tree already exists for this key (both-flagged
					// attribute) the factory returns a SortIndexView that drops its own sortedValues; otherwise
					// (sort-only) the supplier resolves null and it returns an OwnerSortIndex. Never capture an instance.
					final SortIndex newSortIndex = SortIndex.create(
						attributeSchema.getPlainType(), this.referenceKey, lookupKey,
						attributeSchema.getIndexedDecimalPlaces(),
						() -> entryOf(this.sharedValueIndex, lookupKey)
					);
					ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
						.ifPresent(it -> it.addCreatedItem(newSortIndex));
					return newSortIndex;
				}
			);
			// a pre-existing sort index froze its BigDecimal scale at creation; refuse to add a value scaled at a drifted
			// schema scale rather than silently mix two scales (no-op for non-BigDecimal and for a just-created index)
			FilterIndex.assertIndexedDecimalPlacesUnchanged(
				theSortIndex.getIndexedDecimalPlaces(),
				attributeSchema.getIndexedDecimalPlaces(),
				attributeSchema.getName()
			);
			// addRecord mutates the sort index in place — declare it for the O(Δ) commit walk (a freshly created index
			// is already in the diff's modified set; the mark is deduplicated)
			sortIndexes.markValueMutated(attributeKey);
			theSortIndex.addRecord(value, recordId);
		}
	}

	/**
	 * Drops `value` for `recordId` from the sort structure of `attributeSchema`, removing the now-empty
	 * {@link ChainIndex} or {@link SortIndex}. The structure is selected from the schema type the same way
	 * {@link #insertSortAttribute} selects it.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the attribute being removed
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the attribute value to remove
	 * @param recordId        the primary key the value was attributed to
	 */
	public void removeSortAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		final AttributeIndexKey lookupKey = createAttributeKey(
			referenceSchema, attributeSchema, allowedLocales, locale, value);

		if (Predecessor.class.equals(attributeSchema.getType()) || ReferencedEntityPredecessor.class.equals(
			attributeSchema.getType())) {
			// an absent family means an absent index, which the notNull below reports as the caller's error
			final PersistentTransactionalProducerMap<AttributeIndexKey, ChainIndex> chainIndexes = this.chainIndex;
			final ChainIndex theChainIndex = entryOf(chainIndexes, lookupKey);
			notNull(theChainIndex, "Chain index for attribute `" + attributeSchema.getName() + "` not found!");
			// removePredecessor mutates the chain in place — declare it for the O(Δ) commit walk (a subsequent map-remove
			// of an emptied chain is tracked separately as a removal)
			chainIndexes.markValueMutated(lookupKey);
			theChainIndex.removePredecessor(recordId);

			if (theChainIndex.isEmpty()) {
				chainIndexes.remove(lookupKey);
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addRemovedItem(theChainIndex));
			}
		} else {
			// an absent family means an absent index, which the notNull below reports as the caller's error
			final PersistentTransactionalProducerMap<AttributeIndexKey, SortIndex> sortIndexes = this.sortIndex;
			final SortIndex theSortIndex = entryOf(sortIndexes, lookupKey);
			notNull(theSortIndex, "Sort index for attribute `" + attributeSchema.getName() + "` not found!");
			// the sort index froze its BigDecimal scale at creation; refuse to derive a remove probe at a drifted schema
			// scale (which would miss the stored key) rather than silently leave the value behind (no-op for non-BigDecimal)
			FilterIndex.assertIndexedDecimalPlacesUnchanged(
				theSortIndex.getIndexedDecimalPlaces(),
				attributeSchema.getIndexedDecimalPlaces(),
				attributeSchema.getName()
			);
			// removeRecord mutates the sort index in place — declare it for the O(Δ) commit walk
			sortIndexes.markValueMutated(lookupKey);
			theSortIndex.removeRecord(value, recordId);

			if (theSortIndex.isEmpty()) {
				sortIndexes.remove(lookupKey);
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addRemovedItem(theSortIndex));
			}
		}
	}

	/**
	 * Registers the compound `value` for `recordId` in the {@link SortIndex} of a sortable attribute compound. A compound
	 * has no shared filter twin, so it is always backed by a standalone owner sort index whose comparator chain is derived
	 * from the compound's element definitions.
	 *
	 * @param entitySchema          the entity schema (decides whether the compound is localized)
	 * @param referenceSchema       the reference schema owning the compound, or `null` for entity-level compounds
	 * @param compoundSchema        the sortable attribute compound schema
	 * @param attributeTypeProvider resolves the value type of each element attribute by name
	 * @param locale                the locale of the value, or `null` for language-agnostic compounds
	 * @param value                 the compound element values to insert
	 * @param recordId              the primary key the value is attributed to
	 */
	public void insertSortAttributeCompound(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nonnull Function<String, Class<?>> attributeTypeProvider,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		final AttributeIndexKey attributeKey = createAttributeKey(
			entitySchema, referenceSchema, compoundSchema, locale);
		final PersistentTransactionalProducerMap<AttributeIndexKey, SortIndex> sortIndexes = getOrCreateSortIndexMap();
		final SortIndex theSortIndex = sortIndexes.computeIfAbsent(
			attributeKey,
			lookupKey -> {
				// a sortable attribute compound has no shared filter twin by construction, so it is always an owner
				final SortIndex newSortIndex = new OwnerSortIndex(
					compoundSchema.getAttributeElements()
						.stream()
						.map(it -> new ComparatorSource(
							attributeTypeProvider.apply(it.attributeName()),
							it.direction(),
							it.behaviour()
						))
						.toArray(ComparatorSource[]::new),
					this.referenceKey,
					lookupKey
				);
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addCreatedItem(newSortIndex));
				return newSortIndex;
			}
		);
		// addRecord mutates the sort index in place — declare it for the O(Δ) commit walk
		sortIndexes.markValueMutated(attributeKey);
		theSortIndex.addRecord(value, recordId);
	}

	/**
	 * Drops the compound `value` for `recordId` from the {@link SortIndex} of a sortable attribute compound, removing the
	 * index once it empties.
	 *
	 * @param entitySchema    the entity schema (decides whether the compound is localized)
	 * @param referenceSchema the reference schema owning the compound, or `null` for entity-level compounds
	 * @param compoundSchema  the sortable attribute compound schema
	 * @param locale          the locale of the value, or `null` for language-agnostic compounds
	 * @param value           the compound element values to remove
	 * @param recordId        the primary key the value was attributed to
	 */
	public void removeSortAttributeCompound(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		final AttributeIndexKey lookupKey = createAttributeKey(entitySchema, referenceSchema, compoundSchema, locale);
		// an absent family means an absent index, which the notNull below reports as the caller's error
		final PersistentTransactionalProducerMap<AttributeIndexKey, SortIndex> sortIndexes = this.sortIndex;
		final SortIndex theSortIndex = entryOf(sortIndexes, lookupKey);
		notNull(
			theSortIndex, "Sort index for sortable attribute compound `" + compoundSchema.getName() + "` not found!");
		// removeRecord mutates the sort index in place — declare it for the O(Δ) commit walk
		sortIndexes.markValueMutated(lookupKey);
		theSortIndex.removeRecord(value, recordId);

		if (theSortIndex.isEmpty()) {
			sortIndexes.remove(lookupKey);
			ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
				.ifPresent(it -> it.addRemovedItem(theSortIndex));
		}
	}

	@Override
	public void forEachAttributeIndexKey(
		@Nonnull AttributeIndexType type,
		@Nonnull Consumer<AttributeIndexKey> consumer
	) {
		switch (type) {
			case UNIQUE -> {
				forEachInFamily(this.uniqueIndex, (key, index) -> consumer.accept(key));
				// only folded-unique views whose shared tree still exists are advertised, gated exactly as in
				// `getUniqueIndexes()`; the `uniqueIndex` test is what keeps this walk duplicate-free where that
				// method relied on the set it was building
				forEachInFamily(this.uniqueViewIndex, (key, index) -> {
					if (familyHoldsKey(this.sharedValueIndex, key) && !familyHoldsKey(this.uniqueIndex, key)) {
						consumer.accept(key);
					}
				});
			}
			// transactional truth for FILTER = the shared value index key set
			case FILTER -> forEachInFamily(this.sharedValueIndex, (key, tree) -> consumer.accept(key));
			case SORT -> forEachInFamily(this.sortIndex, (key, index) -> consumer.accept(key));
			case CHAIN -> forEachInFamily(this.chainIndex, (key, index) -> consumer.accept(key));
			case CARDINALITY -> throw new GenericEvitaInternalError(
				"An attribute index holds no CARDINALITY sub-indexes - those belong to `ReducedGroupEntityIndex` " +
					"and `ReferencedTypeEntityIndex`, which own their own cardinality maps."
			);
		}
	}

	@Override
	@Nonnull
	public Set<AttributeIndexKey> getUniqueIndexes() {
		// union of the standalone (owner) and folded (view) unique keys
		if (familyIsEmpty(this.uniqueViewIndex)) {
			return keysOfFamily(this.uniqueIndex);
		}
		final Set<AttributeIndexKey> keys = CollectionUtils.createHashSet(
			familySize(this.uniqueIndex) + familySize(this.uniqueViewIndex)
		);
		keys.addAll(keysOfFamily(this.uniqueIndex));
		// only folded-unique views whose shared tree still exists are advertised — a stale view key (whose tree emptied
		// and was dropped) has no slim part to write, so it must be gated here exactly as in collectKeys() and the
		// UniqueIndexView.appendStorageParts guard, or the manifest would diverge from the live sub-index walk
		for (final AttributeIndexKey key : keysOfFamily(this.uniqueViewIndex)) {
			if (familyHoldsKey(this.sharedValueIndex, key)) {
				keys.add(key);
			}
		}
		return keys;
	}

	@Override
	@Nullable
	public UniqueIndex getUniqueIndex(@Nonnull AttributeIndexKey lookupKey) {
		// resolve against the standalone (owner) map first, then the folded (view) map - both are keyed by the unique
		// key, exactly as the schema-addressed overload does
		final UniqueIndex owner = entryOf(this.uniqueIndex, lookupKey);
		return owner != null ? owner : entryOf(this.uniqueViewIndex, lookupKey);
	}

	@Override
	@Nonnull
	public Set<AttributeIndexKey> getFilterIndexes() {
		// transactional truth = the shared value index key set
		return keysOfFamily(this.sharedValueIndex);
	}

	@Override
	@Nullable
	public FilterIndex getFilterIndex(@Nonnull AttributeIndexKey lookupKey) {
		return resolveFilterView(lookupKey);
	}

	@Override
	@Nullable
	public FilterIndex getFilterIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale
	) {
		return resolveFilterView(createAttributeKey(referenceSchema, attributeSchema, locale));
	}

	@Override
	@Nonnull
	public Set<AttributeIndexKey> getSortIndexes() {
		return keysOfFamily(this.sortIndex);
	}

	@Override
	@Nullable
	public SortIndex getSortIndex(@Nonnull AttributeIndexKey lookupKey) {
		return entryOf(this.sortIndex, lookupKey);
	}

	@Override
	@Nullable
	public SortIndex getSortIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale
	) {
		return entryOf(this.sortIndex, createAttributeKey(referenceSchema, attributeSchema, locale));
	}

	@Nullable
	@Override
	public SortIndex getSortIndex(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nullable Locale locale
	) {
		return entryOf(this.sortIndex, createAttributeKey(entitySchema, referenceSchema, compoundSchema, locale));
	}

	@Nonnull
	@Override
	public Set<AttributeIndexKey> getChainIndexes() {
		return keysOfFamily(this.chainIndex);
	}

	@Nullable
	@Override
	public ChainIndex getChainIndex(@Nonnull AttributeIndexKey lookupKey) {
		return entryOf(this.chainIndex, lookupKey);
	}

	@Nullable
	@Override
	public ChainIndex getChainIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale
	) {
		return entryOf(this.chainIndex, createAttributeKey(referenceSchema, attributeSchema, locale));
	}

	@Nullable
	@Override
	public ChainIndex getChainIndex(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nullable Locale locale
	) {
		return entryOf(this.chainIndex, createAttributeKey(entitySchema, referenceSchema, compoundSchema, locale));
	}

	@Override
	public boolean isAttributeIndexEmpty() {
		// shared value index = canonical (transactional) owner of FILTER data; uniqueIndex is a standalone owner
		return familyIsEmpty(this.uniqueIndex) && familyIsEmpty(this.sharedValueIndex) &&
			familyIsEmpty(this.sortIndex) && familyIsEmpty(this.chainIndex);
	}

	/**
	 * Returns the heap every attribute sub-index of this entity type occupies, in bytes.
	 *
	 * # Which map charges a key
	 *
	 * One {@link AttributeIndexKey} instance is filed in several of these maps at once: the key a filter write mints
	 * is put into {@link #sharedValueIndex}, {@link #filterIndex}, {@link #sharedRangeIndex} and
	 * {@link #uniqueViewIndex} by that single call, and it is the same object in all four. So the **shared value index
	 * charges it and the three derived maps charge a slot** — charging it in each would report one object up to four
	 * times, in one figure, for every filterable attribute in the collection. {@link #uniqueIndex}, {@link #sortIndex}
	 * and {@link #chainIndex} are reached by their own write paths, each minting its own key, so each charges what it
	 * holds; a both-filterable-and-sortable attribute genuinely owns two key objects and is charged for two.
	 *
	 * The same reasoning covers the five `persisted*LeafPages` snapshots: they are keyed by the very instances the
	 * sub-index maps hold, so they charge their `int[]` page sequences and a slot for the key. An empty snapshot
	 * contributes nothing at all — it is `Map.of()`, the JVM-wide singleton, which no index owns.
	 *
	 * An **absent** sub-index family likewise contributes nothing, because there is nothing there: a family is not
	 * allocated until something writes to it (see "lazy sub-index maps" on the class). Only the fourteen reference
	 * slots of this object are charged unconditionally, and they exist whether or not the maps behind them do.
	 *
	 * # What the sub-indexes charge
	 *
	 * Each decides for itself, and the two derived view maps are where it matters: a {@link FilterIndexView} and a
	 * folded {@link UniqueIndexView} charge their own object and their query memos but never the shared tree beneath
	 * them, which is charged once here through {@link #sharedValueIndex}. {@link #entityType} is the collection's name
	 * and {@link #referenceKey} belongs to the entity index enclosing this one, so both contribute their slot alone.
	 *
	 * This walks every sub-index and every value tree, so it is `O(indexed values)` — it belongs to
	 * the index detail call and must never be called from a query path.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the key object: a record of a reference name, an attribute name and a locale - the two names belong to the
		// schema and the locale is interned by the JVM, so the record's own object is all this index owns of it
		final long attributeIndexKey = layout.sizeOfObject(3L * layout.referenceSize());
		final ToLongFunction<AttributeIndexKey> ownedKey = key -> attributeIndexKey;
		final ToLongFunction<AttributeIndexKey> borrowedKey = key -> 0L;
		// read each family once - a volatile field re-read mid-arithmetic could see a concurrent writer's first write
		// materialise it, and the figure would then be half of one shape and half of another
		final PersistentTransactionalProducerMap<AttributeIndexKey, UniqueIndex> uniqueIndexes = this.uniqueIndex;
		final PersistentTransactionalProducerMap<AttributeIndexKey, SortIndex> sortIndexes = this.sortIndex;
		final PersistentTransactionalProducerMap<AttributeIndexKey, ChainIndex> chainIndexes = this.chainIndex;
		final PersistentTransactionalProducerMap<AttributeIndexKey, InvertedIndex> sharedValues = this.sharedValueIndex;
		final PersistentTransactionalProducerMap<AttributeIndexKey, RangeIndex> sharedRanges = this.sharedRangeIndex;
		final TransactionalMap<AttributeIndexKey, FilterIndex> filterViews = this.filterIndex;
		final TransactionalMap<AttributeIndexKey, UniqueIndex> uniqueViews = this.uniqueViewIndex;
		// id, then the entityType / referenceKey slots, the seven sub-index maps and the five leaf-page snapshots
		return layout.sizeOfObject(Long.BYTES + 14L * layout.referenceSize())
			+ (uniqueIndexes == null ?
				0L : uniqueIndexes.getHeapSizeInBytes(ownedKey, UniqueIndex::getHeapSizeInBytes))
			+ (sortIndexes == null ? 0L : sortIndexes.getHeapSizeInBytes(ownedKey, SortIndex::getHeapSizeInBytes))
			+ (chainIndexes == null ? 0L : chainIndexes.getHeapSizeInBytes(ownedKey, ChainIndex::getHeapSizeInBytes))
			+ (sharedValues == null ?
				0L : sharedValues.getHeapSizeInBytes(ownedKey, InvertedIndex::getHeapSizeInBytes))
			+ (sharedRanges == null ?
				0L : sharedRanges.getHeapSizeInBytes(borrowedKey, RangeIndex::getHeapSizeInBytes))
			+ (filterViews == null ? 0L : filterViews.getHeapSizeInBytes(borrowedKey, FilterIndex::getHeapSizeInBytes))
			+ (uniqueViews == null ?
				0L : uniqueViews.getHeapSizeInBytes(borrowedKey, UniqueIndex::getHeapSizeInBytes))
			+ leafPageSnapshotHeapSizeInBytes(this.persistedChainLeafPages)
			+ leafPageSnapshotHeapSizeInBytes(this.persistedFilterInvertedLeafPages)
			+ leafPageSnapshotHeapSizeInBytes(this.persistedFilterRangeLeafPages)
			+ leafPageSnapshotHeapSizeInBytes(this.persistedUniqueLeafPages)
			+ leafPageSnapshotHeapSizeInBytes(this.persistedSortLeafPages);
	}

	/**
	 * Prices one per-family on-disk leaf-page snapshot: its map and the page sequences it holds, but not its keys —
	 * those are the sub-index map's own instances, charged there.
	 *
	 * An **empty** snapshot contributes nothing rather than an empty map's object: both the fresh-index constructor
	 * and {@link #snapshotLeafPages} park an empty snapshot on `Map.of()`, which is one immutable instance shared by
	 * the whole JVM and owned by no index in it.
	 *
	 * @param snapshot the per-key on-disk leaf-page snapshot of one paged family
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	private static long leafPageSnapshotHeapSizeInBytes(@Nonnull Map<AttributeIndexKey, int[]> snapshot) {
		if (snapshot.isEmpty()) {
			return 0L;
		}
		final VMLayout layout = VMLayout.current();
		return MapHeapSize.sizeOf(
			snapshot, key -> 0L, pages -> layout.sizeOfArray(pages.length, Integer.BYTES)
		);
	}

	/**
	 * {@inheritDoc}
	 *
	 * **Every walk here is a `forEach`**, for the reason spelled out on {@link #collectKeys}: an accessor asked for on
	 * this path leaves a view object cached in the map for the lifetime of the index, and this path runs from every
	 * entity index constructor as well as from every flush.
	 */
	@Override
	public void getModifiedStorageParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges trappedChanges) {
		// UNIQUE parts: standalone (owner) indexes emit a SINGLE inline root or granular PAGED leaf pages + a PAGED root,
		// folded (view) indexes emit a slim part — both go through appendStorageParts.
		forEachInFamily(
			this.uniqueIndex, (key, index) -> index.appendStorageParts(entityIndexPrimaryKey, trappedChanges)
		);
		forEachInFamily(
			this.uniqueViewIndex, (key, index) -> index.appendStorageParts(entityIndexPrimaryKey, trappedChanges)
		);
		// FILTER parts are produced from the shared tree via the rebuilt filter views (which carry attributeType + range).
		// A small (single-leaf) index emits one inline SINGLE part; a large (multi-leaf) index emits granular PAGED leaf
		// pages + a PAGED root — both go through appendStorageParts.
		forEachInFamily(this.sharedValueIndex, (key, tree) -> {
			final FilterIndex view = resolveFilterView(key);
			if (view != null) {
				view.appendStorageParts(entityIndexPrimaryKey, trappedChanges);
			}
		});
		// SORT parts: owner mode emits its full part, view mode a slim part — both go through appendStorageParts,
		// mirroring the UNIQUE/FILTER loops above.
		forEachInFamily(
			this.sortIndex, (key, index) -> index.appendStorageParts(entityIndexPrimaryKey, trappedChanges)
		);
		// Empty-drop reclaim: a PAGED sub-index emptied and dropped from its map this commit still has its leaf pages on disk, but
		// the dropped index's own appendStorageParts never runs again — so for each paged family diff the pre-commit
		// on-disk snapshot against the surviving keys and emit a removal for every leaf page of a vanished key, or the
		// append-only OffsetIndex copies those orphaned pages forward on every compaction forever. Applied symmetrically
		// to CHAIN, owner UNIQUE, owner SORT and both FILTER axes (shared value tree + range companion); each surviving
		// sub-index still reclaims its own split/merge-freed pages through its appendStorageParts above.
		emitDroppedLeafPageRemovals(
			entityIndexPrimaryKey, this.persistedChainLeafPages, key -> familyHoldsKey(this.chainIndex, key),
			AttributeIndexType.CHAIN, trappedChanges, ChainIndexLeafPageRemoval::new
		);
		emitDroppedLeafPageRemovals(
			entityIndexPrimaryKey, this.persistedUniqueLeafPages, key -> familyHoldsKey(this.uniqueIndex, key),
			AttributeIndexType.UNIQUE, trappedChanges, UniqueIndexLeafPageRemoval::new
		);
		emitDroppedLeafPageRemovals(
			entityIndexPrimaryKey, this.persistedSortLeafPages, key -> familyHoldsKey(this.sortIndex, key),
			AttributeIndexType.SORT, trappedChanges, SortIndexLeafPageRemoval::new
		);
		emitDroppedLeafPageRemovals(
			entityIndexPrimaryKey, this.persistedFilterInvertedLeafPages,
			key -> familyHoldsKey(this.sharedValueIndex, key),
			AttributeIndexType.FILTER, trappedChanges, FilterIndexLeafPageRemoval::new
		);
		emitDroppedLeafPageRemovals(
			entityIndexPrimaryKey, this.persistedFilterRangeLeafPages,
			key -> familyHoldsKey(this.sharedRangeIndex, key),
			AttributeIndexType.FILTER, trappedChanges, RangeIndexLeafPageRemoval::new
		);
		// CHAIN parts: a small (single-leaf) chain index emits one inline SINGLE part; a large (multi-leaf) index emits
		// granular PAGED leaf pages + a PAGED root - both go through appendStorageParts, mirroring the loops above.
		forEachInFamily(
			this.chainIndex, (key, index) -> index.appendStorageParts(entityIndexPrimaryKey, trappedChanges)
		);
		// refresh the empty-drop-reclaim snapshots from the surviving sub-indexes now that each has staged this commit's page set:
		// a reused instance (warm-up / repeated flush) then diffs the next drop against the pages just written here, not a
		// stale construction-time snapshot. Idempotent: re-running it (the baseline-capture pass) reproduces the same maps,
		// and on a transactional commit the pre-merge instance this mutates is discarded (the merge-copy rebuilds afresh).
		this.persistedChainLeafPages = snapshotLeafPages(this.chainIndex, ChainIndex::currentLeafPageSequences);
		this.persistedFilterInvertedLeafPages = snapshotLeafPages(this.sharedValueIndex, InvertedIndex::currentLeafPageSequences);
		this.persistedFilterRangeLeafPages = snapshotLeafPages(this.sharedRangeIndex, RangeIndex::currentLeafPageSequences);
		this.persistedUniqueLeafPages = snapshotLeafPages(this.uniqueIndex, UniqueIndex::currentLeafPageSequences);
		this.persistedSortLeafPages = snapshotLeafPages(this.sortIndex, SortIndex::currentLeafPageSequences);
	}

	/**
	 * Whole-index-drop reclaim: emits a leaf-page removal for EVERY persisted page of all five paged families
	 * (CHAIN, owner UNIQUE, owner SORT, and both FILTER axes — shared value tree + range companion), as if nothing
	 * survives. Called when the owning {@link io.evitadb.index.EntityIndex} is dropped: no sub-index flush will run
	 * again, so the append-only OffsetIndex would copy every orphaned leaf page forward forever unless each is removed
	 * explicitly. Reuses the same {@link #emitDroppedLeafPageRemovals} helper as the per-commit empty-drop reclaim, but
	 * with a `stillPresent` predicate that always returns `false` so every persisted key is treated as vanished.
	 *
	 * The paged FILTER / SORT / UNIQUE / CHAIN roots themselves are manifest-listed and reclaimed by
	 * `EntityIndex.emitVanishedRootRemovals`, so this method emits only leaf pages — never roots. It reads exclusively
	 * the persisted baselines (the `persisted*LeafPages` fields) and has NO side effects: no `forget`, no baseline
	 * mutation.
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param sink                  the trapped-changes accumulator collecting the removal instructions
	 */
	public void emitPersistedLeafPageRemovals(int entityIndexPrimaryKey, @Nonnull TrappedChanges sink) {
		// nothing survives — treat every persisted key of every family as vanished
		final Predicate<AttributeIndexKey> nothingSurvives = key -> false;
		emitDroppedLeafPageRemovals(
			entityIndexPrimaryKey, this.persistedChainLeafPages, nothingSurvives,
			AttributeIndexType.CHAIN, sink, ChainIndexLeafPageRemoval::new
		);
		emitDroppedLeafPageRemovals(
			entityIndexPrimaryKey, this.persistedUniqueLeafPages, nothingSurvives,
			AttributeIndexType.UNIQUE, sink, UniqueIndexLeafPageRemoval::new
		);
		emitDroppedLeafPageRemovals(
			entityIndexPrimaryKey, this.persistedSortLeafPages, nothingSurvives,
			AttributeIndexType.SORT, sink, SortIndexLeafPageRemoval::new
		);
		emitDroppedLeafPageRemovals(
			entityIndexPrimaryKey, this.persistedFilterInvertedLeafPages, nothingSurvives,
			AttributeIndexType.FILTER, sink, FilterIndexLeafPageRemoval::new
		);
		emitDroppedLeafPageRemovals(
			entityIndexPrimaryKey, this.persistedFilterRangeLeafPages, nothingSurvives,
			AttributeIndexType.FILTER, sink, RangeIndexLeafPageRemoval::new
		);
	}

	@Nullable
	public UniqueIndex getUniqueIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Scope scope,
		@Nullable Locale locale
	) {
		final boolean uniqueWithinLocale = attributeSchema.isUniqueWithinLocaleInScope(scope);
		Assert.isTrue(
			locale != null || !uniqueWithinLocale,
			() -> new EntityLocaleMissingException(attributeSchema.getName())
		);
		// resolve against the standalone (owner) map first, then the folded (view) map - both are keyed by the unique key
		final AttributeIndexKey lookupKey = createUniqueAttributeKey(referenceSchema, attributeSchema, scope, locale);
		final UniqueIndex owner = entryOf(this.uniqueIndex, lookupKey);
		return owner != null ? owner : entryOf(this.uniqueViewIndex, lookupKey);
	}

	/**
	 * Synthesizes the full set of {@link AttributeIndexStorageKey} entries currently held by this
	 * index — one key per UNIQUE / FILTER / SORT / CHAIN sub-index — and adds them into `target`.
	 * The keys are composed from the supplied `indexKey` (which carries the discriminator and scope
	 * of the owning {@link io.evitadb.index.EntityIndex}) and the per-attribute key already held
	 * by each sub-index map.
	 *
	 * This method exists as a public accessor so the
	 * {@link io.evitadb.index.component.AttributeIndexComponent} adapter can announce the keys
	 * into the parent index manifest without duplicating the loop. Subclass-specific attribute
	 * types (e.g. CARDINALITY) are owned by the subclass and added separately.
	 *
	 * **Every walk here is a `forEach`, and that is deliberate**: this runs from every entity index constructor, and
	 * asking a map for a `keySet` would leave a permanently cached view object on each of these five maps - see
	 * {@link io.evitadb.index.map.TransactionalMap#forEach} for the arithmetic that makes sixteen bytes matter. The
	 * lambdas capture, so each call allocates five short-lived objects instead; that is eden garbage traded for
	 * retained bytes on every index in the catalog.
	 *
	 * @param indexKey the parent {@link EntityIndexKey} used as the storage-key
	 *                 prefix
	 * @param target   the set into which the synthesized storage keys are added
	 */
	public void collectKeys(
		@Nonnull EntityIndexKey indexKey,
		@Nonnull Set<AttributeIndexStorageKey> target
	) {
		// UNIQUE keys: standalone (owner) keys, plus folded (view) keys that still have a live shared tree (a stale view
		// key with no backing tree must not be announced, else the manifest would list a part that was never written)
		forEachInFamily(
			this.uniqueIndex,
			(key, index) -> target.add(new AttributeIndexStorageKey(indexKey, AttributeIndexType.UNIQUE, key))
		);
		forEachInFamily(this.uniqueViewIndex, (key, index) -> {
			if (familyHoldsKey(this.sharedValueIndex, key)) {
				target.add(new AttributeIndexStorageKey(indexKey, AttributeIndexType.UNIQUE, key));
			}
		});
		// FILTER keys: transactional truth = the shared value index key set
		forEachInFamily(
			this.sharedValueIndex,
			(key, tree) -> target.add(new AttributeIndexStorageKey(indexKey, AttributeIndexType.FILTER, key))
		);
		forEachInFamily(
			this.sortIndex,
			(key, index) -> target.add(new AttributeIndexStorageKey(indexKey, AttributeIndexType.SORT, key))
		);
		forEachInFamily(
			this.chainIndex,
			(key, index) -> target.add(new AttributeIndexStorageKey(indexKey, AttributeIndexType.CHAIN, key))
		);
	}

	/**
	 * {@inheritDoc}
	 *
	 * **Every walk here is a `forEach`**, for the reason spelled out on {@link #collectKeys}. This one is the trap of
	 * the three: it runs on every flush but not from any constructor, so the cached views it used to leave behind were
	 * invisible to a measurement taken on a freshly built index.
	 */
	@Override
	public void resetDirty() {
		forEachInFamily(this.uniqueIndex, (key, index) -> index.resetDirty());
		// reset the shared trees' dirty flags through the resolved views (transactional truth)
		forEachInFamily(this.sharedValueIndex, (key, tree) -> {
			final FilterIndex view = resolveFilterView(key);
			if (view != null) {
				view.resetDirty();
			}
		});
		forEachInFamily(this.sortIndex, (key, index) -> index.resetDirty());
		forEachInFamily(this.chainIndex, (key, index) -> index.resetDirty());
	}

	@Nullable
	@Override
	public AttributeIndexChanges createLayer() {
		return isTransactionAvailable() ? new AttributeIndexChanges() : null;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// a family that was never allocated owns no diff layer, so there is nothing to discharge for it
		removeFamilyLayer(this.uniqueIndex, transactionalLayer);
		removeFamilyLayer(this.sortIndex, transactionalLayer);
		removeFamilyLayer(this.chainIndex, transactionalLayer);
		removeFamilyLayer(this.sharedValueIndex, transactionalLayer);
		removeFamilyLayer(this.sharedRangeIndex, transactionalLayer);
		// the derived view caches are transactional (MVCC isolation) - discharge their diff layers too
		removeFamilyLayer(this.filterIndex, transactionalLayer);
		removeFamilyLayer(this.uniqueViewIndex, transactionalLayer);
		final AttributeIndexChanges changes = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
	}

	@Nonnull
	@Override
	public AttributeIndex createCopyWithMergedTransactionalMemory(
		@Nullable AttributeIndexChanges layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// commit every map. The shared trees + the standalone unique + sort + chain are producer maps merged value-by-value.
		// The filter views and folded unique views carry non-producer values, but their maps are still TRANSACTIONAL (for
		// MVCC isolation): we commit them to obtain the isolated, this-transaction key set (and discharge their diff
		// layers), then the from-maps ctor re-derives the VIEW VALUES fresh over the committed shared trees — the committed
		// caches are passed only to source each key's attributeType for the rebuilt views.
		final AttributeIndex attributeIndex = createCopy(
			this.entityType,
			this.referenceKey,
			committedFamilyCopy(transactionalLayer, this.uniqueIndex),
			committedFamilyCopy(transactionalLayer, this.filterIndex),
			committedFamilyCopy(transactionalLayer, this.uniqueViewIndex),
			committedFamilyCopy(transactionalLayer, this.sortIndex),
			committedFamilyCopy(transactionalLayer, this.chainIndex),
			committedFamilyCopy(transactionalLayer, this.sharedValueIndex),
			committedFamilyCopy(transactionalLayer, this.sharedRangeIndex)
		);
		ofNullable(layer).ifPresent(it -> it.clean(transactionalLayer));
		return attributeIndex;
	}

	/**
	 * Returns the structural scope of this index — `ENTITY` for indexes that store attributes
	 * defined directly on the entity, `REFERENCE` for indexes attached to a relation. Call sites
	 * that fan out mutations across entity / reference indexes use this getter instead of
	 * re-inspecting the surrounding schema.
	 *
	 * @return the immutable scope tag for this index
	 */
	@Nonnull
	public abstract AttributeScope getScope();

	/**
	 * Factory hook implemented by each {@link AttributeIndex} subclass so the merged-transactional-memory
	 * copy preserves the exact subclass identity. The concrete subclass is the only place that
	 * knows whether the resulting index must be {@link EntityAttributeIndex} or {@link ReferenceAttributeIndex};
	 * the base class never instantiates itself.
	 *
	 * @param entityType       the owning entity type
	 * @param referenceKey     the representative reference key (null for {@link EntityAttributeIndex})
	 * @param uniqueIndex      the merged standalone (owner) unique sub-index map
	 * @param filterIndex      the previous filter VIEW map (carries each key's attributeType for rebuilt views)
	 * @param uniqueViewIndex  the previous folded-unique VIEW map (carries each foldable key + its attributeType)
	 * @param sortIndex        the merged sort sub-index map
	 * @param chainIndex       the merged chain sub-index map
	 * @param sharedValueIndex the merged shared value→ValueToRecord tree map
	 * @param sharedRangeIndex the merged shared range-structure map
	 * @return a freshly built subclass-typed copy
	 */
	@Nonnull
	protected abstract AttributeIndex createCopy(
		@Nonnull String entityType,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull Map<AttributeIndexKey, UniqueIndex> uniqueIndex,
		@Nonnull Map<AttributeIndexKey, FilterIndex> filterIndex,
		@Nonnull Map<AttributeIndexKey, UniqueIndex> uniqueViewIndex,
		@Nonnull Map<AttributeIndexKey, SortIndex> sortIndex,
		@Nonnull Map<AttributeIndexKey, ChainIndex> chainIndex,
		@Nonnull Map<AttributeIndexKey, InvertedIndex> sharedValueIndex,
		@Nonnull Map<AttributeIndexKey, RangeIndex> sharedRangeIndex
	);

	/**
	 * Rebuilds the folded UNIQUE view map over the committed shared trees, mirroring {@link #buildFilterViews} and
	 * {@link #deriveSortViews}. Each previous view is rebound to THIS index's committed {@link FilterIndex} view via
	 * {@link UniqueIndex#bindFilterView}: an O(Δ) carry-forward (returns the same instance) when the filter view is
	 * identity-unchanged, or a fresh view over the committed filter view when it was replaced. Only `source` keys that
	 * still have a live shared tree are rebound — a stale key (e.g. from a rolled-back creation that never committed a
	 * tree) is dropped. No view is ever mutated in place, so a carried-forward view is safe to share with the older
	 * snapshot.
	 *
	 * @param sharedValueIndex the committed value→ValueToRecord trees keyed by filter key
	 * @param source           the previous snapshot's / loaded folded-unique views, supplying which keys are foldable
	 *                         unique attributes (and each key's attribute type)
	 * @return a non-transactional map of folded unique views bound to the committed filter views
	 */
	@Nonnull
	private Map<AttributeIndexKey, UniqueIndex> buildUniqueViews(
		@Nonnull Map<AttributeIndexKey, InvertedIndex> sharedValueIndex,
		@Nonnull Map<AttributeIndexKey, UniqueIndex> source
	) {
		// plain delegate: the caller wraps this in a TransactionalMap for MVCC isolation (see the uniqueViewIndex javadoc)
		if (source.isEmpty()) {
			return CollectionUtils.createHashMap(8);
		}
		final Map<AttributeIndexKey, UniqueIndex> views = CollectionUtils.createHashMap(Math.max(8, source.size()));
		for (final Entry<AttributeIndexKey, UniqueIndex> entry : source.entrySet()) {
			final AttributeIndexKey key = entry.getKey();
			if (sharedValueIndex.containsKey(key)) {
				// resolveFilterView is non-null here (the shared tree exists); bindFilterView carries the previous view
				// forward by reference when that filter view is identity-unchanged
				views.put(key, entry.getValue().bindFilterView(resolveFilterView(key)));
			}
		}
		return views;
	}

	/**
	 * Resolves the {@link FilterIndex} VIEW for `lookupKey` from the TRANSACTIONAL {@link #sharedValueIndex} (the source
	 * of truth), wrapping the shared tree with the SCHEMA-supplied attributeType so it works even when the lazy
	 * {@link #filterIndex} cache was never populated for the key (e.g. the representative-reference alias path).
	 * Throws when the shared tree is genuinely absent — that is the only true "not found".
	 */
	@Nonnull
	private FilterIndex resolveFilterViewForMutation(
		@Nonnull AttributeIndexKey lookupKey,
		@Nonnull AttributeSchemaContract attributeSchema
	) {
		// the returned view's removeRecord mutates the shared tree (and shared range) in place — declare it so the
		// O(Δ) commit walk visits these keys and sweeps their layers
		markFilterMutated(lookupKey, attributeSchema);
		final InvertedIndex shared = entryOf(this.sharedValueIndex, lookupKey);
		notNull(shared, "Filter index for `" + attributeSchema.getName() + "` not found!");
		// reuse the cached view when it still wraps the live shared tree, else wrap it afresh from the schema's
		// attributeType (the cache may never have been populated for this key, e.g. the representative-reference alias path)
		return reuseOrRebuildFilterView(
			lookupKey, shared, attributeSchema.getType(), attributeSchema.getIndexedDecimalPlaces()
		);
	}

	/**
	 * Returns the cached {@link FilterIndexView} for `lookupKey` when it still wraps `shared`; otherwise wraps the live
	 * `shared` tree (and, for a range-typed attribute, the shared {@link RangeIndex}) in a fresh view typed by
	 * `attributeType` and writes it back into the {@link #filterIndex} cache so subsequent writes reuse the same façade.
	 * Only the mutation paths resolve views this way; the read path builds its own ephemeral view ({@link #resolveFilterView})
	 * and never mutates the cache.
	 *
	 * @param lookupKey            the attribute-index key whose view is resolved
	 * @param shared               the live shared tree the view must wrap (the source of truth from {@link #sharedValueIndex})
	 * @param attributeType        the attribute type used to wire the view's comparator/normalizer when rebuilding
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 * @return the reused or freshly wrapped filter view over `shared`
	 */
	@Nonnull
	private FilterIndex reuseOrRebuildFilterView(
		@Nonnull AttributeIndexKey lookupKey,
		@Nonnull InvertedIndex shared,
		@Nonnull Class<?> attributeType,
		int indexedDecimalPlaces
	) {
		// refuse to modify a shared value tree whose frozen BigDecimal scale has drifted from the schema's current scale:
		// the tree (and its range index) already hold keys encoded at the frozen scale, so mixing in a value encoded at a
		// different scale would silently corrupt equality / range / ordering (no-op for non-BigDecimal — both are 0)
		FilterIndex.assertIndexedDecimalPlacesUnchanged(
			shared.getIndexedDecimalPlaces(), indexedDecimalPlaces, lookupKey.attributeName()
		);
		final FilterIndex cached = entryOf(this.filterIndex, lookupKey);
		if (cached != null && cached.getInvertedIndex() == shared) {
			return cached;
		}
		final FilterIndex rebuilt = new FilterIndexView(
			lookupKey, shared, entryOf(this.sharedRangeIndex, lookupKey), attributeType, indexedDecimalPlaces
		);
		getOrCreateFilterIndexMap().put(lookupKey, rebuilt);
		return rebuilt;
	}

	/**
	 * Returns the {@link FilterIndex} VIEW for `lookupKey`, creating the owned shared {@link InvertedIndex} (and, for
	 * range-typed attributes, the shared {@link RangeIndex}) plus the wrapping view when absent. The shared structures
	 * are the {@link TransactionalLayerProducer}s registered with the current transactional layer; the view is a thin
	 * façade whose `addRecord`/`removeRecord` mutate the shared tree (and shared range) directly.
	 */
	@Nonnull
	private FilterIndex getOrCreateFilterView(
		@Nonnull AttributeIndexKey lookupKey,
		@Nonnull AttributeSchemaContract attributeSchema
	) {
		// the returned view's addRecord mutates the shared tree (and shared range) in place — declare it so the O(Δ)
		// commit walk visits these keys and sweeps their layers (covers both reuse of an existing shared tree and a
		// freshly created one)
		markFilterMutated(lookupKey, attributeSchema);
		final InvertedIndex existingShared = entryOf(this.sharedValueIndex, lookupKey);
		if (existingShared != null) {
			// the shared tree already exists (created earlier, possibly by another record in this transaction).
			// Reuse the cached view when it still wraps this instance; otherwise rebuild the thin view from the
			// SCHEMA's attributeType — the plain `filterIndex` cache is a per-instance hint that may be empty or
			// stale here (e.g. when a sort view's read was the first transactional access to the key), so we must
			// not depend on it being populated.
			return reuseOrRebuildFilterView(
				lookupKey, existingShared, attributeSchema.getType(), attributeSchema.getIndexedDecimalPlaces()
			);
		}
		// shared tree absent → create it (+ a range index for range-typed attributes) and the wrapping view
		final Class<?> attributeType = attributeSchema.getType();
		final Class<?> plainType = attributeType.isArray() ? attributeType.getComponentType() : attributeType;
		final int indexedDecimalPlaces = attributeSchema.getIndexedDecimalPlaces();
		final InvertedIndex shared = new InvertedIndex(
			plainType,
			FilterIndex.getNormalizer(plainType, indexedDecimalPlaces),
			FilterIndex.getComparator(lookupKey, plainType),
			indexedDecimalPlaces
		);
		getOrCreateSharedValueIndexMap().put(lookupKey, shared);
		ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
			.ifPresent(it -> it.addCreatedItem(shared));
		final RangeIndex sharedRange;
		if (Range.class.isAssignableFrom(plainType)) {
			sharedRange = new RangeIndex();
			getOrCreateSharedRangeIndexMap().put(lookupKey, sharedRange);
			ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
				.ifPresent(it -> it.addCreatedItem(sharedRange));
		} else {
			sharedRange = null;
		}
		final FilterIndex view = new FilterIndexView(
			lookupKey, shared, sharedRange, attributeType, indexedDecimalPlaces
		);
		getOrCreateFilterIndexMap().put(lookupKey, view);
		return view;
	}

	/**
	 * Declares that the shared {@link InvertedIndex} (and, for a range-typed attribute, the shared {@link RangeIndex})
	 * under `lookupKey` is about to be mutated in place by a {@link FilterIndexView}. Because the view dispatches its
	 * `addRecord`/`removeRecord` straight into those producer structures — invisibly to the
	 * {@link PersistentTransactionalProducerMap}'s own change tracking — the keys must be declared so the map's `O(Δ)`
	 * commit visits them and sweeps their transactional layers. Range-ness is derived from the SCHEMA (the same test
	 * {@link #getOrCreateFilterView} uses to decide whether to create a range), so the decision does not depend on
	 * whether the range index has been created yet. Marking a key not yet inserted is harmless: a created key lands in
	 * the diff's modified set and is handled there, the mark merely being deduplicated.
	 *
	 * @param lookupKey       the shared key about to be mutated
	 * @param attributeSchema the attribute schema (its type decides whether a shared range exists)
	 */
	private void markFilterMutated(
		@Nonnull AttributeIndexKey lookupKey,
		@Nonnull AttributeSchemaContract attributeSchema
	) {
		getOrCreateSharedValueIndexMap().markValueMutated(lookupKey);
		final Class<?> attributeType = attributeSchema.getType();
		final Class<?> plainType = attributeType.isArray() ? attributeType.getComponentType() : attributeType;
		if (Range.class.isAssignableFrom(plainType)) {
			getOrCreateSharedRangeIndexMap().markValueMutated(lookupKey);
		}
	}

	/**
	 * Registers (idempotently) the folded unique read-view for `lookupKey`, bound to the live `theFilterIndex`, so reads
	 * resolve the value to its record. Called by the filter write at the fold point, AFTER the shared tree exists, so the
	 * view binds to the real filter view immediately — no null marker, no rebind. Within a transaction the filter view
	 * instance for a key is stable, so the `computeIfAbsent` allocates at most one view per key per transaction; the
	 * commit then carries it forward by reference (the commit-time counterpart is {@link #buildUniqueViews}).
	 *
	 * @param lookupKey       the (filter == unique) key of the folded attribute
	 * @param attributeSchema the attribute schema (its type wires the view's comparator/normalizer)
	 * @param theFilterIndex  the live filter view the folded read-view folds onto
	 */
	private void registerFoldedUniqueView(
		@Nonnull AttributeIndexKey lookupKey,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull FilterIndex theFilterIndex
	) {
		getOrCreateUniqueViewIndexMap().computeIfAbsent(
			lookupKey,
			viewKey -> UniqueIndex.createView(this.entityType, viewKey, attributeSchema.getType(), theFilterIndex)
		);
	}

	/**
	 * Removes the shared {@link InvertedIndex} / {@link RangeIndex} and the filter view for `lookupKey` once the view's
	 * shared tree is empty, registering the removal with the transactional layer. A folded unique view over the same key
	 * is dropped here too — this is the single place the view lifecycle is tied to the shared tree's lifecycle, so the
	 * order in which the mutator runs the unique-remove and filter-remove blocks does not matter.
	 */
	private void removeSharedIfEmpty(@Nonnull AttributeIndexKey lookupKey, @Nonnull FilterIndex theFilterIndex) {
		if (theFilterIndex.isEmpty()) {
			// the derived caches and the range companion are dropped only if they exist: a folded unique view is
			// registered only for a foldable unique attribute, and a range companion only for a range-typed one, so
			// either family can legitimately be absent on an index that holds this filter key
			removeFromFamily(this.filterIndex, lookupKey);
			removeFromFamily(this.uniqueViewIndex, lookupKey);
			final InvertedIndex shared = removeFromFamily(this.sharedValueIndex, lookupKey);
			final RangeIndex sharedRange = removeFromFamily(this.sharedRangeIndex, lookupKey);
			ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
				.ifPresent(it -> {
					if (shared != null) {
						it.addRemovedItem(shared);
					}
					if (sharedRange != null) {
						it.addRemovedItem(sharedRange);
					}
				});
		}
	}

	/**
	 * Enforces uniqueness for a folded unique attribute at the point its value is written into the shared filter tree.
	 * For an array value EVERY element is verified before the caller adds any of them, so a violation on a late element
	 * cannot leave earlier elements half-applied (the caller's add is atomic over the whole array). This mirrors the
	 * standalone {@link UniqueIndex}'s verify-all-then-mutate contract.
	 *
	 * @param lookupKey       the (filter == unique) key of the folded attribute
	 * @param attributeSchema the attribute schema (for the violation message)
	 * @param theFilterIndex  the filter view over the shared tree to probe
	 * @param value           the scalar value or array of values being attributed to `recordId`
	 * @param recordId        the record claiming the value(s)
	 * @throws UniqueValueViolationException when any value is already owned by a different record
	 */
	private void enforceFoldedUniqueness(
		@Nonnull AttributeIndexKey lookupKey,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull FilterIndex theFilterIndex,
		@Nonnull Serializable value,
		int recordId
	) {
		if (value instanceof final Object[] valueArray) {
			for (final Object element : valueArray) {
				assertFoldedUniqueValueFree(
					lookupKey, attributeSchema, theFilterIndex, (Serializable) element, recordId);
			}
		} else {
			assertFoldedUniqueValueFree(lookupKey, attributeSchema, theFilterIndex, value, recordId);
		}
	}

	/**
	 * Asserts a single folded unique value is free to be claimed by `recordId`. The shared bucket for a unique value
	 * holds at most one record; any record other than the one being (re)attributed is a violation — the same ≠-self
	 * rule the standalone {@link UniqueIndex} enforces (an idempotent re-claim by the same record is allowed, and a
	 * reindex that already removed the prior owner sees an empty/own bucket).
	 */
	private void assertFoldedUniqueValueFree(
		@Nonnull AttributeIndexKey lookupKey,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull FilterIndex theFilterIndex,
		@Nonnull Serializable value,
		int recordId
	) {
		final Bitmap bucket = theFilterIndex.getRecordsEqualTo(value);
		final int size = bucket.size();
		for (int i = 0; i < size; i++) {
			final int existing = bucket.get(i);
			if (existing != recordId) {
				throw new UniqueValueViolationException(
					attributeSchema.getName(), lookupKey.locale(), value,
					this.entityType, existing, this.entityType, recordId
				);
			}
		}
	}

	/**
	 * Resolves the {@link FilterIndex} VIEW for `key` against the TRANSACTIONAL {@link #sharedValueIndex} (the source of
	 * truth across commit/rollback), reusing the cached view when it still wraps the current shared instance and
	 * rebuilding it (from the cached view's attributeType) otherwise. Returns `null` when no shared tree exists for `key`
	 * in the current transactional view. The plain {@link #filterIndex} map is a per-instance cache only.
	 */
	@Nullable
	private FilterIndex resolveFilterView(@Nonnull AttributeIndexKey key) {
		final InvertedIndex shared = entryOf(this.sharedValueIndex, key);
		if (shared == null) {
			return null;
		}
		final FilterIndex cached = entryOf(this.filterIndex, key);
		if (cached != null && cached.getInvertedIndex() == shared) {
			return cached;
		}
		// This is a READ path, so it must not mutate the (now transactional) cache. Under MVCC isolation the cache and
		// `sharedValueIndex` are read from the SAME isolated snapshot and maintained in lockstep, so a present cached view
		// always wraps the current shared tree — the rebuild below is a defensive fallback that returns an EPHEMERAL view
		// (no put) rather than corrupting the shared baseline. The attributeType is carried by the cached view.
		Assert.isPremiseValid(
			cached != null,
			() -> new GenericEvitaInternalError("No cached filter view to source attributeType for key `" + key + "`!")
		);
		return new FilterIndexView(
			key, shared, entryOf(this.sharedRangeIndex, key), cached.getAttributeType(),
			cached.getIndexedDecimalPlaces()
		);
	}

	/**
	 * Method retrieves or creates a ChainIndex based on the provided AttributeKey.
	 * If it does not exist in the chainIndex map, it creates and adds a new one.
	 *
	 * @param attributeIndexKey The attribute key used for lookup or creation of the ChainIndex.
	 * @return The existing or newly created ChainIndex.
	 */
	@Nonnull
	private ChainIndex getOrCreateChainIndex(@Nonnull AttributeIndexKey attributeIndexKey) {
		// the returned chain is mutated in place by the caller's upsertPredecessor — declare it so the O(Δ) commit walk
		// visits this key and sweeps its layer (covers reuse of an existing chain; a freshly created one lands in the
		// diff's modified set)
		final PersistentTransactionalProducerMap<AttributeIndexKey, ChainIndex> chainIndexes =
			getOrCreateChainIndexMap();
		chainIndexes.markValueMutated(attributeIndexKey);
		return chainIndexes.computeIfAbsent(
			attributeIndexKey,
			lookupKey -> {
				final ChainIndex newSortIndex = new ChainIndex(this.referenceKey, lookupKey);
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addCreatedItem(newSortIndex));
				return newSortIndex;
			}
		);
	}

	/**
	 * Describes where a unique attribute value's per-value uniqueness is enforced — the outcome reported by
	 * {@link #insertUniqueAttribute} so the paired filter write knows whether it owns enforcement (and folded-view
	 * registration). It is NOT a narration of what the unique-insert did; it answers the caller's question "who enforces
	 * this value's uniqueness?".
	 */
	public enum UniquenessEnforcement {
		/**
		 * Folded representation: the value physically lives in the shared filter tree, so the filter write
		 * ({@link #insertFilterAttribute}) enforces uniqueness and registers the folded read-view. The unique-insert
		 * stores nothing.
		 */
		BY_FILTER_WRITE,
		/**
		 * Standalone representation: uniqueness was already enforced here by a dedicated owner {@link UniqueIndex}
		 * (global-unique-localized, whose locale-less uniqueness the per-locale shared tree cannot model). The filter
		 * write must NOT re-enforce.
		 */
		BY_OWNER_INDEX,
		/**
		 * This index does not maintain uniqueness for the attribute (e.g. a group index, where many entities legitimately
		 * share a value): nothing enforces it.
		 */
		NONE
	}

	/**
	 * Collects the per-item created/removed bookkeeping for the five producer sub-index containers tracked by an
	 * {@link AttributeIndex}: the standalone {@link #uniqueIndex}, the shared {@link #sharedValueIndex} (which owns the
	 * FILTER data), its sibling {@link #sharedRangeIndex}, {@link #sortIndex} and {@link #chainIndex}. The derived view
	 * caches ({@link #filterIndex}, {@link #uniqueViewIndex}) hold no producer state and are not tracked here.
	 */
	public static class AttributeIndexChanges implements Snapshotable<AttributeIndexChanges.AttributeIndexChangesMemento> {
		// five producer containers: UNIQUE is standalone, FILTER data lives in the shared value-index container, the
		// range structure is its own producer container, plus sort and chain.
		private final TransactionalContainerChanges<UniqueIndex, UniqueIndex> uniqueIndexChanges = new TransactionalContainerChanges<>();
		private final TransactionalContainerChanges<InvertedIndex, InvertedIndex> sharedValueIndexChanges = new TransactionalContainerChanges<>();
		private final TransactionalContainerChanges<RangeIndex, RangeIndex> sharedRangeIndexChanges = new TransactionalContainerChanges<>();
		private final TransactionalContainerChanges<SortIndex, SortIndex> sortIndexChanges = new TransactionalContainerChanges<>();
		private final TransactionalContainerChanges<ChainIndex, ChainIndex> chainIndexChanges = new TransactionalContainerChanges<>();

		public void addCreatedItem(@Nonnull UniqueIndex uniqueIndex) {
			this.uniqueIndexChanges.addCreatedItem(uniqueIndex);
		}

		public void addRemovedItem(@Nonnull UniqueIndex uniqueIndex) {
			this.uniqueIndexChanges.addRemovedItem(uniqueIndex);
		}

		public void addCreatedItem(@Nonnull InvertedIndex sharedValueIndex) {
			this.sharedValueIndexChanges.addCreatedItem(sharedValueIndex);
		}

		public void addRemovedItem(@Nonnull InvertedIndex sharedValueIndex) {
			this.sharedValueIndexChanges.addRemovedItem(sharedValueIndex);
		}

		public void addCreatedItem(@Nonnull RangeIndex sharedRangeIndex) {
			this.sharedRangeIndexChanges.addCreatedItem(sharedRangeIndex);
		}

		public void addRemovedItem(@Nonnull RangeIndex sharedRangeIndex) {
			this.sharedRangeIndexChanges.addRemovedItem(sharedRangeIndex);
		}

		public void addCreatedItem(@Nonnull SortIndex sortIndex) {
			this.sortIndexChanges.addCreatedItem(sortIndex);
		}

		public void addRemovedItem(@Nonnull SortIndex sortIndex) {
			this.sortIndexChanges.addRemovedItem(sortIndex);
		}

		public void addCreatedItem(@Nonnull ChainIndex chainIndex) {
			this.chainIndexChanges.addCreatedItem(chainIndex);
		}

		public void addRemovedItem(@Nonnull ChainIndex chainIndex) {
			this.chainIndexChanges.addRemovedItem(chainIndex);
		}

		public void clean(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.uniqueIndexChanges.clean(transactionalLayer);
			this.sharedValueIndexChanges.clean(transactionalLayer);
			this.sharedRangeIndexChanges.clean(transactionalLayer);
			this.sortIndexChanges.clean(transactionalLayer);
			this.chainIndexChanges.clean(transactionalLayer);
		}

		public void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.uniqueIndexChanges.cleanAll(transactionalLayer);
			this.sharedValueIndexChanges.cleanAll(transactionalLayer);
			this.sharedRangeIndexChanges.cleanAll(transactionalLayer);
			this.sortIndexChanges.cleanAll(transactionalLayer);
			this.chainIndexChanges.cleanAll(transactionalLayer);
		}

		@Nonnull
		@Override
		public AttributeIndexChangesMemento snapshot() {
			return new AttributeIndexChangesMemento(
				this.uniqueIndexChanges.snapshot(),
				this.sharedValueIndexChanges.snapshot(),
				this.sharedRangeIndexChanges.snapshot(),
				this.sortIndexChanges.snapshot(),
				this.chainIndexChanges.snapshot()
			);
		}

		@Override
		public void restore(@Nonnull AttributeIndexChangesMemento memento) {
			this.uniqueIndexChanges.restore(memento.uniqueIndexChanges());
			this.sharedValueIndexChanges.restore(memento.sharedValueIndexChanges());
			this.sharedRangeIndexChanges.restore(memento.sharedRangeIndexChanges());
			this.sortIndexChanges.restore(memento.sortIndexChanges());
			this.chainIndexChanges.restore(memento.chainIndexChanges());
		}

		/**
		 * Memento bundling the savepoint state of all five {@link TransactionalContainerChanges} containers tracked
		 * by an {@link AttributeIndexChanges}.
		 *
		 * @param uniqueIndexChanges      snapshot of the standalone unique-index created/removed bookkeeping
		 * @param sharedValueIndexChanges snapshot of the shared value-index (FILTER data) created/removed bookkeeping
		 * @param sharedRangeIndexChanges snapshot of the shared range-index created/removed bookkeeping
		 * @param sortIndexChanges        snapshot of the sort-index created/removed bookkeeping
		 * @param chainIndexChanges       snapshot of the chain-index created/removed bookkeeping
		 */
		public record AttributeIndexChangesMemento(
			@Nonnull ContainerChangesMemento<UniqueIndex> uniqueIndexChanges,
			@Nonnull ContainerChangesMemento<InvertedIndex> sharedValueIndexChanges,
			@Nonnull ContainerChangesMemento<RangeIndex> sharedRangeIndexChanges,
			@Nonnull ContainerChangesMemento<SortIndex> sortIndexChanges,
			@Nonnull ContainerChangesMemento<ChainIndex> chainIndexChanges
		) {
		}

	}

}
