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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.expression.trigger.FacetExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramValueDescriptor;
import io.evitadb.core.expression.trigger.HistogramValueSource;
import io.evitadb.dataType.Range;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.HistogramCapableEntityIndex;
import io.evitadb.index.HistogramIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.facet.FacetGroupIndex;
import io.evitadb.index.facet.FacetIdIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor.RepresentativeReferenceKeys;
import io.evitadb.index.mutation.local.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.index.mutation.local.dataAccess.ExistingDataSupplierFactory;
import io.evitadb.index.mutation.local.dataAccess.ExistingPriceSupplier;
import io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor;
import io.evitadb.index.result.CardinalityChange;
import io.evitadb.spi.store.catalog.persistence.accessor.EntityStoragePartAccessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.evitadb.index.mutation.local.dataAccess.ExistingAttributeValueSupplier.NO_EXISTING_VALUE_SUPPLIER;
import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * A static-method interface that co-locates all reference index mutation logic, extracted from
 * {@link EntityIndexLocalMutationExecutor} to keep that class at a manageable size. All methods are
 * `static` — the interface serves purely as a namespace and grouping device, not a polymorphic contract.
 *
 * ## Index taxonomy
 *
 * References are maintained in three interconnected index types, each serving a different query pattern:
 *
 * ### Reference-type entity index (`REFERENCED_ENTITY_TYPE`)
 *
 * One index per referenced entity type (e.g. `brand`, `category`). Stores all entity attributes that appear
 * in combination with that reference type, keyed by the **referenced entity's** primary key rather than the
 * owning entity's primary key. This index powers `referenceHaving` filter look-ups across an entire
 * referenced type without scanning per-entity indexes.
 *
 * ### Referenced-entity index (`REFERENCED_ENTITY`)
 *
 * One index per (referenced entity type + referenced primary key) combination (e.g. brand #42). Holds
 * all owning-entity data (attributes, prices, facets, locales) for every entity that points at that
 * particular referenced entity instance. Optimal for queries that list all entities of a particular
 * brand or category, because the index already partitions data along those boundaries.
 *
 * ### Referenced-group indexes (`REFERENCED_GROUP_ENTITY_TYPE`, `REFERENCED_GROUP_ENTITY`)
 *
 * Mirrors of the two indexes above but keyed by the **group** primary key instead of the referenced
 * entity primary key. Only maintained when the reference schema has
 * {@link ReferenceIndexedComponents#REFERENCED_GROUP_ENTITY} enabled for the given scope.
 *
 * ## Method groups
 *
 * **Index traversal helpers** — `forEachReferenceIndex`, `forEachUniqueReferenceIndex`: iterate all
 * existing references of the current entity and invoke a {@link ReferenceIndexConsumer} callback
 * with the appropriate {@link AbstractReducedEntityIndex}. Both modes accept an
 * {@link IterationPath} that selects whether the entity-level path (`REFERENCED_ENTITY`), the
 * group-level path (`REFERENCED_GROUP_ENTITY`), or both are traversed. The "unique" mode dedups
 * consumer invocations by {@link AbstractReducedEntityIndex} identity, so callers performing
 * per-target-index work fire exactly once per unique target index even when N references share a
 * single underlying {@link ReducedGroupEntityIndex}.
 *
 * **Index accessor helpers** — `getOrCreate*Index`, `get*IndexKey`: return (creating if absent) the
 * specific {@link EntityIndex} for a given reference name, scope, and primary key.
 *
 * **Lifecycle operations** — `referenceInsert*`, `referenceRemoval*`: wire together all lower-level
 * operations needed when a reference is created or deleted. The `*Global` variants handle the global entity
 * index (facet only, called once per reference); the `*PerComponent` variants handle the type index plus
 * the reduced index (called once per indexed component — entity and/or group).
 *
 * **Facet management** — `addFacetToIndex`, `removeFacetInIndex`, `setFacetGroupInIndex`,
 * `removeFacetGroupInIndex`: add, remove or reassign facet membership for a single reference, guarded by
 * the `isFaceted` and `shouldIndexFacetToTargetIndex` predicates.
 *
 * **Attribute / compound operations** — `attributeUpdate`, `insertInitialSuiteOfSortableAttributeCompounds`,
 * `removeEntireSuiteOfSortableAttributeCompounds`: propagate attribute mutations and sortable compound
 * maintenance into the type index and the reduced entity index, handling both entity-level and
 * reference-level attribute schemas.
 *
 * **Schema predicate helpers** — `isIndexedReferenceFor`, `isIndexedReferenceForFiltering`,
 * `isIndexedReferenceForFilteringAndPartitioning`, `isIndexedForEntityComponent`,
 * `isIndexedForGroupComponent`: evaluate whether a reference schema is configured for the requested
 * indexing level or component in the given scope, used heavily as guards throughout the other methods.
 *
 * ## Thread safety
 *
 * All methods are stateless and operate on data provided via their parameters. Thread safety is delegated
 * to the {@link EntityIndexLocalMutationExecutor} and the {@link EntityIndex} implementations passed in.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface ReferenceIndexMutator {

	/**
	 * Selects which reduced-index family the traversal helpers visit.
	 *
	 * The enum constant names describe the iteration **scope** (which family of reduced indexes is
	 * walked); the `{@link EntityIndexType}` links in the per-constant docs identify the underlying
	 * index type the iteration produces. The two vocabularies intentionally differ — scope-level
	 * names are easier to reason about at call sites, while the storage type links are kept for
	 * cross-reference.
	 *
	 * `BOTH` traverses the entity path before the group path; the order is part of the contract.
	 */
	enum IterationPath {
		/** Per-reference {@link EntityIndexType#REFERENCED_ENTITY} indexes only. */
		REDUCED_ENTITY,
		/** Per-group {@link EntityIndexType#REFERENCED_GROUP_ENTITY} indexes only. */
		GROUP,
		/** Entity path then group path, in that order. */
		BOTH
	}

	/**
	 * Iterates all currently stored references on the active entity and fires the consumer **once per
	 * qualifying reference**, even when N references resolve to the same shared
	 * {@link ReducedGroupEntityIndex} (RGEI). Use this mode when the work performed by the consumer is
	 * intrinsically per-reference — facet add/remove keyed by individual reference key, sortable
	 * attribute compounds, and similar bookkeeping that is parameterised by the iterating reference.
	 *
	 * Callers whose work targets entity-level state on a shared RGEI (entity-level attribute
	 * cardinality, price set-semantic leaves, locale tracking) must use
	 * {@link #forEachUniqueReferenceIndex} instead — otherwise N sibling references would underflow
	 * counters or destroy buckets on second iteration.
	 *
	 * The consumer is invoked with the resolved {@link AbstractReducedEntityIndex} passed as both
	 * `indexForRemoval` and `indexForUpsert` (the iterators never perform a representative-key
	 * migration; only `attributeUpdate` does that, outside this iterator API).
	 *
	 * @param indexType                 minimum {@link ReferenceIndexType} level required for a
	 *                                  reference to qualify
	 * @param executor                  the mutation executor providing entity state and index access
	 * @param referenceIndexConsumer    callback invoked for each qualifying reference
	 * @param referencePredicate        additional filter applied after the schema-level check; use
	 *                                  `Functions.alwaysTrue()` to accept all matching references
	 * @param referencePresenceExpected whether the referenced/group primary key is expected to already
	 *                                  be present in the index (used to resolve the correct
	 *                                  {@link RepresentativeReferenceKey})
	 * @param path                      which path(s) to traverse — see {@link IterationPath}
	 */
	static void forEachReferenceIndex(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceIndexConsumer referenceIndexConsumer,
		@Nonnull Predicate<ReferenceContract> referencePredicate,
		boolean referencePresenceExpected,
		@Nonnull IterationPath path
	) {
		switch (path) {
			case REDUCED_ENTITY -> iterateReducedEntityPath(
				indexType, executor, referenceIndexConsumer, referencePredicate, referencePresenceExpected
			);
			case GROUP -> iterateGroupPath(
				indexType, executor, referenceIndexConsumer, referencePredicate, referencePresenceExpected
			);
			case BOTH -> {
				iterateReducedEntityPath(
					indexType, executor, referenceIndexConsumer, referencePredicate, referencePresenceExpected
				);
				iterateGroupPath(
					indexType, executor, referenceIndexConsumer, referencePredicate, referencePresenceExpected
				);
			}
		}
	}

	/**
	 * Iterates all currently stored references and fires the consumer **at most once per unique
	 * {@link AbstractReducedEntityIndex} Java instance**. When N references on the same entity resolve
	 * to the same shared {@link ReducedGroupEntityIndex} (typical for ZERO_OR_MORE references sharing
	 * a group + representative attribute values), the consumer fires exactly once for that target
	 * index — not N times. The iterator owns the identity dedup; callers no longer need a defensive
	 * `IdentityHashMap` wrapper.
	 *
	 * Use this mode when the work performed by the consumer is entity-scoped relative to the shared
	 * RGEI: entity-level attribute cardinality bookkeeping (set-semantic leaves), entity-level price
	 * index entries (single bucket per price list), locale tracking, and similar one-shot operations.
	 * Per-reference work (facets, reference-level attributes, sortable compounds) must use
	 * {@link #forEachReferenceIndex} instead so each reference gets its own invocation.
	 *
	 * Identity is established by {@link System#identityHashCode} via an internal
	 * {@link IdentityHashMap}; the dedup spans both paths when `path == BOTH`, so an REI visited first
	 * and a shared RGEI visited later both contribute one invocation per unique instance.
	 *
	 * Apart from the dedup wrapper, the semantics — including the predicate filter, group-presence
	 * filter, and entity-path-before-group-path ordering — match {@link #forEachReferenceIndex}
	 * exactly.
	 *
	 * @param indexType                 minimum {@link ReferenceIndexType} level required for a
	 *                                  reference to qualify
	 * @param executor                  the mutation executor providing entity state and index access
	 * @param referenceIndexConsumer    callback invoked for each unique target index
	 * @param referencePredicate        additional filter applied after the schema-level check
	 * @param referencePresenceExpected whether the referenced/group primary key is expected to already
	 *                                  be present in the index
	 * @param path                      which path(s) to traverse — see {@link IterationPath}
	 */
	static void forEachUniqueReferenceIndex(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceIndexConsumer referenceIndexConsumer,
		@Nonnull Predicate<ReferenceContract> referencePredicate,
		boolean referencePresenceExpected,
		@Nonnull IterationPath path
	) {
		// dedup by AbstractReducedEntityIndex identity — single map spans both paths
		final int referenceCount = executor.getReferencesStoragePart().getReferences().length;
		final int upperBound = path == IterationPath.BOTH ? referenceCount << 1 : referenceCount;
		final IdentityHashMap<AbstractReducedEntityIndex, Boolean> visited =
			new IdentityHashMap<>(Math.max(2, upperBound));
		final ReferenceIndexConsumer dedupConsumer =
			(referenceSchema, indexForRemoval, indexForUpsert) -> {
				Assert.isPremiseValid(
					indexForRemoval == indexForUpsert,
					"iterator path must not migrate"
				);
				if (visited.put(indexForUpsert, Boolean.TRUE) == null) {
					referenceIndexConsumer.accept(referenceSchema, indexForRemoval, indexForUpsert);
				}
			};
		forEachReferenceIndex(
			indexType, executor, dedupConsumer, referencePredicate, referencePresenceExpected, path
		);
	}

	/**
	 * Internal helper that drives the per-reference entity-level path. Extracted so both
	 * `forEachReferenceIndex` and `forEachUniqueReferenceIndex` share a single iteration loop.
	 */
	private static void iterateReducedEntityPath(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceIndexConsumer referenceIndexConsumer,
		@Nonnull Predicate<ReferenceContract> referencePredicate,
		boolean referencePresenceExpected
	) {
		final Scope scope = executor.getScope();
		final ReferencesStoragePart referencesStorageContainer = executor.getReferencesStoragePart();
		ReferenceSchemaContract referenceSchema = null;
		for (ReferenceContract reference : referencesStorageContainer.getReferences()) {
			referenceSchema = referenceSchema == null || !Objects.equals(
				referenceSchema.getName(), reference.getReferenceName()) ?
				reference.getReferenceSchemaOrThrow() : referenceSchema;
			if (
				reference.exists() &&
					isIndexedReferenceFor(referenceSchema, scope, indexType) &&
					isIndexedForEntityComponent(referenceSchema, scope) &&
					referencePredicate.test(reference)
			) {
				final ReferenceKey referenceKey = reference.getReferenceKey();
				final RepresentativeReferenceKeys bothKeys = executor.getRepresentativeReferenceKeys(
					referenceKey, referencePresenceExpected
				);

				final ReducedEntityIndex indexToUse = referenceKey.isKnownInternalPrimaryKey() ?
					getOrCreateReferencedEntityIndex(executor, bothKeys.stored(), scope) :
					getOrCreateReferencedEntityIndex(executor, bothKeys.current(), scope);
				referenceIndexConsumer.accept(referenceSchema, indexToUse, indexToUse);
			}
		}
	}

	/**
	 * Internal helper that drives the per-reference group-level path. Extracted so both
	 * `forEachReferenceIndex` and `forEachUniqueReferenceIndex` share a single iteration loop.
	 */
	private static void iterateGroupPath(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceIndexConsumer referenceIndexConsumer,
		@Nonnull Predicate<ReferenceContract> referencePredicate,
		boolean referencePresenceExpected
	) {
		final Scope scope = executor.getScope();
		final ReferencesStoragePart referencesStorageContainer = executor.getReferencesStoragePart();
		ReferenceSchemaContract referenceSchema = null;
		for (ReferenceContract reference : referencesStorageContainer.getReferences()) {
			referenceSchema = referenceSchema == null || !Objects.equals(
				referenceSchema.getName(), reference.getReferenceName()) ?
				reference.getReferenceSchemaOrThrow() : referenceSchema;
			if (
				reference.exists() &&
					isIndexedReferenceFor(referenceSchema, scope, indexType) &&
					isIndexedForGroupComponent(referenceSchema, scope) &&
					referencePredicate.test(reference)
			) {
				// only process references with a group assigned
				final Optional<GroupEntityReference> groupRef = reference.getGroup()
					.filter(Droppable::exists);
				if (groupRef.isPresent()) {
					final int groupPK = groupRef.get().getPrimaryKey();
					final ReferenceKey referenceKey = reference.getReferenceKey();
					final RepresentativeReferenceKeys bothKeys = executor.getRepresentativeReferenceKeys(
						referenceKey, referencePresenceExpected
					);

					// use entity RRK (with referenced entity PK) as group index discriminator
					final RepresentativeReferenceKey entityRRK = referenceKey.isKnownInternalPrimaryKey() ?
						bothKeys.stored() : bothKeys.current();

					final ReducedGroupEntityIndex groupIndex = getOrCreateReferencedGroupEntityIndex(
						executor, entityRRK, groupPK, scope
					);
					referenceIndexConsumer.accept(referenceSchema, groupIndex, groupIndex);
				}
			}
		}
	}

	/**
	 * Returns (or lazily creates) the {@link ReducedEntityIndex} of type {@link EntityIndexType#REFERENCED_ENTITY}
	 * for the given reference key and scope.
	 *
	 * The index is keyed by the {@link RepresentativeReferenceKey} discriminator, which encodes both the referenced
	 * entity type, the referenced entity primary key, and optional representative attribute values used for ordering.
	 *
	 * @param executor     the mutation executor that manages index lifecycle
	 * @param referenceKey the representative key identifying the specific referenced entity instance
	 * @param scope        the scope (e.g. live vs. archived) in which the index is maintained
	 * @return the existing or newly created reduced entity index for the given reference
	 */
	@Nonnull
	static ReducedEntityIndex getOrCreateReferencedEntityIndex(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull RepresentativeReferenceKey referenceKey,
		@Nonnull Scope scope
	) {
		final EntityIndexKey entityIndexKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY, scope,
			referenceKey
		);
		return (ReducedEntityIndex) executor.getOrCreateIndex(entityIndexKey);
	}

	/**
	 * Returns (or lazily creates) the {@link ReducedGroupEntityIndex} of type
	 * {@link EntityIndexType#REFERENCED_GROUP_ENTITY} for the given group PK and scope.
	 *
	 * The method constructs a new {@link RepresentativeReferenceKey} by combining the reference name and
	 * representative attribute values from `entityLevelKey` with the provided `groupPK`. The resulting
	 * discriminator therefore contains the **group PK** (not the referenced entity PK).
	 *
	 * @param executor       the mutation executor that manages index lifecycle
	 * @param entityLevelKey the entity-level representative key supplying reference name and attribute values
	 * @param groupPK        the primary key of the group entity
	 * @param scope          the scope in which the index is maintained
	 * @return the existing or newly created group reduced entity index
	 */
	@Nonnull
	static ReducedGroupEntityIndex getOrCreateReferencedGroupEntityIndex(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull RepresentativeReferenceKey entityLevelKey,
		int groupPK,
		@Nonnull Scope scope
	) {
		final RepresentativeReferenceKey groupScopedKey = new RepresentativeReferenceKey(
			new ReferenceKey(entityLevelKey.referenceName(), groupPK),
			entityLevelKey.representativeAttributeValues()
		);
		final EntityIndexKey entityIndexKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_GROUP_ENTITY, scope,
			groupScopedKey
		);
		return (ReducedGroupEntityIndex) executor.getOrCreateIndex(entityIndexKey);
	}

	/**
	 * Returns (or lazily creates) the {@link ReferencedTypeEntityIndex} of type
	 * {@link EntityIndexType#REFERENCED_ENTITY_TYPE} for the given reference name and scope.
	 *
	 * There is exactly one such index per (reference name, scope) pair. It aggregates attribute data keyed by the
	 * referenced entity's primary key across all owning entities that carry this reference.
	 *
	 * @param executor      the mutation executor that manages index lifecycle
	 * @param referenceName the name of the reference type (e.g. `"brand"`, `"category"`)
	 * @param scope         the scope in which the index is maintained
	 * @return the existing or newly created reference-type entity index
	 */
	@Nonnull
	static ReferencedTypeEntityIndex getOrCreateReferencedTypeEntityIndex(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		final EntityIndexKey entityIndexKey = getReferencedTypeIndexKey(referenceName, scope);
		return getOrCreateReferencedTypeEntityIndex(executor, entityIndexKey);
	}

	/**
	 * Returns (or lazily creates) the {@link ReferencedTypeEntityIndex} of type
	 * {@link EntityIndexType#REFERENCED_GROUP_ENTITY_TYPE} for the given reference name and scope.
	 *
	 * This is the group-level counterpart of
	 * {@link #getOrCreateReferencedTypeEntityIndex(EntityIndexLocalMutationExecutor,
	 * String, Scope)}: one index per (reference name, scope) pair, keyed by group primary keys.
	 *
	 * @param executor      the mutation executor that manages index lifecycle
	 * @param referenceName the name of the reference type
	 * @param scope         the scope in which the index is maintained
	 * @return the existing or newly created reference-group-type entity index
	 */
	@Nonnull
	static ReferencedTypeEntityIndex getOrCreateReferencedGroupTypeEntityIndex(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		final EntityIndexKey entityIndexKey = getReferencedGroupTypeIndexKey(referenceName, scope);
		return getOrCreateReferencedTypeEntityIndex(executor, entityIndexKey);
	}

	/**
	 * Returns (or lazily creates) the {@link ReferencedTypeEntityIndex} for the given pre-constructed
	 * {@link EntityIndexKey}. Convenience overload used in functional contexts where the key was already
	 * built (e.g. via {@link #getReferencedTypeIndexKey} or {@link #getReferencedGroupTypeIndexKey}).
	 *
	 * @param executor       the mutation executor that manages index lifecycle
	 * @param entityIndexKey the fully-constructed index key
	 * @return the existing or newly created referenced-type entity index
	 */
	@Nonnull
	static ReferencedTypeEntityIndex getOrCreateReferencedTypeEntityIndex(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndexKey entityIndexKey
	) {
		return (ReferencedTypeEntityIndex) executor.getOrCreateIndex(entityIndexKey);
	}

	/**
	 * Constructs the {@link EntityIndexKey} for the {@link EntityIndexType#REFERENCED_ENTITY_TYPE} index
	 * corresponding to the given reference name and scope. The discriminator of the returned key is the
	 * reference name string.
	 *
	 * @param referenceName the name of the reference type (e.g. `"brand"`)
	 * @param scope         the scope in which the index resides
	 * @return a fully-constructed entity index key ready to be passed to
	 * {@link EntityIndexLocalMutationExecutor#getOrCreateIndex(EntityIndexKey)}
	 */
	@Nonnull
	static EntityIndexKey getReferencedTypeIndexKey(
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		return new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName);
	}

	/**
	 * Constructs the {@link EntityIndexKey} for the {@link EntityIndexType#REFERENCED_GROUP_ENTITY_TYPE} index
	 * corresponding to the given reference name and scope. The discriminator of the returned key is the
	 * reference name string.
	 *
	 * @param referenceName the name of the reference type (e.g. `"brand"`)
	 * @param scope         the scope in which the index resides
	 * @return a fully-constructed entity index key ready to be passed to
	 * {@link EntityIndexLocalMutationExecutor#getOrCreateIndex(EntityIndexKey)}
	 */
	@Nonnull
	static EntityIndexKey getReferencedGroupTypeIndexKey(
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		return new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, scope, referenceName);
	}

	/**
	 * Returns `true` if the reference schema has {@link ReferenceIndexedComponents#REFERENCED_GROUP_ENTITY}
	 * enabled for the given scope, meaning that group-level reduced indexes
	 * ({@link EntityIndexType#REFERENCED_GROUP_ENTITY} and {@link EntityIndexType#REFERENCED_GROUP_ENTITY_TYPE})
	 * should be maintained for this reference.
	 *
	 * @param referenceSchema the reference schema to evaluate
	 * @param scope           the scope in which group indexing is checked
	 * @return `true` if group-level indexes should be maintained, `false` otherwise
	 */
	static boolean isIndexedForGroupComponent(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope
	) {
		return referenceSchema.getIndexedComponents(scope)
			.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY);
	}

	/**
	 * Returns `true` if the reference schema has {@link ReferenceIndexedComponents#REFERENCED_ENTITY}
	 * enabled for the given scope, meaning that entity-level reduced indexes
	 * ({@link EntityIndexType#REFERENCED_ENTITY} and {@link EntityIndexType#REFERENCED_ENTITY_TYPE})
	 * should be maintained for this reference.
	 *
	 * @param referenceSchema the reference schema to evaluate
	 * @param scope           the scope in which entity indexing is checked
	 * @return `true` if entity-level indexes should be maintained, `false` otherwise
	 */
	static boolean isIndexedForEntityComponent(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope
	) {
		return referenceSchema.getIndexedComponents(scope)
			.contains(ReferenceIndexedComponents.REFERENCED_ENTITY);
	}

	/**
	 * Returns `true` if the reference schema's configured {@link ReferenceIndexType} in the given scope has an ordinal
	 * at least as large as the requested `referenceIndexType`. This implements a "minimum level" check across
	 * the ordered enum: `NONE` < `FOR_FILTERING` < `FOR_FILTERING_AND_PARTITIONING`.
	 *
	 * @param referenceSchema    the reference schema to evaluate
	 * @param scope              the scope in which to check the index type
	 * @param referenceIndexType the minimum required index level
	 * @return `true` if the configured level is equal to or above the requested level
	 */
	static boolean isIndexedReferenceFor(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull ReferenceIndexType referenceIndexType
	) {
		return referenceSchema.getReferenceIndexType(scope).ordinal() >= referenceIndexType.ordinal();
	}

	/**
	 * Returns `true` if the reference schema is configured for at least basic filtering indexing
	 * ({@link ReferenceIndexType#FOR_FILTERING} or higher) in the given scope. When `false`, the reference
	 * cannot participate in `referenceHaving` filter constraints and no reference indexes exist for it.
	 *
	 * @param referenceSchema the reference schema to evaluate
	 * @param scope           the scope in which to check the index type
	 * @return `true` if any indexing level other than {@link ReferenceIndexType#NONE} is configured
	 */
	static boolean isIndexedReferenceForFiltering(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope
	) {
		return referenceSchema.getReferenceIndexType(scope) != ReferenceIndexType.NONE;
	}

	/**
	 * Returns `true` if the reference schema is configured for the highest indexing level
	 * ({@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING}) in the given scope. At this level, entity
	 * attributes and prices are also copied into the per-referenced-entity reduced indexes, enabling efficient
	 * partitioned query execution.
	 *
	 * @param referenceSchema the reference schema to evaluate
	 * @param scope           the scope in which to check the index type
	 * @return `true` if partitioning indexes are enabled for this reference in the given scope
	 */
	static boolean isIndexedReferenceForFilteringAndPartitioning(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope
	) {
		return referenceSchema.getReferenceIndexType(scope) == ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING;
	}

	/**
	 * Propagates a single reference-attribute mutation into both the type-level index and the per-reference reduced
	 * index.
	 *
	 * The attribute is looked up via the reference schema (not the entity schema), since reference attributes are
	 * scoped to the reference, not to the owning entity. The method delegates to
	 * {@link EntityIndexLocalMutationExecutor#updateAttribute(ReferenceSchemaContract, AttributeMutation,
	 * AttributeAndCompoundSchemaProvider, ExistingAttributeValueSupplier, EntityIndex, EntityIndex, boolean, boolean)}
	 * twice: once for the type index (keyed by the internal reduced-index primary key) and once for the reduced
	 * entity index (keyed by the owning entity's primary key, or by the referenced entity's primary key when the
	 * attribute type is {@link ReferencedEntityPredecessor}).
	 *
	 * @param executor                 the mutation executor coordinating the index updates
	 * @param attributeSupplierFactory factory for reading existing reference attribute values from storage
	 * @param referenceTypeIndex       the type-level index receiving the attribute change (keyed by referenced PK)
	 * @param indexForRemoval          the reduced index from which old attribute data is removed; typically the
	 *                                 same object as `indexForUpsert` but passed separately for correct ordering
	 * @param indexForUpsert           the reduced index into which updated attribute data is inserted
	 * @param referenceSchema          the schema describing the reference and its attributes
	 * @param referenceKey             identifies the specific reference instance being updated
	 * @param attributeMutation        the attribute-level mutation to apply (upsert, remove, or delta)
	 */
	static void attributeUpdate(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ExistingDataSupplierFactory attributeSupplierFactory,
		@Nonnull ReferencedTypeEntityIndex referenceTypeIndex,
		@Nonnull AbstractReducedEntityIndex indexForRemoval,
		@Nonnull AbstractReducedEntityIndex indexForUpsert,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull AttributeMutation attributeMutation
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();

		// use different existing attribute value accessor - attributes needs to be looked up in ReferencesStoragePart
		final ExistingAttributeValueSupplier existingValueAccessorFactory = attributeSupplierFactory
			.getNormalizedReferenceAttributeValueSupplier(referenceKey);

		// we access attributes and sortable compounds from the reference schema
		final ReferenceSchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider =
			new ReferenceSchemaAttributeAndCompoundSchemaProvider(
				entitySchema, referenceSchema
			);

		executor.executeWithDifferentPrimaryKeyToIndex(
			(indexType, target) ->
				switch (target) {
					case EXISTING -> indexForRemoval.getPrimaryKey();
					case NEW -> indexForUpsert.getPrimaryKey();
				},
			() -> executor.updateAttribute(
				referenceSchema,
				attributeMutation,
				attributeSchemaProvider,
				existingValueAccessorFactory,
				referenceTypeIndex,
				referenceTypeIndex,
				false,
				false
			)
		);

		final Runnable applyToReducedIndex = () -> executeWithProperPrimaryKey(
			executor,
			referenceKey.primaryKey(),
			attributeMutation.getAttributeKey().attributeName(),
			attributeSchemaProvider::getAttributeSchema,
			() -> executor.updateAttribute(
				referenceSchema,
				attributeMutation,
				attributeSchemaProvider,
				existingValueAccessorFactory,
				indexForRemoval,
				indexForUpsert,
				false,
				true
			)
		);
		// mirror the initial-population logic in `indexAllReferenceLevelAttributes`: on grouped
		// reduced indexes the reference-attribute FilterIndex is keyed on the referenced entity PK,
		// so subsequent attribute updates must follow the same keying rule
		if (indexForUpsert instanceof ReducedGroupEntityIndex
			|| indexForRemoval instanceof ReducedGroupEntityIndex) {
			executor.executeWithDifferentPrimaryKeyToIndex(
				(indexType, target) -> referenceKey.primaryKey(), applyToReducedIndex
			);
		} else {
			applyToReducedIndex.run();
		}
	}

	/**
	 * Performs the global-only indexing operation for a reference insertion.
	 * This adds the facet to the global entity index and must be called exactly once
	 * per reference insert, regardless of how many indexed components are configured.
	 *
	 * @param entityPrimaryKey   the primary key of the entity being indexed
	 * @param referenceSchema    the schema of the reference being inserted
	 * @param globalIndex        the global entity index to add the facet to
	 * @param referenceKey       the reference key identifying the reference
	 * @param groupId            the group primary key, or null if not grouped
	 * @param executor           the mutation executor
	 */
	static void referenceInsertGlobal(
		int entityPrimaryKey,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndex globalIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId,
		@Nonnull EntityIndexLocalMutationExecutor executor
	) {
		addFacetToIndex(
			globalIndex, referenceSchema, referenceKey, groupId, entityPrimaryKey, executor);
		// reported here rather than per component because this runs exactly once per reference, which is the
		// granularity the capability counts use - the fan-out over components is the per-index metric, not this one
		executor.reportReferenceTouched(referenceSchema, globalIndex.getIndexKey().scope());
	}

	/**
	 * Performs per-component indexing for a reference insertion. This registers the PK mapping in
	 * the type index, indexes reference attributes, populates the reduced index with entity data,
	 * and adds the facet to the reduced index. Called once per indexed component (entity and/or group).
	 *
	 * @param entityPrimaryKey            the primary key of the entity being indexed
	 * @param entitySchema                the entity schema
	 * @param referenceSchema             the schema of the reference being inserted
	 * @param executor                    the mutation executor
	 * @param referenceTypeIndex          the type-level index for this component
	 * @param referenceIndex              the reduced entity index for this component
	 * @param referenceKey                the original reference key — used for facets and reference attribute lookup
	 * @param referencedPrimaryKey        the target primary key for type index mapping (entity PK or group PK)
	 * @param groupId                     the group primary key, or null if not grouped
	 * @param existingDataSupplierFactory factory to supply existing data needed for indexing
	 */
	static void referenceInsertPerComponent(
		int entityPrimaryKey,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferencedTypeEntityIndex referenceTypeIndex,
		@Nonnull AbstractReducedEntityIndex referenceIndex,
		@Nonnull ReferenceKey referenceKey,
		int referencedPrimaryKey,
		@Nullable Integer groupId,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		// register reduced index PK → referenced primary key mapping in the type index
		final int pkForReferenceTypeIndex = referenceIndex.getPrimaryKey();
		referenceTypeIndex.insertPrimaryKeyIfMissing(pkForReferenceTypeIndex, referencedPrimaryKey);

		// we access attributes and sortable compounds from the reference schema
		final ReferenceSchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider =
			new ReferenceSchemaAttributeAndCompoundSchemaProvider(
				entitySchema, referenceSchema
			);

		// index all reference attributes to the reference type index
		final ExistingAttributeValueSupplier referenceAttributeValueSupplier = existingDataSupplierFactory
			.getNormalizedReferenceAttributeValueSupplier(referenceKey);

		executor.executeWithDifferentPrimaryKeyToIndex(
			(indexType, target) -> pkForReferenceTypeIndex,
			() -> referenceAttributeValueSupplier
				.getAttributeValues()
				.forEach(attributeValue -> AttributeIndexMutator.executeAttributeUpsert(
					executor,
					referenceSchema,
					attributeSchemaProvider,
					NO_EXISTING_VALUE_SUPPLIER,
					referenceTypeIndex,
					referenceTypeIndex,
					attributeValue.key(),
					Objects.requireNonNull(attributeValue.value()),
					false,
					false
				))
		);

		// index entity primary key into the reduced index and populate with existing data
		if (referenceIndex instanceof ReducedGroupEntityIndex rgei) {
			// group indexes need cardinality tracking — use two-arg version
			// `entityFirstIndexedInTargetIndex` is true only when this insert causes the entity to
			// enter this group reduced index for the first time (cardinality 0 -> 1); subsequent
			// references contributing to the same group still need per-reference indexing (facets,
			// reference attributes) but must skip entity-level data that was already populated
			final boolean entityFirstIndexedInTargetIndex =
				rgei.insertPrimaryKeyIfMissing(entityPrimaryKey, referenceKey.primaryKey())
					== CardinalityChange.BOUNDARY_CROSSED;
			indexAllExistingData(
				executor, referenceIndex,
				entitySchema, referenceSchema,
				referenceKey,
				entityPrimaryKey,
				entityFirstIndexedInTargetIndex,
				existingDataSupplierFactory
			);
		} else {
			final boolean entityFirstIndexedInTargetIndex =
				referenceIndex.insertPrimaryKeyIfMissing(entityPrimaryKey);
			// REI indexes are keyed per-reference so no duplicate refs can land here; always run the
			// full entity-level + reference-level population when the entity is freshly inserted
			if (entityFirstIndexedInTargetIndex) {
				indexAllExistingData(
					executor, referenceIndex,
					entitySchema, referenceSchema,
					referenceKey,
					entityPrimaryKey,
					true,
					existingDataSupplierFactory
				);
			}
		}

		// add facet to reduced index
		addFacetToIndex(
			referenceIndex, referenceSchema, referenceKey, groupId, entityPrimaryKey, executor
		);
	}

	/**
	 * Fully indexes a newly created reference for the entity-level component. Combines the global facet registration
	 * (via {@link #referenceInsertGlobal}) and the per-component registration in the type index and reduced entity
	 * index (via {@link #referenceInsertPerComponent}).
	 *
	 * Use this method when inserting a reference for the entity component only. For group-component indexing, call
	 * {@link #referenceInsertPerComponent} separately with the group primary key and group-specific indexes.
	 *
	 * @param entityPrimaryKey            the primary key of the owning entity
	 * @param entitySchema                the entity schema of the owning entity
	 * @param referenceSchema             the schema describing the reference being inserted
	 * @param executor                    the mutation executor coordinating the index updates
	 * @param entityIndex                 the global entity index to which the facet is registered once
	 * @param referenceTypeIndex          the type-level index ({@link EntityIndexType#REFERENCED_ENTITY_TYPE})
	 * @param referenceIndex              the per-reference reduced index ({@link EntityIndexType#REFERENCED_ENTITY})
	 * @param referenceKey                identifies the specific referenced entity
	 * @param groupId                     the group primary key to associate with the facet, or `null` if not grouped
	 * @param existingDataSupplierFactory factory for reading existing entity data to populate the reduced index
	 */
	static void referenceInsert(
		int entityPrimaryKey,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex entityIndex,
		@Nonnull ReferencedTypeEntityIndex referenceTypeIndex,
		@Nonnull AbstractReducedEntityIndex referenceIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		// global operation — add facet to global index (once per reference)
		referenceInsertGlobal(
			entityPrimaryKey, referenceSchema, entityIndex, referenceKey, groupId,
			executor
		);
		// per-component operation — type index + reduced index
		referenceInsertPerComponent(
			entityPrimaryKey, entitySchema, referenceSchema, executor,
			referenceTypeIndex, referenceIndex, referenceKey,
			referenceKey.primaryKey(), groupId,
			existingDataSupplierFactory
		);
	}

	/**
	 * Performs the global-only operation for a reference removal.
	 * This removes the facet from the global entity index and must be called exactly once
	 * per reference removal, regardless of how many indexed components are configured.
	 *
	 * @param entityPrimaryKey   the primary key of the entity being removed
	 * @param referenceSchema    the schema of the reference being removed
	 * @param globalIndex        the global entity index to remove the facet from
	 * @param referenceKey       the reference key identifying the reference
	 * @param executor           the mutation executor
	 */
	static void referenceRemovalGlobal(
		int entityPrimaryKey,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndex globalIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull EntityIndexLocalMutationExecutor executor
	) {
		removeFacetInIndex(globalIndex, referenceSchema, referenceKey, entityPrimaryKey, executor);
		// see referenceInsertGlobal - the removal is the same one-per-reference event, and costs the same maintenance
		executor.reportReferenceTouched(referenceSchema, globalIndex.getIndexKey().scope());
	}

	/**
	 * Performs per-component removal from the type index and reduced index. Called once per indexed
	 * component (entity and/or group).
	 *
	 * @param entityPrimaryKey            the primary key of the entity being removed
	 * @param entitySchema                the entity schema
	 * @param referenceSchema             the schema of the reference being removed
	 * @param executor                    the mutation executor
	 * @param referenceTypeIndex          the type-level index for this component
	 * @param referenceIndex              the reduced entity index for this component
	 * @param referenceKey                the original reference key — for attribute lookup
	 * @param referencedPrimaryKey        the target primary key for type index mapping (entity PK or group PK)
	 * @param existingDataSupplierFactory factory to supply existing data
	 */
	static void referenceRemovalPerComponent(
		int entityPrimaryKey,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferencedTypeEntityIndex referenceTypeIndex,
		@Nonnull AbstractReducedEntityIndex referenceIndex,
		@Nonnull ReferenceKey referenceKey,
		int referencedPrimaryKey,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		// remove reduced index PK → referenced primary key mapping from the type index
		final int pkForReferenceTypeIndex = referenceIndex.getPrimaryKey();
		referenceTypeIndex.removePrimaryKey(pkForReferenceTypeIndex, referencedPrimaryKey);

		// we access attributes and sortable compounds from the reference schema
		final ReferenceSchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider =
			new ReferenceSchemaAttributeAndCompoundSchemaProvider(
				entitySchema, referenceSchema
			);

		// remove all reference attributes from the reference type index
		final ExistingAttributeValueSupplier referenceAttributeValueSupplier = existingDataSupplierFactory
			.getNormalizedReferenceAttributeValueSupplier(referenceKey);
		executor.executeWithDifferentPrimaryKeyToIndex(
			(indexType, target) -> pkForReferenceTypeIndex,
			() -> referenceAttributeValueSupplier
				.getAttributeValues()
				.forEach(attributeValue -> AttributeIndexMutator.executeAttributeRemoval(
					executor,
					referenceSchema,
					attributeSchemaProvider,
					referenceAttributeValueSupplier,
					referenceTypeIndex,
					referenceTypeIndex,
					attributeValue.key(),
					false,
					false
				))
		);

		// remove entity primary key from the reduced index
		if (referenceIndex instanceof ReducedGroupEntityIndex rgei) {
			// group indexes need cardinality tracking — use two-arg version
			// `entityFullyRemovedFromTargetIndex` is true only when this removal causes the entity
			// to leave this group reduced index entirely (cardinality 1 -> 0); earlier removals on
			// the same (entity, RGEI) pair still need per-reference cleanup (facets, reference
			// attributes) but must skip entity-level data that other references still rely on
			final boolean entityFullyRemovedFromTargetIndex =
				rgei.removePrimaryKey(entityPrimaryKey, referenceKey.primaryKey())
					== CardinalityChange.BOUNDARY_CROSSED;
			removeAllExistingData(
				executor, referenceIndex,
				entitySchema, referenceSchema,
				referenceKey,
				entityPrimaryKey,
				entityFullyRemovedFromTargetIndex,
				existingDataSupplierFactory
			);
		} else {
			final boolean entityFullyRemovedFromTargetIndex =
				referenceIndex.removePrimaryKey(entityPrimaryKey);
			if (entityFullyRemovedFromTargetIndex) {
				removeAllExistingData(
					executor, referenceIndex,
					entitySchema, referenceSchema,
					referenceKey,
					entityPrimaryKey,
					true,
					existingDataSupplierFactory
				);
			}
		}
	}

	/**
	 * Adds a facet entry for the given entity to `index`, but only when both of the following conditions hold:
	 *
	 * 1. The target index should receive facet data for this reference (governed by
	 * {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING} for reduced indexes; always `true` for the
	 * global entity index).
	 * 2. The reference schema marks the reference as faceted in the index's scope.
	 *
	 * @param index              the target entity index (global or reduced)
	 * @param referenceSchema    the schema of the reference; used to check faceting and schema name resolution
	 * @param referenceKey       identifies the specific referenced entity for the facet entry
	 * @param groupId            the group primary key for this facet, or `null` if no group is assigned
	 * @param entityPrimaryKey   the primary key of the entity owning the reference
	 * @param executor           the mutation executor; used for schema look-ups and scope access
	 */
	static void addFacetToIndex(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId,
		int entityPrimaryKey,
		@Nonnull EntityIndexLocalMutationExecutor executor
	) {
		final Scope scope = index.getIndexKey().scope();
		if (
			shouldIndexFacetToTargetIndex(index, referenceSchema, scope, executor) &&
				isFaceted(referenceKey, referenceSchema, scope, executor)
		) {
			// evaluate facet expression if one exists — skip facet add when expression returns false
			final FacetExpressionTrigger trigger = executor.getTriggerFor(referenceKey.referenceName(), scope);
			final boolean shouldBeIndexed = trigger == null || trigger.evaluate(
				entityPrimaryKey, referenceKey,
				executor.getContainerAccessor(), executor.getSchemaResolver(),
				scope
			);
			if (shouldBeIndexed) {
				index.addFacet(referenceSchema, referenceKey, groupId, entityPrimaryKey);
			}
		}
	}

	/**
	 * Atomically replaces the group assignment of an existing facet entry in `index`. The current group (read from
	 * the persisted {@link ReferencesStoragePart}) is first removed and then re-added with the new `groupId`.
	 *
	 * This method is a no-op when the reference schema does not mark the reference as faceted in the index's scope,
	 * or when the target index is an {@link AbstractReducedEntityIndex} for a different reference schema that is not
	 * configured for {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING}.
	 *
	 * During cross-reference propagation, the facet may have already been added with the new group by
	 * direct processing (e.g. via {@link #referenceInsertPerComponent}). In that case, the storage-derived
	 * old group is stale and the facet does not exist in the old group bucket. The method handles this
	 * gracefully by checking facet presence before removal.
	 *
	 * @param entityPrimaryKey the primary key of the entity owning the reference
	 * @param index            the target entity index (global or reduced)
	 * @param referenceSchema  the schema of the reference; used to check faceting
	 * @param referenceKey     identifies the specific referenced entity
	 * @param groupId          the new group primary key to assign
	 * @param executor         the mutation executor; used for reading the existing reference and schema access
	 */
	static void setFacetGroupInIndex(
		int entityPrimaryKey,
		@Nonnull EntityIndex index,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull Integer groupId,
		@Nonnull EntityIndexLocalMutationExecutor executor
	) {
		final Scope scope = index.getIndexKey().scope();
		if (
			shouldIndexFacetToTargetIndex(index, referenceSchema, scope, executor) &&
				isFaceted(referenceKey, referenceSchema, scope, executor)
		) {
			// evaluate facet expression if one exists — apply decision matrix for group change
			final FacetExpressionTrigger trigger = executor.getTriggerFor(referenceKey.referenceName(), scope);
			if (trigger != null) {
				final boolean nowFaceted = trigger.evaluate(
					entityPrimaryKey, referenceKey, executor.getContainerAccessor(), executor.getSchemaResolver(),
					scope
				);
				applyFacetDecisionMatrix(
					index, referenceSchema, referenceKey, groupId, entityPrimaryKey, nowFaceted
				);
			} else {
				// no expression — unconditionally move the facet to the new group
				applyFacetDecisionMatrix(
					index, referenceSchema, referenceKey, groupId, entityPrimaryKey, true
				);
			}
		}
	}

	/**
	 * Applies the facet was/now decision matrix, handling both facet presence changes **and** group transitions
	 * in a single method. Every facet operation inherently involves a group (or `null` for ungrouped), and every
	 * group change may affect whether the facet should exist at all — so both concerns are handled together.
	 *
	 * Decision matrix:
	 *
	 * | Was faceted? | Now faceted? | In target group? | Action                              |
	 * |--------------|--------------|------------------|-------------------------------------|
	 * | yes          | no           | —                | Remove from current group           |
	 * | no           | yes          | —                | Add with target group               |
	 * | yes          | yes          | yes              | no-op                               |
	 * | yes          | yes          | no               | Move (remove current + add target)  |
	 * | no           | no           | —                | no-op                               |
	 *
	 * @param index              the target entity index
	 * @param referenceSchema    the reference schema
	 * @param referenceKey       the reference key
	 * @param targetGroupId      the group the facet should end up in (null for ungrouped)
	 * @param entityPrimaryKey   the entity PK
	 * @param nowFaceted         result of the expression evaluation
	 */
	static void applyFacetDecisionMatrix(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer targetGroupId,
		int entityPrimaryKey,
		boolean nowFaceted
	) {
		final boolean wasFaceted = wasFaceted(index, referenceKey, entityPrimaryKey);
		if (wasFaceted && !nowFaceted) {
			// was faceted, now not — remove from whichever group it currently resides in
			removeFromCurrentGroup(index, referenceSchema, referenceKey, entityPrimaryKey);
		} else if (!wasFaceted && nowFaceted) {
			// was not faceted, now is — add under target group
			index.addFacet(referenceSchema, referenceKey, targetGroupId, entityPrimaryKey);
		} else if (wasFaceted && !isFacetPresentInGroup(index, referenceKey, targetGroupId, entityPrimaryKey)) {
			// was faceted, still faceted, but in a different group — move to target group
			removeFromCurrentGroup(index, referenceSchema, referenceKey, entityPrimaryKey);
			index.addFacet(referenceSchema, referenceKey, targetGroupId, entityPrimaryKey);
		}
		// was==now and already in target group → no-op
	}

	/**
	 * Re-evaluates facet expressions for all references of the owning entity on **both** the
	 * {@link EntityIndex global index} and all applicable {@link ReducedEntityIndex} instances. Used from
	 * all deferred facet re-evaluation call sites to keep both index levels consistent.
	 *
	 * The method performs a single pass over stored references. For each reference whose trigger matches
	 * the relevance predicate, it:
	 *
	 * 1. evaluates the expression
	 * 2. applies the was/now decision matrix to the global index
	 * 3. if the reference schema is indexed at
	 * {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING} level with entity-component indexing,
	 * looks up (or creates) the corresponding {@link ReducedEntityIndex} and applies the same decision
	 * matrix there
	 *
	 * @param globalIndex               the global entity index (must be a global-scoped index)
	 * @param executor                  the mutation executor; provides references storage and schema access
	 * @param entityPrimaryKey          the primary key of the entity owning the references
	 * @param triggerRelevancePredicate predicate that returns `true` when a trigger depends on the changed data
	 */
	static void reEvaluateFacetExpressionsInAllIndexes(
		@Nonnull EntityIndex globalIndex,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		int entityPrimaryKey,
		@Nonnull Predicate<FacetExpressionTrigger> triggerRelevancePredicate
	) {
		final Scope scope = globalIndex.getIndexKey().scope();
		final ReferencesStoragePart referencesStoragePart = executor.getReferencesStoragePart();

		ReferenceSchemaContract cachedSchema = null;
		FacetExpressionTrigger cachedTrigger = null;
		boolean cachedTriggerResolved = false;
		// cache whether the current schema requires reduced-index facet propagation
		boolean cachedNeedsReducedIndex = false;
		for (final ReferenceContract reference : referencesStoragePart.getReferences()) {
			if (!reference.exists()) {
				continue;
			}
			final ReferenceKey referenceKey = reference.getReferenceKey();
			// cache schema and trigger lookups across consecutive references of the same type
			// (references are sorted by name in storage part)
			if (cachedSchema == null || !cachedSchema.getName().equals(referenceKey.referenceName())) {
				cachedSchema = executor.getEntitySchema()
					.getReferenceOrThrowException(referenceKey.referenceName());
				cachedTriggerResolved = false;
				cachedNeedsReducedIndex =
					isIndexedReferenceFor(cachedSchema, scope, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)
						&& isIndexedForEntityComponent(cachedSchema, scope);
			}
			if (!cachedSchema.isFacetedInScope(scope)) {
				continue;
			}
			if (!cachedTriggerResolved) {
				cachedTrigger = executor.getTriggerFor(referenceKey.referenceName(), scope);
				cachedTriggerResolved = true;
			}
			if (cachedTrigger == null || !triggerRelevancePredicate.test(cachedTrigger)) {
				continue;
			}
			final boolean nowFaceted = cachedTrigger.evaluate(
				entityPrimaryKey, referenceKey, executor.getContainerAccessor(), executor.getSchemaResolver(),
				scope
			);
			final Integer groupId = reference.getGroup()
				.filter(Droppable::exists)
				.map(GroupEntityReference::getPrimaryKey)
				.orElse(null);

			// apply to global index
			applyFacetDecisionMatrix(
				globalIndex, cachedSchema, referenceKey, groupId, entityPrimaryKey, nowFaceted
			);

			// apply to reduced entity index when the schema requires partitioning-level indexing
			if (cachedNeedsReducedIndex) {
				final RepresentativeReferenceKeys bothKeys =
					executor.getRepresentativeReferenceKeys(referenceKey, true);
				final ReducedEntityIndex reducedIndex = referenceKey.isKnownInternalPrimaryKey()
					? getOrCreateReferencedEntityIndex(executor, bothKeys.stored(), scope)
					: getOrCreateReferencedEntityIndex(executor, bothKeys.current(), scope);
				applyFacetDecisionMatrix(
					reducedIndex, cachedSchema, referenceKey, groupId, entityPrimaryKey, nowFaceted
				);
			}
		}
	}

	/**
	 * Re-evaluates histogram expressions for all references of an entity after an entity-level
	 * change (e.g., entity attribute mutation). Mirrors {@link #reEvaluateFacetExpressionsInAllIndexes}
	 * for the histogram indexing path.
	 *
	 * For each reference whose histogram triggers match the relevance predicate, removes existing
	 * histogram entries and re-adds them based on current condition evaluation. The remove+add
	 * pattern handles all state transitions correctly (was/was-not indexed vs should/should-not be).
	 *
	 * @param globalIndex               the global entity index (used to derive scope)
	 * @param executor                  the mutation executor providing access to storage, triggers, and indexes
	 * @param entityPrimaryKey          the primary key of the entity whose attribute changed
	 * @param existingDataFactory       factory for creating per-reference attribute value suppliers
	 * @param triggerRelevancePredicate filters histogram triggers to only those affected by the change
	 */
	static void reEvaluateHistogramExpressionsInAllIndexes(
		@Nonnull EntityIndex globalIndex,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		int entityPrimaryKey,
		@Nonnull ExistingDataSupplierFactory existingDataFactory,
		@Nonnull Predicate<HistogramExpressionTrigger> triggerRelevancePredicate
	) {
		final Scope scope = globalIndex.getIndexKey().scope();
		final ReferencesStoragePart referencesStoragePart = executor.getReferencesStoragePart();

		String cachedReferenceName = null;
		boolean cachedHasRelevantTrigger = false;
		for (final ReferenceContract reference : referencesStoragePart.getReferences()) {
			if (!reference.exists()) {
				continue;
			}
			final ReferenceKey referenceKey = reference.getReferenceKey();
			// cache trigger lookups across consecutive references of the same type
			// (references are sorted by name in storage part)
			if (cachedReferenceName == null || !cachedReferenceName.equals(referenceKey.referenceName())) {
				cachedReferenceName = referenceKey.referenceName();
				cachedHasRelevantTrigger = false;
				final Collection<HistogramExpressionTrigger> triggers =
					executor.getLocalHistogramTriggers(referenceKey.referenceName(), scope);
				for (final HistogramExpressionTrigger trigger : triggers) {
					if (triggerRelevancePredicate.test(trigger)) {
						cachedHasRelevantTrigger = true;
						break;
					}
				}
			}
			if (!cachedHasRelevantTrigger) {
				continue;
			}
			final Integer groupId = reference.getGroup()
				.filter(Droppable::exists)
				.map(GroupEntityReference::getPrimaryKey)
				.orElse(null);
			// remove then re-add: removeHistogramFromIndex reads values and removes deterministically,
			// addHistogramToIndex internally evaluates the condition
			removeHistogramFromIndex(executor, referenceKey, groupId, entityPrimaryKey, scope);
			addHistogramToIndex(
				executor, referenceKey, groupId, entityPrimaryKey,
				existingDataFactory.getNormalizedReferenceAttributeValueSupplier(referenceKey),
				scope
			);
		}
	}

	/**
	 * Removes the facet entry for the given entity from `index`. The existing group assignment is read from the
	 * persisted {@link ReferencesStoragePart} so the removal can use the correct (current) group primary key.
	 *
	 * This method is a no-op when the reference schema does not mark the reference as faceted in the index's scope,
	 * or when the target index is an {@link AbstractReducedEntityIndex} for a different reference schema that is not
	 * configured for {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING}.
	 *
	 * @param index              the target entity index (global or reduced)
	 * @param referenceSchema    the schema of the reference; used to check faceting and schema name resolution
	 * @param referenceKey       identifies the specific referenced entity whose facet is to be removed
	 * @param entityPrimaryKey   the primary key of the entity owning the reference
	 * @param executor           the mutation executor; used for reading the existing reference and schema access
	 */
	static void removeFacetInIndex(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		int entityPrimaryKey,
		@Nonnull EntityIndexLocalMutationExecutor executor
	) {
		final Scope scope = index.getIndexKey().scope();
		if (
			shouldIndexFacetToTargetIndex(index, referenceSchema, scope, executor) &&
				isFaceted(referenceKey, referenceSchema, scope, executor)
		) {
			// when expression is defined, only remove if the entity was actually faceted
			// (expression may have evaluated to false at insert time, so no facet was ever added)
			final FacetExpressionTrigger trigger = executor.getTriggerFor(referenceKey.referenceName(), scope);
			if (trigger != null) {
				if (wasFaceted(index, referenceKey, entityPrimaryKey)) {
					final ReferenceContract existingReference = executor.getReferencesStoragePart()
						.findReferenceOrThrowException(referenceKey);
					removeFacetInIndexInternal(
						index, referenceSchema, entityPrimaryKey, existingReference
					);
				}
			} else {
				final ReferenceContract existingReference = executor.getReferencesStoragePart()
					.findReferenceOrThrowException(referenceKey);
				removeFacetInIndexInternal(
					index, referenceSchema, entityPrimaryKey, existingReference
				);
			}
		}
	}

	/**
	 * Atomically clears the group assignment of an existing facet entry in `index`. The current group is read from
	 * the persisted {@link ReferencesStoragePart}, the entry is removed under that group, and then re-added with
	 * a `null` group (ungrouped).
	 *
	 * This method validates via {@link Assert#isPremiseValid} that a non-null group is actually present on the
	 * stored reference — it is a programming error to call this when no group is assigned.
	 *
	 * This method is a no-op when the reference schema does not mark the reference as faceted in the index's scope,
	 * or when the target index is an {@link AbstractReducedEntityIndex} for a reference not configured for
	 * {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING}.
	 *
	 * During cross-reference propagation, the facet may have already been moved to a different group by
	 * direct processing (e.g. via {@link #referenceInsertPerComponent}). In that case, the storage-derived
	 * group is stale and the facet does not exist in the old group bucket. The method handles this
	 * gracefully by checking facet presence before removal via {@link #isFacetPresentInGroup}.
	 *
	 * @param entityPrimaryKey the primary key of the entity owning the reference
	 * @param index            the target entity index (global or reduced)
	 * @param referenceSchema  the schema of the reference; used to check faceting
	 * @param referenceKey     identifies the specific referenced entity
	 * @param executor         the mutation executor; used for reading the existing reference and schema access
	 * @throws io.evitadb.exception.GenericEvitaInternalError if the stored reference has no non-dropped group assigned
	 */
	static void removeFacetGroupInIndex(
		int entityPrimaryKey,
		@Nonnull EntityIndex index,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull EntityIndexLocalMutationExecutor executor
	) {
		final Scope scope = index.getIndexKey().scope();
		if (
			shouldIndexFacetToTargetIndex(index, referenceSchema, scope, executor) &&
				isFaceted(referenceKey, referenceSchema, scope, executor)
		) {
			// evaluate facet expression if one exists — apply decision matrix for group removal
			final FacetExpressionTrigger trigger = executor.getTriggerFor(referenceKey.referenceName(), scope);
			if (trigger != null) {
				final boolean nowFaceted = trigger.evaluate(
					entityPrimaryKey, referenceKey, executor.getContainerAccessor(), executor.getSchemaResolver(),
					scope
				);
				applyFacetDecisionMatrix(
					index, referenceSchema, referenceKey, null, entityPrimaryKey, nowFaceted
				);
			} else {
				// no expression — unconditionally move the facet to ungrouped
				final ReferenceContract existingReference = executor.getReferencesStoragePart()
					.findReferenceOrThrowException(referenceKey);
				isPremiseValid(
					existingReference.getGroup().filter(Droppable::exists).isPresent(),
					"Group is expected to be non-null when RemoveReferenceGroupMutation is about to be executed."
				);
				applyFacetDecisionMatrix(
					index, referenceSchema, referenceKey, null, entityPrimaryKey, true
				);
			}
		}
	}

	/**
	 * Builds and inserts the full suite of sortable attribute compounds for the entity–reference combination
	 * represented by `targetIndex`.
	 *
	 * When `locale` is non-null, only compounds that include at least one attribute for that locale are created.
	 * When `locale` is `null`, only locale-independent (non-localized) compounds are created.
	 *
	 * The method processes two attribute scopes:
	 *
	 * - **Entity-level** compound schemas (from the owning entity schema) — only when the reference schema is
	 * configured for {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING}.
	 * - **Reference-level** compound schemas (from the reference schema) — always, regardless of index type.
	 *
	 * @param executor                    the mutation executor providing entity schema and index access
	 * @param referenceSchema             the schema describing the reference (determines attribute scope and index level)
	 * @param targetIndex                 the reduced entity index into which compounds are inserted
	 * @param referenceKey                the actual reference key identifying the referenced entity (used instead of the
	 *                                    index discriminator, which may contain the group PK for group-level indexes)
	 * @param locale                      if non-null, restrict insertion to compounds for this locale only
	 * @param existingDataSupplierFactory factory for reading existing attribute values from storage
	 */
	static void insertInitialSuiteOfSortableAttributeCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Locale locale,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		applySortableAttributeCompoundSuite(
			executor, referenceSchema, targetIndex, referenceKey, locale,
			existingDataSupplierFactory,
			AttributeIndexMutator::insertInitialSuiteOfSortableAttributeCompounds
		);
	}

	/**
	 * Removes the full suite of sortable attribute compounds for the entity–reference combination represented by
	 * `targetIndex`. This is the exact inverse of
	 * {@link #insertInitialSuiteOfSortableAttributeCompounds(EntityIndexLocalMutationExecutor, ReferenceSchemaContract,
	 * AbstractReducedEntityIndex, ReferenceKey, Locale, ExistingDataSupplierFactory)}.
	 *
	 * When `locale` is non-null, only compounds that include at least one attribute for that locale are removed.
	 * When `locale` is `null`, only locale-independent (non-localized) compounds are removed.
	 *
	 * @param executor                    the mutation executor providing entity schema and index access
	 * @param referenceSchema             the schema describing the reference
	 * @param targetIndex                 the reduced entity index from which compounds are removed
	 * @param referenceKey                the actual reference key identifying the referenced entity (used instead of the
	 *                                    index discriminator, which may contain the group PK for group-level indexes)
	 * @param locale                      if non-null, restrict removal to compounds for this locale only
	 * @param existingDataSupplierFactory factory for reading existing attribute values from storage
	 */
	static void removeEntireSuiteOfSortableAttributeCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Locale locale,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		applySortableAttributeCompoundSuite(
			executor, referenceSchema, targetIndex, referenceKey, locale,
			existingDataSupplierFactory,
			AttributeIndexMutator::removeEntireSuiteOfSortableAttributeCompounds
		);
	}

	/**
	 * Evaluates local histogram triggers for a reference insertion and adds histogram entries to the appropriate
	 * index. Called after facet processing in {@link #referenceInsertPerComponent} to populate histogram indexes
	 * for newly created references.
	 *
	 * For each histogram trigger whose condition evaluates to `true`, reads the source attribute value
	 * and inserts it into the group or type index using the attribute's original numeric type. Handles both
	 * `REFERENCE_ATTRIBUTE` (read from reference storage) and `REFERENCED_ENTITY_ATTRIBUTE` (read from the
	 * referenced entity's global attribute storage) value sources.
	 *
	 * For localized source attributes, iterates all available locales for the source and inserts a separate
	 * histogram entry per locale. For non-localized attributes, a single entry with `locale = null` is inserted.
	 *
	 * @param executor             the mutation executor providing schema, storage access, and index operations
	 * @param referenceKey         identifies the specific referenced entity
	 * @param groupId              the group primary key, or null if not grouped
	 * @param entityPrimaryKey     the primary key of the owner entity
	 * @param existingDataSupplier supplies existing reference attribute values
	 * @param scope                the current scope
	 */
	static void addHistogramToIndex(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId,
		int entityPrimaryKey,
		@Nonnull ExistingAttributeValueSupplier existingDataSupplier,
		@Nonnull Scope scope
	) {
		final Collection<HistogramExpressionTrigger> histogramTriggers =
			executor.getLocalHistogramTriggers(referenceKey.referenceName(), scope);
		if (!histogramTriggers.isEmpty()) {
			final ContainerizedLocalMutationExecutor storageAccessor =
				(ContainerizedLocalMutationExecutor) executor.getContainerAccessor();
			for (final HistogramExpressionTrigger trigger : histogramTriggers) {
				final HistogramValueDescriptor resolution = trigger.getValueDescriptor();

				// evaluate condition (proxy instantiation + expression computation)
				final boolean conditionMet = trigger.evaluate(
					entityPrimaryKey, referenceKey,
					storageAccessor, executor.getSchemaResolver(),
					scope
				);
				if (!conditionMet) {
					continue;
				}

				if (resolution.localized()) {
					// localized source: iterate all available locales and insert per-locale entries
					if (resolution.source() == HistogramValueSource.REFERENCE_ATTRIBUTE) {
						final Set<Locale> locales = existingDataSupplier.getEntityExistingAttributeLocales();
						for (final Locale locale : locales) {
							final Serializable rawValue = readReferenceAttributeValue(
								existingDataSupplier, resolution.sourceAttributeName(), locale
							);
							insertHistogramValues(
								executor, referenceKey.referenceName(), trigger.getHistogramIndexName(),
								locale, resolution, rawValue, entityPrimaryKey, groupId, scope
							);
						}
					} else if (resolution.source() == HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE) {
						final String sourceEntityType = Objects.requireNonNull(resolution.sourceEntityType());
						final Set<Locale> locales = storageAccessor.getReferencedEntityAttributeLocales(
							sourceEntityType, referenceKey.primaryKey()
						);
						for (final Locale locale : locales) {
							final Serializable rawValue = storageAccessor.readReferencedEntityAttribute(
								sourceEntityType,
								referenceKey.primaryKey(),
								resolution.sourceAttributeName(),
								locale,
								scope
							);
							insertHistogramValues(
								executor, referenceKey.referenceName(), trigger.getHistogramIndexName(),
								locale, resolution, rawValue, entityPrimaryKey, groupId, scope
							);
						}
					} else {
						throw new GenericEvitaInternalError(
							"Unexpected histogram value source: " + resolution.source()
						);
					}
				} else {
					// non-localized: single read, single insert with locale = null
					final Serializable rawValue;
					if (resolution.source() == HistogramValueSource.REFERENCE_ATTRIBUTE) {
						rawValue = readReferenceAttributeValue(
							existingDataSupplier, resolution.sourceAttributeName(), null
						);
					} else if (resolution.source() == HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE) {
						rawValue = storageAccessor.readReferencedEntityAttribute(
							Objects.requireNonNull(resolution.sourceEntityType()),
							referenceKey.primaryKey(),
							resolution.sourceAttributeName(),
							null,
							scope
						);
					} else {
						throw new GenericEvitaInternalError(
							"Unexpected histogram value source: " + resolution.source()
						);
					}

					insertHistogramValues(
						executor, referenceKey.referenceName(), trigger.getHistogramIndexName(),
						null, resolution, rawValue, entityPrimaryKey, groupId, scope
					);
				}
			}
		}
	}

	/**
	 * Removes histogram entries using known pre-captured old values. Called when the value source
	 * attribute changed and old values were captured by {@link PreMutationHistogramSnapshot} before
	 * the mutation was applied. Performs surgical removal — no scanning, no condition evaluation.
	 *
	 * When `oldValues` is empty (attribute was null with no default), the entry was never created
	 * in the histogram, so no removal is needed.
	 *
	 * @param executor     the mutation executor
	 * @param referenceKey identifies the specific referenced entity
	 * @param groupId      the group primary key, or null if not grouped
	 * @param ownerPK      the primary key of the owner entity
	 * @param oldValues    pre-captured old histogram values (from PreMutationHistogramSnapshot)
	 * @param trigger      the histogram trigger whose value source was mutated
	 * @param locale       the locale for localized histograms, or `null` for non-localized
	 * @param scope        the current scope
	 */
	static void removeHistogramWithKnownOldValues(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId,
		int ownerPK,
		@Nonnull Serializable[] oldValues,
		@Nonnull HistogramExpressionTrigger trigger,
		@Nullable Locale locale,
		@Nonnull Scope scope
	) {
		final int indexedDecimalPlaces = trigger.getValueDescriptor().indexedDecimalPlaces();
		for (final Serializable value : oldValues) {
			removeSingleHistogramValue(
				executor, referenceKey.referenceName(), trigger.getHistogramIndexName(),
				locale, value, ownerPK, groupId, scope, indexedDecimalPlaces
			);
		}
	}

	/**
	 * Reads current histogram values for all triggers on a reference and removes entries that are
	 * present in the histogram. Used when the condition attribute changed (but not the value source),
	 * during reference removal, or during ungrouped-to-grouped group transfer.
	 *
	 * For each trigger, reads the source value, resolves to the histogram-value array (typed to the
	 * descriptor's `plainType` — either `Number` or `Range` instances), and removes each value only
	 * if it is verified present in the histogram (the condition may have been false when the reference
	 * was indexed, so the entry may not exist).
	 *
	 * @param executor     the mutation executor
	 * @param referenceKey identifies the specific referenced entity
	 * @param groupId      the group primary key, or null if not grouped
	 * @param ownerPK      the primary key of the owner entity
	 * @param scope        the current scope
	 */
	static void removeHistogramIfPresent(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId,
		int ownerPK,
		@Nonnull Scope scope
	) {
		final Collection<HistogramExpressionTrigger> histogramTriggers =
			executor.getLocalHistogramTriggers(referenceKey.referenceName(), scope);
		if (!histogramTriggers.isEmpty()) {
			final ExistingAttributeValueSupplier refAttrSupplier =
				executor.getStoragePartExistingDataFactory().getNormalizedReferenceAttributeValueSupplier(referenceKey);
			for (final HistogramExpressionTrigger trigger : histogramTriggers) {
				removeHistogramForTrigger(executor, referenceKey, groupId, ownerPK, trigger, refAttrSupplier, scope);
			}
		}
	}

	/**
	 * Removes histogram entries contributed by a single trigger for the given reference. Reads the
	 * current attribute value, resolves to the histogram-value array (typed to the descriptor's
	 * `plainType`), and removes each value only if it is verified present in the histogram.
	 *
	 * Extracted as a separate method so that callers needing per-trigger condition evaluation (e.g.,
	 * the synchronous reference removal path) can iterate triggers themselves, evaluate conditions,
	 * and call this method only for triggers whose condition is met.
	 *
	 * @param executor        the mutation executor
	 * @param referenceKey    identifies the specific referenced entity
	 * @param groupId         the group primary key, or null if not grouped
	 * @param ownerPK         the primary key of the owner entity
	 * @param trigger         the histogram trigger to process
	 * @param refAttrSupplier supplier for reading reference attribute values
	 * @param scope           the current scope
	 */
	static void removeHistogramForTrigger(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId,
		int ownerPK,
		@Nonnull HistogramExpressionTrigger trigger,
		@Nonnull ExistingAttributeValueSupplier refAttrSupplier,
		@Nonnull Scope scope
	) {
		final HistogramValueDescriptor resolution = trigger.getValueDescriptor();
		final Set<Locale> locales = resolveHistogramLocales(
			executor, referenceKey, resolution, refAttrSupplier
		);
		for (final Locale locale : locales) {
			final Serializable rawValue = readHistogramSourceValue(
				executor, referenceKey, resolution, refAttrSupplier, locale, scope
			);
			final Serializable[] values = resolveHistogramValues(rawValue, resolution);
			if (values.length > 0) {
				removeHistogramValuesWithGuard(
					executor, referenceKey.referenceName(), trigger.getHistogramIndexName(),
					locale, values, ownerPK, groupId, scope, resolution.indexedDecimalPlaces()
				);
			}
		}
	}

	/**
	 * Removes histogram entries for a reference being removed from indexes (entity removal path).
	 * Reads the current attribute value for each trigger and performs surgical removal. No condition
	 * evaluation needed — removal is unconditional (same pattern as facet removal).
	 *
	 * Reference data is still available at this synchronous call point (storage has pre-mutation data).
	 *
	 * @param executor         the mutation executor
	 * @param referenceKey     identifies the specific referenced entity
	 * @param groupId          the group primary key, or null if not grouped
	 * @param entityPrimaryKey the primary key of the owner entity
	 * @param scope            the current scope
	 */
	static void removeHistogramFromIndex(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId,
		int entityPrimaryKey,
		@Nonnull Scope scope
	) {
		final Collection<HistogramExpressionTrigger> histogramTriggers =
			executor.getLocalHistogramTriggers(referenceKey.referenceName(), scope);
		if (!histogramTriggers.isEmpty()) {
			final ExistingAttributeValueSupplier refAttrSupplier =
				executor.getStoragePartExistingDataFactory()
					.getNormalizedReferenceAttributeValueSupplier(referenceKey);
			for (final HistogramExpressionTrigger trigger : histogramTriggers) {
				final HistogramValueDescriptor resolution = trigger.getValueDescriptor();
				final Set<Locale> locales = resolveHistogramLocales(
					executor, referenceKey, resolution, refAttrSupplier
				);
				for (final Locale locale : locales) {
					final Serializable rawValue = readHistogramSourceValue(
						executor, referenceKey, resolution, refAttrSupplier, locale, scope
					);
					final Serializable[] values = resolveHistogramValues(rawValue, resolution);
					if (values.length > 0) {
						removeHistogramValuesWithGuard(
							executor, referenceKey.referenceName(), trigger.getHistogramIndexName(),
							locale, values, entityPrimaryKey, groupId, scope, resolution.indexedDecimalPlaces()
						);
					}
				}
			}
		}
	}

	/**
	 * Removes histogram entries from pre-resolved group reduced entity indexes. Used during deferred
	 * group transfer when cardinality data in the {@link EntityIndexType#REFERENCED_GROUP_ENTITY_TYPE}
	 * index may have already been cleaned up.
	 *
	 * Reads the current value for each trigger (the value hasn't changed — only the group assignment
	 * has changed), then removes from each pre-resolved RGEI with a histogram existence guard.
	 *
	 * @param executor        the mutation executor
	 * @param referenceName   the reference name
	 * @param referenceKey    the full reference key (needed for value reading)
	 * @param ownerPK         the primary key of the owner entity to remove
	 * @param groupStoragePKs pre-resolved storage PKs of ReducedGroupEntityIndex instances
	 * @param scope           the current scope
	 */
	static void removeHistogramFromPreResolvedGroupIndexes(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull String referenceName,
		@Nonnull ReferenceKey referenceKey,
		int ownerPK,
		@Nonnull int[] groupStoragePKs,
		@Nonnull Scope scope
	) {
		final Collection<HistogramExpressionTrigger> histogramTriggers =
			executor.getLocalHistogramTriggers(referenceName, scope);
		if (histogramTriggers.isEmpty()) {
			return;
		}
		final ExistingAttributeValueSupplier refAttrSupplier =
			executor.getStoragePartExistingDataFactory()
				.getNormalizedReferenceAttributeValueSupplier(referenceKey);
		for (final HistogramExpressionTrigger trigger : histogramTriggers) {
			final HistogramValueDescriptor resolution = trigger.getValueDescriptor();
			final Set<Locale> locales = resolveHistogramLocales(
				executor, referenceKey, resolution, refAttrSupplier
			);
			for (final Locale locale : locales) {
				final Serializable rawValue = readHistogramSourceValue(
					executor, referenceKey, resolution, refAttrSupplier, locale, scope
				);
				final Serializable[] values = resolveHistogramValues(rawValue, resolution);
				final int indexedDecimalPlaces = resolution.indexedDecimalPlaces();
				for (final Serializable value : values) {
					for (final int storagePK : groupStoragePKs) {
						final EntityIndex reducedIndex =
							executor.getEntityIndexByPrimaryKeyForModification(storagePK);
						if (reducedIndex instanceof HistogramCapableEntityIndex hcei) {
							if (isValueInHistogram(hcei, trigger.getHistogramIndexName(), locale, value, ownerPK)) {
								hcei.removeHistogramValue(
									trigger.getHistogramIndexName(), locale, value, ownerPK, indexedDecimalPlaces
								);
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Reads a reference attribute value from the existing data supplier by attribute name and optional locale.
	 *
	 * @param existingDataSupplier the supplier of existing reference attribute values
	 * @param attributeName        the name of the attribute to read
	 * @param locale               the locale for localized attributes, or `null` for non-localized
	 * @return the raw attribute value, or null if not present
	 */
	@Nullable
	static Serializable readReferenceAttributeValue(
		@Nonnull ExistingAttributeValueSupplier existingDataSupplier,
		@Nonnull String attributeName,
		@Nullable Locale locale
	) {
		final AttributeKey key = locale != null
			? new AttributeKey(attributeName, locale)
			: new AttributeKey(attributeName);
		return existingDataSupplier.getAttributeValue(key)
			.map(AttributeValue::value)
			.orElse(null);
	}

	/**
	 * Resolves raw attribute value to an array of histogram values for histogram operations.
	 * Returns either `Number` instances (for plain-numeric source attributes) or `Range` instances
	 * (when the descriptor's `innerNumericType()` is non-null, i.e. the source attribute is a
	 * `NumberRange` subtype). Handles scalar values, array-typed attributes, null values with
	 * defaults, and null values without defaults (returns empty array).
	 *
	 * For range-typed sources `defaultValue` is always `null` by descriptor invariant, so a
	 * missing raw value yields an empty result without consulting the default.
	 *
	 * @param rawValue   the raw attribute value (may be null)
	 * @param resolution the value resolution metadata
	 * @return array of histogram values typed to {@link HistogramValueDescriptor#plainType()};
	 *         empty if no values can be determined
	 */
	@Nonnull
	static Serializable[] resolveHistogramValues(
		@Nullable Serializable rawValue,
		@Nonnull HistogramValueDescriptor resolution
	) {
		final boolean rangeSource = resolution.innerNumericType() != null;
		if (rawValue == null) {
			if (resolution.defaultValue() != null) {
				return new Serializable[]{resolution.defaultValue()};
			}
			return new Serializable[0];
		}
		// values are returned raw — scale normalization happens once at the histogram-index write
		// boundary (HistogramIndexOperations); the removal existence guards normalize their probe
		// with the same scale (see removeHistogramValuesWithGuard / removeSingleHistogramValue)
		if (resolution.arrayType() && rawValue instanceof Serializable[] array) {
			int count = 0;
			for (final Serializable element : array) {
				if (rangeSource ? element instanceof Range<?> : element instanceof Number) {
					count++;
				}
			}
			final Serializable[] result = new Serializable[count];
			int idx = 0;
			for (final Serializable element : array) {
				if (rangeSource) {
					if (element instanceof Range<?> range) {
						result[idx++] = range;
					}
				} else if (element instanceof Number number) {
					result[idx++] = number;
				}
			}
			return result;
		}
		if (rangeSource && rawValue instanceof Range<?> range) {
			return new Serializable[]{range};
		}
		if (!rangeSource && rawValue instanceof Number number) {
			return new Serializable[]{number};
		}
		return new Serializable[0];
	}

	/**
	 * Extracts and returns the {@link RepresentativeReferenceKey} discriminator from the given
	 * reduced entity index, asserting that the discriminator has the expected type.
	 *
	 * @param targetIndex the reduced entity index whose discriminator is extracted
	 * @return the representative reference key discriminator
	 */
	@Nonnull
	private static RepresentativeReferenceKey extractRepresentativeReferenceKey(
		@Nonnull AbstractReducedEntityIndex targetIndex
	) {
		final Serializable discriminator = targetIndex.getIndexKey().discriminator();
		Assert.isPremiseValid(
			discriminator instanceof RepresentativeReferenceKey,
			"Entity index key discriminator must be RepresentativeReferenceKey!"
		);
		return (RepresentativeReferenceKey) discriminator;
	}

	/**
	 * Returns `true` if facet data should be written into `index` for the given reference schema and scope.
	 *
	 * For the **global** entity index this always returns `true` — facets are always tracked globally.
	 * For an {@link AbstractReducedEntityIndex} the decision depends on the index type of the **indexed** reference
	 * schema (the one the reduced index was created for, which may differ from `referenceSchema`):
	 * facets are written into a reduced index only when its owning reference schema is configured for
	 * {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING}.
	 *
	 * The `referenceSchema` parameter is an optimization hint: if its name matches the reduced index's reference name,
	 * it is reused directly without an additional schema look-up from the entity schema.
	 *
	 * @param index           the target index; may be a global {@link EntityIndex} or an
	 *                        {@link AbstractReducedEntityIndex}
	 * @param referenceSchema the schema for the reference being mutated (used as a schema look-up hint)
	 * @param scope           the scope in which the decision is evaluated
	 * @param executor        the mutation executor; used to look up the reduced index's own reference schema when needed
	 * @return `true` if a facet should be added to/removed from this index
	 */
	private static boolean shouldIndexFacetToTargetIndex(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull EntityIndexLocalMutationExecutor executor
	) {
		if (index instanceof AbstractReducedEntityIndex rei) {
			final ReferenceSchemaContract indexSchema;
			if (referenceSchema.getName().equals(rei.getReferenceKey().referenceName())) {
				indexSchema = referenceSchema;
			} else {
				indexSchema = executor
					.getEntitySchema()
					.getReferenceOrThrowException(rei.getReferenceKey().referenceName());
			}
			return isIndexedReferenceForFilteringAndPartitioning(indexSchema, scope);
		} else {
			return true;
		}
	}

	/**
	 * Returns `true` if the reference identified by `referenceKey` is configured as faceted in the given scope.
	 *
	 * The `referenceSchema` parameter is an optimization hint: if its name matches the reference key's reference name,
	 * it is used directly; otherwise the correct schema is retrieved from the entity schema via the executor.
	 *
	 * @param referenceKey    identifies the specific reference to evaluate
	 * @param referenceSchema the schema for the reference being mutated (used as a schema look-up hint)
	 * @param scope           the scope in which the faceted status is evaluated
	 * @param executor        the mutation executor used to retrieve the correct schema when the hint does not match
	 * @return `true` if the reference is configured as faceted (`isFacetedInScope`) in the given scope
	 */
	private static boolean isFaceted(
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull EntityIndexLocalMutationExecutor executor
	) {
		final ReferenceSchemaContract referenceSchemaToUse = getReferenceSchemaFor(
			referenceKey, referenceSchema, executor
		);
		return referenceSchemaToUse.isFacetedInScope(scope);
	}

	/**
	 * Resolves and returns the corresponding {@link ReferenceSchemaContract} for the provided {@link ReferenceKey}.
	 * If the reference name within the given {@code referenceKey} matches the name of the provided
	 * {@code referenceSchema},
	 * the {@code referenceSchema} is returned. Otherwise, the method retrieves the appropriate schema from
	 * the {@link EntityIndexLocalMutationExecutor}.
	 *
	 * @param referenceKey    the key that specifies the reference name to locate the schema for
	 * @param referenceSchema the current schema to compare against the reference key
	 * @param executor        the executor used to fetch the entity schema in case the provided schema name does not match
	 * @return the resolved {@link ReferenceSchemaContract} corresponding to the specified {@link ReferenceKey}
	 */
	@Nonnull
	private static ReferenceSchemaContract getReferenceSchemaFor(
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndexLocalMutationExecutor executor
	) {
		return referenceKey.referenceName().equals(referenceSchema.getName()) ?
			referenceSchema :
			executor.getEntitySchema().getReferenceOrThrowException(referenceKey.referenceName());
	}

	/**
	 * Checks whether a specific **entity** (identified by `entityPrimaryKey`) has its facet entry
	 * (identified by `referenceKey`) in the given `groupId` bucket of the `index`'s facet index.
	 * Returns `true` only if the entity PK is present in the bitmap of that facet within the specified group.
	 *
	 * For grouped facets (non-null `groupId`), iterates the grouped facet indexes to find the matching group,
	 * then checks the facet's bitmap for the entity PK. For ungrouped facets (null `groupId`), checks the
	 * not-grouped bucket via {@link FacetReferenceIndex#getNotGroupedFacets()}.
	 *
	 * This guard is necessary because index mutations are applied **before** storage mutations
	 * (see `LocalMutationExecutorCollector.execute`). When a facet's group changes via
	 * `SetReferenceGroupMutation`, the direct processing path creates the new group's reduced index
	 * via {@link #referenceInsertPerComponent}, which calls `indexAllFacets`. Since the storage part
	 * has not yet been updated, `indexAllFacets` reads the **stale old group** and inserts the facet
	 * under it. The direct path then adds the facet again under the **correct new group**, leaving
	 * the facet in both groups within the index.
	 *
	 * When cross-reference propagation subsequently tries to move the facet (reading the old group
	 * from the still-unmodified storage part), the facet may no longer reside in the old group bucket.
	 * Without this guard, calling `removeFacet` with a stale group would trigger assertion errors in
	 * {@link io.evitadb.index.facet.FacetIndex} or {@link FacetReferenceIndex}. All three call sites
	 * ({@link #setFacetGroupInIndex}, {@link #removeFacetGroupInIndex}, and
	 * {@link #removeFacetInIndexInternal}) use this check to silently skip the removal when the facet
	 * is not present in the expected group bucket.
	 *
	 * @param index            the entity index to check
	 * @param referenceKey     the reference key identifying the facet
	 * @param groupId          the group to check in, or `null` for ungrouped facets
	 * @param entityPrimaryKey the primary key of the entity whose membership is being verified
	 * @return `true` if the entity PK is present in the facet's bitmap within the specified group bucket
	 */
	private static boolean isFacetPresentInGroup(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId,
		int entityPrimaryKey
	) {
		final FacetReferenceIndex facetRefIndex = index.getFacetingEntities().get(referenceKey.referenceName());
		if (facetRefIndex == null) {
			return false;
		}
		if (groupId != null) {
			for (final FacetGroupIndex groupIndex : facetRefIndex.getGroupedFacets()) {
				if (Objects.equals(groupIndex.getGroupId(), groupId)) {
					final FacetIdIndex facetIdIndex = groupIndex.getFacetIdIndex(referenceKey.primaryKey());
					return facetIdIndex != null && facetIdIndex.getRecords().contains(entityPrimaryKey);
				}
			}
			return false;
		}
		// for ungrouped facets, check the not-grouped bucket directly
		final FacetGroupIndex notGrouped = facetRefIndex.getNotGroupedFacets();
		if (notGrouped == null) {
			return false;
		}
		final FacetIdIndex facetIdIndex = notGrouped.getFacetIdIndex(referenceKey.primaryKey());
		return facetIdIndex != null && facetIdIndex.getRecords().contains(entityPrimaryKey);
	}

	/**
	 * Determines whether the given entity was previously faceted for the specified reference in the given index.
	 * Checks both ungrouped and grouped facet buckets. This is used by the re-evaluation decision matrix:
	 *
	 * - `was=true, now=false` -> remove from facet index
	 * - `was=false, now=true` -> add to facet index
	 * - otherwise -> no-op
	 *
	 * @param index            the entity index to check
	 * @param referenceKey     the reference key identifying the facet
	 * @param entityPrimaryKey the entity primary key to look for in the facet bitmap
	 * @return `true` if the entity PK is present in any facet bucket for the given reference
	 */
	private static boolean wasFaceted(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceKey referenceKey,
		int entityPrimaryKey
	) {
		final FacetReferenceIndex facetRefIndex = index.getFacetingEntities().get(referenceKey.referenceName());
		if (facetRefIndex == null) {
			return false;
		}
		// check ungrouped bucket
		final FacetGroupIndex notGrouped = facetRefIndex.getNotGroupedFacets();
		if (notGrouped != null) {
			final FacetIdIndex facetIdIndex = notGrouped.getFacetIdIndex(referenceKey.primaryKey());
			if (facetIdIndex != null && facetIdIndex.getRecords().contains(entityPrimaryKey)) {
				return true;
			}
		}
		// check grouped buckets
		for (final FacetGroupIndex groupIndex : facetRefIndex.getGroupedFacets()) {
			final FacetIdIndex facetIdIndex = groupIndex.getFacetIdIndex(referenceKey.primaryKey());
			if (facetIdIndex != null && facetIdIndex.getRecords().contains(entityPrimaryKey)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Removes the entity from the facet index for the given reference, regardless of which group it is currently in.
	 * Scans both the ungrouped bucket and all grouped buckets to find and remove the entity. This is necessary during
	 * group transitions with expression evaluation, where the old group is not known from the storage part (it may
	 * have already been updated).
	 *
	 * @param index            the entity index containing the facet data
	 * @param referenceSchema  the schema of the reference
	 * @param referenceKey     the reference key identifying the facet
	 * @param entityPrimaryKey the entity PK to remove
	 */
	private static void removeFromCurrentGroup(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		int entityPrimaryKey
	) {
		final FacetReferenceIndex facetRefIndex = index.getFacetingEntities().get(referenceKey.referenceName());
		if (facetRefIndex == null) {
			return;
		}
		// check ungrouped bucket
		final FacetGroupIndex notGrouped = facetRefIndex.getNotGroupedFacets();
		if (notGrouped != null) {
			final FacetIdIndex facetIdIndex = notGrouped.getFacetIdIndex(referenceKey.primaryKey());
			if (facetIdIndex != null && facetIdIndex.getRecords().contains(entityPrimaryKey)) {
				index.removeFacet(referenceSchema, referenceKey, null, entityPrimaryKey);
				return;
			}
		}
		// check grouped buckets
		for (final FacetGroupIndex groupIndex : facetRefIndex.getGroupedFacets()) {
			final FacetIdIndex facetIdIndex = groupIndex.getFacetIdIndex(referenceKey.primaryKey());
			if (facetIdIndex != null && facetIdIndex.getRecords().contains(entityPrimaryKey)) {
				index.removeFacet(referenceSchema, referenceKey, groupIndex.getGroupId(), entityPrimaryKey);
				return;
			}
		}
	}

	/**
	 * Common skeleton for both inserting and removing sortable attribute compound suites.
	 * Applies `primaryOp` to both entity-level and reference-level attribute compound schemas.
	 */
	private static void applySortableAttributeCompoundSuite(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Locale locale,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nonnull SortableCompoundOperation primaryOp
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final RepresentativeReferenceKey indexRrk = extractRepresentativeReferenceKey(targetIndex);
		final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
			referenceKey, indexRrk.representativeAttributeValues()
		);

		// if the reference is indexed for filtering and partitioning, index attributes from the entity schema
		final Scope scope = targetIndex.getIndexKey().scope();
		if (isIndexedReferenceFor(referenceSchema, scope, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)) {
			final EntitySchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider =
				new EntitySchemaAttributeAndCompoundSchemaProvider(entitySchema);
			final ExistingAttributeValueSupplier entityAttributeValueSupplier =
				existingDataSupplierFactory.getNormalizedEntityAttributeValueSupplier();

			primaryOp.apply(
				executor, referenceSchema, targetIndex, locale, attributeSchemaProvider,
				entitySchema, entityAttributeValueSupplier
			);
		}

		// then apply to reference-level attributes and sortable compounds
		final ReferenceSchemaAttributeAndCompoundSchemaProvider referenceSchemaAttributeProvider =
			new ReferenceSchemaAttributeAndCompoundSchemaProvider(entitySchema, referenceSchema);
		final ExistingAttributeValueSupplier referenceAttributeValueSupplier =
			existingDataSupplierFactory.getNormalizedReferenceAttributeValueSupplier(rrk);

		primaryOp.apply(
			executor, referenceSchema, targetIndex, locale, referenceSchemaAttributeProvider,
			referenceSchema, referenceAttributeValueSupplier
		);
	}

	/**
	 * Executes `lambda` under the correct primary key context for the named attribute.
	 *
	 * For attributes whose type is assignable from {@link ReferencedEntityPredecessor}, the indexing operation
	 * must use the **referenced entity's** primary key (not the owning entity's), because predecessor ordering
	 * is expressed relative to the referenced entity. In that case `lambda` is wrapped in
	 * {@link EntityIndexLocalMutationExecutor#executeWithDifferentPrimaryKeyToIndex} with `primaryKeyToIndex`
	 * (the referenced entity's PK). For all other attribute types, `lambda` is invoked directly.
	 *
	 * @param executor                the mutation executor; provides the `executeWithDifferentPrimaryKeyToIndex`
	 *                                override mechanism
	 * @param primaryKeyToIndex       the referenced entity (or group) primary key to use when the attribute is a
	 *                                {@link ReferencedEntityPredecessor}
	 * @param attributeName           the name of the attribute being indexed; used to look up its schema
	 * @param attributeSchemaProvider resolves the {@link AttributeSchema} for a given attribute name
	 * @param lambda                  the indexing action to execute under the correct primary key context
	 */
	private static void executeWithProperPrimaryKey(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		int primaryKeyToIndex,
		@Nonnull String attributeName,
		@Nonnull Function<String, AttributeSchema> attributeSchemaProvider,
		@Nonnull Runnable lambda
	) {
		// we need to index entity primary key into the referenced entity index for all attributes
		final AttributeSchema attributeSchema = attributeSchemaProvider.apply(attributeName);
		if (ReferencedEntityPredecessor.class.isAssignableFrom(attributeSchema.getPlainType())) {
			executor.executeWithDifferentPrimaryKeyToIndex(
				(indexType, target) -> primaryKeyToIndex, lambda
			);
		} else {
			lambda.run();
		}
	}

	/**
	 * Populates a newly created reduced entity index with all existing indexable data for the owning entity. Called
	 * when an entity's primary key is inserted into the reduced index for the first time — i.e. the first time a
	 * reference to this particular referenced entity is added to the owning entity.
	 *
	 * The method iterates and indexes:
	 * - all facets (all references on the entity that are configured as faceted in the index's scope),
	 * - all locales and attribute locales from the entity body,
	 * - all indexed prices (only when the reference schema is `FOR_FILTERING_AND_PARTITIONING`),
	 * - all indexed entity-level and reference-level attributes, and
	 * - all sortable attribute compounds.
	 *
	 * @param executor                    the mutation executor providing entity schema, container access and index ops
	 * @param targetIndex                 the newly populated reduced entity index
	 * @param entitySchema                the entity schema of the owning entity
	 * @param referenceSchema             the schema of the reference being inserted
	 * @param referenceKey                the actual reference key identifying the referenced entity (used instead of the
	 *                                    index discriminator, which may contain the group PK for group-level indexes)
	 * @param entityPrimaryKey            the primary key of the owning entity being indexed into the reduced index
	 * @param existingDataSupplierFactory factory that supplies the entity's current attributes, prices and references
	 */
	private static void indexAllExistingData(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		int entityPrimaryKey,
		boolean entityFirstIndexedInTargetIndex,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		final String entityType = entitySchema.getName();

		// per-reference fanout: facets are keyed by (referenceKey, entityPrimaryKey) and the
		// idempotency check in `indexAllFacets` ensures duplicates do not pile up when multiple
		// references on the same entity resolve to the same group reduced index
		indexAllFacets(executor, referenceSchema, targetIndex, entityPrimaryKey);

		// per-reference fanout: reference attributes and reference-attribute sortable compounds
		// are keyed by the reference's primary key — each reference contributes its own keys, so
		// every reference must run this branch regardless of whether the entity is already present
		indexAllReferenceLevelAttributes(
			executor, referenceSchema, targetIndex, referenceKey, existingDataSupplierFactory
		);
		insertInitialSuiteOfSortableAttributeCompounds(
			executor, referenceSchema, targetIndex, referenceKey, null, existingDataSupplierFactory
		);

		// entity-level data (locales, prices, entity attributes) is shared across all references
		// resolving to this reduced index — index it exactly once, on the first reference that
		// causes the entity to enter the index. Set-semantic leaves (price indexes) would otherwise
		// either accumulate the same record once (idempotent insert) and then collapse to zero on
		// the first remove leaving subsequent references with a corrupt view
		if (entityFirstIndexedInTargetIndex) {
			final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
			final EntityBodyStoragePart entityCnt = containerAccessor.getEntityStoragePart(
				entityType, entityPrimaryKey, EntityExistence.MUST_EXIST);

			for (Locale locale : entityCnt.getLocales()) {
				executor.upsertEntityLocaleInTargetIndex(
					locale, entitySchema, targetIndex, entityPrimaryKey
				);
			}
			for (Locale locale : entityCnt.getAttributeLocales()) {
				executor.upsertEntityAttributeLocaleInTargetIndex(
					locale, entitySchema, targetIndex, referenceKey, existingDataSupplierFactory
				);
			}

			indexAllPrices(
				executor, referenceSchema, targetIndex, existingDataSupplierFactory.getPriceSupplier());
			indexAllEntityLevelAttributes(
				executor, referenceSchema, targetIndex, referenceKey, existingDataSupplierFactory
			);
		}
	}

	/**
	 * Indexes all faceted references of the owning entity into `targetIndex`. Only references that are currently
	 * stored (not dropped) and whose schema marks them as faceted in the index's scope are processed.
	 *
	 * To avoid redundant schema and trigger lookups the method takes advantage of the fact that references in
	 * {@link ReferencesStoragePart} are stored sorted by reference name — both the schema and the expression trigger
	 * are re-fetched only when the reference name changes between iterations.
	 *
	 * This method is called from {@link #indexAllExistingData} when an entity is first inserted into a reduced index.
	 *
	 * @param executor           the mutation executor; provides the references storage part and schema access
	 * @param referenceSchema    the reference schema for which the reduced index was just created; used as a hint
	 *                           to avoid re-fetching when the current reference name matches
	 * @param targetIndex        the reduced entity index receiving the facet entries
	 * @param entityPrimaryKey   the primary key of the owning entity
	 */
	private static void indexAllFacets(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		int entityPrimaryKey
	) {
		final Scope scope = targetIndex.getIndexKey().scope();
		if (shouldIndexFacetToTargetIndex(targetIndex, referenceSchema, scope, executor)) {
			final ReferencesStoragePart referencesStorageContainer = executor.getReferencesStoragePart();

			ReferenceSchemaContract referenceKeySchema = null;
			FacetExpressionTrigger cachedTrigger = null;
			boolean cachedTriggerResolved = false;
			for (ReferenceContract reference : referencesStorageContainer.getReferences()) {
				final ReferenceKey referenceKey = reference.getReferenceKey();
				final Optional<GroupEntityReference> groupReference = reference.getGroup();

				// we gain advantage of sorted references in the storage container to avoid unnecessary schema lookups
				if (referenceKeySchema == null ||
					!referenceKeySchema.getName().equals(referenceKey.referenceName())) {
					referenceKeySchema = getReferenceSchemaFor(referenceKey, referenceSchema, executor);
					cachedTriggerResolved = false;
				}
				if (reference.exists() && referenceKeySchema.isFacetedInScope(scope)) {
					// cache trigger lookup across consecutive references of the same type
					if (!cachedTriggerResolved) {
						cachedTrigger = executor.getTriggerFor(referenceKey.referenceName(), scope);
						cachedTriggerResolved = true;
					}
					if (cachedTrigger != null && !cachedTrigger.evaluate(
						entityPrimaryKey, referenceKey, executor.getContainerAccessor(),
						executor.getSchemaResolver(), scope
					)) {
						continue;
					}
					final Integer groupId = groupReference
						.filter(Droppable::exists)
						.map(GroupEntityReference::getPrimaryKey)
						.orElse(null);
					targetIndex.addFacet(referenceSchema, referenceKey, groupId, entityPrimaryKey);
				}
			}
		}
	}

	/**
	 * Indexes all existing prices of the owning entity into `targetIndex`. Prices are only indexed when the reference
	 * schema is configured for {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING} in the index's scope;
	 * for {@link ReferenceIndexType#FOR_FILTERING} references this method is a no-op.
	 *
	 * Called from {@link #indexAllExistingData} when an entity is first inserted into a reduced index.
	 *
	 * @param executor              the mutation executor coordinating the price index updates
	 * @param referenceSchema       the reference schema; determines whether price indexing applies
	 * @param targetIndex           the reduced entity index into which prices are inserted
	 * @param existingPriceSupplier supplies the entity's current prices and price inner-record handling
	 */
	private static void indexAllPrices(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull ExistingPriceSupplier existingPriceSupplier
	) {
		final Scope scope = targetIndex.getIndexKey().scope();
		if (isIndexedReferenceFor(referenceSchema, scope, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)) {
			existingPriceSupplier
				.getExistingPrices()
				.forEach(price ->
					         PriceIndexMutator.priceUpsert(
						         executor,
						         referenceSchema,
						         targetIndex,
						         price.priceKey(),
						         price.innerRecordId(),
						         price.validity(),
						         price.priceWithoutTax(),
						         price.priceWithTax(),
						         price.indexed(),
						         null,
						         existingPriceSupplier.getPriceInnerRecordHandling(),
						         PriceIndexMutator.createPriceProvider(price)
					         )
				);
		}
	}

	/**
	 * Indexes the entity-level (owning-entity) attributes of the given entity into `targetIndex`. The
	 * one-shot half of the attribute fanout: entity attributes are keyed by the owner entity's PK and
	 * are shared across all references resolving to the same reduced index, so this branch fires
	 * exactly once per (entity, index) pair when the entity first enters the index.
	 *
	 * Entity-level attributes are only indexed when the reference schema is configured for
	 * {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING} in the index's scope; otherwise the
	 * reduced index does not expose entity attribute lookups.
	 */
	private static void indexAllEntityLevelAttributes(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		// only index entity-level attributes when the reference is configured for filtering and partitioning;
		// otherwise the reduced index does not expose entity attribute lookups and we can skip the work
		final Scope scope = targetIndex.getIndexKey().scope();
		if (isIndexedReferenceFor(referenceSchema, scope, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)) {
			final EntitySchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider =
				new EntitySchemaAttributeAndCompoundSchemaProvider(entitySchema);

			existingDataSupplierFactory.getNormalizedEntityAttributeValueSupplier()
				.getAttributeValues()
				.forEach(
					attribute ->
						AttributeIndexMutator.executeAttributeUpsert(
							executor,
							referenceSchema,
							attributeSchemaProvider,
							NO_EXISTING_VALUE_SUPPLIER,
							targetIndex,
							targetIndex,
							attribute.key(),
							Objects.requireNonNull(attribute.value()),
							false,
							false
						)
				);
		}
	}

	/**
	 * Indexes the reference-level attributes of the given reference into `targetIndex`. Reference-level
	 * attributes are keyed by the reference's primary key (the referenced entity's PK), not the owner
	 * entity's PK, so two different references on the same owner contribute under different cardinality
	 * keys and must be indexed independently — this is the per-reference half of
	 * {@link #indexAllEntityLevelAttributes} that {@link #indexAllExistingData} fires for every
	 * reference resolving to a given group reduced index.
	 */
	private static void indexAllReferenceLevelAttributes(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final RepresentativeReferenceKey indexRrk = extractRepresentativeReferenceKey(targetIndex);
		final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
			referenceKey, indexRrk.representativeAttributeValues()
		);
		final ReferenceSchemaAttributeAndCompoundSchemaProvider referenceSchemaAttributeProvider =
			new ReferenceSchemaAttributeAndCompoundSchemaProvider(
				entitySchema, referenceSchema
			);

		// for grouped references (RGEI), key the reference-attribute FilterIndex records on the
		// referenced entity PK instead of the owner PK — this is what enables REFERENCE_ATTRIBUTE
		// histogram boundary resolution on grouped references (matches the RTEI scheme established
		// in `referenceInsertPerComponent` where `executeWithDifferentPrimaryKeyToIndex` swaps the
		// recordId to the reduced-index PK before inserting into the RTEI attribute FilterIndex)
		final Runnable indexReferenceAttributes = () -> existingDataSupplierFactory
			.getNormalizedReferenceAttributeValueSupplier(rrk)
			.getAttributeValues()
			.forEach(attribute ->
				         executeWithProperPrimaryKey(
					         executor,
					         rrk.primaryKey(),
					         attribute.key().attributeName(),
					         referenceSchemaAttributeProvider::getAttributeSchema,
					         () -> AttributeIndexMutator.executeAttributeUpsert(
						         executor,
						         referenceSchema,
						         referenceSchemaAttributeProvider,
						         NO_EXISTING_VALUE_SUPPLIER,
						         targetIndex,
						         targetIndex,
						         attribute.key(),
						         Objects.requireNonNull(attribute.value()),
						         false,
						         false
					         )
				         )
			);
		if (targetIndex instanceof ReducedGroupEntityIndex) {
			executor.executeWithDifferentPrimaryKeyToIndex(
				(indexType, target) -> rrk.primaryKey(), indexReferenceAttributes
			);
		} else {
			indexReferenceAttributes.run();
		}
	}

	/**
	 * Removes all previously indexed data for the owning entity from the given reduced entity index. Called when an
	 * entity's primary key is removed from the reduced index for the last time — i.e. the last reference to this
	 * particular referenced entity has been deleted from the owning entity.
	 *
	 * This is the symmetric counterpart of {@link #indexAllExistingData} and follows the same ordering in reverse:
	 * facets, locales, prices, entity-level and reference-level attributes, and sortable attribute compounds.
	 *
	 * @param executor                    the mutation executor providing entity schema, container access and index ops
	 * @param targetIndex                 the reduced entity index from which data is removed
	 * @param entitySchema                the entity schema of the owning entity
	 * @param referenceSchema             the schema of the reference being removed
	 * @param referenceKey                the actual reference key identifying the referenced entity (used instead of the
	 *                                    index discriminator, which may contain the group PK for group-level indexes)
	 * @param entityPrimaryKey            the primary key of the owning entity being de-indexed from the reduced index
	 * @param existingDataSupplierFactory factory supplying the entity's current attributes, prices and references
	 */
	private static void removeAllExistingData(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		int entityPrimaryKey,
		boolean entityFullyRemovedFromTargetIndex,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		final String entityType = entitySchema.getName();

		// per-reference fanout: facets are removed using the (referenceKey, entityPrimaryKey) tuple
		// and `removeAllFacets` already filters via `wasFaceted` so duplicate calls from sibling
		// references resolving to the same group reduced index are idempotent
		removeAllFacets(executor, referenceSchema, targetIndex, entityPrimaryKey);

		// per-reference fanout: reference attributes and reference-attribute sortable compounds are
		// keyed by this reference's primary key — each reference owns its own keys, so every
		// reference removal must run this branch regardless of whether other references keep the
		// entity present in the target index
		removeAllReferenceLevelAttributes(
			executor, referenceSchema, targetIndex, referenceKey, existingDataSupplierFactory
		);
		removeEntireSuiteOfSortableAttributeCompounds(
			executor, referenceSchema, targetIndex, referenceKey, null, existingDataSupplierFactory
		);

		// entity-level data (locales, prices, entity attributes) is shared across all references
		// resolving to this reduced index — de-index it exactly once, on the last reference whose
		// removal causes the entity to leave the index entirely. Without this gating, set-semantic
		// leaves (price indexes) would have their bucket destroyed by the first reference's removal
		// while sibling references still expect the data to be present
		if (entityFullyRemovedFromTargetIndex) {
			final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
			final EntityBodyStoragePart entityCnt = containerAccessor.getEntityStoragePart(
				entityType, entityPrimaryKey, EntityExistence.MUST_EXIST);

			for (Locale locale : entityCnt.getLocales()) {
				executor.removeEntityLocaleInTargetIndex(
					locale, entitySchema, targetIndex, entityPrimaryKey
				);
			}
			for (Locale locale : entityCnt.getAttributeLocales()) {
				executor.removeEntityAttributeLocaleInTargetIndex(
					locale, entitySchema, targetIndex, referenceKey, existingDataSupplierFactory
				);
			}

			removeAllPrices(
				executor, referenceSchema, targetIndex, existingDataSupplierFactory.getPriceSupplier()
			);
			removeAllEntityLevelAttributes(
				executor, referenceSchema, targetIndex, referenceKey, existingDataSupplierFactory
			);
		}
	}

	/**
	 * Removes all faceted references of the owning entity from `targetIndex`. The inverse of {@link #indexAllFacets}.
	 *
	 * Only references that are currently stored (not dropped) and whose schema marks them as faceted in the index's
	 * scope are processed. The same sorted-iteration schema-caching optimization as in {@link #indexAllFacets} is
	 * applied to avoid redundant schema look-ups.
	 *
	 * @param executor           the mutation executor; provides the references storage part and schema access
	 * @param referenceSchema    the reference schema for which the reduced index is being cleaned up; used as a hint
	 * @param targetIndex        the reduced entity index from which facet entries are removed
	 * @param entityPrimaryKey   the primary key of the owning entity
	 */
	private static void removeAllFacets(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		int entityPrimaryKey
	) {
		final Scope scope = targetIndex.getIndexKey().scope();
		if (shouldIndexFacetToTargetIndex(targetIndex, referenceSchema, scope, executor)) {
			final ReferencesStoragePart referencesStorageContainer = executor.getReferencesStoragePart();

			ReferenceSchemaContract referenceKeySchema = null;
			for (ReferenceContract reference : referencesStorageContainer.getReferences()) {
				final ReferenceKey referenceKey = reference.getReferenceKey();
				// we gain advantage of sorted references in the storage container to avoid unnecessary schema lookups
				referenceKeySchema =
					referenceKeySchema == null ||
						!referenceKeySchema.getName().equals(referenceKey.referenceName())
						? getReferenceSchemaFor(referenceKey, referenceSchema, executor)
						: referenceKeySchema;
				if (reference.exists() && referenceKeySchema.isFacetedInScope(scope)
					&& wasFaceted(targetIndex, referenceKey, entityPrimaryKey)) {
					removeFacetInIndexInternal(
						targetIndex, referenceSchema, entityPrimaryKey, reference
					);
				}
			}
		}
	}

	/**
	 * Removes a facet associated with a given entity in the provided index.
	 *
	 * During cross-reference propagation, the facet may have already been moved to a different group by
	 * direct processing (e.g. via {@link #referenceInsertPerComponent}). In that case, the storage-derived
	 * group is stale and the facet does not exist in the old group bucket. The method handles this
	 * gracefully by checking facet presence before removal via {@link #isFacetPresentInGroup}.
	 *
	 * @param index              the entity index where the facet is to be removed
	 * @param referenceSchema    the schema of the reference that identifies the facet
	 * @param entityPrimaryKey   the primary key of the entity whose facet is to be removed
	 * @param existingReference  the existing reference containing the facet key and optional group information
	 */
	private static void removeFacetInIndexInternal(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceSchemaContract referenceSchema,
		int entityPrimaryKey,
		@Nonnull ReferenceContract existingReference
	) {
		final ReferenceKey referenceKey = existingReference.getReferenceKey();
		final Integer groupId = existingReference.getGroup()
			.filter(Droppable::exists)
			.map(EntityReferenceContract::getPrimaryKey)
			.orElse(null);
		// only remove from the group if the facet is actually present there — during
		// cross-reference propagation the direct processing path may have already moved
		// the facet, making the storage-derived group stale
		if (isFacetPresentInGroup(index, referenceKey, groupId, entityPrimaryKey)) {
			index.removeFacet(referenceSchema, referenceKey, groupId, entityPrimaryKey);
		}
	}

	/**
	 * Removes all indexed prices of the owning entity from `targetIndex`. The inverse of {@link #indexAllPrices}.
	 *
	 * Prices are only removed when the reference schema is configured for
	 * {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING} in the index's scope; otherwise this method is
	 * a no-op.
	 *
	 * @param executor              the mutation executor coordinating the price index updates
	 * @param referenceSchema       the reference schema; determines whether price de-indexing applies
	 * @param targetIndex           the reduced entity index from which prices are removed
	 * @param existingPriceSupplier supplies the entity's current prices needed for the removal key
	 */
	private static void removeAllPrices(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull ExistingPriceSupplier existingPriceSupplier
	) {
		final Scope scope = targetIndex.getIndexKey().scope();
		if (isIndexedReferenceFor(referenceSchema, scope, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)) {
			existingPriceSupplier.getExistingPrices()
				.forEach(
					price -> PriceIndexMutator.priceRemove(
						executor,
						referenceSchema,
						targetIndex,
						price.priceKey(),
						existingPriceSupplier
					)
				);
		}
	}

	/**
	 * Removes the entity-level (owning-entity) attributes of the given entity from `targetIndex`. The
	 * counterpart of {@link #indexAllEntityLevelAttributes}: entity attributes are keyed by the owner
	 * entity's PK and are shared across all references resolving to the same reduced index, so this
	 * branch fires exactly once per (entity, index) pair when the entity is fully leaving the index.
	 *
	 * Entity-level attributes are only de-indexed when the reference schema is configured for
	 * {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING} in the index's scope; otherwise the
	 * reduced index never received the entity attribute records to begin with.
	 */
	private static void removeAllEntityLevelAttributes(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		// only de-index entity-level attributes when the reference is configured for filtering and
		// partitioning; otherwise the reduced index never received entity attribute records to begin with
		final Scope scope = targetIndex.getIndexKey().scope();
		if (isIndexedReferenceFor(referenceSchema, scope, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)) {
			final EntitySchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider =
				new EntitySchemaAttributeAndCompoundSchemaProvider(entitySchema);

			existingDataSupplierFactory.getNormalizedEntityAttributeValueSupplier()
				.getAttributeValues()
				.forEach(attribute ->
					         AttributeIndexMutator.executeAttributeRemoval(
						         executor,
						         referenceSchema,
						         attributeSchemaProvider,
						         existingDataSupplierFactory.getNormalizedEntityAttributeValueSupplier(),
						         targetIndex,
						         targetIndex,
						         attribute.key(),
						         false,
						         false
					         )
				);
		}
	}

	/**
	 * Removes the reference-level attributes of the given reference from `targetIndex`. The per-reference
	 * counterpart of {@link #removeAllEntityLevelAttributes}: reference attributes are keyed by the
	 * reference's primary key, so each reference removal must drop its own keys regardless of whether
	 * the entity remains in the target index via sibling references.
	 */
	private static void removeAllReferenceLevelAttributes(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final RepresentativeReferenceKey indexRrk = extractRepresentativeReferenceKey(targetIndex);
		final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
			referenceKey, indexRrk.representativeAttributeValues()
		);
		final ReferenceSchemaAttributeAndCompoundSchemaProvider referenceSchemaAttributeProvider =
			new ReferenceSchemaAttributeAndCompoundSchemaProvider(
				entitySchema, referenceSchema
			);

		final ExistingAttributeValueSupplier referenceAttributeValueSupplier = existingDataSupplierFactory
			.getNormalizedReferenceAttributeValueSupplier(rrk);
		// mirror the RGEI re-keying performed during insert: reference-attribute FilterIndex records
		// on grouped reduced indexes live under the referenced entity PK, not the owner PK
		final Runnable removeReferenceAttributes = () -> referenceAttributeValueSupplier
			.getAttributeValues()
			.forEach(attribute ->
				         executeWithProperPrimaryKey(
					         executor,
					         rrk.primaryKey(),
					         attribute.key().attributeName(),
					         referenceSchemaAttributeProvider::getAttributeSchema,
					         () -> AttributeIndexMutator.executeAttributeRemoval(
						         executor,
						         referenceSchema,
						         referenceSchemaAttributeProvider,
						         referenceAttributeValueSupplier,
						         targetIndex,
						         targetIndex,
						         attribute.key(),
						         false,
						         false
					         )
				         )
			);
		if (targetIndex instanceof ReducedGroupEntityIndex) {
			executor.executeWithDifferentPrimaryKeyToIndex(
				(indexType, target) -> rrk.primaryKey(), removeReferenceAttributes
			);
		} else {
			removeReferenceAttributes.run();
		}
	}

	/**
	 * Reads the histogram source attribute value for the given trigger, dispatching to either
	 * reference attribute or referenced entity attribute depending on the value source.
	 *
	 * @param executor        the mutation executor
	 * @param referenceKey    the reference key
	 * @param resolution      the value descriptor specifying source type, attribute name, and entity type
	 * @param refAttrSupplier supplier for reference attribute values
	 * @param locale          the locale for localized attributes, or `null`
	 * @return the raw attribute value, or null if not present
	 */
	@Nullable
	private static Serializable readHistogramSourceValue(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull HistogramValueDescriptor resolution,
		@Nonnull ExistingAttributeValueSupplier refAttrSupplier,
		@Nullable Locale locale,
		@Nonnull Scope scope
	) {
		if (resolution.source() == HistogramValueSource.REFERENCE_ATTRIBUTE) {
			return readReferenceAttributeValue(refAttrSupplier, resolution.sourceAttributeName(), locale);
		} else if (resolution.source() == HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE) {
			return ((ContainerizedLocalMutationExecutor) executor.getContainerAccessor())
				.readReferencedEntityAttribute(
					Objects.requireNonNull(resolution.sourceEntityType()),
					referenceKey.primaryKey(),
					resolution.sourceAttributeName(),
					locale,
					scope
				);
		} else {
			throw new GenericEvitaInternalError("Unexpected histogram value source: " + resolution.source());
		}
	}

	/**
	 * Resolves the set of locales for a histogram trigger. For non-localized attributes returns a
	 * singleton set with a `null` locale. For localized attributes, resolves the locale set from
	 * the appropriate source (reference attribute locales or referenced entity attribute locales).
	 *
	 * @param executor        the mutation executor
	 * @param referenceKey    the reference key
	 * @param resolution      the value descriptor
	 * @param refAttrSupplier supplier for reference attribute locales
	 * @return set of locales (contains `null` for non-localized)
	 */
	@Nonnull
	private static Set<Locale> resolveHistogramLocales(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull HistogramValueDescriptor resolution,
		@Nonnull ExistingAttributeValueSupplier refAttrSupplier
	) {
		if (!resolution.localized()) {
			return Collections.singleton(null);
		}
		if (resolution.source() == HistogramValueSource.REFERENCE_ATTRIBUTE) {
			return refAttrSupplier.getEntityExistingAttributeLocales();
		} else if (resolution.source() == HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE) {
			return ((ContainerizedLocalMutationExecutor) executor.getContainerAccessor())
				.getReferencedEntityAttributeLocales(
					Objects.requireNonNull(resolution.sourceEntityType()),
					referenceKey.primaryKey()
				);
		} else {
			throw new GenericEvitaInternalError("Unexpected histogram value source: " + resolution.source());
		}
	}

	/**
	 * Removes histogram values from the appropriate index (grouped or ungrouped), guarded by
	 * {@link #isValueInHistogram} to prevent cardinality assertion failures for entries that were
	 * never inserted (condition was false at index time).
	 *
	 * Values may be `Number` instances (for plain-numeric histograms) or `Range` instances (for
	 * range-typed histograms). The downstream index APIs accept `Serializable` directly.
	 *
	 * @param executor             the mutation executor
	 * @param referenceName        the reference name
	 * @param histogramName        the histogram definition name
	 * @param locale               the locale, or `null` for non-localized
	 * @param values               the histogram values to remove (`Number` or `Range` instances)
	 * @param ownerPK              the primary key of the owner entity
	 * @param groupId              the group primary key (null for ungrouped)
	 * @param scope                the current scope
	 * @param indexedDecimalPlaces the source attribute schema's indexed decimal places, threaded to the
	 *                             histogram-index write boundary for scale normalization
	 */
	private static void removeHistogramValuesWithGuard(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Serializable[] values,
		int ownerPK,
		@Nullable Integer groupId,
		@Nonnull Scope scope,
		int indexedDecimalPlaces
	) {
		for (final Serializable value : values) {
			if (groupId != null) {
				final EntityIndexKey groupTypeKey = new EntityIndexKey(
					EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, scope, referenceName
				);
				final EntityIndex groupTypeIndex = executor.getIndexIfExists(groupTypeKey);
				if (groupTypeIndex instanceof ReferencedTypeEntityIndex rtei) {
					final int[] storagePKs = rtei.getAllReferenceIndexes(groupId);
					for (final int storagePK : storagePKs) {
						final EntityIndex reducedIndex =
							executor.getEntityIndexByPrimaryKeyForModification(storagePK);
						if (reducedIndex instanceof HistogramCapableEntityIndex hcei) {
							if (isValueInHistogram(hcei, histogramName, locale, value, ownerPK)) {
								hcei.removeHistogramValue(histogramName, locale, value, ownerPK, indexedDecimalPlaces);
							}
						}
					}
				}
			} else {
				final EntityIndexKey typeKey = new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName
				);
				final EntityIndex typeIndex = executor.getIndexIfExists(typeKey);
				if (typeIndex instanceof HistogramCapableEntityIndex hcei) {
					if (isValueInHistogram(hcei, histogramName, locale, value, ownerPK)) {
						executor.getOrCreateIndex(typeKey);
						hcei.removeHistogramValue(histogramName, locale, value, ownerPK, indexedDecimalPlaces);
					}
				}
			}
		}
	}

	/**
	 * Removes a single histogram value from the appropriate index (grouped or ungrouped) with an
	 * existence guard. Used by {@link #removeHistogramWithKnownOldValues} where the old values are
	 * captured from pre-mutation storage state. The guard is necessary because the condition
	 * expression may have been `false` when the value was originally indexed — the attribute existed
	 * in storage but was never added to the histogram.
	 *
	 * @param executor             the mutation executor
	 * @param referenceName        the reference name
	 * @param histogramName        the histogram definition name
	 * @param locale               the locale, or `null` for non-localized
	 * @param value                the histogram value to remove (`Number` or `Range` instance)
	 * @param ownerPK              the primary key of the owner entity
	 * @param groupId              the group primary key (null for ungrouped)
	 * @param scope                the current scope
	 * @param indexedDecimalPlaces the source attribute schema's indexed decimal places, threaded to the
	 *                             histogram-index write boundary for scale normalization
	 */
	private static void removeSingleHistogramValue(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK,
		@Nullable Integer groupId,
		@Nonnull Scope scope,
		int indexedDecimalPlaces
	) {
		if (groupId != null) {
			final EntityIndexKey groupTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, scope, referenceName
			);
			final EntityIndex groupTypeIndex = executor.getIndexIfExists(groupTypeKey);
			if (groupTypeIndex instanceof ReferencedTypeEntityIndex rtei) {
				final int[] storagePKs = rtei.getAllReferenceIndexes(groupId);
				for (final int storagePK : storagePKs) {
					final EntityIndex reducedIndex = executor.getEntityIndexByPrimaryKeyForModification(storagePK);
					if (reducedIndex instanceof HistogramCapableEntityIndex hcei) {
						if (isValueInHistogram(hcei, histogramName, locale, value, ownerPK)) {
							hcei.removeHistogramValue(histogramName, locale, value, ownerPK, indexedDecimalPlaces);
						}
					}
				}
			}
		} else {
			final EntityIndexKey typeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName
			);
			final EntityIndex typeIndex = executor.getIndexIfExists(typeKey);
			if (typeIndex instanceof HistogramCapableEntityIndex hcei) {
				if (isValueInHistogram(hcei, histogramName, locale, value, ownerPK)) {
					executor.getOrCreateIndex(typeKey);
					hcei.removeHistogramValue(histogramName, locale, value, ownerPK, indexedDecimalPlaces);
				}
			}
		}
	}

	/**
	 * Checks whether the histogram index contains the given (value, ownerPK) pair. Returns `false`
	 * when the histogram index is null, the FilterIndex for the locale is null, or the specific value
	 * bucket does not contain the ownerPK.
	 *
	 * This is an O(log n) point lookup: `getInvertedIndex().getRecordsEqualTo(normalizedValue)` fetches
	 * only the bucket keyed by the probe value (an empty bitmap on miss) rather than scanning every
	 * bucket, and RoaringBitmap `contains()` is O(1).
	 *
	 * The stored bucket values are canonicalized (e.g. a scaled `Integer` for a `BigDecimal` value type)
	 * at the histogram-index write boundary, so the probe `value` is canonicalized through the histogram
	 * index's own normalizer before the lookup — otherwise a raw `BigDecimal`/`BigDecimalNumberRange`
	 * would never match its stored (scaled / re-encoded) counterpart and the guard would suppress a valid
	 * removal.
	 *
	 * @param entityIndex          the histogram-capable entity index
	 * @param histogramName        the histogram definition name
	 * @param locale               the locale, or `null` for non-localized
	 * @param value                the value to check
	 * @param ownerPK              the owner PK to check
	 * @return `true` if the histogram contains the (value, ownerPK) pair
	 */
	private static boolean isValueInHistogram(
		@Nonnull HistogramCapableEntityIndex entityIndex,
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK
	) {
		final HistogramIndex histogramIndex = entityIndex.getHistogramIndex(histogramName);
		if (histogramIndex == null) {
			return false;
		}
		final FilterIndex filterIndex = histogramIndex.getFilterIndex(locale);
		if (filterIndex == null) {
			return false;
		}
		// buckets store the canonicalized key (e.g. a scaled Integer for a BigDecimal value type), so the raw
		// probe must be normalized through the same path before the lookup
		final Serializable normalizedValue = histogramIndex.normalizeValue(value);
		// direct O(log n) tree lookup for the single bucket keyed by the normalized value - avoids materializing the
		// entire histogram just to test membership in one bucket (getRecordsEqualTo returns an empty bitmap on miss)
		return filterIndex.getInvertedIndex().getRecordsEqualTo(normalizedValue).contains(ownerPK);
	}

	/**
	 * Inserts a raw attribute value into the histogram index, preserving the attribute's original numeric type.
	 * Handles both scalar and array attribute values. If the raw value is null and a default is defined in the
	 * resolution, the default is used. If both are null, no entry is created.
	 *
	 * @param executor      the mutation executor
	 * @param referenceName the reference name
	 * @param histogramName the histogram definition name
	 * @param locale        the locale for localized histograms, or `null` for non-localized
	 * @param resolution    the value resolution metadata
	 * @param rawValue      the raw attribute value (may be null)
	 * @param ownerPK       the primary key of the owner entity
	 * @param groupId       the group primary key (null for ungrouped)
	 * @param scope         the current scope
	 */
	private static void insertHistogramValues(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull HistogramValueDescriptor resolution,
		@Nullable Serializable rawValue,
		int ownerPK,
		@Nullable Integer groupId,
		@Nonnull Scope scope
	) {
		final Class<? extends Serializable> plainType = resolution.plainType();
		final int indexedDecimalPlaces = resolution.indexedDecimalPlaces();
		if (rawValue == null) {
			// apply default value if specified (already converted to plainType at build time)
			if (resolution.defaultValue() != null) {
				insertSingleHistogramValue(
					executor, referenceName, histogramName, locale,
					resolution.defaultValue(), ownerPK, groupId, scope, plainType, indexedDecimalPlaces
				);
			}
			return;
		}
		// values are passed raw — scale normalization happens once at the histogram-index write
		// boundary (HistogramIndexOperations), so this path stays free of scale semantics
		// enforce the array/scalar contract declared by the resolution before dispatching — a
		// mismatch between `arrayType` and the runtime shape of `rawValue` is a programming error
		// (schema vs. value drift) that must surface immediately rather than silently mis-index
		final boolean rawValueIsArray = rawValue instanceof Serializable[];
		if (resolution.arrayType() != rawValueIsArray) {
			throw new GenericEvitaInternalError(
				"Histogram value shape mismatch for reference `" + referenceName + "`, histogram `" +
					histogramName + "`: resolution declares arrayType=" + resolution.arrayType() +
					" but rawValue is " + (rawValueIsArray ? "an array" : "a scalar") +
					" (class: " + rawValue.getClass().getName() + ")."
			);
		}
		if (rawValueIsArray) {
			// array-typed: iterate each element, values are already in the attribute's native type
			final Serializable[] array = (Serializable[]) rawValue;
			for (final Serializable element : array) {
				if (element instanceof Number number) {
					insertSingleHistogramValue(
						executor, referenceName, histogramName, locale,
						number, ownerPK, groupId, scope, plainType, indexedDecimalPlaces
					);
				} else if (element instanceof Range<?> range) {
					insertSingleHistogramValue(
						executor, referenceName, histogramName, locale,
						range, ownerPK, groupId, scope, plainType, indexedDecimalPlaces
					);
				} else if (element != null) {
					throw new GenericEvitaInternalError(
						"Unsupported histogram array element type for reference `" + referenceName +
							"`, histogram `" + histogramName + "`: expected Number or Range, got " +
							element.getClass().getName() + "."
					);
				}
			}
		} else if (rawValue instanceof Number number) {
			insertSingleHistogramValue(
				executor, referenceName, histogramName, locale,
				number, ownerPK, groupId, scope, plainType, indexedDecimalPlaces
			);
		} else if (rawValue instanceof Range<?> range) {
			insertSingleHistogramValue(
				executor, referenceName, histogramName, locale,
				range, ownerPK, groupId, scope, plainType, indexedDecimalPlaces
			);
		} else {
			throw new GenericEvitaInternalError(
				"Unsupported histogram value type for reference `" + referenceName +
					"`, histogram `" + histogramName + "`: expected Number or Range, got " +
					rawValue.getClass().getName() + "."
			);
		}
	}

	/**
	 * Inserts a single value into the histogram index in the appropriate index (grouped or ungrouped).
	 * The value is in the attribute's original numeric type.
	 *
	 * @param executor      the mutation executor
	 * @param referenceName the reference name
	 * @param histogramName the histogram definition name
	 * @param locale        the locale for localized histograms, or `null` for non-localized
	 * @param value         the histogram value in its original type (a `Number` for plain numeric
	 *                      attributes or a `Range` instance for Range-typed attributes)
	 * @param ownerPK              the primary key of the owner entity
	 * @param groupId              the group primary key (null for ungrouped)
	 * @param scope                the current scope
	 * @param valueType            the plain type of the value (used for lazy index creation)
	 * @param indexedDecimalPlaces the source attribute schema's indexed decimal places, threaded to
	 *                             the histogram-index write boundary for scale normalization
	 */
	private static void insertSingleHistogramValue(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK,
		@Nullable Integer groupId,
		@Nonnull Scope scope,
		@Nonnull Class<? extends Serializable> valueType,
		int indexedDecimalPlaces
	) {
		if (groupId != null) {
			// grouped reference: find the ReducedGroupEntityIndex via the group type index
			final EntityIndexKey groupTypeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, scope, referenceName
			);
			final EntityIndex groupTypeIndex = executor.getIndexIfExists(groupTypeKey);
			if (groupTypeIndex == null) {
				throw new GenericEvitaInternalError(
					"Cannot insert histogram value: group ReducedTypeEntityIndex is missing for " +
						"reference `" + referenceName + "`, histogram `" + histogramName +
						"`, scope `" + scope + "`. Add `REFERENCED_GROUP_ENTITY` to " +
						"indexedComponentsInScopes for this scope (the schema-load validator " +
						"now blocks this combination, but pre-existing catalogs may still " +
						"reach this state)."
				);
			}
			// REFERENCED_GROUP_ENTITY_TYPE always resolves to a ReferencedTypeEntityIndex by
			// construction — any other type is a programming error
			if (!(groupTypeIndex instanceof ReferencedTypeEntityIndex rtei)) {
				throw new GenericEvitaInternalError(
					"Expected ReferencedTypeEntityIndex for REFERENCED_GROUP_ENTITY_TYPE key on " +
						"reference `" + referenceName + "`, scope `" + scope + "`, got " +
						groupTypeIndex.getClass().getName() + "."
				);
			}
			final int[] storagePKs = rtei.getAllReferenceIndexes(groupId);
			for (final int storagePK : storagePKs) {
				final EntityIndex reducedIndex = executor.getEntityIndexByPrimaryKeyForModification(storagePK);
				// the type-level index advertises this storage PK, so the per-group reduced index
				// must exist — a missing entry signals a corrupted index linkage
				if (reducedIndex == null) {
					throw new GenericEvitaInternalError(
						"Cannot insert histogram value: per-group reduced index is missing for " +
							"reference `" + referenceName + "`, histogram `" + histogramName +
							"`, storage PK `" + storagePK + "`, group `" + groupId +
							"`, scope `" + scope + "` — `ReferencedTypeEntityIndex` advertised " +
							"this PK but no index is registered under it."
					);
				}
				// every reduced group index must implement HistogramCapableEntityIndex —
				// ReducedGroupEntityIndex is the only concrete type stored under these PKs
				if (!(reducedIndex instanceof HistogramCapableEntityIndex hcei)) {
					throw new GenericEvitaInternalError(
						"Expected HistogramCapableEntityIndex for grouped reduced index of " +
							"reference `" + referenceName + "`, histogram `" + histogramName +
							"`, storage PK `" + storagePK + "`, scope `" + scope + "`, got " +
							reducedIndex.getClass().getName() + "."
					);
				}
				hcei.insertHistogramValue(histogramName, locale, value, ownerPK, valueType, indexedDecimalPlaces);
			}
		} else {
			// ungrouped reference: insert into ReferencedTypeEntityIndex
			final EntityIndexKey typeKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName
			);
			final EntityIndex index = executor.getIndexIfExists(typeKey);
			if (index == null) {
				// symmetric to the grouped branch above — the type-level ReferencedTypeEntityIndex
				// (where ungrouped-reference histograms must live) is missing because
				// `REFERENCED_ENTITY` is not configured in indexedComponents for this scope.
				throw new GenericEvitaInternalError(
					"Cannot insert histogram value: ReferencedTypeEntityIndex is missing for " +
						"reference `" + referenceName + "`, histogram `" + histogramName +
						"`, scope `" + scope + "`. Add `REFERENCED_ENTITY` to " +
						"indexedComponentsInScopes for this scope."
				);
			}
			// REFERENCED_ENTITY_TYPE always resolves to a ReferencedTypeEntityIndex which
			// implements HistogramCapableEntityIndex — any other type is a programming error
			if (!(index instanceof HistogramCapableEntityIndex hcei)) {
				throw new GenericEvitaInternalError(
					"Expected HistogramCapableEntityIndex for REFERENCED_ENTITY_TYPE key on " +
						"reference `" + referenceName + "`, histogram `" + histogramName +
						"`, scope `" + scope + "`, got " + index.getClass().getName() + "."
				);
			}
			// mark the index dirty — it is modified via insertHistogramValue below
			executor.getOrCreateIndex(typeKey);
			hcei.insertHistogramValue(histogramName, locale, value, ownerPK, valueType, indexedDecimalPlaces);
		}
	}

	/**
	 * Functional interface capturing the shared signature of the two sortable-attribute-compound lifecycle
	 * operations defined in {@link AttributeIndexMutator}:
	 * {@link AttributeIndexMutator#insertInitialSuiteOfSortableAttributeCompounds} and
	 * {@link AttributeIndexMutator#removeEntireSuiteOfSortableAttributeCompounds}.
	 *
	 * Used internally by {@link ReferenceIndexMutator#insertInitialSuiteOfSortableAttributeCompounds} and
	 * {@link ReferenceIndexMutator#removeEntireSuiteOfSortableAttributeCompounds} to avoid duplicating the
	 * entity-schema vs. reference-schema dispatch logic.
	 */
	@FunctionalInterface
	interface SortableCompoundOperation {

		/**
		 * Applies a sortable attribute compound insert or remove to the given entity index.
		 *
		 * @param executor                the mutation executor coordinating index operations
		 * @param referenceSchema         the reference schema providing the indexing context, or `null` for entity-only
		 *                                operations
		 * @param entityIndex             the target index (always an {@link AbstractReducedEntityIndex} in practice)
		 * @param locale                  if non-null, restrict the operation to compounds for this locale
		 * @param attributeSchemaProvider provides attribute and compound schemas for the relevant scope
		 * @param compoundProvider        provides the sortable attribute compound schemas to operate on
		 * @param attributeValueSupplier  supplies the current attribute values needed to build/remove compounds
		 */
		void apply(
			@Nonnull EntityIndexLocalMutationExecutor executor,
			@Nullable ReferenceSchemaContract referenceSchema,
			@Nonnull EntityIndex entityIndex,
			@Nullable Locale locale,
			@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
			@Nonnull SortableAttributeCompoundSchemaProvider<?, ? extends SortableAttributeCompoundSchemaContract> compoundProvider,
			@Nonnull ExistingAttributeValueSupplier attributeValueSupplier
		);

	}

}
