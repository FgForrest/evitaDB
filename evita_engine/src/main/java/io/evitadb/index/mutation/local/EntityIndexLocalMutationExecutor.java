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

package io.evitadb.index.mutation.local;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutationExecutor;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.ParentMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.PriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ComparableReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.RepresentativeAttributeDefinition;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.ElementKind;
import io.evitadb.core.catalog.CatalogExpressionTriggerRegistry;
import io.evitadb.core.expression.trigger.DependencyType;
import io.evitadb.core.expression.trigger.ExpressionIndexTrigger;
import io.evitadb.core.expression.trigger.FacetExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramExpressionTrigger;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.map.LazyHashMap;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.TriConsumer;
import io.evitadb.index.*;
import io.evitadb.index.mutation.ConsistencyCheckingLocalMutationExecutor;
import io.evitadb.index.mutation.EntityIndexMutation;
import io.evitadb.index.mutation.IndexImplicitMutations;
import io.evitadb.index.mutation.IndexMutation;
import io.evitadb.index.mutation.ReevaluateExpressionMutation;
import io.evitadb.index.mutation.local.handler.LocalMutationHandler;
import io.evitadb.index.mutation.local.handler.LocalMutationHandlerRegistry;
import io.evitadb.index.mutation.local.dataAccess.EntityExistingDataFactory;
import io.evitadb.index.mutation.local.dataAccess.EntityIndexedReferenceSupplier;
import io.evitadb.index.mutation.local.dataAccess.EntityPriceSupplier;
import io.evitadb.index.mutation.local.dataAccess.EntityStoragePartExistingDataFactory;
import io.evitadb.index.mutation.local.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.index.mutation.local.dataAccess.ExistingDataSupplierFactory;
import io.evitadb.index.mutation.local.dataAccess.ReferenceSupplier;
import io.evitadb.index.price.PriceSuperIndex;
import io.evitadb.index.usage.SchemaCapabilityKey;
import io.evitadb.index.usage.SchemaCapabilityUsage;
import io.evitadb.index.IndexActivity;
import io.evitadb.index.usage.SchemaCapabilityUsageRegistry;
import io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor.LocaleScope;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor.LocaleWithScope;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.PricesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.spi.store.catalog.shared.model.PriceWithInternalIds;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.Functions;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntBiFunction;

import static io.evitadb.index.mutation.local.HierarchyPlacementMutator.removeParent;
import static io.evitadb.index.mutation.local.HierarchyPlacementMutator.setParent;
import static io.evitadb.utils.Assert.isPremiseValid;
import static java.util.Optional.of;

/**
 * This class applies changes in {@link LocalMutation} to one or multiple {@link EntityIndex} so that changes
 * are reflected
 * in next filtering / sorting query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class EntityIndexLocalMutationExecutor implements LocalMutationExecutor {
	/**
	 * Shared empty instance returned when no cross-entity triggers fire.
	 */
	private static final IndexImplicitMutations EMPTY_INDEX_IMPLICIT_MUTATIONS =
		new IndexImplicitMutations(new EntityIndexMutation[0]);
	/**
	 * Cross-entity dependency types for entity-level attribute changes. When an entity-level attribute
	 * mutates, the registry is consulted for these dependency types.
	 */
	private static final DependencyType[] CROSS_ENTITY_ATTRIBUTE_DEPENDENCY_TYPES = {
		DependencyType.REFERENCED_ENTITY_ATTRIBUTE,
		DependencyType.GROUP_ENTITY_ATTRIBUTE,
		DependencyType.PARENT_ENTITY_ATTRIBUTE
	};
	/**
	 * Cross-entity dependency types for reference-level attribute changes. When a reference attribute
	 * on the mutated entity changes, the registry is consulted for these dependency types.
	 */
	private static final DependencyType[] CROSS_ENTITY_REFERENCE_ATTRIBUTE_DEPENDENCY_TYPES = {
		DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE,
		DependencyType.GROUP_ENTITY_REFERENCE_ATTRIBUTE,
		DependencyType.PARENT_ENTITY_REFERENCE_ATTRIBUTE
	};
	/**
	 * How many {@link Scope} constants there are, read once so {@link #accessedCatalogIndexes} can be slotted by
	 * ordinal without asking the enum for its (defensively copied) value array on every entity mutation.
	 */
	private static final int SCOPE_COUNT = Scope.values().length;
	/**
	 * The {@link EntitySchemaContract#getName()} of the entity type.
	 */
	private final String entityType;
	/**
	 * The {@link WritableEntityStorageContainerAccessor} that allows to access to current and previous containers
	 * with the data of the entity.
	 */
	@Getter private final WritableEntityStorageContainerAccessor containerAccessor;
	/**
	 * List of entity primary keys that should be indexed by certain {@link IndexType}. We need sometimes to index
	 * different primary keys depending on the index type.
	 */
	private final LinkedList<ToIntBiFunction<IndexType, Target>> entityPrimaryKey = new LinkedList<>();
	/**
	 * The accessor that allows to create or remove {@link CatalogIndex} instances.
	 */
	private final IndexMaintainer<CatalogIndexKey, CatalogIndex> catalogIndexCreatingAccessor;
	/**
	 * The accessor that allows to create or remove {@link EntityIndex} instances.
	 */
	private final IndexMaintainer<EntityIndexKey, EntityIndex> entityIndexCreatingAccessor;
	/**
	 * The accessor that allows to retrieve current entity schema.
	 */
	private final Supplier<EntitySchema> schemaAccessor;
	/**
	 * Resolves an entity type name to its compact primary key and back. Supplied by the current catalog snapshot at
	 * construction and threaded into the global unique index (via {@link CatalogIndex#insertUniqueAttribute} /
	 * {@link CatalogIndex#removeUniqueAttribute}), which stores the compact type primary key inside its packed tuples
	 * and no longer holds a catalog back-reference of its own.
	 */
	@Nonnull private final EntityTypeClassifierResolver entityTypeClassifierResolver;
	/**
	 * The sequence service that assigns new price internal ids.
	 */
	@Nonnull private final IntSupplier priceInternalIdSupplier;
	/**
	 * Supplier that allows to retrieve full entity body. It's used only when entity changes its scope.
	 */
	private final Supplier<Entity> fullEntitySupplier;
	/**
	 * Supplier that provides the catalog-level trigger registry for cross-entity expression triggers.
	 * When non-null, enables source-side detection of attribute changes that affect facet indexing in
	 * other entity collections. Null in test scenarios or when the feature is disabled — in that case
	 * {@link #popIndexImplicitMutations} returns an empty result.
	 */
	@Nullable private final Supplier<CatalogExpressionTriggerRegistry> triggerRegistrySupplier;
	/**
	 * Supplier that provides local {@link FacetExpressionTrigger} instances for inline expression evaluation
	 * within `ReferenceIndexMutator`. When called with (referenceName, scope), returns the pre-built trigger
	 * for that reference/scope pair, or `null` if no expression is defined. Null supplier means no local
	 * expression evaluation is performed (backward-compatible default).
	 */
	@Nullable private final BiFunction<String, Scope, FacetExpressionTrigger> localFacetTriggerSupplier;
	/**
	 * Optional function resolving entity type names to their schemas across the entire catalog. When non-null,
	 * enables cross-entity expression evaluation where the expression accesses referenced/group entity attributes
	 * via `$reference.referencedEntity.attributes[...]` or `$reference.groupEntity?.attributes[...]`. When null,
	 * the schema resolver falls back to always returning this executor's own entity schema (the pre-existing
	 * behavior that only works for local expressions).
	 */
	@Nullable private final Function<String, EntitySchemaContract> crossEntitySchemaResolver;
	/**
	 * The usage counters of the collection this executor writes into — the place a *"nobody has filtered by this
	 * attribute in a month, yet every upsert maintains its index"* reading is accumulated. Held by reference: the
	 * registry outlives every catalog version, while this executor lives for exactly one entity mutation.
	 */
	@Nonnull private final SchemaCapabilityUsageRegistry usageRegistry;
	/**
	 * The usage counters of the **catalog** this executor writes into, which is where a globally-unique attribute's
	 * numbers live: the index a write of one maintains is the catalog's {@link CatalogIndex}, and the query that reads
	 * it may name no collection at all, so neither side of that attribute's reading belongs to any single collection.
	 * Held by reference for the same reason the collection's registry is - it outlives every catalog version.
	 */
	@Nonnull private final SchemaCapabilityUsageRegistry catalogUsageRegistry;
	/**
	 * Whether this mutation counts the index-maintenance effort it costs - the server-wide
	 * `server.usageStatisticsTracking` switch, resolved once when the executor is built.
	 *
	 * With it off nothing here resolves a capability holder and nothing stamps an index, which removes a map
	 * lookup per touched element and a CAS per touched index from every entity mutation.
	 */
	private final boolean usageStatisticsTracking;
	/**
	 * Set contains keys of indexes that were accessed in this particular entity upsert / removal.
	 */
	private final Set<EntityIndexKey> accessedIndexes = CollectionUtils.createHashSet(32);
	/**
	 * Catalog indexes this entity mutation acquired for modification, slotted by {@link Scope#ordinal()} so the same
	 * index acquired repeatedly (once per globally-unique attribute written) is counted **once** in
	 * {@link #applyChanges()} — the same per-entity-mutation dedup discipline {@link #accessedIndexes} gives the
	 * entity indexes.
	 *
	 * Allocated lazily, because most entity mutations touch no globally-unique attribute at all and would otherwise
	 * pay for an array they never fill.
	 */
	@Nullable private CatalogIndex[] accessedCatalogIndexes;
	/**
	 * The schema elements this entity mutation has already reported as touched, which is what keeps one mutation from
	 * counting an attribute once per index that maintains it. An entity attribute write reaches the global index and
	 * every reduced index carrying the attribute; a reference attribute write reaches the referenced-type index and
	 * the reduced index — all of them report, and only the first of each (element, scope) gets past this list.
	 *
	 * A linear scan rather than a hash set, deliberately: the list holds the elements **one entity mutation** writes,
	 * so it is a handful of entries, and scanning it compares four fields where probing a set would first have to
	 * allocate a key on every one of those fan-out events. Allocated lazily and only ever once per element.
	 */
	@Nullable private List<TouchedSchemaElement> touchedElements;
	/**
	 * The holders {@link #applyChanges()} will increment — resolved once, when their element is first reported, so the
	 * flush is a walk with no lookups and the fan-out events after the first cost a list scan and nothing else.
	 */
	@Nullable private List<SchemaCapabilityUsage> touchedCapabilities;
	/**
	 * Contains index of calculated {@link RepresentativeReferenceKey} that include representative attribute values
	 * for each reference that allows duplicates. This prevents from recalculating these values multiple times
	 * during a single entity upsert (calculation is quite expensive). Map contains two key variants in case any
	 * of the representative attributes changes during the upsert.
	 */
	private final Map<ComparableReferenceKey, RepresentativeReferenceKeys>
		memoizedRepresentativeAttributes = new LazyHashMap<>(8);
	/**
	 * Memoized factory that allows to retrieve existing attribute values from the current storage part.
	 */
	private EntityStoragePartExistingDataFactory storagePartExistingDataFactory;
	/**
	 * Set of keys of indexes that were created in this particular entity upsert.
	 */
	private Set<RepresentativeReferenceKey> createdReferences;
	/**
	 * List of all mutations that are being processed right now.
	 */
	@Nullable private List<? extends LocalMutation<?, ?>> localMutations;
	/**
	 * Deferred expression re-evaluation actions collected during index mutation processing. These actions
	 * are executed AFTER the corresponding storage write completes, ensuring that the expression evaluation
	 * reads the updated attribute values from storage. Without deferral, the expression would read stale
	 * (pre-mutation) values because the index updater runs before the storage writer for each mutation.
	 * Contains both facet and histogram re-evaluation lambdas (flushed together).
	 */
	@Nullable private List<Runnable> deferredExpressionReEvaluations;
	/**
	 * Pre-mutation entity attribute values captured during the container implicit-mutation phase (before index
	 * updates) for use in cross-entity histogram trigger mutations. Keyed by attribute name → locale → raw value. Uses
	 * `putIfAbsent` to capture only the true pre-mutation value when the same attribute is mutated
	 * multiple times in one batch. Populated lazily in {@link #applyAttributeMutation}.
	 */
	@Nullable private Map<String, Map<Locale, Serializable>> capturedOldEntityAttributeValues;
	/**
	 * References whose histogram add was already deferred during reference insertion. When a reference
	 * attribute mutation follows an insert in the same batch, the histogram re-evaluation must skip
	 * its own `addHistogramToIndex` call to avoid double-inserting the value (corrupting cardinality).
	 */
	@Nonnull private Set<ReferenceKey> referencesWithDeferredHistogramAdd = Collections.emptySet();
	/**
	 * Memoized scope of the current entity.
	 */
	private Scope memoizedScope;

	/**
	 * Converts a map of mutations-per-target-type into an {@link IndexImplicitMutations} result.
	 *
	 * @param mutationsByTargetType map of target entity type to list of index mutations, or null if empty
	 * @return the envelope result, or {@link #EMPTY_INDEX_IMPLICIT_MUTATIONS} if null/empty
	 */
	@Nonnull
	private static IndexImplicitMutations groupByTargetEntityType(
		@Nullable Map<String, List<IndexMutation>> mutationsByTargetType
	) {
		if (mutationsByTargetType == null || mutationsByTargetType.isEmpty()) {
			return EMPTY_INDEX_IMPLICIT_MUTATIONS;
		}
		final EntityIndexMutation[] envelopes = new EntityIndexMutation[mutationsByTargetType.size()];
		int i = 0;
		for (final Map.Entry<String, List<IndexMutation>> entry : mutationsByTargetType.entrySet()) {
			envelopes[i++] = new EntityIndexMutation(
				entry.getKey(),
				entry.getValue().toArray(IndexMutation[]::new)
			);
		}
		return new IndexImplicitMutations(envelopes);
	}

	/**
	 * Extracts the primary key of the active (non-dropped) group from the given reference,
	 * or returns null if no active group is assigned.
	 *
	 * @param reference the reference contract to extract the group from
	 * @return the group primary key, or null if no active group exists
	 */
	@Nullable
	private static Integer extractActiveGroupPrimaryKey(@Nonnull ReferenceContract reference) {
		return reference.getGroup()
			.filter(Droppable::exists)
			.map(GroupEntityReference::getPrimaryKey)
			.orElse(null);
	}

	/**
	 * Attempts to index an entity by inserting its primary key into the global index if it is missing.
	 *
	 * @param entityPrimaryKey the primary key of the entity to be indexed
	 * @param globalIndex      an instance of {@code GlobalEntityIndex} used to check and insert the primary key
	 * @return {@code true} if the primary key was successfully inserted;
	 * {@code false} if the primary key was already present
	 */
	private static boolean indexEntity(
		int entityPrimaryKey,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		return globalIndex.insertPrimaryKeyIfMissing(entityPrimaryKey);
	}

	public EntityIndexLocalMutationExecutor(
		@Nonnull WritableEntityStorageContainerAccessor containerAccessor,
		int entityPrimaryKey,
		@Nonnull IndexMaintainer<EntityIndexKey, EntityIndex> entityIndexCreatingAccessor,
		@Nonnull IndexMaintainer<CatalogIndexKey, CatalogIndex> catalogIndexCreatingAccessor,
		@Nonnull Supplier<EntitySchema> schemaAccessor,
		@Nonnull IntSupplier priceInternalIdSupplier,
		@Nonnull Supplier<Entity> fullEntitySupplier,
		@Nullable Supplier<CatalogExpressionTriggerRegistry> triggerRegistrySupplier,
		@Nullable BiFunction<String, Scope, FacetExpressionTrigger> localFacetTriggerSupplier,
		@Nullable Function<String, EntitySchemaContract> crossEntitySchemaResolver,
		@Nonnull EntityTypeClassifierResolver entityTypeClassifierResolver,
		@Nonnull SchemaCapabilityUsageRegistry usageRegistry,
		@Nonnull SchemaCapabilityUsageRegistry catalogUsageRegistry,
		boolean usageStatisticsTracking
	) {
		this.containerAccessor = containerAccessor;
		this.entityPrimaryKey.add((anyType, anyPurpose) -> entityPrimaryKey);
		this.entityIndexCreatingAccessor = entityIndexCreatingAccessor;
		this.catalogIndexCreatingAccessor = catalogIndexCreatingAccessor;
		this.schemaAccessor = schemaAccessor;
		this.priceInternalIdSupplier = priceInternalIdSupplier;
		this.entityType = schemaAccessor.get().getName();
		this.fullEntitySupplier = fullEntitySupplier;
		this.triggerRegistrySupplier = triggerRegistrySupplier;
		this.localFacetTriggerSupplier = localFacetTriggerSupplier;
		this.crossEntitySchemaResolver = crossEntitySchemaResolver;
		this.entityTypeClassifierResolver = entityTypeClassifierResolver;
		this.usageRegistry = usageRegistry;
		this.catalogUsageRegistry = catalogUsageRegistry;
		this.usageStatisticsTracking = usageStatisticsTracking;
	}

	/**
	 * Returns the scope of the current entity. If the scope has already been retrieved and memoized, it returns the
	 * memoized value. Otherwise, it fetches the scope from the main entity storage part, memoizes it, and returns it.
	 *
	 * During a `SetEntityScopeMutation` the memoized value is intentionally flipped to the target scope mid-transition
	 * (after removal from the source scope, before insertion into the target) so scope-sensitive gates consulted while
	 * re-indexing observe the target scope.
	 *
	 * @return The scope of the current entity.
	 */
	@Nonnull
	public Scope getScope() {
		if (this.memoizedScope == null) {
			this.memoizedScope = this.containerAccessor.getEntityStoragePart(
					this.entityType,
					getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.EXISTING),
					EntityExistence.MUST_EXIST
				)
				.getScope();
		}
		return this.memoizedScope;
	}

	/**
	 * Re-points the memoized scope of the active entity. Must be called during a scope transition after the entity has
	 * been removed from the old scope's indexes and before it is re-added to the new scope's indexes, so that
	 * scope-sensitive gates consulted while re-indexing into the target scope observe the target scope. Calling this at
	 * the wrong moment leaves the executor pointing at the wrong indexes for the remainder of the batch.
	 *
	 * @param scope the new scope of the active entity
	 */
	public void setMemoizedScope(@Nonnull Scope scope) {
		this.memoizedScope = scope;
	}

	/**
	 * Returns the full entity body for the active mutation batch, resolved via the supplier
	 * installed at construction. Required by scope-change processing, which needs the full entity
	 * state to capture pre-mutation attribute values and to drive the `removeEntityFromIndexes` /
	 * `addEntityToIndexes` calls.
	 *
	 * @return the full entity body for the active mutation batch
	 */
	@Nonnull
	public Entity getFullEntity() {
		return this.fullEntitySupplier.get();
	}

	/**
	 * Returns the entity type name (`EntitySchemaContract#getName`). Stable across the lifetime
	 * of this executor — captured at construction from the schema accessor.
	 *
	 * @return the entity type name
	 */
	@Nonnull
	public String getEntityType() {
		return this.entityType;
	}

	/**
	 * Fan-out helper used by handlers whose work is intrinsically per-reference (facets, sortable
	 * compounds — keyed by individual reference key). Thin wrapper around
	 * `ReferenceIndexMutator.forEachReferenceIndex` so the handler call site spells out the
	 * `IterationPath` exactly once and never reaches into the static method directly.
	 *
	 * @param indexType                 minimum reference-index level required for a reference to qualify
	 * @param consumer                  callback invoked for each qualifying reference
	 * @param referencePredicate        additional predicate applied after the schema-level filter
	 * @param referencePresenceExpected whether the referenced/group primary key is expected to be
	 *                                  present in the index when resolving the representative key
	 * @param path                      which path(s) to traverse
	 */
	public void fanOutPerReference(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull ReferenceIndexConsumer consumer,
		@Nonnull Predicate<ReferenceContract> referencePredicate,
		boolean referencePresenceExpected,
		@Nonnull ReferenceIndexMutator.IterationPath path
	) {
		ReferenceIndexMutator.forEachReferenceIndex(
			indexType, this, consumer, referencePredicate, referencePresenceExpected, path
		);
	}

	/**
	 * Fan-out helper used by handlers whose work is entity-scoped relative to the (possibly
	 * shared) reduced index: entity-level attribute cardinality, set-semantic price leaves,
	 * locale tracking. Thin wrapper around `ReferenceIndexMutator.forEachUniqueReferenceIndex`
	 * with explicit `IterationPath` at the call site.
	 *
	 * @param indexType                 minimum reference-index level required for a reference to qualify
	 * @param consumer                  callback invoked at most once per unique target index
	 * @param referencePredicate        additional predicate applied after the schema-level filter
	 * @param referencePresenceExpected whether the referenced/group primary key is expected to be
	 *                                  present in the index when resolving the representative key
	 * @param path                      which path(s) to traverse
	 */
	public void fanOutUniquePerIndex(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull ReferenceIndexConsumer consumer,
		@Nonnull Predicate<ReferenceContract> referencePredicate,
		boolean referencePresenceExpected,
		@Nonnull ReferenceIndexMutator.IterationPath path
	) {
		ReferenceIndexMutator.forEachUniqueReferenceIndex(
			indexType, this, consumer, referencePredicate, referencePresenceExpected, path
		);
	}

	/**
	 * Returns `true` if facet expression triggers are installed, meaning that mutations should
	 * re-evaluate facet indexing expressions. The supplier is set at construction time and its
	 * presence signals that the catalog uses conditional facet indexing. When this method returns
	 * `false`, all facet re-evaluation calls can be skipped because no expressions exist
	 * to evaluate.
	 *
	 * @return `true` if facet expression re-evaluation is enabled
	 */
	public boolean hasFacetExpressionTriggers() {
		return this.localFacetTriggerSupplier != null;
	}

	/**
	 * Returns the catalog expression trigger registry if available, or `null` if the catalog does
	 * not use conditional bucket/histogram indexing. Encapsulates both the supplier presence check
	 * and the supplier invocation, so that call sites need only a single null check. When this
	 * method returns `null`, all histogram re-evaluation and cross-entity trigger checks can be
	 * skipped because no registry exists to query.
	 *
	 * @return the registry, or `null` if expression trigger processing is disabled
	 */
	@Nullable
	public CatalogExpressionTriggerRegistry getCatalogExpressionTriggerRegistry() {
		return this.triggerRegistrySupplier != null ? this.triggerRegistrySupplier.get() : null;
	}

	/**
	 * Defers an expression re-evaluation action to be executed after the corresponding storage write
	 * completes. This ensures that expression evaluation reads updated attribute/associated data values
	 * from storage, not stale pre-mutation values. Call {@link #finishLocalMutationExecutionPhase()} after
	 * the storage writer has processed the mutation to execute all deferred actions. Used for both facet
	 * and histogram expression re-evaluations.
	 *
	 * @param action the re-evaluation action to defer
	 */
	public void deferExpressionReEvaluation(@Nonnull Runnable action) {
		if (this.deferredExpressionReEvaluations == null) {
			this.deferredExpressionReEvaluations = new ArrayList<>(4);
		}
		this.deferredExpressionReEvaluations.add(action);
	}

	/**
	 * Returns the local {@link FacetExpressionTrigger} for the given reference name and scope, or `null` if no
	 * expression is defined for that combination. Delegates directly to the underlying supplier — the lookup is
	 * O(1) (three map gets in {@link CatalogExpressionTriggerRegistry#getLocalTrigger}).
	 *
	 * Callers that iterate sorted references should cache the result themselves across consecutive references
	 * of the same type (see `reEvaluateFacetExpressionsInAllIndexes` and `indexAllFacets` in
	 * {@link ReferenceIndexMutator}).
	 *
	 * @param referenceName the name of the reference to look up
	 * @param scope         the scope to look up
	 * @return the trigger, or `null` if no expression is defined or the trigger supplier is not installed
	 */
	@Nullable
	public FacetExpressionTrigger getTriggerFor(@Nonnull String referenceName, @Nonnull Scope scope) {
		return this.localFacetTriggerSupplier == null
			? null
			: this.localFacetTriggerSupplier.apply(referenceName, scope);
	}

	/**
	 * Returns the local histogram triggers for the given reference name and current scope. Delegates to
	 * the trigger registry supplier. Returns an empty collection if no trigger registry or no histogram
	 * triggers are defined for the given reference.
	 *
	 * @param referenceName the name of the reference to look up
	 * @param scope         the scope to look up
	 * @return histogram triggers (empty collection if none defined, never null)
	 */
	@Nonnull
	public Collection<HistogramExpressionTrigger> getLocalHistogramTriggers(
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		final CatalogExpressionTriggerRegistry registry = getCatalogExpressionTriggerRegistry();
		if (registry == null) {
			return Collections.emptyList();
		}
		return registry.getLocalHistogramTriggers(this.entityType, referenceName, scope);
	}

	/**
	 * Returns a schema resolver function that maps entity type names to their schemas. Used by
	 * {@link ExpressionIndexTrigger#evaluate} for resolving entity schemas during expression evaluation.
	 *
	 * When a cross-entity schema resolver was provided at construction time, it is used to resolve schemas
	 * for entity types other than this executor's own entity type. For the owner entity type, the local
	 * schema accessor is always used to ensure the most up-to-date schema is returned. When no cross-entity
	 * resolver was provided, the resolver falls back to always returning the owner entity schema (which is
	 * only correct for local-only expressions that do not access referenced or group entity data).
	 *
	 * @return a function resolving entity type name to entity schema
	 */
	@Nonnull
	public Function<String, EntitySchemaContract> getSchemaResolver() {
		if (this.crossEntitySchemaResolver != null) {
			return entityType -> this.entityType.equals(entityType)
				? this.schemaAccessor.get()
				: this.crossEntitySchemaResolver.apply(entityType);
		}
		return entityType -> this.schemaAccessor.get();
	}

	/**
	 * Returns list of engine-internal index mutations that were detected during processing of the given
	 * input mutations. This method is the index-executor analogue of
	 * {@link ConsistencyCheckingLocalMutationExecutor#popImplicitMutations}.
	 *
	 * Iterates `inputMutations` to find {@link AttributeMutation} instances, consults the
	 * {@link CatalogExpressionTriggerRegistry} for each mutated attribute name, and produces
	 * {@link ReevaluateExpressionMutation} envelopes for cross-entity dispatch. For entity
	 * removals, all registered triggers fire without per-attribute filtering.
	 *
	 * No old-vs-new value comparison is performed — any attribute mutation on a trigger-registered
	 * attribute fires the trigger. This is safe over-firing: the target-side executor performs
	 * idempotent operations, so unnecessary triggers result in a no-op rather than incorrect state.
	 *
	 * @param inputMutations list of local mutations that were applied
	 * @return index mutations to dispatch to target collections
	 */
	@Nonnull
	public IndexImplicitMutations popIndexImplicitMutations(
		@Nonnull List<? extends LocalMutation<?, ?>> inputMutations
	) {
		// early return if no registry
		final CatalogExpressionTriggerRegistry registry = getCatalogExpressionTriggerRegistry();
		if (registry == null) {
			return EMPTY_INDEX_IMPLICIT_MUTATIONS;
		}
		final int entityPK = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.NEW);

		// branch: entity removal fires all entity-level attribute triggers without per-attribute comparison
		if (this.containerAccessor.isEntityRemovedEntirely()) {
			return buildRemovalMutations(registry, entityPK);
		}

		// branch: attribute change path — iterate input mutations directly
		return buildAttributeChangeMutations(registry, entityPK, inputMutations);
	}

	/**
	 * Prepares the necessary initial setup for the provided local mutations, ensuring
	 * appropriate indexes and configurations are in place for further processing. This
	 * initializes required components such as global indexes and sortable attribute compounds.
	 *
	 * @param localMutations the list of mutations that are applied locally; these mutations
	 *                       are used to make changes to the current state
	 */
	public void prepare(@Nonnull List<? extends LocalMutation<?, ?>> localMutations) {
		this.localMutations = localMutations;

		final EntityIndex globalIndex = getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, getScope()));
		final int recordId = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.NEW);
		final boolean created = globalIndex.insertPrimaryKeyIfMissing(recordId);
		if (created) {
			// we need to set-up all the entity compounds that rely on non-localized attributes
			// they will exist even if the attributes are not present (i.e. compounds contain only NULL values)
			final EntitySchema entitySchema = getEntitySchema();
			AttributeIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
				this,
				null,
				globalIndex,
				null,
				new EntitySchemaAttributeAndCompoundSchemaProvider(entitySchema),
				entitySchema,
				getStoragePartExistingDataFactory().getNormalizedEntityAttributeValueSupplier()
			);

			if (this.schemaAccessor.get().isWithHierarchy()) {
				setParent(
					this, globalIndex,
					getPrimaryKeyToIndex(IndexType.HIERARCHY_INDEX, Target.NEW),
					null
				);
			}
		}
	}

	@Override
	public void applyMutation(@Nonnull LocalMutation<?, ?> localMutation) {
		final GlobalEntityIndex globalIndex = (GlobalEntityIndex) getOrCreateIndex(
			new EntityIndexKey(EntityIndexType.GLOBAL, getScope())
		);
		dispatchViaRegistry(localMutation, globalIndex);
	}

	/**
	 * Captures the wildcard mutation type as a concrete `M` so the registry-returned handler can
	 * be invoked without an unchecked cast at the call site.
	 */
	private <M extends LocalMutation<?, ?>> void dispatchViaRegistry(
		@Nonnull M localMutation,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		final LocalMutationHandler<M> handler = LocalMutationHandlerRegistry.resolve(localMutation);
		handler.apply(localMutation, this, globalIndex);
	}

	/**
	 * Finalizes this entity's index changes that can only be resolved once every local mutation for the entity
	 * has been applied: per-locale language upsert / removal, the full removal of the entity from the indexes
	 * (when it is removed entirely) and the sweep of indexes that became empty.
	 *
	 * This work historically lived in {@code commit()}, but the collector invoked {@code commit()} from its
	 * {@code finish()} step in a way that bypassed rollback on failure, so a throw here produced a
	 * half-committed index. {@link io.evitadb.core.collection.LocalMutationExecutorCollector} now calls this
	 * method once at the end of the protected execution phase (before the commit / rollback decision and before
	 * the storage executor's promote), so any failure is caught and routed to the transaction-bound savepoint
	 * rollback; entity removal and the empty-index sweep run only after consistency checks and
	 * cross-entity index dispatch have already succeeded, exactly as before.
	 */
	@Override
	public void applyChanges() {
		final int primaryKeyToIndex = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.NEW);
		final LocaleWithScope[] entityAddedLocales = this.containerAccessor.getAddedLocales();
		final LocaleWithScope[] entityRemovedLocales = this.containerAccessor.getRemovedLocales();
		if (entityAddedLocales.length > 0 || entityRemovedLocales.length > 0) {
			final ExistingDataSupplierFactory existingAttributeFactory = getStoragePartExistingDataFactory();
			final EntitySchema entitySchema = getEntitySchema();
			for (LocaleWithScope localeWithScope : entityAddedLocales) {
				final EnumSet<LocaleScope> scope = localeWithScope.scope();
				final Locale locale = localeWithScope.locale();
				if (scope.contains(LocaleScope.ENTITY)) {
					upsertEntityLocale(locale, entitySchema, existingAttributeFactory);
				}
				if (scope.contains(LocaleScope.ATTRIBUTE)) {
					upsertEntityAttributeLocale(locale, entitySchema, existingAttributeFactory);
				}
			}
			for (LocaleWithScope localeWithScope : entityRemovedLocales) {
				final EnumSet<LocaleScope> scope = localeWithScope.scope();
				final Locale locale = localeWithScope.locale();
				if (scope.contains(LocaleScope.ENTITY)) {
					removeEntityLocale(locale, entitySchema, existingAttributeFactory);
				}
				if (scope.contains(LocaleScope.ATTRIBUTE)) {
					removeEntityAttributeLocale(locale, entitySchema, existingAttributeFactory);
				}
			}
		}

		if (this.containerAccessor.isEntityRemovedEntirely()) {
			// remove the entity itself from the indexes
			removeEntity(primaryKeyToIndex);
		}

		// one instant for the whole entity mutation, so a single upsert cannot stamp the indexes it touched with two
		// different moments
		final long now = System.currentTimeMillis();

		// count the index-maintenance effort this entity mutation cost, then remove all empty indexes after this
		// executor is committed
		for (EntityIndexKey accessedIndexKey : this.accessedIndexes) {
			final EntityIndex entityIndex = this.entityIndexCreatingAccessor.getIndexIfExists(accessedIndexKey);
			// an index acquired earlier in this mutation may already be gone - `removeEntity` above drops the indexes
			// an entirely removed entity emptied out
			if (entityIndex == null) {
				continue;
			}
			// counted once per index per entity mutation, never per attribute write, and counted for work performed
			// rather than work that survives - a rolled-back transaction still paid this maintenance
			final IndexActivity entityIndexActivity = entityIndex.getActivity();
			if (entityIndexActivity != null) {
				entityIndexActivity.recordUpdate(now);
			}
			// global live index is never removed and is always present (even if empty)
			if (!(accessedIndexKey.type() == EntityIndexType.GLOBAL && accessedIndexKey.scope() == Scope.LIVE)
				&& entityIndex.isEmpty()) {
				this.entityIndexCreatingAccessor.removeIndex(accessedIndexKey);
			}
		}

		// the catalog indexes this mutation maintained, if it maintained any globally-unique attribute at all
		if (this.accessedCatalogIndexes != null) {
			for (final CatalogIndex catalogIndex : this.accessedCatalogIndexes) {
				if (catalogIndex != null) {
					final IndexActivity catalogIndexActivity = catalogIndex.getActivity();
					if (catalogIndexActivity != null) {
						catalogIndexActivity.recordUpdate(now);
					}
				}
			}
		}

		// the schema capabilities this mutation maintained the indexes of - one increment each however many indexes
		// that took, and the same instant the indexes above were stamped with. Emptied on the way out so that a second
		// call could not count the same work twice
		if (this.touchedCapabilities != null) {
			for (int i = 0; i < this.touchedCapabilities.size(); i++) {
				this.touchedCapabilities.get(i).recordUpdated(now);
			}
			this.touchedCapabilities.clear();
		}
		if (this.touchedElements != null) {
			this.touchedElements.clear();
		}
	}

	@Override
	public void commit() {
		// Index changes are finalized eagerly in applyChanges() during the protected execution phase of
		// LocalMutationExecutorCollector, so that a failure routes to the savepoint rollback instead of leaving
		// a half-committed index. On the index side commit() is therefore a pure no-op promote: the
		// transactional diff layer is published when the surrounding transaction commits.
		//
		// Note there is no per-executor rollback() counterpart: a failed entity mutation is reverted structurally
		// by the savepoint opened in LocalMutationExecutorCollector — the transactional diff-layer savepoint while
		// a transaction is active, the WarmUpSavepoint journal on the non-transactional warm-up path; the legacy
		// hand-written undo actions have been removed.
	}

	/**
	 * Executes and clears all deferred expression re-evaluation actions (both facet and histogram).
	 * Must be called after the storage writer has processed the current mutation so that expression
	 * evaluation reads the updated values.
	 */
	@Override
	public void finishLocalMutationExecutionPhase() {
		if (this.deferredExpressionReEvaluations != null && !this.deferredExpressionReEvaluations.isEmpty()) {
			for (final Runnable action : this.deferredExpressionReEvaluations) {
				action.run();
			}
			this.deferredExpressionReEvaluations.clear();
		}
	}

	@Nonnull
	public CatalogIndex getCatalogIndex(@Nonnull Scope scope) {
		final CatalogIndex catalogIndex = this.catalogIndexCreatingAccessor.getOrCreateIndex(
			new CatalogIndexKey(scope)
		);
		if (this.accessedCatalogIndexes == null) {
			this.accessedCatalogIndexes = new CatalogIndex[SCOPE_COUNT];
		}
		// remember the acquisition rather than counting it here — see the field's javadoc for why it is deduplicated
		this.accessedCatalogIndexes[scope.ordinal()] = catalogIndex;
		return catalogIndex;
	}

	/**
	 * Returns the entity type name ↔ compact primary key resolver backed by the current catalog snapshot. Threaded into
	 * the global unique index by {@link AttributeIndexMutator} so the index can decode the entity type stored inside its
	 * packed tuples without holding a catalog back-reference.
	 *
	 * @return the entity type classifier resolver
	 */
	@Nonnull
	public EntityTypeClassifierResolver getEntityTypeClassifierResolver() {
		return this.entityTypeClassifierResolver;
	}

	/**
	 * Reports that this entity mutation maintained the indexes an attribute's capabilities cost, in the given scope.
	 *
	 * Callers report **every** time they touch the attribute — once per index the write fans out over — and this
	 * method throws away all but the first, because the number being accumulated is *"entity mutations that touched
	 * the element"* and not *"physical index operations performed"*. The fan-out width is a legitimately different
	 * metric and the per-index counters are where it is visible; see {@link SchemaCapabilityUsage} for why the two
	 * must not be conflated.
	 *
	 * Which capabilities are counted follows the attribute's flags in **that** scope, and matches
	 * {@link SchemaCapabilityUsageRegistry#alignWith(EntitySchemaContract)} exactly - notably `unique()` counts as
	 * `FILTERABLE` too, because a unique attribute is filterable by that fact alone. That agreement is what makes the
	 * registry's eager seeding honest: every row alignment mints for this collection is one this method can raise.
	 *
	 * Nothing is incremented here: the holders are only collected, and {@link #applyChanges()} increments them once,
	 * with the instant it stamps the touched indexes with.
	 *
	 * # The globally-unique attribute is counted twice, on purpose
	 *
	 * A globally-unique attribute is maintained in the collection's own indexes **and** in the catalog's
	 * {@link CatalogIndex}, and it is declared by the catalog schema rather than by this collection's. It therefore
	 * lands in both registries: the collection's, because writing this entity did maintain that collection's indexes,
	 * and the catalog's, because that is where a reader asking *"is this global attribute worth its uniqueness
	 * index?"* looks - and where the request of a query that named no collection at all was counted.
	 *
	 * @param containerName   name of the reference declaring the attribute, or null when the entity declares it - take
	 *                        this from {@link AttributeAndCompoundSchemaProvider#getContainerName()} and never from
	 *                        the reference schema of the index being written, which for an entity attribute is
	 *                        whichever reference owns the reduced index
	 * @param attributeSchema the attribute whose indexes were maintained
	 * @param scope           the scope of the index that was maintained
	 */
	public void reportAttributeTouched(
		@Nullable String containerName,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Scope scope
	) {
		final String attributeName = attributeSchema.getName();
		if (!markTouched(ElementKind.ATTRIBUTE, containerName, attributeName, null, scope)) {
			return;
		}
		final boolean unique = attributeSchema.isUniqueInScope(scope);
		if (unique || attributeSchema.isFilterableInScope(scope)) {
			rememberCapability(
				new SchemaCapabilityKey(
					ElementKind.ATTRIBUTE, containerName, attributeName, Capability.FILTERABLE, scope
				)
			);
		}
		if (attributeSchema.isSortableInScope(scope)) {
			rememberCapability(
				new SchemaCapabilityKey(ElementKind.ATTRIBUTE, containerName, attributeName, Capability.SORTABLE, scope)
			);
		}
		if (unique) {
			rememberCapability(
				new SchemaCapabilityKey(ElementKind.ATTRIBUTE, containerName, attributeName, Capability.UNIQUE, scope)
			);
		}
		if (containerName == null && attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema
			&& globalAttributeSchema.isUniqueGloballyInScope(scope)
		) {
			// the catalog's copy of the same event - resolved from the catalog registry and deduplicated by the very
			// same `markTouched` call above, since a global attribute is one element however many indexes carry it
			rememberCatalogCapability(
				new SchemaCapabilityKey(ElementKind.ATTRIBUTE, null, attributeName, Capability.FILTERABLE, scope)
			);
			rememberCatalogCapability(
				new SchemaCapabilityKey(ElementKind.ATTRIBUTE, null, attributeName, Capability.UNIQUE, scope)
			);
		}
	}

	/**
	 * Reports that this entity mutation maintained a sortable attribute compound's sort index, in the given scope.
	 * Deduplicated per entity mutation exactly like {@link #reportAttributeTouched}, which matters more here than
	 * there: a compound is rebuilt once per constituent attribute written and once per index it lives in.
	 *
	 * @param containerName name of the reference declaring the compound, or null when the entity declares it - see
	 *                      {@link #reportAttributeTouched} for where to take it from
	 * @param compoundName  name of the compound whose sort index was maintained
	 * @param scope         the scope of the index that was maintained
	 */
	public void reportSortableCompoundTouched(
		@Nullable String containerName,
		@Nonnull String compoundName,
		@Nonnull Scope scope
	) {
		if (markTouched(ElementKind.SORTABLE_COMPOUND, containerName, compoundName, null, scope)) {
			rememberCapability(SchemaCapabilityKey.sortableCompound(containerName, compoundName, scope));
		}
	}

	/**
	 * Reports that this entity mutation maintained the indexes a **reference's own** flags cost, in the given scope -
	 * the reduced entity indexes `indexed()` creates, the facet index `faceted()` adds to them, and the bucketed
	 * histogram `bucketed()` adds on top.
	 *
	 * Deduplicated per entity mutation exactly like {@link #reportAttributeTouched}, which matters here for the same
	 * reason it matters for a compound: a single upsert may write several references of the same name - one per
	 * referenced entity - and each of them maintains the same schema flags.
	 *
	 * Nothing is reported for a reference that is not indexed in this scope, because then none of the three indexes
	 * exists to be maintained. That is also why `INDEXED` needs no flag test of its own once past the guard.
	 *
	 * `BUCKETED` is tested through {@link SchemaCapabilityUsageRegistry#maintainsHistogramIn} rather than through the
	 * bare `bucketed()` flag, so that this site agrees with the two that seed and prune the same row.
	 *
	 * @param referenceSchema the reference whose indexes were maintained
	 * @param scope           the scope of the index that was maintained
	 */
	public void reportReferenceTouched(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope
	) {
		// `isIndexedReferenceForFiltering` rather than `isIndexedInScope`: the two decide the same fact, but the
		// former reads `getReferenceIndexType`, which `ReflectedReferenceSchema` does not override and so cannot
		// throw for a reflected reference whose target is not attached yet. The bulk indexing paths guard with the
		// same accessor, so this site cannot fail on a reference they were willing to hand it
		if (!ReferenceIndexMutator.isIndexedReferenceForFiltering(referenceSchema, scope)) {
			return;
		}
		final String referenceName = referenceSchema.getName();
		if (!markTouched(ElementKind.REFERENCE, null, referenceName, null, scope)) {
			return;
		}
		rememberCapability(SchemaCapabilityKey.reference(referenceName, Capability.INDEXED, scope));
		// readability checked rather than assumed: `isFacetedInScope` throws for a reflected reference that inherits
		// the flag from a target not attached yet, and the bulk unindex path reaches here guarded only by an
		// index-type test that tolerates exactly that state. Reporting nothing is the right answer for a reference
		// whose faceting cannot even be determined
		if (SchemaCapabilityUsageRegistry.isReadable(
				referenceSchema, ReflectedReferenceSchemaContract::isFacetedInherited
			) && referenceSchema.isFacetedInScope(scope)
		) {
			rememberCapability(SchemaCapabilityKey.reference(referenceName, Capability.FACETED, scope));
		}
		// the same predicate the registry seeds and prunes by, deliberately shared rather than restated: a histogram
		// declared without a value expression is never maintained, so filing it here would mint a row claiming
		// maintenance that never happened - and one the next alignment would then drop again
		if (SchemaCapabilityUsageRegistry.maintainsHistogramIn(referenceSchema, scope)) {
			rememberCapability(SchemaCapabilityKey.reference(referenceName, Capability.BUCKETED, scope));
		}
	}

	/**
	 * Reports that this entity mutation maintained one of the two indexes the **entity itself** declares - its
	 * hierarchy placement or its prices.
	 *
	 * Unlike every other reporting method here this deduplicates **per capability**, not per element: both flags
	 * belong to the same element - the entity - but are maintained by different mutators reacting to different
	 * mutations, so an element-level entry would let whichever ran first swallow the other. See
	 * {@link TouchedSchemaElement}.
	 *
	 * @param capability which of the entity's own flags was maintained - `HIERARCHY_INDEXED` or `PRICE_INDEXED`
	 * @param scope      the scope of the index that was maintained
	 */
	public void reportEntityCapabilityTouched(
		@Nonnull Capability capability,
		@Nonnull Scope scope
	) {
		final EntitySchema entitySchema = getEntitySchema();
		// no `default` branch on purpose: a future entity-level flag must fail to compile here rather than go
		// uncounted, and a flag that belongs to some other element must not be silently accepted
		final boolean maintained = switch (capability) {
			case HIERARCHICAL -> entitySchema.isHierarchyIndexedInScope(scope);
			case PRICED -> entitySchema.isPriceIndexedInScope(scope);
			case FILTERABLE, SORTABLE, UNIQUE, FACETED, INDEXED, BUCKETED -> throw new GenericEvitaInternalError(
				"Entity `" + entitySchema.getName() + "` cannot carry capability " + capability + " directly."
			);
		};
		if (!maintained) {
			return;
		}
		final String entityType = entitySchema.getName();
		if (markTouched(ElementKind.ENTITY, null, entityType, capability, scope)) {
			rememberCapability(SchemaCapabilityKey.entity(entityType, capability, scope));
		}
	}

	/**
	 * Records that the named element has been reported in this entity mutation, and says whether that was the first
	 * time - which is the whole of the per-entity-mutation deduplication.
	 *
	 * @param kind          what kind of element it is - an attribute and a compound may share a name
	 * @param containerName name of the declaring reference, or null when the entity declares the element
	 * @param elementName   name of the element
	 * @param capability    the single flag to deduplicate, or null to deduplicate the element as a whole - see
	 *                      {@link TouchedSchemaElement} for which kinds need which
	 * @param scope         the scope the element was maintained in
	 * @return true when this is the first report of that element in this entity mutation
	 */
	private boolean markTouched(
		@Nonnull ElementKind kind,
		@Nullable String containerName,
		@Nonnull String elementName,
		@Nullable Capability capability,
		@Nonnull Scope scope
	) {
		if (this.touchedElements == null) {
			this.touchedElements = new ArrayList<>(16);
		} else {
			for (int i = 0; i < this.touchedElements.size(); i++) {
				final TouchedSchemaElement touched = this.touchedElements.get(i);
				if (touched.kind() == kind && touched.scope() == scope &&
					touched.elementName().equals(elementName) &&
					Objects.equals(touched.containerName(), containerName) &&
					touched.capability() == capability
				) {
					return false;
				}
			}
		}
		this.touchedElements.add(new TouchedSchemaElement(kind, containerName, elementName, capability, scope));
		return true;
	}

	/**
	 * Resolves the holder counting the given capability and queues it for the flush in {@link #applyChanges()}.
	 * Called at most once per capability per entity mutation, which is what makes the registry's map lookup
	 * affordable here.
	 *
	 * @param key the capability that was maintained
	 */
	private void rememberCapability(@Nonnull SchemaCapabilityKey key) {
		if (!this.usageStatisticsTracking) {
			return;
		}
		if (this.touchedCapabilities == null) {
			this.touchedCapabilities = new ArrayList<>(16);
		}
		this.touchedCapabilities.add(this.usageRegistry.resolve(key));
	}

	/**
	 * The catalog-registry counterpart of {@link #rememberCapability}, for the capabilities of a globally-unique
	 * attribute. The queue and the flush are shared - a holder is a holder, and which registry handed it over stops
	 * mattering the moment it has been resolved.
	 *
	 * @param key the capability that was maintained, always an attribute the catalog schema declares itself
	 */
	private void rememberCatalogCapability(@Nonnull SchemaCapabilityKey key) {
		if (!this.usageStatisticsTracking) {
			return;
		}
		if (this.touchedCapabilities == null) {
			this.touchedCapabilities = new ArrayList<>(16);
		}
		this.touchedCapabilities.add(this.catalogUsageRegistry.resolve(key));
	}

	/**
	 * Ensures the specified language is added to the target index for the given entity.
	 * If the language already exists in the target index for the entity, an exception is thrown.
	 *
	 * @param locale       the locale representing the language to be added
	 * @param entitySchema the schema of the entity to which the language is being added
	 * @param targetIndex  the target index where the language should be updated
	 * @param epk          the primary key of the entity being updated
	 */
	public void upsertEntityLocaleInTargetIndex(
		@Nonnull Locale locale,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull EntityIndex targetIndex,
		int epk
	) {
		targetIndex.upsertLanguage(locale, epk, entitySchema);
	}

	/**
	 * Ensures the specified language is removed from the target index for the given entity.
	 * If the language already exists in the target index for the entity, an exception is thrown.
	 *
	 * @param locale       the locale representing the language to be removed
	 * @param entitySchema the schema of the entity to which the language is being removed
	 * @param targetIndex  the target index where the language should be removed
	 * @param epk          the primary key of the entity being updated
	 */
	public void removeEntityLocaleInTargetIndex(
		@Nonnull Locale locale,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull EntityIndex targetIndex,
		int epk
	) {
		targetIndex.removeLanguage(locale, epk);
	}

	/**
	 * Updates or inserts (upserts) the specified entity attribute locale into the target index.
	 *
	 * @param locale                      the locale information that needs to be updated or inserted
	 * @param entitySchema                the schema of the entity the locale information belongs to
	 * @param targetIndex                 the target index where the locale data should be upserted
	 * @param existingDataSupplierFactory the factory supplying existing data supplier methods for lookups
	 */
	public void upsertEntityAttributeLocaleInTargetIndex(
		@Nonnull Locale locale,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityIndex targetIndex,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		insertInitialSuiteOfSortableAttributeCompounds(
			locale, entitySchema, targetIndex, existingDataSupplierFactory
		);
	}

	/**
	 * Updates or inserts (upserts) the specified entity attribute locale into the target index,
	 * using an explicit reference key. This overload is used when the index discriminator may not
	 * contain the correct reference key (e.g. for group-level reduced indexes).
	 *
	 * @param locale                      the locale information that needs to be updated or inserted
	 * @param entitySchema                the schema of the entity the locale information belongs to
	 * @param targetIndex                 the target index where the locale data should be upserted
	 * @param referenceKey                the explicit reference key to use instead of extracting from the index discriminator
	 * @param existingDataSupplierFactory the factory supplying existing data supplier methods for lookups
	 */
	public void upsertEntityAttributeLocaleInTargetIndex(
		@Nonnull Locale locale,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityIndex targetIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		insertInitialSuiteOfSortableAttributeCompounds(
			locale, entitySchema, targetIndex, referenceKey, existingDataSupplierFactory
		);
	}

	/**
	 * Removes a specific locale's data from the target entity index.
	 *
	 * @param locale                      the locale that should be removed from the target index
	 * @param entitySchema                the schema of the entity defining its structure and constraints
	 * @param targetIndex                 the target index from which the locale data should be removed
	 * @param existingDataSupplierFactory a factory for supplying existing data related to the entity
	 */
	public void removeEntityAttributeLocaleInTargetIndex(
		@Nonnull Locale locale,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityIndex targetIndex,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		removeEntireSuiteOfSortableAttributeCompounds(
			locale, entitySchema, targetIndex, existingDataSupplierFactory
		);
	}

	/**
	 * Removes a specific locale's data from the target entity index, using an explicit reference key.
	 * This overload is used when the index discriminator may not contain the correct reference key
	 * (e.g. for group-level reduced indexes).
	 *
	 * @param locale                      the locale that should be removed from the target index
	 * @param entitySchema                the schema of the entity defining its structure and constraints
	 * @param targetIndex                 the target index from which the locale data should be removed
	 * @param referenceKey                the explicit reference key to use instead of extracting from the index discriminator
	 * @param existingDataSupplierFactory a factory for supplying existing data related to the entity
	 */
	public void removeEntityAttributeLocaleInTargetIndex(
		@Nonnull Locale locale,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityIndex targetIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		removeEntireSuiteOfSortableAttributeCompounds(
			locale, entitySchema, targetIndex, referenceKey, existingDataSupplierFactory
		);
	}

	/**
	 * Retrieves the ReferencesStoragePart instance associated with the entity type and primary key index.
	 *
	 * @return a non-null ReferencesStoragePart instance containing reference storage data
	 */
	@Nonnull
	public ReferencesStoragePart getReferencesStoragePart() {
		return this.containerAccessor.getReferencesStoragePart(
			this.entityType, getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.EXISTING)
		);
	}

	/**
	 * Retrieves the representative reference key for the given reference key. Depending on the cardinality
	 * of the reference schema, the method calculates representative attributes for all existing references
	 * of the specified type if duplicates are allowed. If no duplicates are allowed, a representative key
	 * is created directly based on the specified reference key.
	 *
	 * This method calculates a representative reference key that will be valid for the state of the entity when all
	 * local mutations has already been applied.
	 *
	 * @param entityPrimaryKey          the primary key of the entity for which the representative reference
	 *                                  key is being retrieved
	 * @param globalEntityIndex         the global index containing information about all entities
	 * @param referenceKey              the key identifying the specific referenced entity
	 * @param referenceSchema           the schema contract of the reference to provide structural details
	 * @param referencePresenceExpected a flag indicating whether the presence of the reference is expected
	 *                                  or optional
	 * @return the representative reference key, which may carry additional representative attributes
	 * if the reference supports duplicates
	 */
	@Nonnull
	public RepresentativeReferenceKey getRepresentativeReferenceKey(
		int entityPrimaryKey,
		@Nonnull GlobalEntityIndex globalEntityIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean referencePresenceExpected
	) {
		return this.memoizedRepresentativeAttributes.computeIfAbsent(
			new ComparableReferenceKey(referenceKey),
			crk -> getRepresentativeReferenceKeysAndUpdateIndexesIfNecessary(
				entityPrimaryKey, globalEntityIndex, referenceKey,
				crk, referenceSchema, referencePresenceExpected
			)
		).current();
	}

	/**
	 * Retrieves the representative reference key for the given reference key. Depending on the cardinality
	 * of the reference schema, the method calculates representative attributes for all existing references
	 * of the specified type if duplicates are allowed. If no duplicates are allowed, a representative key
	 * is created directly based on the specified reference key.
	 *
	 * @param referenceKey the key of the reference for which the representative key is to be retrieved
	 * @return the representative reference key, which may carry additional representative attributes
	 * if the reference supports duplicates
	 */
	@Nonnull
	public RepresentativeReferenceKeys getRepresentativeReferenceKeys(
		@Nonnull ReferenceKey referenceKey,
		boolean referencePresenceExpected
	) {
		final EntitySchema entitySchema = getEntitySchema();
		final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(referenceKey.referenceName());
		if (referenceSchema.getCardinality().allowsDuplicates()) {
			// we need to calculate representative attributes for all existing references of this type
			final RepresentativeAttributeDefinition rad = referenceSchema.getRepresentativeAttributeDefinition();
			final ReferencesStoragePart referencesStoragePart = getReferencesStoragePart();
			final Optional<ReferenceContract> reference = referencePresenceExpected ?
				of(referencesStoragePart.findReferenceOrThrowException(referenceKey)) :
				referencesStoragePart.findReference(referenceKey);

			// first fill representative attributes with default values and current values of the reference
			final Serializable[] storedRAV = rad.getRepresentativeValues(reference.orElse(null));
			Serializable[] currentRAV = storedRAV;

			// then peek into all local mutations and update values according to them
			// this prevents from reindexing data when local mutations are gradually applied one by one
			if (this.localMutations != null) {
				boolean created = false;
				for (LocalMutation<?, ?> localMutation : this.localMutations) {
					if (localMutation instanceof ReferenceMutation<?> rm) {
						if (ReferenceKey.FULL_COMPARATOR.compare(rm.getReferenceKey(), referenceKey) == 0) {
							if (localMutation instanceof InsertReferenceMutation) {
								created = true;
							} else if (localMutation instanceof ReferenceAttributeMutation ram) {
								final String attributeName = ram.getAttributeKey().attributeName();
								final OptionalInt attributeNameIndex = rad.getAttributeNameIndex(attributeName);
								if (attributeNameIndex.isPresent()) {
									final int index = attributeNameIndex.getAsInt();
									final AttributeMutation attributeMutation = ram.getAttributeMutation();
									final AttributeValue updatedValue = attributeMutation
										.mutateLocal(
											entitySchema,
											reference.flatMap(it -> it.getAttributeValue(attributeName))
												.orElse(null)
										);
									final Serializable newValue = updatedValue.exists() ? updatedValue.value() : null;
									if (!Objects.equals(newValue, storedRAV[index])) {
										//noinspection ArrayEquality
										if (currentRAV == storedRAV) {
											currentRAV = Arrays.copyOf(storedRAV, storedRAV.length);
										}
										currentRAV[index] = newValue;
									}
								}
							}
						}
					}
				}
				if (created) {
					if (this.createdReferences == null) {
						this.createdReferences = CollectionUtils.createHashSet(16);
					}
					this.createdReferences.add(new RepresentativeReferenceKey(referenceKey, currentRAV));
				}
			}

			if (Arrays.equals(storedRAV, currentRAV)) {
				final RepresentativeReferenceKey singleKey = new RepresentativeReferenceKey(referenceKey, storedRAV);
				return new RepresentativeReferenceKeys(singleKey, singleKey);
			} else {
				return new RepresentativeReferenceKeys(
					new RepresentativeReferenceKey(referenceKey, storedRAV),
					new RepresentativeReferenceKey(referenceKey, currentRAV)
				);
			}
		} else {
			final RepresentativeReferenceKey singleKey = new RepresentativeReferenceKey(referenceKey);
			return new RepresentativeReferenceKeys(singleKey, singleKey);
		}
	}

	/**
	 * Returns current entity schema.
	 */
	@Nonnull
	public EntitySchema getEntitySchema() {
		return this.schemaAccessor.get();
	}

	/**
	 * Returns primary key that should be indexed by certain {@link IndexType}. Argument of index type is necessary
	 * because for example for {@link EntityIndexType#REFERENCED_ENTITY_TYPE} we need to index referenced entity id for
	 * {@link IndexType#ATTRIBUTE_FILTER_INDEX} and {@link IndexType#ATTRIBUTE_UNIQUE_INDEX}, but entity
	 * id for {@link IndexType#ATTRIBUTE_SORT_INDEX}.
	 *
	 * @param indexType the index type for which primary key should be resolved
	 * @param target    whether we want to index primary key of existing or new entity
	 * @return primary key that should be indexed
	 */
	public int getPrimaryKeyToIndex(@Nonnull IndexType indexType, @Nonnull Target target) {
		isPremiseValid(!this.entityPrimaryKey.isEmpty(), "Should not ever happen.");
		//noinspection ConstantConditions
		return this.entityPrimaryKey.peek().applyAsInt(indexType, target);
	}

	/**
	 * Method allows overloading default implementation that returns entity primary key for all {@link IndexType} values.
	 */
	void executeWithDifferentPrimaryKeyToIndex(
		@Nonnull ToIntBiFunction<IndexType, Target> entityPrimaryKeyResolver,
		@Nonnull Runnable runnable
	) {
		try {
			this.entityPrimaryKey.push(entityPrimaryKeyResolver);
			runnable.run();
		} finally {
			this.entityPrimaryKey.pop();
		}
	}

	/**
	 * Method returns existing index or creates new and adds it to the changed set of indexes that needs persisting.
	 */
	@Nonnull
	public EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey entityIndexKey) {
		this.accessedIndexes.add(entityIndexKey);
		return this.entityIndexCreatingAccessor.getOrCreateIndex(entityIndexKey);
	}

	/**
	 * Returns the price index of this collection's GLOBAL entity index of the given scope - the index that owns the
	 * memory-expensive price records every reduced index of that scope merely references.
	 *
	 * Every price write is routed through it: a reduced index no longer keeps a pointer to the super index backing it,
	 * so the caller - which is by construction pinned to a single catalog version - supplies the super index for the
	 * version it is mutating. The scope is taken from the index being mutated rather than from {@link #getScope()},
	 * because a scope change moves prices between two scopes' indexes within one mutation.
	 *
	 * @param scope the scope whose GLOBAL entity index owns the super price indexes
	 * @return the price index of the GLOBAL entity index of the given scope
	 */
	@Nonnull
	public PriceSuperIndex getGlobalPriceIndex(@Nonnull Scope scope) {
		return ((GlobalEntityIndex) getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, scope))).getPriceIndex();
	}

	/**
	 * Returns the existing index for the given key without creating it and without registering it for
	 * modification tracking. Returns `null` if the index does not exist. Used for read-only lookups
	 * (e.g., existence checks before calling {@link #getOrCreateIndex(EntityIndexKey)}).
	 *
	 * **Important:** do not use this method when the caller intends to mutate the returned index — use
	 * {@link #getOrCreateIndex(EntityIndexKey)} instead to ensure the index is tracked for persistence.
	 *
	 * @param entityIndexKey the key identifying the index to look up
	 * @return the existing index, or `null` if no index exists for the given key
	 */
	@Nullable
	public EntityIndex getIndexIfExists(@Nonnull EntityIndexKey entityIndexKey) {
		return this.entityIndexCreatingAccessor.getIndexIfExists(entityIndexKey);
	}

	/**
	 * Returns the existing entity index by its storage primary key, or `null` if not found.
	 * Used for resolving reduced indexes from their internal primary key without creating new indexes.
	 *
	 * @param indexPrimaryKey the storage primary key of the index
	 * @return the existing index, or `null` if no index with the given PK exists
	 */
	@Nullable
	EntityIndex getEntityIndexByPrimaryKeyIfExists(int indexPrimaryKey) {
		return this.entityIndexCreatingAccessor.getIndexByPrimaryKeyIfExists(indexPrimaryKey);
	}

	/**
	 * Returns the existing entity index by its storage primary key and registers it for modification tracking
	 * so that its changed storage parts are persisted on flush. Returns `null` if no index with the given PK exists.
	 * Use this method instead of {@link #getEntityIndexByPrimaryKeyIfExists(int)} when the caller intends to
	 * mutate the returned index (e.g. inserting/removing histogram values).
	 *
	 * @param indexPrimaryKey the storage primary key of the index
	 * @return the existing index registered for modification, or `null` if no index with the given PK exists
	 */
	@Nullable
	EntityIndex getEntityIndexByPrimaryKeyForModification(int indexPrimaryKey) {
		return this.entityIndexCreatingAccessor.getOrCreateIndexByPrimaryKey(indexPrimaryKey);
	}

	/**
	 * Method returns existing index by primary key and adds it to the changed set of indexes that needs persisting.
	 *
	 * The resolution MUST go through the registering accessor, not the plain read: every caller of this method mutates
	 * the index it gets back (it is the by-primary-key half of {@code getAllReducedIndexes}, which feeds the reduced
	 * indexes an entity upsert then writes into). An index that is mutated without being registered is invisible to the
	 * flush, so its changed storage parts are never persisted — and it is invisible to the dirty-key snapshot the
	 * commit-time merge prunes on, so the merge carries it across the catalog version by reference and orphans the
	 * transactional layers its mutation created, which surfaces as a `StaleTransactionMemoryException` that suspends
	 * the catalog. {@link #accessedIndexes} does not cover this: that set drives the empty-index cleanup, not
	 * persistence.
	 */
	@Nonnull
	Optional<EntityIndex> getIndexByPrimaryKey(int indexPrimaryKey) {
		final EntityIndex index = this.entityIndexCreatingAccessor.getOrCreateIndexByPrimaryKey(indexPrimaryKey);
		this.accessedIndexes.add(index.getIndexKey());
		return of(index);
	}

	/**
	 * Method processes all mutations that targets entity attributes - e.g. {@link AttributeMutation}.
	 */
	public void updateAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeMutation attributeMutation,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull ExistingAttributeValueSupplier existingValueSupplier,
		@Nonnull EntityIndex indexForRemoval,
		@Nonnull EntityIndex indexForUpsert,
		boolean updateGlobalIndex,
		boolean updateCompounds
	) {
		final AttributeKey affectedAttribute = attributeMutation.getAttributeKey();

		if (attributeMutation instanceof UpsertAttributeMutation) {
			final Serializable attributeValue = ((UpsertAttributeMutation) attributeMutation).getAttributeValue();
			AttributeIndexMutator.executeAttributeUpsert(
				this, referenceSchema, attributeSchemaProvider, existingValueSupplier,
				indexForRemoval, indexForUpsert, affectedAttribute, attributeValue, updateGlobalIndex, updateCompounds
			);
		} else if (attributeMutation instanceof RemoveAttributeMutation) {
			AttributeIndexMutator.executeAttributeRemoval(
				this, referenceSchema, attributeSchemaProvider, existingValueSupplier,
				indexForRemoval, indexForUpsert, affectedAttribute, updateGlobalIndex, updateCompounds
			);
		} else if (attributeMutation instanceof ApplyDeltaAttributeMutation<?> applyDeltaAttributeMutation) {
			final Number attributeValue = applyDeltaAttributeMutation.getAttributeValue();
			AttributeIndexMutator.executeAttributeDelta(
				this, referenceSchema, attributeSchemaProvider, existingValueSupplier,
				indexForRemoval, indexForUpsert, affectedAttribute, attributeValue
			);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new GenericEvitaInternalError("Unknown mutation: " + attributeMutation.getClass());
		}
	}

	/**
	 * Retrieves or creates an instance of ExistingDataSupplierFactory for the current storage part.
	 * If the factory is not already created, it initializes the factory using the root primary key and the type.
	 *
	 * @return An instance of ExistingDataSupplierFactory associated with the current storage part.
	 */
	@Nonnull
	public EntityStoragePartExistingDataFactory getStoragePartExistingDataFactory() {
		if (this.storagePartExistingDataFactory == null) {
			this.storagePartExistingDataFactory = new EntityStoragePartExistingDataFactory(
				this.containerAccessor,
				this.getEntitySchema(),
				this.entityPrimaryKey.getFirst().applyAsInt(IndexType.ENTITY_INDEX, Target.EXISTING),
				this.memoizedRepresentativeAttributes
			);
		}
		return this.storagePartExistingDataFactory;
	}

	/**
	 * Builds {@link IndexImplicitMutations} for the attribute change path. Iterates `inputMutations`
	 * to find {@link AttributeMutation} and {@link ReferenceAttributeMutation} instances, extracts
	 * attribute names (and reference names for reference attributes), and consults the trigger
	 * registry for each unique attribute. Duplicate attribute names within the batch are
	 * deduplicated — a single trigger firing is sufficient regardless of how many mutations touch the
	 * same attribute.
	 *
	 * Entity-level attributes are looked up against {@link #CROSS_ENTITY_ATTRIBUTE_DEPENDENCY_TYPES},
	 * while reference-level attributes are looked up against
	 * {@link #CROSS_ENTITY_REFERENCE_ATTRIBUTE_DEPENDENCY_TYPES} with an additional filter on the
	 * dependent reference name.
	 *
	 * @param registry       the trigger registry to consult
	 * @param entityPK       the primary key of the mutated entity
	 * @param inputMutations the list of applied local mutations
	 * @return index mutations to dispatch, or {@link #EMPTY_INDEX_IMPLICIT_MUTATIONS} if no triggers fire
	 */
	@Nonnull
	private IndexImplicitMutations buildAttributeChangeMutations(
		@Nonnull CatalogExpressionTriggerRegistry registry,
		int entityPK,
		@Nonnull List<? extends LocalMutation<?, ?>> inputMutations
	) {
		Map<String, List<IndexMutation>> mutationsByTargetType = null;
		// track already-processed entity-level attribute names to avoid duplicate trigger firings
		Set<String> processedEntityAttributes = null;
		// track already-processed (referenceName, attributeName) pairs for reference attributes
		Set<String> processedRefAttributes = null;

		for (final LocalMutation<?, ?> mutation : inputMutations) {
			if (mutation instanceof SetEntityScopeMutation) {
				// scope change affects entity visibility in all scopes — fire all triggers
				// unconditionally, same as entity removal
				return buildRemovalMutations(registry, entityPK);
			} else if (mutation instanceof ReferenceAttributeMutation refAttrMutation) {
				final String referenceName = refAttrMutation.getReferenceKey().referenceName();
				final String attributeName = refAttrMutation.getAttributeKey().attributeName();
				// deduplicate by (referenceName + '\0' + attributeName) to avoid collisions
				final String deduplicationKey = referenceName + '\0' + attributeName;
				if (processedRefAttributes != null && processedRefAttributes.contains(deduplicationKey)) {
					continue;
				}

				mutationsByTargetType = collectReferenceAttributeTriggers(
					registry, entityPK, referenceName, attributeName, mutationsByTargetType
				);

				if (processedRefAttributes == null) {
					processedRefAttributes = CollectionUtils.createHashSet(8);
				}
				processedRefAttributes.add(deduplicationKey);
			} else if (mutation instanceof AttributeMutation attributeMutation) {
				final String attributeName = attributeMutation.getAttributeKey().attributeName();

				// deduplicate: skip if this attribute name was already processed in this batch
				if (processedEntityAttributes != null && processedEntityAttributes.contains(attributeName)) {
					continue;
				}

				mutationsByTargetType = collectEntityAttributeTriggers(
					registry, entityPK, attributeName, mutationsByTargetType
				);

				if (processedEntityAttributes == null) {
					processedEntityAttributes = CollectionUtils.createHashSet(8);
				}
				processedEntityAttributes.add(attributeName);
			}
		}

		return groupByTargetEntityType(mutationsByTargetType);
	}

	/**
	 * Consults the trigger registry for entity-level attribute dependency types and collects
	 * {@link ReevaluateExpressionMutation} instances for each matching trigger.
	 *
	 * @param registry              the trigger registry to consult
	 * @param entityPK              the primary key of the mutated entity
	 * @param attributeName         the entity-level attribute that changed
	 * @param mutationsByTargetType existing mutations map (may be null, will be lazily created)
	 * @return the mutations map (possibly newly created)
	 */
	@Nullable
	private Map<String, List<IndexMutation>> collectEntityAttributeTriggers(
		@Nonnull CatalogExpressionTriggerRegistry registry,
		int entityPK,
		@Nonnull String attributeName,
		@Nullable Map<String, List<IndexMutation>> mutationsByTargetType
	) {
		for (final DependencyType dependencyType : CROSS_ENTITY_ATTRIBUTE_DEPENDENCY_TYPES) {
			final List<ExpressionIndexTrigger> triggers =
				registry.getTriggersForAttribute(this.entityType, dependencyType, attributeName);
			for (final ExpressionIndexTrigger trigger : triggers) {
				if (mutationsByTargetType == null) {
					mutationsByTargetType = CollectionUtils.createHashMap(4);
				}
				final ReevaluateExpressionMutation mutation = new ReevaluateExpressionMutation(
					trigger.getReferenceName(), entityPK, dependencyType, trigger.getScope(),
					this.capturedOldEntityAttributeValues
				);
				final List<IndexMutation> mutations = mutationsByTargetType
					.computeIfAbsent(trigger.getOwnerEntityType(), k -> new ArrayList<>(4));
				if (!mutations.contains(mutation)) {
					mutations.add(mutation);
				}
			}
		}
		return mutationsByTargetType;
	}

	/*
		FRIENDLY METHODS
	 */

	/**
	 * Consults the trigger registry for reference-level attribute dependency types and collects
	 * {@link ReevaluateExpressionMutation} instances for each matching trigger. Only triggers
	 * whose {@link ExpressionIndexTrigger#getDependentReferenceName()} matches the mutated reference
	 * name are included — multiple references on the same entity may share attribute names.
	 *
	 * @param registry              the trigger registry to consult
	 * @param entityPK              the primary key of the mutated entity
	 * @param referenceName         the reference name on the mutated entity whose attribute changed
	 * @param attributeName         the reference-level attribute that changed
	 * @param mutationsByTargetType existing mutations map (may be null, will be lazily created)
	 * @return the mutations map (possibly newly created)
	 */
	@Nullable
	private Map<String, List<IndexMutation>> collectReferenceAttributeTriggers(
		@Nonnull CatalogExpressionTriggerRegistry registry,
		int entityPK,
		@Nonnull String referenceName,
		@Nonnull String attributeName,
		@Nullable Map<String, List<IndexMutation>> mutationsByTargetType
	) {
		for (final DependencyType dependencyType : CROSS_ENTITY_REFERENCE_ATTRIBUTE_DEPENDENCY_TYPES) {
			final List<ExpressionIndexTrigger> triggers =
				registry.getTriggersForAttribute(this.entityType, dependencyType, attributeName);
			for (final ExpressionIndexTrigger trigger : triggers) {
				// filter: only fire if the trigger depends on the same reference that was mutated
				if (!referenceName.equals(trigger.getDependentReferenceName())) {
					continue;
				}
				if (mutationsByTargetType == null) {
					mutationsByTargetType = CollectionUtils.createHashMap(4);
				}
				// reference attribute changes on the source entity cannot affect entity-level histogram
				// value sources — only condition expressions may be affected, so no old values needed
				final ReevaluateExpressionMutation mutation = ReevaluateExpressionMutation.withoutOldValues(
					trigger.getReferenceName(), entityPK, dependencyType, trigger.getScope()
				);
				final List<IndexMutation> mutations = mutationsByTargetType
					.computeIfAbsent(trigger.getOwnerEntityType(), k -> new ArrayList<>(4));
				if (!mutations.contains(mutation)) {
					mutations.add(mutation);
				}
			}
		}
		return mutationsByTargetType;
	}

	/**
	 * Builds {@link IndexImplicitMutations} for the entity removal path. Entity removal changes all
	 * properties to null, so all triggers — both entity-level attribute and reference-level attribute
	 * dependency types — fire unconditionally without per-attribute filtering or old-vs-new comparison.
	 *
	 * @param registry the trigger registry to consult
	 * @param entityPK the primary key of the removed entity
	 * @return index mutations to dispatch, or {@link #EMPTY_INDEX_IMPLICIT_MUTATIONS} if no triggers
	 */
	@Nonnull
	private IndexImplicitMutations buildRemovalMutations(
		@Nonnull CatalogExpressionTriggerRegistry registry,
		int entityPK
	) {
		Map<String, List<IndexMutation>> mutationsByTargetType = null;
		mutationsByTargetType = collectAllTriggersForRemoval(
			registry, entityPK, CROSS_ENTITY_ATTRIBUTE_DEPENDENCY_TYPES, mutationsByTargetType
		);
		mutationsByTargetType = collectAllTriggersForRemoval(
			registry, entityPK, CROSS_ENTITY_REFERENCE_ATTRIBUTE_DEPENDENCY_TYPES, mutationsByTargetType
		);
		return groupByTargetEntityType(mutationsByTargetType);
	}

	/**
	 * Iterates the given dependency types and collects all triggers registered for this entity type
	 * unconditionally (no attribute filtering). Used during entity removal where all attributes
	 * effectively become null.
	 *
	 * @param registry              the trigger registry to consult
	 * @param entityPK              the primary key of the removed entity
	 * @param dependencyTypes       the dependency types to iterate
	 * @param mutationsByTargetType existing mutations map (may be null, will be lazily created)
	 * @return the mutations map (possibly newly created)
	 */
	@Nullable
	private Map<String, List<IndexMutation>> collectAllTriggersForRemoval(
		@Nonnull CatalogExpressionTriggerRegistry registry,
		int entityPK,
		@Nonnull DependencyType[] dependencyTypes,
		@Nullable Map<String, List<IndexMutation>> mutationsByTargetType
	) {
		for (final DependencyType dependencyType : dependencyTypes) {
			final List<ExpressionIndexTrigger> triggers =
				registry.getTriggersFor(this.entityType, dependencyType);
			for (final ExpressionIndexTrigger trigger : triggers) {
				if (mutationsByTargetType == null) {
					mutationsByTargetType = CollectionUtils.createHashMap(4);
				}
				// entity removal: pass all captured old values so each trigger's value source can be resolved
				final ReevaluateExpressionMutation mutation = new ReevaluateExpressionMutation(
					trigger.getReferenceName(), entityPK, dependencyType, trigger.getScope(),
					this.capturedOldEntityAttributeValues
				);
				final List<IndexMutation> mutations = mutationsByTargetType
					.computeIfAbsent(trigger.getOwnerEntityType(), k -> new ArrayList<>(4));
				if (!mutations.contains(mutation)) {
					mutations.add(mutation);
				}
			}
		}
		return mutationsByTargetType;
	}

	/**
	 * Defers histogram re-evaluation when a reference attribute changes. For each histogram trigger that
	 * references the changed attribute — either as the value source or in its condition expression — the
	 * histogram bucket is removed and re-added with the updated attribute value.
	 *
	 * When the changed attribute is the histogram value source, the old attribute value is captured
	 * BEFORE deferral (while the storage still has pre-mutation data) and passed to the deferred lambda
	 * for precise surgical removal. This avoids the O(references x buckets) full-reindex fallback.
	 */
	public void deferHistogramReEvaluationForReferenceAttribute(
		@Nonnull ReferenceKey referenceKey,
		@Nonnull String changedAttribute,
		@Nonnull Scope scope
	) {
		// if the reference was just inserted in this batch, the insert path already deferred a
		// histogram add — skip re-evaluation to avoid double-inserting the value (corrupting cardinality)
		if (this.referencesWithDeferredHistogramAdd.contains(referenceKey)) {
			return;
		}
		final Collection<HistogramExpressionTrigger> histTriggers =
			getLocalHistogramTriggers(referenceKey.referenceName(), scope);
		if (!histTriggers.isEmpty()) {
			final int entityPK = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.EXISTING);
			// capture old values and condition results BEFORE deferral — storage still has
			// pre-mutation data at this point (entityIndexUpdater runs before changeCollector)
			final ExistingAttributeValueSupplier oldSupplier =
				getStoragePartExistingDataFactory().getNormalizedReferenceAttributeValueSupplier(referenceKey);
			final ContainerizedLocalMutationExecutor storageAccessor =
				(ContainerizedLocalMutationExecutor) getContainerAccessor();
			final PreMutationHistogramSnapshot snapshot = new PreMutationHistogramSnapshot(
				histTriggers, changedAttribute, oldSupplier,
				entityPK, referenceKey, storageAccessor, getSchemaResolver(), scope
			);
			deferExpressionReEvaluation(() -> {
				final ReferenceContract ref = getReferencesStoragePart()
					.findReference(referenceKey).orElse(null);
				final Integer groupPK = ref != null ? extractActiveGroupPrimaryKey(ref) : null;
				for (final HistogramExpressionTrigger trigger : histTriggers) {
					final boolean conditionExpressionAffected =
						trigger.getLocalReferenceAttributes().contains(changedAttribute);
					if (snapshot.isValueSourceChanged(trigger)) {
						// value source changed: use pre-captured old values for surgical removal
						if (snapshot.isOldConditionMet(trigger)) {
							for (final Entry<Locale, Serializable[]> entry : snapshot.getOldValuesByLocale(trigger).entrySet()) {
								ReferenceIndexMutator.removeHistogramWithKnownOldValues(
									this, referenceKey, groupPK, entityPK,
									entry.getValue(), trigger, entry.getKey(), scope
								);
							}
						}
						ReferenceIndexMutator.addHistogramToIndex(
							this, referenceKey, groupPK, entityPK,
							getStoragePartExistingDataFactory()
								.getNormalizedReferenceAttributeValueSupplier(referenceKey),
							scope
						);
					} else if (conditionExpressionAffected) {
						// condition changed but value source did not: current value = old value
						if (snapshot.isOldConditionMet(trigger)) {
							ReferenceIndexMutator.removeHistogramIfPresent(
								this, referenceKey, groupPK, entityPK, scope
							);
						}
						ReferenceIndexMutator.addHistogramToIndex(
							this, referenceKey, groupPK, entityPK,
							getStoragePartExistingDataFactory()
								.getNormalizedReferenceAttributeValueSupplier(referenceKey),
							scope
						);
					}
				}
			});
		}
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Captures the pre-mutation value of an entity attribute for deterministic cross-entity histogram
	 * removal. Uses `putIfAbsent` to preserve only the true pre-mutation value when the same attribute
	 * is mutated multiple times in one batch.
	 *
	 * @param attributeKey the attribute key identifying the mutated attribute
	 * @param supplier     pre-mutation attribute value supplier (reads from storage parts before write)
	 */
	public void captureOldEntityAttributeValue(
		@Nonnull AttributeKey attributeKey,
		@Nonnull ExistingAttributeValueSupplier supplier
	) {
		supplier.getAttributeValue(attributeKey).ifPresent(av -> {
			final Serializable value = av.value();
			if (value != null) {
				if (this.capturedOldEntityAttributeValues == null) {
					this.capturedOldEntityAttributeValues = CollectionUtils.createHashMap(8);
				}
				this.capturedOldEntityAttributeValues
					.computeIfAbsent(attributeKey.attributeName(), k -> CollectionUtils.createHashMap(4))
					.putIfAbsent(attributeKey.locale(), value);
			}
		});
	}

	/**
	 * Captures entity attribute values into the pre-mutation map only for attributes referenced by
	 * cross-entity triggers. Used by scope change paths where captured attributes become unavailable
	 * in the source FilterIndex after the operation.
	 *
	 * @param entity         the full entity whose attribute values should be captured
	 * @param attributeNames the set of attribute names referenced by triggers (from registry)
	 */
	public void captureEntityAttributeValues(@Nonnull Entity entity, @Nonnull Set<String> attributeNames) {
		for (final AttributeValue attributeValue : entity.getAttributeValues()) {
			if (!attributeNames.contains(attributeValue.key().attributeName())) {
				continue;
			}
			final Serializable rawValue = attributeValue.value();
			if (rawValue != null) {
				// normalize BigDecimal values to match the canonical form used in indexes
				final Serializable value = NumberUtils.normalizeIfBigDecimal(rawValue);
				if (this.capturedOldEntityAttributeValues == null) {
					this.capturedOldEntityAttributeValues = CollectionUtils.createHashMap(8);
				}
				this.capturedOldEntityAttributeValues
					.computeIfAbsent(
						attributeValue.key().attributeName(),
						k -> CollectionUtils.createHashMap(4)
					)
					.putIfAbsent(attributeValue.key().locale(), value);
			}
		}
	}

	/**
	 * Removes the entity from group-level indexes associated with the given reference key.
	 * Looks up the reference from storage and delegates to
	 * {@link #removeFromGroupIndexes(int, EntitySchema, ReferenceSchemaContract,
	 * RepresentativeReferenceKey, ReferenceKey, ReferenceContract, Scope,
	 * ExistingDataSupplierFactory)}.
	 *
	 * @param epk                 the entity primary key
	 * @param entitySchema        the entity schema
	 * @param referenceSchema     the reference schema
	 * @param rrk                 the entity-level representative reference key
	 * @param referenceKey        the reference key
	 * @param scope               the current scope
	 * @param existingDataFactory factory supplying existing data for the removal
	 */
	private void removeFromGroupIndexes(
		int epk,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull RepresentativeReferenceKey rrk,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull Scope scope,
		@Nonnull ExistingDataSupplierFactory existingDataFactory
	) {
		final ReferenceContract existingReference = getReferencesStoragePart()
			.findReferenceOrThrowException(referenceKey);
		removeFromGroupIndexes(
			epk, entitySchema, referenceSchema, rrk, referenceKey,
			existingReference, scope, existingDataFactory
		);
	}

	/**
	 * Removes the entity from group-level indexes associated with the given reference.
	 * Does nothing if the reference has no active group.
	 *
	 * @param epk                 the entity primary key
	 * @param entitySchema        the entity schema
	 * @param referenceSchema     the reference schema
	 * @param rrk                 the entity-level representative reference key
	 * @param referenceKey        the reference key
	 * @param existingReference   the existing reference contract (already resolved)
	 * @param scope               the current scope
	 * @param existingDataFactory factory supplying existing data for the removal
	 */
	private void removeFromGroupIndexes(
		int epk,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull RepresentativeReferenceKey rrk,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ReferenceContract existingReference,
		@Nonnull Scope scope,
		@Nonnull ExistingDataSupplierFactory existingDataFactory
	) {
		final Integer groupPK = extractActiveGroupPrimaryKey(existingReference);
		if (groupPK != null) {
			final ReferencedTypeEntityIndex groupTypeIndex =
				ReferenceIndexMutator.getOrCreateReferencedGroupTypeEntityIndex(
					this, rrk.referenceName(), scope
				);
			final AbstractReducedEntityIndex groupIndex = ReferenceIndexMutator.getOrCreateReferencedGroupEntityIndex(
				this, rrk, groupPK, scope
			);
			ReferenceIndexMutator.referenceRemovalPerComponent(
				epk, entitySchema, referenceSchema, this,
				groupTypeIndex, groupIndex, referenceKey, groupPK,
				existingDataFactory
			);
		}
	}

	/**
	 * Inserts the entity into group-level indexes for the given group primary key.
	 * Creates or retrieves the group type index and group reduced index, then delegates
	 * to {@link ReferenceIndexMutator#referenceInsertPerComponent}.
	 *
	 * @param epk                         the entity primary key
	 * @param entitySchema                the entity schema
	 * @param referenceSchema             the reference schema
	 * @param rrk                         the entity-level representative reference key
	 * @param referenceKey                the reference key
	 * @param groupPK                     the group primary key
	 * @param scope                       the current scope
	 * @param existingDataSupplierFactory factory supplying existing data for the insertion
	 */
	private void insertIntoGroupIndexes(
		int epk,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull RepresentativeReferenceKey rrk,
		@Nonnull ReferenceKey referenceKey,
		int groupPK,
		@Nonnull Scope scope,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		final ReferencedTypeEntityIndex groupTypeIndex =
			ReferenceIndexMutator.getOrCreateReferencedGroupTypeEntityIndex(
				this, rrk.referenceName(), scope
			);
		final AbstractReducedEntityIndex groupIndex =
			ReferenceIndexMutator.getOrCreateReferencedGroupEntityIndex(
				this, rrk, groupPK, scope
			);
		ReferenceIndexMutator.referenceInsertPerComponent(
			epk, entitySchema, referenceSchema, this,
			groupTypeIndex, groupIndex, referenceKey,
			groupPK, groupPK,
			existingDataSupplierFactory
		);
	}

	/**
	 * Retrieves the representative reference keys for the provided parameters, adjusting the relevant references
	 * within the system as necessary. This method ensures that references are properly managed between their
	 * current and stored states.
	 *
	 * @param entityPrimaryKey          the primary key of the entity for which references are being managed
	 * @param globalEntityIndex         a reference to the global index of all entities in scope
	 * @param referenceKey              the unique key identifying the specific reference being managed
	 * @param comparableReferenceKey    a comparable key that assists in identifying and differentiating references
	 * @param referenceSchema           the schema that defines the contract and structure of the reference
	 * @param referencePresenceExpected a boolean flag to indicate if the reference is expected to exist
	 * @return a {@link RepresentativeReferenceKeys} object containing both the current and stored reference keys
	 */
	@Nonnull
	private RepresentativeReferenceKeys getRepresentativeReferenceKeysAndUpdateIndexesIfNecessary(
		int entityPrimaryKey,
		@Nonnull GlobalEntityIndex globalEntityIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ComparableReferenceKey comparableReferenceKey,
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean referencePresenceExpected
	) {
		final Scope scope = getScope();
		final EntityStoragePartExistingDataFactory existingStoragePartFactory = getStoragePartExistingDataFactory();
		final RepresentativeReferenceKeys bothKeys = getRepresentativeReferenceKeys(
			comparableReferenceKey.referenceKey(),
			referencePresenceExpected
		);
		if (bothKeys.differ() && (this.createdReferences == null || !this.createdReferences.contains(
			bothKeys.current()))) {
			final EntitySchema entitySchema = getEntitySchema();
			existingStoragePartFactory.executeWithRepresentativeReferenceKeyAlias(
				bothKeys.current(), bothKeys.stored(),
				() -> {
					// global: always — migrate facet between representative keys
					// look up the reference to extract group assignment for migration
					final ReferenceContract reference = getReferencesStoragePart()
						.findReference(referenceKey)
						.orElse(null);
					final Integer groupId = reference != null
						? extractActiveGroupPrimaryKey(reference) : null;
					ReferenceIndexMutator.referenceRemovalGlobal(
						entityPrimaryKey, referenceSchema, globalEntityIndex,
						referenceKey, this
					);
					ReferenceIndexMutator.referenceInsertGlobal(
						entityPrimaryKey, referenceSchema, globalEntityIndex,
						referenceKey, groupId, this
					);
					// entity component migration (only when configured)
					if (ReferenceIndexMutator.isIndexedForEntityComponent(referenceSchema, scope)) {
						final ReferencedTypeEntityIndex referenceTypeIndex =
							ReferenceIndexMutator.getOrCreateReferencedTypeEntityIndex(
								this, referenceKey.referenceName(), scope
							);
						final ReducedEntityIndex formerReferenceIndex =
							ReferenceIndexMutator.getOrCreateReferencedEntityIndex(
								this, bothKeys.stored(), scope
							);
						final ReducedEntityIndex newReferenceIndex =
							ReferenceIndexMutator.getOrCreateReferencedEntityIndex(
								this, bothKeys.current(), scope
							);
						ReferenceIndexMutator.referenceRemovalPerComponent(
							entityPrimaryKey, entitySchema, referenceSchema, this,
							referenceTypeIndex, formerReferenceIndex, referenceKey,
							referenceKey.primaryKey(),
							existingStoragePartFactory
						);
						ReferenceIndexMutator.referenceInsertPerComponent(
							entityPrimaryKey, entitySchema, referenceSchema, this,
							referenceTypeIndex, newReferenceIndex, referenceKey,
							referenceKey.primaryKey(), groupId,
							existingStoragePartFactory
						);
					}
					// group component migration (if group indexing enabled and group exists)
					if (ReferenceIndexMutator.isIndexedForGroupComponent(referenceSchema, scope)) {
						if (reference != null) {
							final Integer groupPK = extractActiveGroupPrimaryKey(reference);
							if (groupPK != null) {
								final ReferencedTypeEntityIndex groupTypeIndex =
									ReferenceIndexMutator.getOrCreateReferencedGroupTypeEntityIndex(
										this, referenceKey.referenceName(), scope
									);
								final ReducedGroupEntityIndex formerGroupIndex =
									ReferenceIndexMutator.getOrCreateReferencedGroupEntityIndex(
										this, bothKeys.stored(), groupPK, scope
									);
								final ReducedGroupEntityIndex newGroupIndex =
									ReferenceIndexMutator.getOrCreateReferencedGroupEntityIndex(
										this, bothKeys.current(), groupPK, scope
									);
								ReferenceIndexMutator.referenceRemovalPerComponent(
									entityPrimaryKey, entitySchema, referenceSchema, this,
									groupTypeIndex, formerGroupIndex, referenceKey, groupPK,
									existingStoragePartFactory
								);
								ReferenceIndexMutator.referenceInsertPerComponent(
									entityPrimaryKey, entitySchema, referenceSchema, this,
									groupTypeIndex, newGroupIndex, referenceKey, groupPK, groupPK,
									existingStoragePartFactory
								);
							}
						}
					}
				}
			);
		}
		return bothKeys;
	}

	/**
	 * Inserts an initial suite of sortable attribute compounds into the specified target index.
	 *
	 * This method determines whether the target index is associated with a reference key or not
	 * and delegates to the appropriate mutator. It utilizes the existing data supplier factory
	 * to source any required pre-existing data.
	 *
	 * @param locale                      the locale context for the operation, which may be null;
	 *                                    if null, the operation is performed without locale-specific considerations
	 * @param targetIndex                 the target entity index where the sortable attribute compounds will be inserted;
	 *                                    it must not be null
	 * @param existingDataSupplierFactory the factory to create suppliers that provide existing
	 *                                    data used during the insert; it must not be null
	 */
	private void insertInitialSuiteOfSortableAttributeCompounds(
		@Nullable Locale locale,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityIndex targetIndex,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		insertInitialSuiteOfSortableAttributeCompounds(
			locale, entitySchema, targetIndex, null, existingDataSupplierFactory
		);
	}

	/**
	 * Inserts an initial suite of sortable attribute compounds into the specified target index,
	 * using an explicit reference key to identify the referenced entity. This overload is used when
	 * the index discriminator may not contain the correct reference key (e.g. for group-level
	 * reduced indexes where the discriminator holds the group PK instead of the referenced entity PK).
	 *
	 * @param locale                      the locale context for the operation, which may be null;
	 *                                    if null, the operation is performed without locale-specific considerations
	 * @param targetIndex                 the target entity index where the sortable attribute compounds will be inserted;
	 *                                    it must not be null
	 * @param referenceKey                the explicit reference key to use for the reduced entity index lookup;
	 *                                    when non-null it overrides the key extracted from the index discriminator
	 * @param existingDataSupplierFactory the factory to create suppliers that provide existing
	 *                                    data used during the insert; it must not be null
	 */
	private void insertInitialSuiteOfSortableAttributeCompounds(
		@Nullable Locale locale,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityIndex targetIndex,
		@Nullable ReferenceKey referenceKey,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		if (targetIndex instanceof AbstractReducedEntityIndex reducedEntityIndex &&
			targetIndex.getIndexKey().discriminator() instanceof RepresentativeReferenceKey representativeKey) {
			final ReferenceKey effectiveReferenceKey = referenceKey != null
				? referenceKey : representativeKey.referenceKey();
			final ReferenceSchemaContract referenceSchema = entitySchema
				.getReferenceOrThrowException(effectiveReferenceKey.referenceName());
			if (ReferenceIndexMutator.isIndexedReferenceForFilteringAndPartitioning(referenceSchema, getScope())) {
				ReferenceIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
					this,
					referenceSchema,
					reducedEntityIndex,
					effectiveReferenceKey,
					locale,
					existingDataSupplierFactory
				);
			}
		} else {
			AttributeIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
				this,
				null,
				targetIndex,
				locale,
				new EntitySchemaAttributeAndCompoundSchemaProvider(entitySchema),
				entitySchema,
				existingDataSupplierFactory.getNormalizedEntityAttributeValueSupplier()
			);
		}
	}

	/**
	 * Removes an entire suite of sortable attribute compounds from the specified entity index.
	 *
	 * @param locale                      The locale for which the attribute compounds should be removed.
	 *                                    This may be null if locale-specific processing is not required.
	 * @param targetIndex                 The target entity index from which the sortable attribute
	 *                                    compounds will be removed. Must not be null.
	 * @param existingDataSupplierFactory A factory for creating suppliers of existing data
	 *                                    that may be used to assist in the removal process.
	 *                                    Must not be null.
	 */
	private void removeEntireSuiteOfSortableAttributeCompounds(
		@Nullable Locale locale,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityIndex targetIndex,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		removeEntireSuiteOfSortableAttributeCompounds(
			locale, entitySchema, targetIndex, null, existingDataSupplierFactory
		);
	}

	/**
	 * Removes an entire suite of sortable attribute compounds from the specified entity index,
	 * using an explicit reference key to identify the referenced entity. This overload is used when
	 * the index discriminator may not contain the correct reference key (e.g. for group-level
	 * reduced indexes where the discriminator holds the group PK instead of the referenced entity PK).
	 *
	 * @param locale                      the locale for which the attribute compounds should be removed;
	 *                                    this may be null if locale-specific processing is not required
	 * @param targetIndex                 the target entity index from which the sortable attribute
	 *                                    compounds will be removed; must not be null
	 * @param referenceKey                the explicit reference key to use for the reduced entity index lookup;
	 *                                    when non-null it overrides the key extracted from the index discriminator
	 * @param existingDataSupplierFactory a factory for creating suppliers of existing data
	 *                                    that may be used to assist in the removal process;
	 *                                    must not be null
	 */
	private void removeEntireSuiteOfSortableAttributeCompounds(
		@Nullable Locale locale,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityIndex targetIndex,
		@Nullable ReferenceKey referenceKey,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		ReferenceSchemaContract referenceSchema = null;
		if (targetIndex instanceof AbstractReducedEntityIndex reducedEntityIndex &&
			targetIndex.getIndexKey().discriminator() instanceof RepresentativeReferenceKey representativeKey) {
			final ReferenceKey effectiveReferenceKey = referenceKey != null
				? referenceKey : representativeKey.referenceKey();
			final String referenceName = effectiveReferenceKey.referenceName();
			// same contract as the previous getReference(..).orElseThrow(..) chain, without the Optional and the lambda
			referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
			if (ReferenceIndexMutator.isIndexedReferenceForFilteringAndPartitioning(referenceSchema, getScope())) {
				ReferenceIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
					this,
					referenceSchema, reducedEntityIndex,
					effectiveReferenceKey,
					locale,
					existingDataSupplierFactory
				);
			}
		} else {
			AttributeIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
				this,
				referenceSchema,
				targetIndex,
				locale,
				new EntitySchemaAttributeAndCompoundSchemaProvider(entitySchema),
				entitySchema,
				existingDataSupplierFactory.getNormalizedEntityAttributeValueSupplier()
			);
		}
	}

	/**
	 * Removes entity itself from indexes.
	 */
	private void removeEntity(int primaryKey) {
		final EntityIndex globalIndex = getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, getScope()));
		if (globalIndex.removePrimaryKey(primaryKey)) {
			// we need to remove the entity compounds rely on non-localized attributes
			// they will exist even if the attributes are not present (i.e. compounds contain only NULL values)
			final EntitySchema entitySchema = getEntitySchema();
			AttributeIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
				this,
				null,
				globalIndex,
				null,
				new EntitySchemaAttributeAndCompoundSchemaProvider(entitySchema),
				entitySchema,
				getStoragePartExistingDataFactory().getNormalizedEntityAttributeValueSupplier()
			);
		}
	}

	/**
	 * Removes entity from all indexes with passed scope.
	 *
	 * @param entity entity to be removed
	 * @param scope  scope of the entity
	 */
	public void removeEntityFromIndexes(@Nonnull Entity entity, @Nonnull Scope scope) {
		final GlobalEntityIndex globalIndex = (GlobalEntityIndex) getOrCreateIndex(
			new EntityIndexKey(EntityIndexType.GLOBAL, scope)
		);
		final int entityPrimaryKey = entity.getPrimaryKeyOrThrowException();
		final EntitySchema entitySchema = this.getEntitySchema();
		final EntityExistingDataFactory existingDataSupplierFactory = new EntityExistingDataFactory(
			entity, entitySchema);
		// un-index prices first - this will remove them from global and reduced indexes
		unindexAllPrices(entity, scope, globalIndex, existingDataSupplierFactory);
		// un-index references (and their attributes) - we need to do this first - before the global attributes
		unindexReferences(entity, scope, entitySchema, globalIndex, existingDataSupplierFactory);
		// un-index attributes
		unindexAllGlobalAttributes(entity, entitySchema, globalIndex, existingDataSupplierFactory);
		// un-index hierarchy (hierarchies are only in global index)
		unindexHierarchyPlacement(entityPrimaryKey, entitySchema, globalIndex);
		// remove all languages from the global indexes
		unindexLocales(entity, entitySchema, globalIndex, existingDataSupplierFactory);
		// finally, remove entity from the global index
		unindexPrimaryKey(entityPrimaryKey, scope, globalIndex);
	}

	/**
	 * Unindexes a primary key from the global entity index and potentially removes
	 * the global index if it is empty and the scope is not LIVE.
	 *
	 * @param entityPrimaryKey The primary key of the entity to be unindexed.
	 * @param scope            The scope indicating the context in which the method is called.
	 * @param globalIndex      The global entity index from which the primary key is to be removed.
	 */
	@SuppressWarnings("MethodMayBeStatic")
	private void unindexPrimaryKey(
		int entityPrimaryKey,
		@Nonnull Scope scope,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		Assert.isPremiseValid(
			globalIndex.getIndexKey().scope() == scope,
			"Scope of the global index must match the provided scope! " +
				"Provided scope: " + scope + ", global index scope: " +
				globalIndex.getIndexKey().scope() + "."
		);
		globalIndex.removePrimaryKey(entityPrimaryKey);
	}

	/**
	 * Unindexes the locales associated with the provided entity.
	 * This method iterates through each locale in the entity and ensures that
	 * the locale is removed from the global index.
	 *
	 * @param entity                      The entity whose locales are to be unindexed.
	 * @param entitySchema                The schema of the entity for which locales are being unindexed.
	 * @param globalIndex                 The global index from which the locales should be removed.
	 * @param existingDataSupplierFactory Factory to supply existing data related to the entity.
	 */
	private void unindexLocales(
		@Nonnull Entity entity,
		@Nonnull EntitySchema entitySchema,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull EntityExistingDataFactory existingDataSupplierFactory
	) {
		final int epk = entity.getPrimaryKeyOrThrowException();
		for (Locale locale : entity.getAttributeLocales()) {
			removeEntityAttributeLocaleInTargetIndex(locale, entitySchema, globalIndex, existingDataSupplierFactory);
		}
		for (Locale locale : entity.getLocales()) {
			removeEntityLocaleInTargetIndex(locale, entitySchema, globalIndex, epk);
		}
	}

	/**
	 * This method is responsible for removing the hierarchy placement of an entity from a global index.
	 * The operation is only performed if the entity schema has a hierarchical structure.
	 *
	 * @param entityPrimaryKey the primary key of the entity whose hierarchy placement is to be removed
	 * @param entitySchema     the schema of the entity which dictates whether the entity supports a hierarchy
	 * @param globalIndex      the global index from which the entity's hierarchy placement will be removed
	 */
	private void unindexHierarchyPlacement(
		int entityPrimaryKey,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		if (entitySchema.isWithHierarchy()) {
			removeParent(
				this,
				globalIndex,
				entityPrimaryKey
			);
		}
	}

	/**
	 * Unindexes all global attributes for a given entity within a specified scope.
	 * This method ensures that attribute values associated with the entity are removed
	 * from the global index and other related indexes as defined by the scope and schemas.
	 *
	 * Attributes are held in these indexes:
	 *
	 * - {@link GlobalEntityIndex}
	 * - {@link AbstractReducedEntityIndex}
	 *
	 * @param entity                      The entity whose global attributes are to be unindexed.
	 * @param entitySchema                The schema contract defining the attributes and their metadata for the entity.
	 * @param globalIndex                 The global index from which attributes are to be removed.
	 * @param existingDataSupplierFactory A factory providing access to existing data needed for attribute removal.
	 */
	private void unindexAllGlobalAttributes(
		@Nonnull Entity entity,
		@Nonnull EntitySchema entitySchema,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull EntityExistingDataFactory existingDataSupplierFactory
	) {
		for (final AttributeValue attributeValue : entity.getAttributeValues()) {
			if (!attributeValue.exists()) {
				continue;
			}
			final AttributeKey key = attributeValue.key();
			AttributeIndexMutator.executeAttributeRemoval(
				this,
				null,
				new EntitySchemaAttributeAndCompoundSchemaProvider(entitySchema),
				existingDataSupplierFactory.getNormalizedEntityAttributeValueSupplier(),
				globalIndex,
				globalIndex,
				key,
				true,
				true
			);
			existingDataSupplierFactory.registerRemoval(key);
		}

		// unindex all non-localized attribute compounds
		removeEntireSuiteOfSortableAttributeCompounds(
			null, entitySchema, globalIndex, existingDataSupplierFactory
		);
	}

	/**
	 * Unindexes all references and their attributes for the given entity within the specified scope. Reference
	 * attributes are held in these indexes:
	 *
	 * - {@link ReferencedTypeEntityIndex}
	 * - {@link AbstractReducedEntityIndex}
	 *
	 * @param entity                      The entity whose references are to be unindexed
	 * @param scope                       The scope in which the references are to be unindexed
	 * @param entitySchema                The schema contract of the entity
	 * @param globalIndex                 The global entity index used for unindexing
	 * @param existingDataSupplierFactory A factory for supplying existing data required for unindexing
	 */
	private void unindexReferences(
		@Nonnull Entity entity,
		@Nonnull Scope scope,
		@Nonnull EntitySchema entitySchema,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull EntityExistingDataFactory existingDataSupplierFactory
	) {
		final int epk = entity.getPrimaryKeyOrThrowException();
		// references come in name-sorted runs, so the schema resolved for the previous reference is almost always
		// the one needed again - resolve it, and the facts derived from it, once per run instead of once per
		// reference. The scope is fixed for the whole loop and the schema is immutable, so all three facts can
		// only change where the run boundary moves. The `indexedForFiltering` guards below keep the evaluation
		// order identical to the per-reference form they replace
		ReferenceSchemaContract lastResolvedReferenceSchema = null;
		boolean indexedForFiltering = false;
		boolean indexedForEntityComponent = false;
		boolean indexedForGroupComponent = false;
		for (ReferenceContract reference : entity.getReferences()) {
			if (reference.exists()) {
				final ReferenceKey referenceKey = reference.getReferenceKey();
				if (lastResolvedReferenceSchema == null ||
					!lastResolvedReferenceSchema.getName().equals(referenceKey.referenceName())) {
					lastResolvedReferenceSchema =
						entitySchema.getReferenceOrThrowException(referenceKey.referenceName());
					indexedForFiltering = ReferenceIndexMutator.isIndexedReferenceForFiltering(
						lastResolvedReferenceSchema, scope
					);
					indexedForEntityComponent = indexedForFiltering &&
						ReferenceIndexMutator.isIndexedForEntityComponent(lastResolvedReferenceSchema, scope);
					indexedForGroupComponent = indexedForFiltering &&
						ReferenceIndexMutator.isIndexedForGroupComponent(lastResolvedReferenceSchema, scope);
				}
				final ReferenceSchemaContract referenceSchema = lastResolvedReferenceSchema;
				final RepresentativeReferenceKey rrk = getRepresentativeReferenceKey(
					epk, globalIndex, referenceKey, referenceSchema, true
				);
				if (indexedForFiltering) {
					final Integer groupId = extractActiveGroupPrimaryKey(reference);
					// histogram: remove histogram entries before facet/component cleanup
					ReferenceIndexMutator.removeHistogramFromIndex(
						this, referenceKey, groupId, epk, scope
					);
					// global: always — remove facet from global index
					ReferenceIndexMutator.referenceRemovalGlobal(
						epk, referenceSchema, globalIndex, referenceKey,
						this
					);
					// entity component: entity type index + entity reduced index (only when configured)
					if (indexedForEntityComponent) {
						final ReferencedTypeEntityIndex referenceTypeIndex =
							ReferenceIndexMutator.getOrCreateReferencedTypeEntityIndex(
								this, referenceKey.referenceName(), scope
							);
						final ReducedEntityIndex mainReferenceIndex =
							ReferenceIndexMutator.getOrCreateReferencedEntityIndex(this, rrk, scope);
						ReferenceIndexMutator.referenceRemovalPerComponent(
							epk, entitySchema, referenceSchema, this,
							referenceTypeIndex, mainReferenceIndex, referenceKey,
							referenceKey.primaryKey(),
							existingDataSupplierFactory
						);
					}
					// group component: independent — group type index + group reduced index
					if (indexedForGroupComponent) {
						removeFromGroupIndexes(
							epk, entitySchema, referenceSchema, rrk, referenceKey,
							reference, scope, existingDataSupplierFactory
						);
					}
				}
			}
		}
	}

	/**
	 * Unindexes all the prices associated with the given entity within the specified scope.
	 *
	 * @param entity                      the entity whose prices need to be unindexed
	 * @param scope                       the scope within which the prices should be unindexed
	 * @param globalIndex                 the global index from which prices should be removed
	 * @param existingDataSupplierFactory a factory to obtain existing data required for price removal
	 */
	private void unindexAllPrices(
		@Nonnull Entity entity,
		@Nonnull Scope scope,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull EntityExistingDataFactory existingDataSupplierFactory
	) {
		final EntityPriceSupplier priceSupplier = existingDataSupplierFactory.getPriceSupplier();
		final ReferenceSupplier referenceSupplier = new EntityIndexedReferenceSupplier(entity, scope);

		final TriConsumer<ReferenceSchemaContract, EntityIndex, PriceWithInternalIds> priceRemovalOperation =
			(referenceSchema, index, price) -> PriceIndexMutator.priceRemove(
				this,
				referenceSchema,
				index,
				price.priceKey(),
				price,
				entity.getPriceInnerRecordHandling()
			);

		priceSupplier.getExistingPrices()
			.filter(Droppable::exists)
			.forEach(
				price -> {
					// first remove from reduced indexes, because they consult the super index
					applyOnExistingReducedIndexes(
						scope,
						referenceSupplier,
						(referenceSchema, index) -> {
							if (ReferenceIndexMutator.isIndexedReferenceFor(
								referenceSchema, scope,
								ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING
							)) {
								priceRemovalOperation.accept(referenceSchema, index, price);
							}
						}
					);
					// then remove from the super index
					//noinspection DataFlowIssue
					priceRemovalOperation.accept(null, globalIndex, price);
					priceSupplier.registerRemoval(price.priceKey());
				}
			);
	}

	/**
	 * Add entity to all indexes with passed scope.
	 *
	 * @param entity entity to be added
	 * @param scope  scope of the entity
	 */
	public void addEntityToIndexes(@Nonnull Entity entity, @Nonnull Scope scope) {
		final GlobalEntityIndex globalIndex = (GlobalEntityIndex) getOrCreateIndex(
			new EntityIndexKey(EntityIndexType.GLOBAL, scope)
		);
		final int entityPrimaryKey = entity.getPrimaryKeyOrThrowException();
		final EntitySchema entitySchema = this.getEntitySchema();
		final EntityExistingDataFactory existingDataSupplierFactory = new EntityExistingDataFactory(
			entity, entitySchema);
		// add entity from to the global index
		indexEntity(entityPrimaryKey, globalIndex);
		// add all languages to the indexes
		indexAllLocales(entity, entitySchema, globalIndex, existingDataSupplierFactory);
		// index attributes
		indexAllGlobalAttributes(entity, entitySchema, globalIndex, existingDataSupplierFactory);
		// index hierarchy (hierarchies are only in global index)
		indexHierarchyPlacement(entity, entitySchema, globalIndex);
		// index prices
		indexAllPrices(entity, scope, globalIndex, existingDataSupplierFactory);
		// index references (and their attributes)
		indexAllReferences(entity, scope, entitySchema, globalIndex, existingDataSupplierFactory);
	}

	/**
	 * Indexes all locales for the given entity by invoking the method to upsert language-specific entities
	 * into the target index.
	 *
	 * @param entity                      The entity containing locales to be indexed.
	 * @param entitySchema                The schema of the entity providing structure and constraints.
	 * @param globalIndex                 The global index where language-specific entries will be upserted.
	 * @param existingDataSupplierFactory Factory to supply existing data necessary for the upsert operation.
	 */
	private void indexAllLocales(
		@Nonnull Entity entity,
		@Nonnull EntitySchema entitySchema,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull EntityExistingDataFactory existingDataSupplierFactory
	) {
		final int epk = entity.getPrimaryKeyOrThrowException();
		for (Locale locale : entity.getAttributeLocales()) {
			upsertEntityAttributeLocaleInTargetIndex(locale, entitySchema, globalIndex, existingDataSupplierFactory);
		}
		for (Locale locale : entity.getLocales()) {
			upsertEntityLocaleInTargetIndex(locale, entitySchema, globalIndex, epk);
		}
	}

	/**
	 * Indexes all global attributes for a given entity. Attributes are held in these indexes:
	 *
	 * - {@link GlobalEntityIndex}
	 * - {@link AbstractReducedEntityIndex}
	 *
	 * @param entity                      The entity containing attributes to be indexed.
	 * @param entitySchema                The schema contract of the entity providing attribute definitions.
	 * @param globalIndex                 The global index where attributes will be upserted.
	 * @param existingDataSupplierFactory The factory supplying existing data for the entity.
	 */
	private void indexAllGlobalAttributes(
		@Nonnull Entity entity,
		@Nonnull EntitySchema entitySchema,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull EntityExistingDataFactory existingDataSupplierFactory
	) {
		// now index all attributes
		for (final AttributeValue attributeValue : entity.getAttributeValues()) {
			if (!attributeValue.exists()) {
				continue;
			}
			final AttributeKey key = attributeValue.key();
			AttributeIndexMutator.executeAttributeUpsert(
				this,
				null,
				new EntitySchemaAttributeAndCompoundSchemaProvider(entitySchema),
				ExistingAttributeValueSupplier.NO_EXISTING_VALUE_SUPPLIER,
				globalIndex,
				globalIndex,
				key,
				attributeValue.valueOrThrowException(),
				true,
				false
			);
		}

		// index all non-localized attribute compounds
		insertInitialSuiteOfSortableAttributeCompounds(
			null, entitySchema, globalIndex, existingDataSupplierFactory
		);
	}

	/**
	 * Indexes the hierarchy placement of an entity within a global entity index.
	 *
	 * @param entity       the entity whose hierarchy placement needs to be indexed
	 * @param entitySchema the schema contract of the entity
	 * @param globalIndex  the global entity index where the hierarchy placement will be indexed
	 */
	private void indexHierarchyPlacement(
		@Nonnull Entity entity,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		if (entitySchema.isWithHierarchy() && entity.getParent().isPresent()) {
			setParent(
				this,
				globalIndex,
				entity.getPrimaryKeyOrThrowException(),
				entity.getParent().getAsInt()
			);
		}
	}

	/**
	 * Indexes all prices using the provided scope, global index, and data supplier factory.
	 * This method retrieves the price data using the existingDataSupplierFactory and
	 * applies the price upsert operation on the global index and reduced indexes.
	 *
	 * @param scope                       The scope within which prices should be indexed.
	 * @param globalIndex                 The global index where all prices are indexed primarily.
	 * @param existingDataSupplierFactory The factory used to obtain existing data suppliers, including price suppliers.
	 */
	private void indexAllPrices(
		@Nonnull Entity entity,
		@Nonnull Scope scope,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull EntityExistingDataFactory existingDataSupplierFactory
	) {
		final EntityPriceSupplier priceSupplier = existingDataSupplierFactory.getPriceSupplier();
		final ReferenceSupplier referenceSupplier = new EntityIndexedReferenceSupplier(entity, scope);

		final TriConsumer<ReferenceSchemaContract, EntityIndex, PriceWithInternalIds> priceUpsertOperation =
			(referenceSchema, index, price) -> PriceIndexMutator.priceUpsert(
				this,
				referenceSchema,
				index,
				price.priceKey(),
				price.innerRecordId(),
				price.validity(),
				price.priceWithoutTax(),
				price.priceWithTax(),
				price.indexed(),
				null,
				priceSupplier.getPriceInnerRecordHandling(),
				PriceIndexMutator.createPriceProvider(price)
			);
		priceSupplier.getExistingPrices()
			.filter(Droppable::exists)
			.forEach(
				price -> {
					//noinspection DataFlowIssue
					priceUpsertOperation.accept(null, globalIndex, price);
					applyOnExistingReducedIndexes(
						scope,
						referenceSupplier,
						(referenceSchema, index) -> {
							if (ReferenceIndexMutator.isIndexedReferenceFor(
								referenceSchema, scope,
								ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING
							)) {
								priceUpsertOperation.accept(referenceSchema, index, price);
							}
						}
					);
				}
			);
	}

	/**
	 * Indexes all references and reference attributes of a given entity within the specified scope and schema by
	 * updating the global index and any relevant entity indexes. Reference attributes are held in these indexes:
	 *
	 * - {@link ReferencedTypeEntityIndex}
	 * - {@link AbstractReducedEntityIndex}
	 *
	 * @param entity                      The entity containing references to be indexed.
	 * @param scope                       The scope within which indexing is to be performed.
	 * @param entitySchema                A contract that provides the structure and rules for the entity schema.
	 * @param globalIndex                 The global index that maintains references to all entities.
	 * @param existingDataSupplierFactory Factory to supply existing data needed for indexing.
	 */
	private void indexAllReferences(
		@Nonnull Entity entity,
		@Nonnull Scope scope,
		@Nonnull EntitySchema entitySchema,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull EntityExistingDataFactory existingDataSupplierFactory
	) {
		final int epk = entity.getPrimaryKeyOrThrowException();
		// references come in name-sorted runs, so the schema resolved for the previous reference is almost always
		// the one needed again - resolve it, and the facts derived from it, once per run instead of once per
		// reference. The scope is fixed for the whole loop and the schema is immutable, so all four facts can
		// only change where the run boundary moves. The guards below keep the evaluation order identical to the
		// per-reference form they replace - `faceted` in particular is only ever evaluated under the same
		// condition that guarded it before
		ReferenceSchemaContract lastResolvedReferenceSchema = null;
		boolean indexedForFiltering = false;
		boolean indexedForEntityComponent = false;
		boolean indexedForGroupComponent = false;
		boolean faceted = false;
		for (ReferenceContract reference : entity.getReferences()) {
			if (reference.exists()) {
				final ReferenceKey referenceKey = reference.getReferenceKey();
				if (lastResolvedReferenceSchema == null ||
					!lastResolvedReferenceSchema.getName().equals(referenceKey.referenceName())) {
					lastResolvedReferenceSchema =
						entitySchema.getReferenceOrThrowException(referenceKey.referenceName());
					indexedForFiltering = ReferenceIndexMutator.isIndexedReferenceForFiltering(
						lastResolvedReferenceSchema, scope
					);
					indexedForEntityComponent = indexedForFiltering &&
						ReferenceIndexMutator.isIndexedForEntityComponent(lastResolvedReferenceSchema, scope);
					indexedForGroupComponent = indexedForFiltering &&
						ReferenceIndexMutator.isIndexedForGroupComponent(lastResolvedReferenceSchema, scope);
					faceted = indexedForEntityComponent &&
						lastResolvedReferenceSchema.isFacetedInScope(scope);
				}
				final ReferenceSchemaContract referenceSchema = lastResolvedReferenceSchema;
				final RepresentativeReferenceKey rrk = getRepresentativeReferenceKey(
					epk, globalIndex, referenceKey, referenceSchema, true
				);
				if (indexedForFiltering) {
					final Integer groupId = extractActiveGroupPrimaryKey(reference);
					// prevent referenceInsertGlobal from deferring a histogram add —
					// indexAllReferences handles histogram directly with the correct groupId
					if (this.referencesWithDeferredHistogramAdd.isEmpty()) {
						this.referencesWithDeferredHistogramAdd = CollectionUtils.createHashSet(4);
					}
					this.referencesWithDeferredHistogramAdd.add(referenceKey);
					// global: always — add facet to global index
					ReferenceIndexMutator.referenceInsertGlobal(
						epk, referenceSchema, globalIndex, referenceKey, groupId,
						this
					);
					// entity component: entity type index + entity reduced index (only when configured)
					if (indexedForEntityComponent) {
						final ReferencedTypeEntityIndex referenceTypeIndex =
							ReferenceIndexMutator.getOrCreateReferencedTypeEntityIndex(
								this, rrk.referenceName(), scope
							);
						final ReducedEntityIndex mainReferenceIndex =
							ReferenceIndexMutator.getOrCreateReferencedEntityIndex(
								this, rrk, scope
							);
						ReferenceIndexMutator.referenceInsertPerComponent(
							epk, entitySchema, referenceSchema, this,
							referenceTypeIndex, mainReferenceIndex, referenceKey,
							referenceKey.primaryKey(), groupId,
							existingDataSupplierFactory
						);
						// cross-reference facet indexing: only when entity component is enabled
						if (faceted) {
							for (ReferenceContract otherRef : entity.getReferences()) {
								if (ReferenceKey.FULL_COMPARATOR.compare(
									rrk.referenceKey(), otherRef.getReferenceKey()
								) != 0) {
									ReferenceIndexMutator.addFacetToIndex(
										mainReferenceIndex,
										referenceSchema,
										referenceKey,
										groupId,
										epk,
										this
									);
								}
							}
						}
					}
					// group component: independent — group type index + group reduced index
					if (groupId != null && indexedForGroupComponent) {
						insertIntoGroupIndexes(
							epk, entitySchema, referenceSchema, rrk, referenceKey,
							groupId, scope, existingDataSupplierFactory
						);
					}
					// histogram: add histogram entries after facet/component setup
					ReferenceIndexMutator.addHistogramToIndex(
						this, referenceKey, groupId, epk,
						existingDataSupplierFactory.getNormalizedReferenceAttributeValueSupplier(referenceKey),
						scope
					);
				}
			}
		}
	}

	/**
	 * Applies passed consumer function on all {@link AbstractReducedEntityIndex} related to currently existing
	 * {@link ReferenceContract} of the entity.
	 */
	private void applyOnExistingReducedIndexes(
		@Nonnull Scope scope,
		@Nonnull ReferenceSupplier referenceSupplier,
		@Nonnull BiConsumer<ReferenceSchemaContract, EntityIndex> entityIndexConsumer
	) {
		final EntitySchema entitySchema = getEntitySchema();
		final AtomicReference<ReferenceSchemaContract> referenceSchema = new AtomicReference<>();
		referenceSupplier
			.getReferenceKeys()
			.forEach(refKey -> {
				ReferenceSchemaContract theReferenceSchema = referenceSchema.get();
				if (theReferenceSchema == null || !Objects.equals(
					refKey.referenceName(), theReferenceSchema.getName())) {
					theReferenceSchema = entitySchema.getReferenceOrThrowException(refKey.referenceName());
					referenceSchema.set(theReferenceSchema);
				}
				// entity reduced indexes (only when entity component is configured)
				if (ReferenceIndexMutator.isIndexedForEntityComponent(theReferenceSchema, scope)) {
					final List<ReducedEntityIndex> allReducedIndexes =
						ContainerizedLocalMutationExecutor.getAllReducedIndexes(
							eik -> ReferenceIndexMutator.getOrCreateReferencedTypeEntityIndex(this, eik),
							eik -> (ReducedEntityIndex) getOrCreateIndex(eik),
							epk -> getIndexByPrimaryKey(epk).map(ReducedEntityIndex.class::cast).orElse(null),
							scope,
							theReferenceSchema.getName(),
							refKey.primaryKey(),
							theReferenceSchema.getCardinality().allowsDuplicates(),
							EntityIndexType.REFERENCED_ENTITY_TYPE,
							EntityIndexType.REFERENCED_ENTITY
						);
					for (ReducedEntityIndex reducedIndex : allReducedIndexes) {
						entityIndexConsumer.accept(theReferenceSchema, reducedIndex);
					}
				}
				// group reduced indexes
				if (ReferenceIndexMutator.isIndexedForGroupComponent(theReferenceSchema, scope)) {
					final ReferenceContract reference = getReferencesStoragePart()
						.findReference(refKey)
						.orElse(null);
					if (reference != null) {
						final Integer groupPK = extractActiveGroupPrimaryKey(reference);
						if (groupPK != null) {
							final List<ReducedGroupEntityIndex> groupReducedIndexes =
								ContainerizedLocalMutationExecutor.getAllReducedIndexes(
									eik -> ReferenceIndexMutator.getOrCreateReferencedTypeEntityIndex(this, eik),
									eik -> (ReducedGroupEntityIndex) getOrCreateIndex(eik),
									epk -> getIndexByPrimaryKey(epk).map(ReducedGroupEntityIndex.class::cast)
										.orElse(null),
									scope,
									theReferenceSchema.getName(),
									groupPK,
									// always use type index path to locate all reduced group indexes for a given group PK
									true,
									EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE,
									EntityIndexType.REFERENCED_GROUP_ENTITY
								);
							for (AbstractReducedEntityIndex groupIndex : groupReducedIndexes) {
								entityIndexConsumer.accept(theReferenceSchema, groupIndex);
							}
						}
					}
				}
			});
	}

	/**
	 * Processes all mutations that target entity references - e.g. {@link ReferenceMutation}. This method
	 * alters contents of the primary indexes - i.e. global index, reference type and referenced entity index for
	 * the particular referenced entity.
	 *
	 * Dispatches to a dedicated handler per mutation type:
	 *
	 * - {@link SetReferenceGroupMutation} → {@link #updateReferenceOnSetGroup}
	 * - {@link RemoveReferenceGroupMutation} → {@link #updateReferenceOnRemoveGroup}
	 * - {@link ReferenceAttributeMutation} → {@link #updateReferenceOnAttributeChange}
	 * - {@link InsertReferenceMutation} → {@link #updateReferenceOnInsert}
	 * - {@link RemoveReferenceMutation} → {@link #updateReferenceOnRemoval}
	 */
	public void updateReferences(
		@Nonnull ReferenceMutation<?> referenceMutation,
		@Nonnull GlobalEntityIndex entityIndex
	) {
		final Scope scope = getScope();
		final int epk = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.NEW);
		final ReferenceKey referenceKey = referenceMutation.getReferenceKey();
		final ReferenceSchema referenceSchema = getEntitySchema().getReferenceOrThrowException(
			referenceKey.referenceName());
		final boolean entityComponentEnabled = ReferenceIndexMutator.isIndexedForEntityComponent(
			referenceSchema, scope);
		final boolean groupIndexingEnabled = ReferenceIndexMutator.isIndexedForGroupComponent(referenceSchema, scope);

		if (referenceMutation instanceof SetReferenceGroupMutation m) {
			updateReferenceOnSetGroup(
				m, entityIndex, epk, referenceKey, referenceSchema,
				entityComponentEnabled, groupIndexingEnabled, scope
			);
		} else if (referenceMutation instanceof RemoveReferenceGroupMutation m) {
			updateReferenceOnRemoveGroup(
				m, entityIndex, epk, referenceKey, referenceSchema,
				entityComponentEnabled, groupIndexingEnabled, scope
			);
		} else if (referenceMutation instanceof ReferenceAttributeMutation m) {
			updateReferenceOnAttributeChange(
				m, entityIndex, epk, referenceKey, referenceSchema,
				entityComponentEnabled, groupIndexingEnabled, scope
			);
		} else if (referenceMutation instanceof InsertReferenceMutation) {
			updateReferenceOnInsert(
				entityIndex, epk, referenceKey, referenceSchema,
				entityComponentEnabled, scope
			);
		} else if (referenceMutation instanceof RemoveReferenceMutation) {
			updateReferenceOnRemoval(
				entityIndex, epk, referenceKey, referenceSchema,
				entityComponentEnabled, groupIndexingEnabled, scope
			);
		} else {
			throw new GenericEvitaInternalError("Unknown mutation: " + referenceMutation.getClass());
		}
	}

	/**
	 * Handles {@link SetReferenceGroupMutation}: updates the facet group assignment in global
	 * and entity-component indexes, transfers group indexes, and defers facet expression
	 * re-evaluation and histogram group transfer.
	 */
	private void updateReferenceOnSetGroup(
		@Nonnull SetReferenceGroupMutation mutation,
		@Nonnull GlobalEntityIndex entityIndex,
		int epk,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ReferenceSchema referenceSchema,
		boolean entityComponentEnabled,
		boolean groupIndexingEnabled,
		@Nonnull Scope scope
	) {
		final RepresentativeReferenceKey rrk = getRepresentativeReferenceKey(
			epk, entityIndex, referenceKey, referenceSchema, true
		);
		// update facet group in global index (always)
		ReferenceIndexMutator.setFacetGroupInIndex(
			epk, entityIndex, referenceSchema,
			mutation.getReferenceKey(),
			mutation.getGroupPrimaryKey(),
			this
		);
		// entity component: update facet group in entity reduced index
		if (entityComponentEnabled) {
			final ReducedEntityIndex referenceIndex =
				ReferenceIndexMutator.getOrCreateReferencedEntityIndex(this, rrk, scope);
			ReferenceIndexMutator.setFacetGroupInIndex(
				epk, referenceIndex, referenceSchema,
				mutation.getReferenceKey(),
				mutation.getGroupPrimaryKey(),
				this
			);
		}
		// defer histogram group transfer while cardinality data is still present
		deferHistogramGroupTransfer(referenceKey, mutation.getGroupPrimaryKey(), epk, scope);
		// group component: remove old group index, create new group index
		if (groupIndexingEnabled) {
			final EntitySchema entitySchema = getEntitySchema();
			final EntityStoragePartExistingDataFactory existingStoragePartFactory = getStoragePartExistingDataFactory();
			// remove from old group indexes (if old group existed)
			removeFromGroupIndexes(
				epk, entitySchema, referenceSchema, rrk, referenceKey,
				scope, existingStoragePartFactory
			);
			// insert into new group indexes
			final int newGroupPK = mutation.getGroupPrimaryKey();
			insertIntoGroupIndexes(
				epk, entitySchema, referenceSchema, rrk, referenceKey,
				newGroupPK, scope, existingStoragePartFactory
			);
		}
		// defer facet expression re-evaluation
		deferFacetExpressionReEvaluation(entityIndex, epk, referenceKey.referenceName());
	}

	/**
	 * Handles {@link RemoveReferenceGroupMutation}: removes the facet group assignment from
	 * global and entity-component indexes, cleans up group indexes, and defers facet expression
	 * re-evaluation and histogram transfer to ungrouped state.
	 */
	private void updateReferenceOnRemoveGroup(
		@Nonnull RemoveReferenceGroupMutation mutation,
		@Nonnull GlobalEntityIndex entityIndex,
		int epk,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ReferenceSchema referenceSchema,
		boolean entityComponentEnabled,
		boolean groupIndexingEnabled,
		@Nonnull Scope scope
	) {
		final RepresentativeReferenceKey rrk = getRepresentativeReferenceKey(
			epk, entityIndex, referenceKey, referenceSchema, true
		);
		// update facet group in global index (always)
		ReferenceIndexMutator.removeFacetGroupInIndex(
			epk, entityIndex, referenceSchema,
			mutation.getReferenceKey(),
			this
		);
		// entity component: update facet group in entity reduced index
		if (entityComponentEnabled) {
			final ReducedEntityIndex referenceIndex =
				ReferenceIndexMutator.getOrCreateReferencedEntityIndex(this, rrk, scope);
			ReferenceIndexMutator.removeFacetGroupInIndex(
				epk, referenceIndex, referenceSchema,
				mutation.getReferenceKey(),
				this
			);
		}
		// defer histogram group transfer while cardinality data is still present
		deferHistogramGroupTransfer(referenceKey, null, epk, scope);
		// group component: remove from group indexes
		if (groupIndexingEnabled) {
			removeFromGroupIndexes(
				epk, getEntitySchema(), referenceSchema, rrk, referenceKey,
				scope, getStoragePartExistingDataFactory()
			);
		}
		// defer facet expression re-evaluation
		deferFacetExpressionReEvaluation(entityIndex, epk, referenceKey.referenceName());
	}

	/**
	 * Handles {@link ReferenceAttributeMutation}: updates reference attributes in entity-component
	 * and group-component indexes.
	 */
	private void updateReferenceOnAttributeChange(
		@Nonnull ReferenceAttributeMutation mutation,
		@Nonnull GlobalEntityIndex entityIndex,
		int epk,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ReferenceSchema referenceSchema,
		boolean entityComponentEnabled,
		boolean groupIndexingEnabled,
		@Nonnull Scope scope
	) {
		final AttributeMutation attributeMutation = mutation.getAttributeMutation();
		final RepresentativeReferenceKey rrk = getRepresentativeReferenceKey(
			epk, entityIndex, referenceKey, referenceSchema, true
		);
		final EntityStoragePartExistingDataFactory existingStoragePartFactory = getStoragePartExistingDataFactory();
		// entity component: attribute update on entity type index + entity reduced index
		if (entityComponentEnabled) {
			final ReferencedTypeEntityIndex referenceTypeIndex =
				ReferenceIndexMutator.getOrCreateReferencedTypeEntityIndex(
					this, referenceKey.referenceName(), scope
				);
			final ReducedEntityIndex referenceIndex =
				ReferenceIndexMutator.getOrCreateReferencedEntityIndex(this, rrk, scope);
			ReferenceIndexMutator.attributeUpdate(
				this, existingStoragePartFactory,
				referenceTypeIndex,
				// we may pass the same index for both removal and upsert, because in
				// method `getRepresentativeReferenceKey` all data are migrated already
				referenceIndex, referenceIndex,
				referenceSchema, referenceKey, attributeMutation
			);
		}
		// group component: attribute update on group type index + group reduced index
		if (groupIndexingEnabled) {
			final ReferenceContract existingReference = getReferencesStoragePart()
				.findReference(referenceKey)
				.orElse(null);
			if (existingReference != null) {
				final Integer groupPK = extractActiveGroupPrimaryKey(existingReference);
				if (groupPK != null) {
					final ReferencedTypeEntityIndex groupTypeIndex =
						ReferenceIndexMutator.getOrCreateReferencedGroupTypeEntityIndex(
							this, referenceKey.referenceName(), scope
						);
					final ReducedGroupEntityIndex groupIndex =
						ReferenceIndexMutator.getOrCreateReferencedGroupEntityIndex(
							this, rrk, groupPK, scope
						);
					ReferenceIndexMutator.attributeUpdate(
						this, existingStoragePartFactory,
						groupTypeIndex,
						groupIndex, groupIndex,
						referenceSchema, referenceKey, attributeMutation
					);
				}
			}
		}
	}

	/**
	 * Handles {@link InsertReferenceMutation}: adds the facet to global and entity-component
	 * indexes, and defers facet expression re-evaluation and histogram addition.
	 *
	 * Group component is not handled here — group is always assigned later via
	 * {@link SetReferenceGroupMutation}.
	 */
	private void updateReferenceOnInsert(
		@Nonnull GlobalEntityIndex entityIndex,
		int epk,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ReferenceSchema referenceSchema,
		boolean entityComponentEnabled,
		@Nonnull Scope scope
	) {
		final EntitySchema entitySchema = getEntitySchema();
		// groupId is null at insert time — group component is handled by SetReferenceGroupMutation
		final RepresentativeReferenceKey rrk = getRepresentativeReferenceKey(
			epk, entityIndex, referenceKey, referenceSchema, false
		);
		// global: always — add facet to global index
		ReferenceIndexMutator.referenceInsertGlobal(
			epk, referenceSchema, entityIndex, referenceKey, null,
			this
		);
		// entity component: entity type index + entity reduced index
		if (entityComponentEnabled) {
			final ReferencedTypeEntityIndex referenceTypeIndex =
				ReferenceIndexMutator.getOrCreateReferencedTypeEntityIndex(
					this, referenceKey.referenceName(), scope
				);
			final ReducedEntityIndex referenceIndex =
				ReferenceIndexMutator.getOrCreateReferencedEntityIndex(this, rrk, scope);
			ReferenceIndexMutator.referenceInsertPerComponent(
				epk, entitySchema, referenceSchema, this,
				referenceTypeIndex, referenceIndex, referenceKey,
				referenceKey.primaryKey(), null,
				getStoragePartExistingDataFactory()
			);
		}
		// defer re-evaluation to after storage write so expression can build
		// the reference proxy from storage — during InsertReferenceMutation the
		// reference data is not yet persisted when addFacetToIndex evaluates the
		// trigger, so cross-entity expressions ($reference.referencedEntity.*,
		// $reference.groupEntity?.*) always return false at initial evaluation
		deferFacetExpressionReEvaluation(entityIndex, epk, referenceKey.referenceName());
		// defer histogram evaluation for same reason as facets — reference data
		// not yet persisted at this point; groupId is null at insert time
		// skipped when indexAllReferences already registered this key (handles histogram directly)
		if (!this.referencesWithDeferredHistogramAdd.contains(referenceKey)
			&& !getLocalHistogramTriggers(referenceKey.referenceName(), scope).isEmpty()) {
			deferExpressionReEvaluation(
				() -> ReferenceIndexMutator.addHistogramToIndex(
					this, referenceKey, null, epk,
					getStoragePartExistingDataFactory().getNormalizedReferenceAttributeValueSupplier(referenceKey),
					scope
				)
			);
			// track this reference so deferHistogramReEvaluationForReferenceAttribute skips its own add
			if (this.referencesWithDeferredHistogramAdd.isEmpty()) {
				this.referencesWithDeferredHistogramAdd = CollectionUtils.createHashSet(4);
			}
			this.referencesWithDeferredHistogramAdd.add(referenceKey);
		}
	}

	/**
	 * Handles {@link RemoveReferenceMutation}: removes histogram data synchronously (before
	 * reference data removal), then removes the facet from global and entity-component indexes,
	 * and cleans up group indexes.
	 *
	 * Unlike other mutation handlers, this method does not defer facet expression re-evaluation.
	 * The facet index entry is removed synchronously by
	 * {@link ReferenceIndexMutator#referenceRemovalGlobal}, so there is no stale expression
	 * state to re-evaluate.
	 */
	private void updateReferenceOnRemoval(
		@Nonnull GlobalEntityIndex entityIndex,
		int epk,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ReferenceSchema referenceSchema,
		boolean entityComponentEnabled,
		boolean groupIndexingEnabled,
		@Nonnull Scope scope
	) {
		final EntitySchema entitySchema = getEntitySchema();
		final RepresentativeReferenceKey rrk = getRepresentativeReferenceKey(
			epk, entityIndex, referenceKey, referenceSchema, true
		);
		// histogram removal: surgical removal of values contributed by the removed reference
		// (storage still has pre-mutation data at this synchronous point)
		removeHistogramWithConditionGuard(referenceKey, epk, scope);
		// global: always — remove facet from global index
		ReferenceIndexMutator.referenceRemovalGlobal(
			epk, referenceSchema, entityIndex, referenceKey,
			this
		);
		// entity component: entity type index + entity reduced index
		if (entityComponentEnabled) {
			final ReferencedTypeEntityIndex referenceTypeIndex =
				ReferenceIndexMutator.getOrCreateReferencedTypeEntityIndex(
					this, rrk.referenceName(), scope
				);
			final ReducedEntityIndex referenceIndex =
				ReferenceIndexMutator.getOrCreateReferencedEntityIndex(this, rrk, scope);
			ReferenceIndexMutator.referenceRemovalPerComponent(
				epk, entitySchema, referenceSchema, this,
				referenceTypeIndex, referenceIndex, referenceKey,
				referenceKey.primaryKey(),
				getStoragePartExistingDataFactory()
			);
		}
		// group component: remove from group indexes (if reference had a group)
		if (groupIndexingEnabled) {
			removeFromGroupIndexes(
				epk, entitySchema, referenceSchema, rrk, referenceKey,
				scope, getStoragePartExistingDataFactory()
			);
		}
	}

	/**
	 * Removes histogram entries for a reference being removed, evaluating each trigger's condition
	 * against the current (pre-mutation) storage state. Triggers whose condition evaluates to `false`
	 * are skipped — the reference never contributed to the histogram, so removing values would
	 * incorrectly decrement another reference's cardinality for the same `(value, ownerPK)` pair.
	 *
	 * Must only be called synchronously when storage still contains pre-mutation data.
	 *
	 * @param referenceKey the reference being removed
	 * @param ownerPK      the primary key of the owner entity
	 * @param scope        the current scope
	 */
	private void removeHistogramWithConditionGuard(
		@Nonnull ReferenceKey referenceKey,
		int ownerPK,
		@Nonnull Scope scope
	) {
		final Collection<HistogramExpressionTrigger> histogramTriggers =
			getLocalHistogramTriggers(referenceKey.referenceName(), scope);
		if (!histogramTriggers.isEmpty()) {
			final ReferenceContract removedRef = getReferencesStoragePart()
				.findReference(referenceKey).orElse(null);
			final Integer groupId = removedRef != null ? extractActiveGroupPrimaryKey(removedRef) : null;
			final ContainerizedLocalMutationExecutor storageAccessor =
				(ContainerizedLocalMutationExecutor) getContainerAccessor();
			final ExistingAttributeValueSupplier refAttrSupplier = getStoragePartExistingDataFactory()
				.getNormalizedReferenceAttributeValueSupplier(referenceKey);
			for (final HistogramExpressionTrigger trigger : histogramTriggers) {
				final boolean conditionMet = trigger.evaluate(
					ownerPK, referenceKey, storageAccessor, getSchemaResolver(), scope
				);
				if (conditionMet) {
					ReferenceIndexMutator.removeHistogramForTrigger(
						this, referenceKey, groupId, ownerPK, trigger, refAttrSupplier, scope
					);
				}
			}
		}
	}

	/**
	 * Defers re-evaluation of facet expression triggers for the given reference name.
	 * The deferred action runs after storage write so expressions can read updated data.
	 *
	 * This is used by mutations that modify reference data without removing the facet
	 * index entry (SetGroup, RemoveGroup, Insert). {@link RemoveReferenceMutation} does
	 * not need this because it removes the facet entry synchronously.
	 *
	 * @param entityIndex   the global entity index
	 * @param epk           entity primary key
	 * @param referenceName name of the mutated reference
	 */
	private void deferFacetExpressionReEvaluation(
		@Nonnull GlobalEntityIndex entityIndex,
		int epk,
		@Nonnull String referenceName
	) {
		if (hasFacetExpressionTriggers()) {
			deferExpressionReEvaluation(
				() -> ReferenceIndexMutator.reEvaluateFacetExpressionsInAllIndexes(
					entityIndex, this, epk,
					trigger -> referenceName.equals(trigger.getReferenceName())
				)
			);
		}
	}

	/**
	 * Defers histogram bucket transfer when a reference's group assignment changes.
	 * Resolves the old group PK from the current storage state, removes the entity
	 * from the old group's histogram bucket, and adds it to the new group's bucket.
	 *
	 * Only used by SetGroup and RemoveGroup mutations — Insert has a simpler add-only
	 * path (no old group exists), and Remove does synchronous cleanup.
	 *
	 * @param referenceKey key identifying the specific reference instance
	 * @param newGroupPK   primary key of the new group (null if removing group)
	 * @param epk          entity primary key
	 * @param scope        current indexing scope
	 */
	private void deferHistogramGroupTransfer(
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer newGroupPK,
		int epk,
		@Nonnull Scope scope
	) {
		if (!getLocalHistogramTriggers(referenceKey.referenceName(), scope).isEmpty()) {
			final ReferenceContract existingRef = getReferencesStoragePart()
				.findReference(referenceKey).orElse(null);
			final Integer oldGroupPK = existingRef != null
				? extractActiveGroupPrimaryKey(existingRef) : null;
			// eagerly resolve the storage PKs of the old group's reduced indexes while
			// the cardinality data in REFERENCED_GROUP_ENTITY_TYPE is still present —
			// removeFromGroupIndexes removes this data before the deferred lambda runs
			final int[] oldGroupStoragePKs = resolveGroupStoragePKs(
				referenceKey.referenceName(), oldGroupPK, scope
			);
			deferExpressionReEvaluation(() -> {
				if (oldGroupStoragePKs.length > 0) {
					ReferenceIndexMutator.removeHistogramFromPreResolvedGroupIndexes(
						this, referenceKey.referenceName(), referenceKey, epk, oldGroupStoragePKs, scope
					);
				} else if (oldGroupPK == null) {
					// ungrouped: value hasn't changed, only group assignment is being set
					ReferenceIndexMutator.removeHistogramIfPresent(
						this, referenceKey, null, epk, scope
					);
				}
				ReferenceIndexMutator.addHistogramToIndex(
					this, referenceKey, newGroupPK, epk,
					getStoragePartExistingDataFactory().getNormalizedReferenceAttributeValueSupplier(referenceKey),
					scope
				);
			});
		}
	}

	/**
	 * Resolves the storage primary keys of reduced group entity indexes for the given group.
	 * Must be called while the cardinality data in {@link EntityIndexType#REFERENCED_GROUP_ENTITY_TYPE}
	 * is still present (i.e., before {@code removeFromGroupIndexes} is called).
	 *
	 * @param referenceName the reference name
	 * @param groupPK       the group primary key, or null if ungrouped
	 * @param scope         the current scope
	 * @return the storage PKs of the group's reduced indexes, or empty array if none
	 */
	@Nonnull
	private int[] resolveGroupStoragePKs(
		@Nonnull String referenceName,
		@Nullable Integer groupPK,
		@Nonnull Scope scope
	) {
		if (groupPK == null) {
			return new int[0];
		}
		final EntityIndexKey groupTypeKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, scope, referenceName
		);
		final EntityIndex groupTypeIndex = getIndexIfExists(groupTypeKey);
		if (groupTypeIndex instanceof ReferencedTypeEntityIndex rtei) {
			return rtei.getAllReferenceIndexes(groupPK);
		}
		return new int[0];
	}

	/**
	 * Method inserts language for entity if entity lacks information about used language.
	 */
	private void upsertEntityLocale(
		@Nonnull Locale locale,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		final int epk = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.NEW);
		final EntityIndex globalIndex = getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, getScope()));
		upsertEntityLocaleInTargetIndex(locale, entitySchema, globalIndex, epk);
		applyOnExistingReducedIndexes(
			getScope(),
			existingDataSupplierFactory.getReferenceSupplier(),
			(referenceSchema, index) -> upsertEntityLocaleInTargetIndex(locale, entitySchema, index, epk)
		);
	}

	/**
	 * Method removes language for entity.
	 */
	private void removeEntityLocale(
		@Nonnull Locale locale,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		final int epk = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.EXISTING);
		final EntityIndex globalIndex = getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, getScope()));
		removeEntityLocaleInTargetIndex(locale, entitySchema, globalIndex, epk);
		applyOnExistingReducedIndexes(
			getScope(),
			existingDataSupplierFactory.getReferenceSupplier(),
			(referenceSchema, index) -> {
				// removal mutations happen before indexes are created and thus the created indexes will not have
				// the language set (entity already lacks the language) - so we cannot remove it for those indexes
				final RepresentativeReferenceKey referenceKey = Objects.requireNonNull(
					(RepresentativeReferenceKey) index.getIndexKey().discriminator()
				);
				if (this.createdReferences == null || !this.createdReferences.contains(referenceKey)) {
					removeEntityLocaleInTargetIndex(locale, entitySchema, index, epk);
				}
			}
		);
	}

	/**
	 * Method inserts language for entity if entity lacks information about used language.
	 */
	private void upsertEntityAttributeLocale(
		@Nonnull Locale locale,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		final EntityIndex globalIndex = getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, getScope()));
		upsertEntityAttributeLocaleInTargetIndex(locale, entitySchema, globalIndex, existingDataSupplierFactory);
		applyOnExistingReducedIndexes(
			getScope(),
			existingDataSupplierFactory.getReferenceSupplier(),
			(referenceSchema, index) ->
				upsertEntityAttributeLocaleInTargetIndex(locale, entitySchema, index, existingDataSupplierFactory)
		);
	}

	/**
	 * Method removes language for entity.
	 */
	private void removeEntityAttributeLocale(
		@Nonnull Locale locale,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		final EntityIndex globalIndex = getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, getScope()));
		removeEntityAttributeLocaleInTargetIndex(locale, entitySchema, globalIndex, existingDataSupplierFactory);
		applyOnExistingReducedIndexes(
			getScope(),
			existingDataSupplierFactory.getReferenceSupplier(),
			(referenceSchema, index) -> {
				// removal mutations happen before indexes are created and thus the created indexes will not have
				// the language set (entity already lacks the language) - so we cannot remove it for those indexes
				final RepresentativeReferenceKey referenceKey = Objects.requireNonNull(
					(RepresentativeReferenceKey) index.getIndexKey().discriminator()
				);
				if (this.createdReferences == null || !this.createdReferences.contains(referenceKey)) {
					removeEntityAttributeLocaleInTargetIndex(locale, entitySchema, index, existingDataSupplierFactory);
				}
			}
		);
	}

	/**
	 * Method processes all mutations that target entity references - e.g. {@link ReferenceMutation}. This method
	 * alters contents of the secondary indexes - i.e. all referenced entity indexes that are used in the main entity
	 * except the referenced entity index that directly connects to {@link ReferenceMutation#getReferenceKey()} because
	 * this is altered in {@link #updateReferences(ReferenceMutation, GlobalEntityIndex)} method.
	 */
	public void updateReferencesInReferenceIndex(
		@Nonnull ReferenceMutation<?> referenceMutation,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex indexForRemoval,
		@Nonnull AbstractReducedEntityIndex indexForUpsert
	) {
		if (referenceMutation instanceof SetReferenceGroupMutation upsertReferenceGroupMutation) {
			ReferenceIndexMutator.setFacetGroupInIndex(
				getPrimaryKeyToIndex(IndexType.FACET_INDEX, Target.NEW),
				indexForUpsert,
				referenceSchema,
				upsertReferenceGroupMutation.getReferenceKey(),
				upsertReferenceGroupMutation.getGroupPrimaryKey(),
				this
			);
		} else if (referenceMutation instanceof RemoveReferenceGroupMutation removeReferenceGroupMutation) {
			ReferenceIndexMutator.removeFacetGroupInIndex(
				getPrimaryKeyToIndex(IndexType.FACET_INDEX, Target.EXISTING),
				indexForRemoval,
				referenceSchema,
				removeReferenceGroupMutation.getReferenceKey(),
				this
			);
		} else if (referenceMutation instanceof ReferenceAttributeMutation) {
			// do nothing - attributes are not indexed in reduced entity index
		} else if (referenceMutation instanceof InsertReferenceMutation) {
			ReferenceIndexMutator.addFacetToIndex(
				indexForUpsert,
				referenceSchema,
				referenceMutation.getReferenceKey(),
				null,
				getPrimaryKeyToIndex(IndexType.FACET_INDEX, Target.NEW),
				this
			);
		} else if (referenceMutation instanceof RemoveReferenceMutation) {
			ReferenceIndexMutator.removeFacetInIndex(
				indexForRemoval,
				referenceSchema,
				referenceMutation.getReferenceKey(),
				getPrimaryKeyToIndex(IndexType.FACET_INDEX, Target.EXISTING),
				this
			);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new GenericEvitaInternalError("Unknown mutation: " + referenceMutation.getClass());
		}
	}

	/**
	 * Method switches inner handling strategy for the entity - e.g. {@link SetPriceInnerRecordHandlingMutation}
	 */
	public void updatePriceHandlingForEntity(
		@Nonnull SetPriceInnerRecordHandlingMutation priceHandlingMutation,
		@Nonnull GlobalEntityIndex index
	) {
		final PricesStoragePart priceStorageContainer = getContainerAccessor()
			.getPriceStoragePart(this.entityType, getPrimaryKeyToIndex(IndexType.PRICE_INDEX, Target.EXISTING));
		final PriceInnerRecordHandling originalInnerRecordHandling = priceStorageContainer.getPriceInnerRecordHandling();
		final PriceInnerRecordHandling newPriceInnerRecordHandling = priceHandlingMutation.getPriceInnerRecordHandling();

		if (originalInnerRecordHandling != newPriceInnerRecordHandling) {

			final TriConsumer<ReferenceSchemaContract, EntityIndex, EntityIndex> pricesRemoval =
				(referenceSchema, indexForRemoval, indexForUpsert) -> {
					for (PriceWithInternalIds price : priceStorageContainer.getPrices()) {
						PriceIndexMutator.priceRemove(
							this, referenceSchema, indexForRemoval, price.priceKey(),
							price,
							originalInnerRecordHandling
						);
					}
				};

			final TriConsumer<ReferenceSchemaContract, EntityIndex, EntityIndex> pricesInsertion =
				(referenceSchema, indexForRemoval, indexForUpsert) -> {
					for (PriceWithInternalIds price : priceStorageContainer.getPrices()) {
						PriceIndexMutator.priceUpsert(
							this,
							referenceSchema, indexForUpsert, price.priceKey(),
							price.innerRecordId(),
							price.validity(),
							price.priceWithoutTax(),
							price.priceWithTax(),
							price.indexed(),
							null,
							newPriceInnerRecordHandling,
							PriceIndexMutator.createPriceProvider(price)
						);
					}
				};

			// first remove data from reduced indexes (entity + group)
			// per-unique: price leaves are set-semantic — when N references share an RGEI, the per-bucket
			// `priceRemove` must fire once per unique target index, not once per reference (otherwise the
			// second reference removes from an already-destroyed bucket and throws).
			ReferenceIndexMutator.forEachUniqueReferenceIndex(
				ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING, this,
				pricesRemoval::accept, Functions.alwaysTrue(), true,
				ReferenceIndexMutator.IterationPath.BOTH
			);

			// now we can safely remove the data from a super index
			//noinspection DataFlowIssue
			pricesRemoval.accept(null, index, index);

			// next we need to add data to super index first
			//noinspection DataFlowIssue
			pricesInsertion.accept(null, index, index);

			// and then we can add data to reduced indexes (entity + group)
			// per-unique: symmetric to the removal path above — `priceUpsert` must fire once per unique
			// target bucket so a shared RGEI does not receive duplicate inserts for the same price.
			ReferenceIndexMutator.forEachUniqueReferenceIndex(
				ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING, this,
				pricesInsertion::accept, Functions.alwaysTrue(), true,
				ReferenceIndexMutator.IterationPath.BOTH
			);
		}
	}

	/**
	 * Method processes all mutations that targets entity prices - e.g. {@link PriceMutation}.
	 */
	public void updatePriceIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull PriceMutation priceMutation,
		@Nonnull EntityIndex indexForRemoval,
		@Nonnull EntityIndex indexForUpsert
	) {
		final PriceKey priceKey = priceMutation.getPriceKey();

		if (priceMutation instanceof final UpsertPriceMutation upsertPriceMutation) {
			final int theEntityPrimaryKey = this.getPrimaryKeyToIndex(IndexType.PRICE_INDEX, Target.NEW);
			PriceIndexMutator.priceUpsert(
				this,
				referenceSchema, indexForUpsert, priceKey,
				upsertPriceMutation.getInnerRecordId(),
				upsertPriceMutation.getValidity(),
				upsertPriceMutation.getPriceWithoutTax(),
				upsertPriceMutation.getPriceWithTax(),
				upsertPriceMutation.isIndexed(),
				this.getStoragePartExistingDataFactory().getPriceSupplier(),
				(thePriceKey, theInnerRecordId) -> {
					final OptionalInt existingInternalId = this.containerAccessor.findExistingInternalId(
						this.entityType, theEntityPrimaryKey, thePriceKey
					);
					// -1 value is used of old prices that were not indexed and thus do not have internal id
					// now all prices have internal id, so we can safely use -1 as a marker for non-existing price id
					if (existingInternalId.isPresent() && existingInternalId.getAsInt() != -1) {
						return existingInternalId.getAsInt();
					} else {
						final int newlyAssignedId = this.priceInternalIdSupplier.getAsInt();
						this.containerAccessor.registerAssignedPriceId(
							theEntityPrimaryKey, thePriceKey, newlyAssignedId);
						return newlyAssignedId;
					}
				}
			);
		} else if (priceMutation instanceof RemovePriceMutation) {
			PriceIndexMutator.priceRemove(
				this,
				referenceSchema, indexForRemoval, priceKey,
				this.getStoragePartExistingDataFactory().getPriceSupplier()
			);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new GenericEvitaInternalError("Unknown mutation: " + priceMutation.getClass());
		}
	}

	/**
	 * Method processes all mutations that targets hierarchy placement - e.g. {@link SetParentMutation}
	 * and {@link RemoveParentMutation}.
	 */
	public void updateHierarchyPlacement(@Nonnull ParentMutation parentMutation, @Nonnull EntityIndex index) {
		if (parentMutation instanceof final SetParentMutation setMutation) {
			setParent(
				this, index,
				getPrimaryKeyToIndex(IndexType.HIERARCHY_INDEX, Target.NEW),
				setMutation.getParentPrimaryKey()
			);
		} else if (parentMutation instanceof RemoveParentMutation) {
			removeParent(
				this, index,
				getPrimaryKeyToIndex(IndexType.HIERARCHY_INDEX, Target.EXISTING)
			);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new GenericEvitaInternalError("Unknown mutation: " + parentMutation.getClass());
		}
	}

	/**
	 * Enumeration representing the desired target of an indexing operation (either existing an index, or a new index).
	 */
	public enum Target {
		EXISTING,
		NEW
	}

	/**
	 * A record that encapsulates two {@link RepresentativeReferenceKey} objects representing
	 * a stored key and a current key. The stored key may be null, while the current key cannot
	 * be null.
	 *
	 * This class is designed to hold references that distinguish between a persisted (stored)
	 * version and an current version of a {@link RepresentativeReferenceKey} that takes current entity mutation
	 * in account.
	 *
	 * The two fields are:
	 * - {@code stored}: A nullable reference to a {@link RepresentativeReferenceKey} representing
	 * the stored state.
	 * - {@code current}: A non-null reference to a {@link RepresentativeReferenceKey} representing
	 * the current state.
	 */
	public record RepresentativeReferenceKeys(
		@Nonnull RepresentativeReferenceKey stored,
		@Nonnull RepresentativeReferenceKey current
	) {

		/**
		 * Determines whether the stored key and the current key differ.
		 *
		 * This method compares the `stored` and `current` fields to check if they reference different
		 * {@link RepresentativeReferenceKey} objects.
		 *
		 * @return {@code true} if the `stored` key is different from the `current` key,
		 * {@code false} otherwise.
		 */
		public boolean differ() {
			return !this.stored.equals(this.current);
		}
	}

	/**
	 * One schema element this entity mutation has already reported as touched, in one scope - the deduplication key
	 * of {@link #markTouched}.
	 *
	 * It is not a {@link SchemaCapabilityKey}: an element is reported once and yields up to three capabilities, so
	 * deduplicating at the element level performs one scan where a capability-level one would perform three.
	 *
	 * `capability` is the exception that keeps that true. An attribute, a compound and a reference each have **one**
	 * reporting site that files all of their capabilities together, so they deduplicate at the element level and leave
	 * it null. The entity does not: its `withHierarchy()` and `withPrice()` flags are maintained by different mutators
	 * reacting to different mutations, so a shared element-level entry would let whichever ran first swallow the
	 * other. Those rows therefore carry the capability and deduplicate per flag.
	 *
	 * @param kind          what kind of element it is - an attribute and a sortable compound may carry the same name
	 * @param containerName name of the reference declaring the element, or null when the entity declares it
	 * @param elementName   name of the element
	 * @param capability    the single flag being deduplicated, or null to deduplicate the element as a whole
	 * @param scope         the scope whose indexes were maintained
	 */
	private record TouchedSchemaElement(
		@Nonnull ElementKind kind,
		@Nullable String containerName,
		@Nonnull String elementName,
		@Nullable Capability capability,
		@Nonnull Scope scope
	) {
	}

}
