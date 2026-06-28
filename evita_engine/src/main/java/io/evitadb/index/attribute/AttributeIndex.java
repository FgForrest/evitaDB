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
import io.evitadb.index.map.PersistentTransactionalProducerMap;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
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
import java.util.function.Function;
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
	 */
	@Nonnull private final PersistentTransactionalProducerMap<AttributeIndexKey, UniqueIndex> uniqueIndex;
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
	 * The cached views WRAP {@link InvertedIndex} instances, and a touched key receives a fresh tree instance at commit;
	 * a carried-forward view would wrap a stale tree. So the map's transactional role is in-tx ISOLATION + committed
	 * KEY-SET only — the VALUES are re-derived fresh over the committed shared trees in the from-maps constructor
	 * ({@link #buildFilterViews}). The map is maintained in lockstep with {@link #sharedValueIndex} (every key created
	 * by {@link #getOrCreateFilterView} and dropped by {@link #removeSharedIfEmpty} is mirrored here), so the read path
	 * resolves a committed key without ever mutating the baseline.
	 */
	@Nonnull private final TransactionalMap<AttributeIndexKey, FilterIndex> filterIndex;
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
	 */
	@Nonnull private final TransactionalMap<AttributeIndexKey, UniqueIndex> uniqueViewIndex;
	/**
	 * This transactional map (index) contains for each attribute single instance of {@link SortIndex}
	 * (respective single instance for each attribute-locale combination in case of language specific attribute).
	 */
	@Nonnull private final PersistentTransactionalProducerMap<AttributeIndexKey, SortIndex> sortIndex;
	/**
	 * This transactional map (index) contains for each attribute single instance of {@link ChainIndex}
	 * (respective single instance for each attribute-locale combination in case of language specific attribute).
	 */
	@Nonnull private final PersistentTransactionalProducerMap<AttributeIndexKey, ChainIndex> chainIndex;
	/**
	 * OWNED shared comparator-ordered value→ValueToRecord tree, one per single FILTERABLE attribute key (keyed by the
	 * filter {@link #createAttributeKey} shape). The {@link FilterIndex} is a non-producing view over this tree; a
	 * both-flagged {@link SortIndex} reads its cardinality from it. This map is a {@link TransactionalLayerProducer} for
	 * the FILTER data. The {@link UniqueIndex} is a separate standalone structure. See {@link #sharedRangeIndex} for the
	 * sibling range structure of range-typed attributes.
	 */
	@Nonnull private final PersistentTransactionalProducerMap<AttributeIndexKey, InvertedIndex> sharedValueIndex;
	/**
	 * OWNED sibling range structure of range-typed filterable attributes (keyed by the same filter
	 * {@link #createAttributeKey} shape as {@link #sharedValueIndex}). Only present for attributes whose plain type is
	 * assignable to {@link Range}. Kept beside {@link #sharedValueIndex} so the {@link FilterIndex}
	 * view stays stateless and the range structure commits independently.
	 */
	@Nonnull private final PersistentTransactionalProducerMap<AttributeIndexKey, RangeIndex> sharedRangeIndex;

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
	 * Creates an empty attribute index for a fresh entity / reference index. All sub-index maps start empty and are
	 * populated lazily as attributes are inserted.
	 *
	 * @param entityType   the entity type this index belongs to
	 * @param referenceKey the reference discriminator when this index backs a reference-scoped index, or `null` for
	 *                     the global entity index
	 */
	protected AttributeIndex(@Nonnull String entityType, @Nullable RepresentativeReferenceKey referenceKey) {
		this.entityType = entityType;
		this.referenceKey = referenceKey;
		this.uniqueIndex = new PersistentTransactionalProducerMap<>(
			CollectionUtils.createHashMap(32), UniqueIndex.class, Function.identity());
		this.sortIndex = new PersistentTransactionalProducerMap<>(
			CollectionUtils.createHashMap(32), SortIndex.class, Function.identity());
		this.chainIndex = new PersistentTransactionalProducerMap<>(
			CollectionUtils.createHashMap(32), ChainIndex.class, Function.identity());
		this.sharedValueIndex = new PersistentTransactionalProducerMap<>(
			CollectionUtils.createHashMap(32), InvertedIndex.class, Function.identity());
		this.sharedRangeIndex = new PersistentTransactionalProducerMap<>(
			CollectionUtils.createHashMap(32), RangeIndex.class, Function.identity());
		// derived view caches: transactional for MVCC isolation between concurrent readers and an in-flight writer — see
		// the field javadoc. FilterIndexView is a non-producer value (carried by reference); UniqueIndexView extends the
		// producer UniqueIndex (its commit methods are no-ops), so it needs the producer-valued ctor to avoid the
		// plain-value commit path's null-wrapper NPE. Both are re-derived fresh at commit regardless.
		this.filterIndex = new TransactionalMap<>(CollectionUtils.createHashMap(32));
		this.uniqueViewIndex = new TransactionalMap<>(
			CollectionUtils.createHashMap(32), UniqueIndex.class, Function.identity());
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
		// the shared trees must be built FIRST: the sort views below bind directly to THIS index's committed shared trees
		this.sharedValueIndex = new PersistentTransactionalProducerMap<>(
			sharedValueIndex, InvertedIndex.class, Function.identity());
		this.sharedRangeIndex = new PersistentTransactionalProducerMap<>(
			sharedRangeIndex, RangeIndex.class, Function.identity());
		this.uniqueIndex = new PersistentTransactionalProducerMap<>(
			uniqueIndex, UniqueIndex.class, Function.identity());
		this.chainIndex = new PersistentTransactionalProducerMap<>(chainIndex, ChainIndex.class, Function.identity());
		// derive the SORT views bound to the committed shared trees: each view-mode index is carried forward by reference
		// when its wrapped tree is identity-unchanged, or replaced by a fresh immutable copy (sharing the committed
		// sorted-records façade) when the tree was replaced. Owner-mode indexes carry forward unchanged. Never mutated in
		// place, so a carried-forward view is safe to share with the older snapshot (no isolation hazard).
		this.sortIndex = new PersistentTransactionalProducerMap<>(
			deriveSortViews(sharedValueIndex, sortIndex), SortIndex.class, Function.identity());
		// derive the FILTER views over the just-committed shared trees: carry each previous view forward by reference when
		// its wrapped tree(s) are identity-unchanged (the common case — the producer-map merge keeps untouched trees
		// identity-stable), and freshly wrap only the keys whose tree was created/replaced this commit. A view is never mutated in place, so
		// sharing a carried-forward view with the older snapshot is safe; the invariant "no committed view wraps a stale
		// tree" is preserved because a replaced tree always fails the identity check and is rewrapped.
		this.filterIndex = new TransactionalMap<>(buildFilterViews(sharedValueIndex, sharedRangeIndex, filterIndex));
		// rebuild the folded UNIQUE views fresh over the same committed shared trees (mirrors the FILTER views); the
		// source map's key set tells us which keys are foldable unique attributes.
		this.uniqueViewIndex = new TransactionalMap<>(
			buildUniqueViews(sharedValueIndex, uniqueViewIndex), UniqueIndex.class, Function.identity());
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
		final UniqueIndex theUniqueIndex = this.uniqueIndex.computeIfAbsent(
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
		this.uniqueIndex.markValueMutated(lookupKey);
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
		final UniqueIndex theUniqueIndex = this.uniqueIndex.get(lookupKey);
		notNull(theUniqueIndex, "Unique index for attribute `" + attributeSchema.getName() + "` not found!");
		// unregisterUniqueKey mutates the standalone unique index in place — declare it for the O(Δ) commit walk
		this.uniqueIndex.markValueMutated(lookupKey);
		theUniqueIndex.unregisterUniqueKey(value, recordId);

		if (theUniqueIndex.isEmpty()) {
			this.uniqueIndex.remove(lookupKey);
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
		final AttributeIndexKey lookupKey = createAttributeKey(
			referenceSchema, attributeSchema, allowedLocales, locale, value);
		final FilterIndex theFilterIndex = getOrCreateFilterView(lookupKey, attributeSchema);
		if (foldedUnique) {
			// this write owns the folded unique attribute: enforce uniqueness against the shared tree BEFORE the write,
			// then register the folded read-view bound to the live filter view (the tree now exists, so no rebind needed)
			enforceFoldedUniqueness(lookupKey, attributeSchema, theFilterIndex, value, recordId);
			registerFoldedUniqueView(lookupKey, attributeSchema, theFilterIndex);
		}
		theFilterIndex.addRecord(recordId, value);
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
		final AttributeIndexKey lookupKey = createAttributeKey(
			referenceSchema, attributeSchema, allowedLocales, locale, value);
		final FilterIndex theFilterIndex = resolveFilterViewForMutation(lookupKey, attributeSchema);
		theFilterIndex.removeRecord(recordId, value);
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
		final AttributeIndexKey lookupKey = createAttributeKey(
			referenceSchema, attributeSchema, allowedLocales, locale, value);
		final FilterIndex theFilterIndex = getOrCreateFilterView(lookupKey, attributeSchema);
		if (foldedUnique) {
			// this write owns the folded unique array: verify EVERY new element before any is added (atomic over the whole
			// array), then register the folded read-view bound to the live filter view
			enforceFoldedUniqueness(lookupKey, attributeSchema, theFilterIndex, value, recordId);
			registerFoldedUniqueView(lookupKey, attributeSchema, theFilterIndex);
		}
		theFilterIndex.addRecordDelta(recordId, value);
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
		final AttributeIndexKey lookupKey = createAttributeKey(
			referenceSchema, attributeSchema, allowedLocales, locale, value);
		final FilterIndex theFilterIndex = resolveFilterViewForMutation(lookupKey, attributeSchema);
		theFilterIndex.removeRecordDelta(recordId, value);
		removeSharedIfEmpty(lookupKey, theFilterIndex);
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
			final SortIndex theSortIndex = this.sortIndex.computeIfAbsent(
				attributeKey,
				lookupKey -> {
					// pass a parent-bound supplier: when a shared tree already exists for this key (both-flagged
					// attribute) the factory returns a SortIndexView that drops its own sortedValues; otherwise
					// (sort-only) the supplier resolves null and it returns an OwnerSortIndex. Never capture an instance.
					final SortIndex newSortIndex = SortIndex.create(
						attributeSchema.getPlainType(), this.referenceKey, lookupKey,
						attributeSchema.getIndexedDecimalPlaces(),
						() -> this.sharedValueIndex.get(lookupKey)
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
			this.sortIndex.markValueMutated(attributeKey);
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
			final ChainIndex theChainIndex = this.chainIndex.get(lookupKey);
			notNull(theChainIndex, "Chain index for attribute `" + attributeSchema.getName() + "` not found!");
			// removePredecessor mutates the chain in place — declare it for the O(Δ) commit walk (a subsequent map-remove
			// of an emptied chain is tracked separately as a removal)
			this.chainIndex.markValueMutated(lookupKey);
			theChainIndex.removePredecessor(recordId);

			if (theChainIndex.isEmpty()) {
				this.chainIndex.remove(lookupKey);
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addRemovedItem(theChainIndex));
			}
		} else {
			final SortIndex theSortIndex = this.sortIndex.get(lookupKey);
			notNull(theSortIndex, "Sort index for attribute `" + attributeSchema.getName() + "` not found!");
			// the sort index froze its BigDecimal scale at creation; refuse to derive a remove probe at a drifted schema
			// scale (which would miss the stored key) rather than silently leave the value behind (no-op for non-BigDecimal)
			FilterIndex.assertIndexedDecimalPlacesUnchanged(
				theSortIndex.getIndexedDecimalPlaces(),
				attributeSchema.getIndexedDecimalPlaces(),
				attributeSchema.getName()
			);
			// removeRecord mutates the sort index in place — declare it for the O(Δ) commit walk
			this.sortIndex.markValueMutated(lookupKey);
			theSortIndex.removeRecord(value, recordId);

			if (theSortIndex.isEmpty()) {
				this.sortIndex.remove(lookupKey);
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
		final SortIndex theSortIndex = this.sortIndex.computeIfAbsent(
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
		this.sortIndex.markValueMutated(attributeKey);
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
		final SortIndex theSortIndex = this.sortIndex.get(lookupKey);
		notNull(
			theSortIndex, "Sort index for sortable attribute compound `" + compoundSchema.getName() + "` not found!");
		// removeRecord mutates the sort index in place — declare it for the O(Δ) commit walk
		this.sortIndex.markValueMutated(lookupKey);
		theSortIndex.removeRecord(value, recordId);

		if (theSortIndex.isEmpty()) {
			this.sortIndex.remove(lookupKey);
			ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
				.ifPresent(it -> it.addRemovedItem(theSortIndex));
		}
	}

	@Override
	@Nonnull
	public Set<AttributeIndexKey> getUniqueIndexes() {
		// union of the standalone (owner) and folded (view) unique keys
		if (this.uniqueViewIndex.isEmpty()) {
			return this.uniqueIndex.keySet();
		}
		final Set<AttributeIndexKey> keys = CollectionUtils.createHashSet(
			this.uniqueIndex.size() + this.uniqueViewIndex.size()
		);
		keys.addAll(this.uniqueIndex.keySet());
		// only folded-unique views whose shared tree still exists are advertised — a stale view key (whose tree emptied
		// and was dropped) has no slim part to write, so it must be gated here exactly as in collectKeys() and the
		// UniqueIndexView.appendStorageParts guard, or the manifest would diverge from the live sub-index walk
		for (final AttributeIndexKey key : this.uniqueViewIndex.keySet()) {
			if (this.sharedValueIndex.containsKey(key)) {
				keys.add(key);
			}
		}
		return keys;
	}

	@Override
	@Nonnull
	public Set<AttributeIndexKey> getFilterIndexes() {
		// transactional truth = the shared value index key set
		return this.sharedValueIndex.keySet();
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
		return this.sortIndex.keySet();
	}

	@Override
	@Nullable
	public SortIndex getSortIndex(@Nonnull AttributeIndexKey lookupKey) {
		return this.sortIndex.get(lookupKey);
	}

	@Override
	@Nullable
	public SortIndex getSortIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale
	) {
		return this.sortIndex.get(createAttributeKey(referenceSchema, attributeSchema, locale));
	}

	@Nullable
	@Override
	public SortIndex getSortIndex(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nullable Locale locale
	) {
		return this.sortIndex.get(createAttributeKey(entitySchema, referenceSchema, compoundSchema, locale));
	}

	@Nonnull
	@Override
	public Set<AttributeIndexKey> getChainIndexes() {
		return this.chainIndex.keySet();
	}

	@Nullable
	@Override
	public ChainIndex getChainIndex(@Nonnull AttributeIndexKey lookupKey) {
		return this.chainIndex.get(lookupKey);
	}

	@Nullable
	@Override
	public ChainIndex getChainIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale
	) {
		return this.chainIndex.get(createAttributeKey(referenceSchema, attributeSchema, locale));
	}

	@Nullable
	@Override
	public ChainIndex getChainIndex(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nullable Locale locale
	) {
		return this.chainIndex.get(createAttributeKey(entitySchema, referenceSchema, compoundSchema, locale));
	}

	@Override
	public boolean isAttributeIndexEmpty() {
		// shared value index = canonical (transactional) owner of FILTER data; uniqueIndex is a standalone owner
		return this.uniqueIndex.isEmpty() && this.sharedValueIndex.isEmpty() &&
			this.sortIndex.isEmpty() && this.chainIndex.isEmpty();
	}

	@Override
	public void getModifiedStorageParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges trappedChanges) {
		// UNIQUE parts: standalone (owner) indexes emit a SINGLE inline root or granular PAGED leaf pages + a PAGED root,
		// folded (view) indexes emit a slim part — both go through appendStorageParts.
		for (Entry<AttributeIndexKey, UniqueIndex> entry : this.uniqueIndex.entrySet()) {
			entry.getValue().appendStorageParts(entityIndexPrimaryKey, trappedChanges);
		}
		for (Entry<AttributeIndexKey, UniqueIndex> entry : this.uniqueViewIndex.entrySet()) {
			entry.getValue().appendStorageParts(entityIndexPrimaryKey, trappedChanges);
		}
		// FILTER parts are produced from the shared tree via the rebuilt filter views (which carry attributeType + range).
		// A small (single-leaf) index emits one inline SINGLE part; a large (multi-leaf) index emits granular PAGED leaf
		// pages + a PAGED root — both go through appendStorageParts.
		for (final AttributeIndexKey key : this.sharedValueIndex.keySet()) {
			final FilterIndex view = resolveFilterView(key);
			if (view != null) {
				view.appendStorageParts(entityIndexPrimaryKey, trappedChanges);
			}
		}
		for (Entry<AttributeIndexKey, SortIndex> entry : this.sortIndex.entrySet()) {
			ofNullable(entry.getValue().createStoragePart(entityIndexPrimaryKey))
				.ifPresent(trappedChanges::addChangeToStore);
		}
		for (Entry<AttributeIndexKey, ChainIndex> entry : this.chainIndex.entrySet()) {
			ofNullable(entry.getValue().createStoragePart(entityIndexPrimaryKey))
				.ifPresent(trappedChanges::addChangeToStore);
		}
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
		final UniqueIndex owner = this.uniqueIndex.get(lookupKey);
		return owner != null ? owner : this.uniqueViewIndex.get(lookupKey);
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
		for (final AttributeIndexKey key : this.uniqueIndex.keySet()) {
			target.add(new AttributeIndexStorageKey(indexKey, AttributeIndexType.UNIQUE, key));
		}
		for (final AttributeIndexKey key : this.uniqueViewIndex.keySet()) {
			if (this.sharedValueIndex.containsKey(key)) {
				target.add(new AttributeIndexStorageKey(indexKey, AttributeIndexType.UNIQUE, key));
			}
		}
		// FILTER keys: transactional truth = the shared value index key set
		for (final AttributeIndexKey key : this.sharedValueIndex.keySet()) {
			target.add(new AttributeIndexStorageKey(indexKey, AttributeIndexType.FILTER, key));
		}
		for (final AttributeIndexKey key : this.sortIndex.keySet()) {
			target.add(new AttributeIndexStorageKey(indexKey, AttributeIndexType.SORT, key));
		}
		for (final AttributeIndexKey key : this.chainIndex.keySet()) {
			target.add(new AttributeIndexStorageKey(indexKey, AttributeIndexType.CHAIN, key));
		}
	}

	@Override
	public void resetDirty() {
		for (UniqueIndex theUniqueIndex : this.uniqueIndex.values()) {
			theUniqueIndex.resetDirty();
		}
		// reset the shared trees' dirty flags through the resolved views (transactional truth)
		for (final AttributeIndexKey key : this.sharedValueIndex.keySet()) {
			final FilterIndex view = resolveFilterView(key);
			if (view != null) {
				view.resetDirty();
			}
		}
		for (SortIndex theSortIndex : this.sortIndex.values()) {
			theSortIndex.resetDirty();
		}
		for (ChainIndex theChainIndex : this.chainIndex.values()) {
			theChainIndex.resetDirty();
		}
	}

	@Nullable
	@Override
	public AttributeIndexChanges createLayer() {
		return isTransactionAvailable() ? new AttributeIndexChanges() : null;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.uniqueIndex.removeLayer(transactionalLayer);
		this.sortIndex.removeLayer(transactionalLayer);
		this.chainIndex.removeLayer(transactionalLayer);
		this.sharedValueIndex.removeLayer(transactionalLayer);
		this.sharedRangeIndex.removeLayer(transactionalLayer);
		// the derived view caches are transactional (MVCC isolation) - discharge their diff layers too
		this.filterIndex.removeLayer(transactionalLayer);
		this.uniqueViewIndex.removeLayer(transactionalLayer);
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
			transactionalLayer.getStateCopyWithCommittedChanges(this.uniqueIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.filterIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.uniqueViewIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sortIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.chainIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sharedValueIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sharedRangeIndex)
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
		final InvertedIndex shared = this.sharedValueIndex.get(lookupKey);
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
		final FilterIndex cached = this.filterIndex.get(lookupKey);
		if (cached != null && cached.getInvertedIndex() == shared) {
			return cached;
		}
		final FilterIndex rebuilt = new FilterIndexView(
			lookupKey, shared, this.sharedRangeIndex.get(lookupKey), attributeType, indexedDecimalPlaces
		);
		this.filterIndex.put(lookupKey, rebuilt);
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
		final InvertedIndex existingShared = this.sharedValueIndex.get(lookupKey);
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
		this.sharedValueIndex.put(lookupKey, shared);
		ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
			.ifPresent(it -> it.addCreatedItem(shared));
		final RangeIndex sharedRange;
		if (Range.class.isAssignableFrom(plainType)) {
			sharedRange = new RangeIndex();
			this.sharedRangeIndex.put(lookupKey, sharedRange);
			ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
				.ifPresent(it -> it.addCreatedItem(sharedRange));
		} else {
			sharedRange = null;
		}
		final FilterIndex view = new FilterIndexView(
			lookupKey, shared, sharedRange, attributeType, indexedDecimalPlaces
		);
		this.filterIndex.put(lookupKey, view);
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
		this.sharedValueIndex.markValueMutated(lookupKey);
		final Class<?> attributeType = attributeSchema.getType();
		final Class<?> plainType = attributeType.isArray() ? attributeType.getComponentType() : attributeType;
		if (Range.class.isAssignableFrom(plainType)) {
			this.sharedRangeIndex.markValueMutated(lookupKey);
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
		this.uniqueViewIndex.computeIfAbsent(
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
			this.filterIndex.remove(lookupKey);
			this.uniqueViewIndex.remove(lookupKey);
			final InvertedIndex shared = this.sharedValueIndex.remove(lookupKey);
			final RangeIndex sharedRange = this.sharedRangeIndex.remove(lookupKey);
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
		final InvertedIndex shared = this.sharedValueIndex.get(key);
		if (shared == null) {
			return null;
		}
		final FilterIndex cached = this.filterIndex.get(key);
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
			key, shared, this.sharedRangeIndex.get(key), cached.getAttributeType(),
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
		this.chainIndex.markValueMutated(attributeIndexKey);
		return this.chainIndex.computeIfAbsent(
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
		private final TransactionalContainerChanges<Void, UniqueIndex, UniqueIndex> uniqueIndexChanges = new TransactionalContainerChanges<>();
		private final TransactionalContainerChanges<Void, InvertedIndex, InvertedIndex> sharedValueIndexChanges = new TransactionalContainerChanges<>();
		private final TransactionalContainerChanges<Void, RangeIndex, RangeIndex> sharedRangeIndexChanges = new TransactionalContainerChanges<>();
		private final TransactionalContainerChanges<SortIndexChanges, SortIndex, SortIndex> sortIndexChanges = new TransactionalContainerChanges<>();
		private final TransactionalContainerChanges<ChainIndexChanges, ChainIndex, ChainIndex> chainIndexChanges = new TransactionalContainerChanges<>();

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
