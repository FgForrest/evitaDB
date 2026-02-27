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

package io.evitadb.index.mutation.index;

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
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.facet.FacetGroupIndex;
import io.evitadb.index.facet.FacetIndexContract;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.index.mutation.index.EntityIndexLocalMutationExecutor.RepresentativeReferenceKeys;
import io.evitadb.index.mutation.index.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.index.mutation.index.dataAccess.ExistingDataSupplierFactory;
import io.evitadb.index.mutation.index.dataAccess.ExistingPriceSupplier;
import io.evitadb.spi.store.catalog.persistence.accessor.EntityStoragePartAccessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.evitadb.index.mutation.index.dataAccess.ExistingAttributeValueSupplier.NO_EXISTING_VALUE_SUPPLIER;
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
 * **Index traversal helpers** — `executeWithReferenceIndexes`, `executeWithGroupReferenceIndexes`,
 * `executeWithAllReferenceIndexes`: iterate all existing references of the current entity and invoke a
 * {@link ReferenceIndexConsumer} callback with the appropriate {@link AbstractReducedEntityIndex}.
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
	 * Iterates all currently stored references and invokes `referenceIndexConsumer` for each reference that exists
	 * and whose schema is configured for at least the given `indexType` level in the current scope.
	 *
	 * Convenience overload that accepts all references (no predicate filtering). Delegates to
	 * {@link #executeWithReferenceIndexes(ReferenceIndexType, EntityIndexLocalMutationExecutor, ReferenceIndexConsumer,
	 * Predicate, boolean)}.
	 *
	 * @param indexType                  minimum {@link ReferenceIndexType} level required for a reference to be processed
	 * @param executor                   the mutation executor providing entity state and index access
	 * @param referenceIndexConsumer     callback invoked with the reference schema and the matching
	 *                                   {@link AbstractReducedEntityIndex} (passed as both `indexForRemoval`
	 *                                   and `indexForUpsert`)
	 * @param referencePresenceExpected  whether the referenced entity's primary key is expected to already be present
	 *                                   in the index (used to resolve the correct {@link RepresentativeReferenceKey})
	 */
	static void executeWithReferenceIndexes(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceIndexConsumer referenceIndexConsumer,
		boolean referencePresenceExpected
	) {
		executeWithReferenceIndexes(
			indexType,
			executor,
			referenceIndexConsumer,
			referenceContract -> true,
			referencePresenceExpected
		);
	}

	/**
	 * Iterates all currently stored references and invokes `referenceIndexConsumer` for each reference that:
	 *
	 * 1. has not been dropped (i.e. {@link io.evitadb.api.requestResponse.data.Droppable#exists()} returns `true`),
	 * 2. has a reference schema configured for at least the given `indexType` level in the current scope,
	 * 3. has the {@link ReferenceIndexedComponents#REFERENCED_ENTITY} component enabled for the scope, and
	 * 4. passes the optional `referencePredicate` test.
	 *
	 * The appropriate {@link AbstractReducedEntityIndex} of type {@link EntityIndexType#REFERENCED_ENTITY} is obtained
	 * (or created) for each matching reference and passed to the consumer. References that use an internal
	 * (already-known) primary key resolve the stored {@link RepresentativeReferenceKey}; newly assigned external
	 * primary keys resolve the current one.
	 *
	 * @param indexType                  minimum {@link ReferenceIndexType} level required for a reference to be processed
	 * @param executor                   the mutation executor providing entity state and index access
	 * @param referenceIndexConsumer     callback invoked with the reference schema and the matching
	 *                                   {@link AbstractReducedEntityIndex} (passed as both `indexForRemoval`
	 *                                   and `indexForUpsert`)
	 * @param referencePredicate         additional filter applied after the schema-level check; use
	 *                                   `referenceContract -> true` to process all matching references
	 * @param referencePresenceExpected  whether the referenced entity's primary key is expected to already be present
	 *                                   in the index (used to resolve the correct {@link RepresentativeReferenceKey})
	 */
	static void executeWithReferenceIndexes(
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
	 * Iterates all currently stored references and invokes `referenceIndexConsumer` for each reference that exists,
	 * has a reference schema configured for at least the given `indexType` level AND for group indexing
	 * ({@link ReferenceIndexedComponents#REFERENCED_GROUP_ENTITY}), and has a non-dropped group assigned.
	 *
	 * Convenience overload that accepts all references (no predicate filtering). Delegates to
	 * {@link #executeWithGroupReferenceIndexes(ReferenceIndexType, EntityIndexLocalMutationExecutor,
	 * ReferenceIndexConsumer, Predicate, boolean)}.
	 *
	 * @param indexType                  minimum {@link ReferenceIndexType} level required for a reference to be processed
	 * @param executor                   the mutation executor providing entity state and index access
	 * @param referenceIndexConsumer     callback invoked with the reference schema and the matching group-level
	 *                                   {@link AbstractReducedEntityIndex} (passed as both `indexForRemoval`
	 *                                   and `indexForUpsert`)
	 * @param referencePresenceExpected  whether the group primary key is expected to already be present in the index
	 */
	static void executeWithGroupReferenceIndexes(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceIndexConsumer referenceIndexConsumer,
		boolean referencePresenceExpected
	) {
		executeWithGroupReferenceIndexes(
			indexType,
			executor,
			referenceIndexConsumer,
			referenceContract -> true,
			referencePresenceExpected
		);
	}

	/**
	 * Iterates all currently stored references and invokes `referenceIndexConsumer` for each reference that:
	 *
	 * 1. has not been dropped (i.e. {@link io.evitadb.api.requestResponse.data.Droppable#exists()} returns `true`),
	 * 2. has a reference schema configured for at least the given `indexType` level in the current scope,
	 * 3. has a reference schema that enables group indexing ({@link ReferenceIndexedComponents#REFERENCED_GROUP_ENTITY}),
	 * 4. has a non-dropped {@link GroupEntityReference} assigned, and
	 * 5. passes the optional `referencePredicate` test.
	 *
	 * The group-level {@link AbstractReducedEntityIndex} of type {@link EntityIndexType#REFERENCED_GROUP_ENTITY}
	 * is obtained (or created) using a {@link RepresentativeReferenceKey} derived from the entity-level key by
	 * substituting the entity primary key with the group primary key.
	 *
	 * @param indexType                  minimum {@link ReferenceIndexType} level required for a reference to be processed
	 * @param executor                   the mutation executor providing entity state and index access
	 * @param referenceIndexConsumer     callback invoked with the reference schema and the matching group-level
	 *                                   {@link AbstractReducedEntityIndex} (passed as both `indexForRemoval`
	 *                                   and `indexForUpsert`)
	 * @param referencePredicate         additional filter applied after the schema-level check; use
	 *                                   `referenceContract -> true` to process all matching references
	 * @param referencePresenceExpected  whether the group primary key is expected to already be present in the index
	 */
	static void executeWithGroupReferenceIndexes(
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
						executor, entityRRK, scope
					);
					referenceIndexConsumer.accept(referenceSchema, groupIndex, groupIndex);
				}
			}
		}
	}

	/**
	 * Convenience method that applies `referenceIndexConsumer` to both entity-level
	 * ({@link EntityIndexType#REFERENCED_ENTITY}) and group-level ({@link EntityIndexType#REFERENCED_GROUP_ENTITY})
	 * reduced indexes in a single call, accepting all references without additional predicate filtering.
	 *
	 * Equivalent to calling {@link #executeWithReferenceIndexes} followed by
	 * {@link #executeWithGroupReferenceIndexes} with the same arguments.
	 *
	 * @param indexType                  minimum {@link ReferenceIndexType} level required for a reference to be processed
	 * @param executor                   the mutation executor providing entity state and index access
	 * @param referenceIndexConsumer     callback invoked for each matching reference and its reduced index
	 * @param referencePresenceExpected  whether the referenced/group primary key is expected to already be present
	 *                                   in the index
	 */
	static void executeWithAllReferenceIndexes(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceIndexConsumer referenceIndexConsumer,
		boolean referencePresenceExpected
	) {
		executeWithReferenceIndexes(indexType, executor, referenceIndexConsumer, referencePresenceExpected);
		executeWithGroupReferenceIndexes(indexType, executor, referenceIndexConsumer, referencePresenceExpected);
	}

	/**
	 * Convenience method that applies `referenceIndexConsumer` to both entity-level
	 * ({@link EntityIndexType#REFERENCED_ENTITY}) and group-level ({@link EntityIndexType#REFERENCED_GROUP_ENTITY})
	 * reduced indexes in a single call, filtering references through `referencePredicate`.
	 *
	 * Equivalent to calling {@link #executeWithReferenceIndexes} followed by
	 * {@link #executeWithGroupReferenceIndexes} with the same arguments.
	 *
	 * @param indexType                  minimum {@link ReferenceIndexType} level required for a reference to be processed
	 * @param executor                   the mutation executor providing entity state and index access
	 * @param referenceIndexConsumer     callback invoked for each matching reference and its reduced index
	 * @param referencePredicate         additional filter applied after the schema-level check
	 * @param referencePresenceExpected  whether the referenced/group primary key is expected to already be present
	 *                                   in the index
	 */
	static void executeWithAllReferenceIndexes(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceIndexConsumer referenceIndexConsumer,
		@Nonnull Predicate<ReferenceContract> referencePredicate,
		boolean referencePresenceExpected
	) {
		executeWithReferenceIndexes(indexType, executor, referenceIndexConsumer, referencePredicate, referencePresenceExpected);
		executeWithGroupReferenceIndexes(indexType, executor, referenceIndexConsumer, referencePredicate, referencePresenceExpected);
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
	 * Returns (or lazily creates) the {@link AbstractReducedEntityIndex} of type
	 * {@link EntityIndexType#REFERENCED_GROUP_ENTITY} for the given group reference key and scope.
	 *
	 * The group index mirrors the entity index but is keyed by the **group** primary key rather than the referenced
	 * entity primary key. It is only created when the reference schema has group indexing enabled
	 * ({@link ReferenceIndexedComponents#REFERENCED_GROUP_ENTITY}) for the scope.
	 *
	 * @param executor         the mutation executor that manages index lifecycle
	 * @param groupReferenceKey the representative key identifying the specific referenced group instance
	 * @param scope             the scope in which the index is maintained
	 * @return the existing or newly created group reduced entity index
	 */
	@Nonnull
	static ReducedGroupEntityIndex getOrCreateReferencedGroupEntityIndex(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull RepresentativeReferenceKey groupReferenceKey,
		@Nonnull Scope scope
	) {
		final EntityIndexKey entityIndexKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_GROUP_ENTITY, scope,
			groupReferenceKey
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
	 * This is the group-level counterpart of {@link #getOrCreateReferencedTypeEntityIndex(EntityIndexLocalMutationExecutor,
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
	 *         {@link EntityIndexLocalMutationExecutor#getOrCreateIndex(EntityIndexKey)}
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
	 *         {@link EntityIndexLocalMutationExecutor#getOrCreateIndex(EntityIndexKey)}
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
	 * Returns `true` if the reference schema's configured {@link ReferenceIndexType} in the given scope has an ordinal
	 * at least as large as the requested `referenceIndexType`. This implements a "minimum level" check across
	 * the ordered enum: `NONE` < `FOR_FILTERING` < `FOR_FILTERING_AND_PARTITIONING`.
	 *
	 * @param referenceSchema     the reference schema to evaluate
	 * @param scope               the scope in which to check the index type
	 * @param referenceIndexType  the minimum required index level
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
	 * If the reference name within the given {@code referenceKey} matches the name of the provided {@code referenceSchema},
	 * the {@code referenceSchema} is returned. Otherwise, the method retrieves the appropriate schema from
	 * the {@link EntityIndexLocalMutationExecutor}.
	 *
	 * @param referenceKey the key that specifies the reference name to locate the schema for
	 * @param referenceSchema the current schema to compare against the reference key
	 * @param executor the executor used to fetch the entity schema in case the provided schema name does not match
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
	 * @param executor                the mutation executor coordinating the index updates
	 * @param attributeSupplierFactory factory for reading existing reference attribute values from storage
	 * @param referenceTypeIndex      the type-level index receiving the attribute change (keyed by referenced PK)
	 * @param indexForRemoval         the reduced index from which old attribute data is removed; typically the
	 *                                same object as `indexForUpsert` but passed separately for correct ordering
	 * @param indexForUpsert          the reduced index into which updated attribute data is inserted
	 * @param referenceSchema         the schema describing the reference and its attributes
	 * @param referenceKey            identifies the specific reference instance being updated
	 * @param attributeMutation       the attribute-level mutation to apply (upsert, remove, or delta)
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
			.getReferenceAttributeValueSupplier(referenceKey);

		// we access attributes and sortable compounds from the reference schema
		final ReferenceSchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider = new ReferenceSchemaAttributeAndCompoundSchemaProvider(
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

		executeWithProperPrimaryKey(
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
	}

	/**
	 * Performs the global-only indexing operation for a reference insertion.
	 * This adds the facet to the global entity index and must be called exactly once
	 * per reference insert, regardless of how many indexed components are configured.
	 *
	 * @param entityPrimaryKey     the primary key of the entity being indexed
	 * @param referenceSchema      the schema of the reference being inserted
	 * @param globalIndex          the global entity index to add the facet to
	 * @param referenceKey         the reference key identifying the reference
	 * @param groupId              the group primary key, or null if not grouped
	 * @param executor             the mutation executor
	 * @param undoActionConsumer   consumer for undo actions, or null if not needed
	 */
	static void referenceInsertGlobal(
		int entityPrimaryKey,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndex globalIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		addFacetToIndex(globalIndex, referenceSchema, referenceKey, groupId, entityPrimaryKey, executor, undoActionConsumer);
	}

	/**
	 * Performs per-component indexing for a reference insertion. This registers the PK mapping in
	 * the type index, indexes reference attributes, populates the reduced index with entity data,
	 * and adds the facet to the reduced index. Called once per indexed component (entity and/or group).
	 *
	 * @param entityPrimaryKey       the primary key of the entity being indexed
	 * @param entitySchema           the entity schema
	 * @param referenceSchema        the schema of the reference being inserted
	 * @param executor               the mutation executor
	 * @param referenceTypeIndex     the type-level index for this component
	 * @param referenceIndex         the reduced entity index for this component
	 * @param referenceKey           the original reference key — used for facets and reference attribute lookup
	 * @param referencedPrimaryKey   the target primary key for type index mapping (entity PK or group PK)
	 * @param groupId                the group primary key, or null if not grouped
	 * @param existingDataSupplierFactory factory to supply existing data needed for indexing
	 * @param undoActionConsumer     consumer for undo actions, or null if not needed
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
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		// register reduced index PK → referenced primary key mapping in the type index
		final int pkForReferenceTypeIndex = referenceIndex.getPrimaryKey();
		if (referenceTypeIndex.insertPrimaryKeyIfMissing(
			pkForReferenceTypeIndex, referencedPrimaryKey) && undoActionConsumer != null) {
			undoActionConsumer.accept(
				() -> referenceTypeIndex.removePrimaryKey(pkForReferenceTypeIndex, referencedPrimaryKey));
		}

		// we access attributes and sortable compounds from the reference schema
		final ReferenceSchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider = new ReferenceSchemaAttributeAndCompoundSchemaProvider(
			entitySchema, referenceSchema
		);

		// index all reference attributes to the reference type index
		final ExistingAttributeValueSupplier referenceAttributeValueSupplier = existingDataSupplierFactory
			.getReferenceAttributeValueSupplier(referenceKey);

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
					false,
					undoActionConsumer
				))
		);

		// index entity primary key into the reduced index and populate with existing data
		if (referenceIndex instanceof ReducedGroupEntityIndex rgei) {
			// group indexes need cardinality tracking — use two-arg version
			if (rgei.insertPrimaryKeyIfMissing(entityPrimaryKey, referenceKey.primaryKey())) {
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(
						() -> rgei.removePrimaryKey(entityPrimaryKey, referenceKey.primaryKey())
					);
				}
				// index all previously added global entity attributes, prices and facets
				indexAllExistingData(
					executor, referenceIndex,
					entitySchema, referenceSchema,
					entityPrimaryKey,
					existingDataSupplierFactory,
					undoActionConsumer
				);
			}
		} else {
			if (referenceIndex.insertPrimaryKeyIfMissing(entityPrimaryKey)) {
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(() -> referenceIndex.removePrimaryKey(entityPrimaryKey));
				}
				// index all previously added global entity attributes, prices and facets
				indexAllExistingData(
					executor, referenceIndex,
					entitySchema, referenceSchema,
					entityPrimaryKey,
					existingDataSupplierFactory,
					undoActionConsumer
				);
			}
		}

		// add facet to reduced index
		addFacetToIndex(
			referenceIndex, referenceSchema, referenceKey, groupId, entityPrimaryKey, executor, undoActionConsumer
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
	 * @param entityPrimaryKey         the primary key of the owning entity
	 * @param entitySchema             the entity schema of the owning entity
	 * @param referenceSchema          the schema describing the reference being inserted
	 * @param executor                 the mutation executor coordinating the index updates
	 * @param entityIndex              the global entity index to which the facet is registered once
	 * @param referenceTypeIndex       the type-level index ({@link EntityIndexType#REFERENCED_ENTITY_TYPE})
	 * @param referenceIndex           the per-reference reduced index ({@link EntityIndexType#REFERENCED_ENTITY})
	 * @param referenceKey             identifies the specific referenced entity
	 * @param groupId                  the group primary key to associate with the facet, or `null` if not grouped
	 * @param existingDataSupplierFactory factory for reading existing entity data to populate the reduced index
	 * @param undoActionConsumer       if non-null, receives inverse operations to undo every index change; used
	 *                                 during scope migration and speculative indexing
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
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		// global operation — add facet to global index (once per reference)
		referenceInsertGlobal(
			entityPrimaryKey, referenceSchema, entityIndex, referenceKey, groupId,
			executor, undoActionConsumer
		);
		// per-component operation — type index + reduced index
		referenceInsertPerComponent(
			entityPrimaryKey, entitySchema, referenceSchema, executor,
			referenceTypeIndex, referenceIndex, referenceKey,
			referenceKey.primaryKey(), groupId,
			existingDataSupplierFactory, undoActionConsumer
		);
	}

	/**
	 * Performs the global-only operation for a reference removal.
	 * This removes the facet from the global entity index and must be called exactly once
	 * per reference removal, regardless of how many indexed components are configured.
	 *
	 * @param entityPrimaryKey     the primary key of the entity being removed
	 * @param referenceSchema      the schema of the reference being removed
	 * @param globalIndex          the global entity index to remove the facet from
	 * @param referenceKey         the reference key identifying the reference
	 * @param executor             the mutation executor
	 * @param undoActionConsumer   consumer for undo actions, or null if not needed
	 */
	static void referenceRemovalGlobal(
		int entityPrimaryKey,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndex globalIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		removeFacetInIndex(globalIndex, referenceSchema, referenceKey, entityPrimaryKey, executor, undoActionConsumer);
	}

	/**
	 * Performs per-component removal from the type index and reduced index. Called once per indexed
	 * component (entity and/or group).
	 *
	 * @param entityPrimaryKey       the primary key of the entity being removed
	 * @param entitySchema           the entity schema
	 * @param referenceSchema        the schema of the reference being removed
	 * @param executor               the mutation executor
	 * @param referenceTypeIndex     the type-level index for this component
	 * @param referenceIndex         the reduced entity index for this component
	 * @param referenceKey           the original reference key — for attribute lookup
	 * @param referencedPrimaryKey   the target primary key for type index mapping (entity PK or group PK)
	 * @param existingDataSupplierFactory factory to supply existing data
	 * @param undoActionConsumer     consumer for undo actions, or null if not needed
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
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		// remove reduced index PK → referenced primary key mapping from the type index
		final int pkForReferenceTypeIndex = referenceIndex.getPrimaryKey();
		if (referenceTypeIndex.removePrimaryKey(
			pkForReferenceTypeIndex, referencedPrimaryKey) && undoActionConsumer != null) {
			undoActionConsumer.accept(() -> referenceTypeIndex.insertPrimaryKeyIfMissing(
				pkForReferenceTypeIndex,
				referencedPrimaryKey
			));
		}

		// we access attributes and sortable compounds from the reference schema
		final ReferenceSchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider = new ReferenceSchemaAttributeAndCompoundSchemaProvider(
			entitySchema, referenceSchema
		);

		// remove all reference attributes from the reference type index
		final ExistingAttributeValueSupplier referenceAttributeValueSupplier = existingDataSupplierFactory
			.getReferenceAttributeValueSupplier(referenceKey);
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
					false,
					undoActionConsumer
				))
		);

		// remove entity primary key from the reduced index
		if (referenceIndex instanceof ReducedGroupEntityIndex rgei) {
			// group indexes need cardinality tracking — use two-arg version
			if (rgei.removePrimaryKey(entityPrimaryKey, referenceKey.primaryKey())) {
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(
						() -> rgei.insertPrimaryKeyIfMissing(entityPrimaryKey, referenceKey.primaryKey())
					);
				}
				// remove all entity attributes and prices
				removeAllExistingData(
					executor, referenceIndex,
					entitySchema, referenceSchema,
					entityPrimaryKey,
					existingDataSupplierFactory,
					undoActionConsumer
				);
			}
		} else {
			if (referenceIndex.removePrimaryKey(entityPrimaryKey)) {
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(() -> referenceIndex.insertPrimaryKeyIfMissing(entityPrimaryKey));
				}
				// remove all entity attributes and prices
				removeAllExistingData(
					executor, referenceIndex,
					entitySchema, referenceSchema,
					entityPrimaryKey,
					existingDataSupplierFactory,
					undoActionConsumer
				);
			}
		}
	}

	/**
	 * Adds a facet entry for the given entity to `index`, but only when both of the following conditions hold:
	 *
	 * 1. The target index should receive facet data for this reference (governed by
	 *    {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING} for reduced indexes; always `true` for the
	 *    global entity index).
	 * 2. The reference schema marks the reference as faceted in the index's scope.
	 *
	 * If `undoActionConsumer` is provided, the corresponding removal operation is registered so that the change
	 * can be rolled back (used during speculative indexing and scope migration).
	 *
	 * @param index               the target entity index (global or reduced)
	 * @param referenceSchema     the schema of the reference; used to check faceting and schema name resolution
	 * @param referenceKey        identifies the specific referenced entity for the facet entry
	 * @param groupId             the group primary key for this facet, or `null` if no group is assigned
	 * @param entityPrimaryKey    the primary key of the entity owning the reference
	 * @param executor            the mutation executor; used for schema look-ups and scope access
	 * @param undoActionConsumer  if non-null, receives a `removeFacet` lambda for undoing this operation
	 */
	static void addFacetToIndex(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId,
		int entityPrimaryKey,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final Scope scope = index.getIndexKey().scope();
		if (
			shouldIndexFacetToTargetIndex(index, referenceSchema, scope, executor) &&
				isFaceted(referenceKey, referenceSchema, scope, executor)
		) {
			index.addFacet(referenceSchema, referenceKey, groupId, entityPrimaryKey);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(
					() -> index.removeFacet(referenceSchema, referenceKey, groupId, entityPrimaryKey));
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
	 * Note: no `undoActionConsumer` is supported here because this operation is always called in the context of
	 * a {@link io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation} which is not
	 * subject to speculative undoing in the same way as insertions.
	 *
	 * @param entityPrimaryKey  the primary key of the entity owning the reference
	 * @param index             the target entity index (global or reduced)
	 * @param referenceSchema   the schema of the reference; used to check faceting
	 * @param referenceKey      identifies the specific referenced entity
	 * @param groupId           the new group primary key to assign
	 * @param executor          the mutation executor; used for reading the existing reference and schema access
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
			final ReferenceContract existingReference = executor.getReferencesStoragePart()
				.findReferenceOrThrowException(referenceKey);
			final Optional<Integer> existingGroupId = existingReference.getGroup()
				.filter(Droppable::exists)
				.map(EntityReferenceContract::getPrimaryKey);

			final Integer oldGroupId = existingGroupId.orElse(null);
			// only remove from the old group if the facet is actually present there — during
			// cross-reference propagation the direct processing path may have already inserted
			// the facet with the new group, making the storage-derived old group stale
			if (isFacetPresentInGroup(index, referenceKey, oldGroupId)) {
				index.removeFacet(
					referenceSchema,
					referenceKey,
					oldGroupId,
					entityPrimaryKey
				);
			}
			index.addFacet(
				referenceSchema,
				referenceKey,
				groupId,
				entityPrimaryKey
			);
		}
	}

	/**
	 * Checks whether a specific facet (identified by `referenceKey`) is present in the given `groupId` bucket
	 * of the `index`'s facet index. Returns `true` if the facet exists in the specified group, `false` otherwise.
	 *
	 * For grouped facets (non-null `groupId`), delegates to {@link FacetIndexContract#isFacetInGroup}.
	 * For ungrouped facets (null `groupId`), checks the not-grouped bucket via
	 * {@link FacetReferenceIndex#getNotGroupedFacets()}.
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
	 * @param index        the entity index to check
	 * @param referenceKey the reference key identifying the facet
	 * @param groupId      the group to check in, or `null` for ungrouped facets
	 * @return `true` if the facet is present in the specified group bucket
	 */
	private static boolean isFacetPresentInGroup(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId
	) {
		if (groupId != null) {
			return index.isFacetInGroup(
				referenceKey.referenceName(), groupId, referenceKey.primaryKey()
			);
		}
		// for ungrouped facets, check the not-grouped bucket directly
		final FacetReferenceIndex facetRefIndex = index.getFacetingEntities().get(referenceKey.referenceName());
		if (facetRefIndex == null) {
			return false;
		}
		final FacetGroupIndex notGrouped = facetRefIndex.getNotGroupedFacets();
		return notGrouped != null
			&& notGrouped.getFacetIdIndex(referenceKey.primaryKey()) != null;
	}

	/**
	 * Removes the facet entry for the given entity from `index`. The existing group assignment is read from the
	 * persisted {@link ReferencesStoragePart} so the removal can use the correct (current) group primary key.
	 *
	 * This method is a no-op when the reference schema does not mark the reference as faceted in the index's scope,
	 * or when the target index is an {@link AbstractReducedEntityIndex} for a different reference schema that is not
	 * configured for {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING}.
	 *
	 * If `undoActionConsumer` is provided, the corresponding `addFacet` operation is registered so that the removal
	 * can be rolled back.
	 *
	 * @param index               the target entity index (global or reduced)
	 * @param referenceSchema     the schema of the reference; used to check faceting and schema name resolution
	 * @param referenceKey        identifies the specific referenced entity whose facet is to be removed
	 * @param entityPrimaryKey    the primary key of the entity owning the reference
	 * @param executor            the mutation executor; used for reading the existing reference and schema access
	 * @param undoActionConsumer  if non-null, receives an `addFacet` lambda for undoing this operation
	 */
	static void removeFacetInIndex(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		int entityPrimaryKey,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final Scope scope = index.getIndexKey().scope();
		if (
			shouldIndexFacetToTargetIndex(index, referenceSchema, scope, executor) &&
				isFaceted(referenceKey, referenceSchema, scope, executor)
		) {
			final ReferenceContract existingReference = executor.getReferencesStoragePart()
				.findReferenceOrThrowException(referenceKey);

			removeFacetInIndexInternal(index, referenceSchema, entityPrimaryKey, existingReference, undoActionConsumer);
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
	 * @param entityPrimaryKey  the primary key of the entity owning the reference
	 * @param index             the target entity index (global or reduced)
	 * @param referenceSchema   the schema of the reference; used to check faceting
	 * @param referenceKey      identifies the specific referenced entity
	 * @param executor          the mutation executor; used for reading the existing reference and schema access
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
			final ReferenceContract existingReference = executor.getReferencesStoragePart()
				.findReferenceOrThrowException(referenceKey);
			isPremiseValid(
				existingReference.getGroup().filter(Droppable::exists).isPresent(),
				"Group is expected to be non-null when RemoveReferenceGroupMutation is about to be executed."
			);
			final int groupId = existingReference.getGroup().map(GroupEntityReference::getPrimaryKey).orElseThrow();

			// only remove from the old group if the facet is actually present there — during
			// cross-reference propagation the direct processing path may have already moved
			// the facet, making the storage-derived group stale
			if (isFacetPresentInGroup(index, referenceKey, groupId)) {
				index.removeFacet(
					referenceSchema,
					referenceKey,
					groupId,
					entityPrimaryKey
				);
			}
			index.addFacet(
				referenceSchema,
				referenceKey,
				null,
				entityPrimaryKey
			);
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
	 *   configured for {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING}.
	 * - **Reference-level** compound schemas (from the reference schema) — always, regardless of index type.
	 *
	 * If `undoActionConsumer` is provided, the inverse removal operations are registered for each compound.
	 *
	 * @param executor                  the mutation executor providing entity schema and index access
	 * @param referenceSchema           the schema describing the reference (determines attribute scope and index level)
	 * @param targetIndex               the reduced entity index into which compounds are inserted
	 * @param locale                    if non-null, restrict insertion to compounds for this locale only
	 * @param existingDataSupplierFactory factory for reading existing attribute values from storage
	 * @param undoActionConsumer        if non-null, receives inverse compound removal operations for rollback
	 */
	static void insertInitialSuiteOfSortableAttributeCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nullable Locale locale,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		applySortableAttributeCompoundSuite(
			executor, referenceSchema, targetIndex, locale,
			existingDataSupplierFactory, undoActionConsumer,
			AttributeIndexMutator::insertInitialSuiteOfSortableAttributeCompounds,
			AttributeIndexMutator::removeEntireSuiteOfSortableAttributeCompounds
		);
	}

	/**
	 * Removes the full suite of sortable attribute compounds for the entity–reference combination represented by
	 * `targetIndex`. This is the exact inverse of
	 * {@link #insertInitialSuiteOfSortableAttributeCompounds(EntityIndexLocalMutationExecutor, ReferenceSchemaContract,
	 * AbstractReducedEntityIndex, Locale, ExistingDataSupplierFactory, Consumer)}.
	 *
	 * When `locale` is non-null, only compounds that include at least one attribute for that locale are removed.
	 * When `locale` is `null`, only locale-independent (non-localized) compounds are removed.
	 *
	 * @param executor                  the mutation executor providing entity schema and index access
	 * @param referenceSchema           the schema describing the reference
	 * @param targetIndex               the reduced entity index from which compounds are removed
	 * @param locale                    if non-null, restrict removal to compounds for this locale only
	 * @param existingDataSupplierFactory factory for reading existing attribute values from storage
	 * @param undoActionConsumer        if non-null, receives inverse compound insertion operations for rollback
	 */
	static void removeEntireSuiteOfSortableAttributeCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nullable Locale locale,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		applySortableAttributeCompoundSuite(
			executor, referenceSchema, targetIndex, locale,
			existingDataSupplierFactory, undoActionConsumer,
			AttributeIndexMutator::removeEntireSuiteOfSortableAttributeCompounds,
			AttributeIndexMutator::insertInitialSuiteOfSortableAttributeCompounds
		);
	}

	/**
	 * Common skeleton for both inserting and removing sortable attribute compound suites.
	 * Applies `primaryOp` to both entity-level and reference-level attribute compound schemas,
	 * and registers `undoOp` as the inverse for each.
	 */
	private static void applySortableAttributeCompoundSuite(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nullable Locale locale,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer,
		@Nonnull SortableCompoundOperation primaryOp,
		@Nonnull SortableCompoundOperation undoOp
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final RepresentativeReferenceKey rrk = extractRepresentativeReferenceKey(targetIndex);

		// if the reference is indexed for filtering and partitioning, index attributes from the entity schema
		final Scope scope = targetIndex.getIndexKey().scope();
		if (isIndexedReferenceFor(referenceSchema, scope, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)) {
			final EntitySchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider =
				new EntitySchemaAttributeAndCompoundSchemaProvider(entitySchema);
			final ExistingAttributeValueSupplier entityAttributeValueSupplier =
				existingDataSupplierFactory.getEntityAttributeValueSupplier();

			primaryOp.apply(
				executor, referenceSchema, targetIndex, locale, attributeSchemaProvider,
				entitySchema, entityAttributeValueSupplier, undoActionConsumer
			);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(
					() -> undoOp.apply(
						executor, referenceSchema, targetIndex, locale, attributeSchemaProvider,
						entitySchema, entityAttributeValueSupplier, undoActionConsumer
					)
				);
			}
		}

		// then apply to reference-level attributes and sortable compounds
		final ReferenceSchemaAttributeAndCompoundSchemaProvider referenceSchemaAttributeProvider =
			new ReferenceSchemaAttributeAndCompoundSchemaProvider(entitySchema, referenceSchema);
		final ExistingAttributeValueSupplier referenceAttributeValueSupplier =
			existingDataSupplierFactory.getReferenceAttributeValueSupplier(rrk);

		primaryOp.apply(
			executor, referenceSchema, targetIndex, locale, referenceSchemaAttributeProvider,
			referenceSchema, referenceAttributeValueSupplier, undoActionConsumer
		);
		if (undoActionConsumer != null) {
			undoActionConsumer.accept(
				() -> undoOp.apply(
					executor, referenceSchema, targetIndex, locale, referenceSchemaAttributeProvider,
					referenceSchema, referenceAttributeValueSupplier, undoActionConsumer
				)
			);
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
		 * @param executor               the mutation executor coordinating index operations
		 * @param referenceSchema        the reference schema providing the indexing context, or `null` for entity-only
		 *                               operations
		 * @param entityIndex            the target index (always an {@link AbstractReducedEntityIndex} in practice)
		 * @param locale                 if non-null, restrict the operation to compounds for this locale
		 * @param attributeSchemaProvider provides attribute and compound schemas for the relevant scope
		 * @param compoundProvider       provides the sortable attribute compound schemas to operate on
		 * @param attributeValueSupplier supplies the current attribute values needed to build/remove compounds
		 * @param undoActionConsumer     if non-null, receives inverse operations to undo this compound change
		 */
		void apply(
			@Nonnull EntityIndexLocalMutationExecutor executor,
			@Nullable ReferenceSchemaContract referenceSchema,
			@Nonnull EntityIndex entityIndex,
			@Nullable Locale locale,
			@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
			@Nonnull SortableAttributeCompoundSchemaProvider<?,
				? extends SortableAttributeCompoundSchemaContract> compoundProvider,
			@Nonnull ExistingAttributeValueSupplier attributeValueSupplier,
			@Nullable Consumer<Runnable> undoActionConsumer
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
	 * @param executor                  the mutation executor providing entity schema, container access and index ops
	 * @param targetIndex               the newly populated reduced entity index
	 * @param entitySchema              the entity schema of the owning entity
	 * @param referenceSchema           the schema of the reference being inserted
	 * @param entityPrimaryKey          the primary key of the owning entity being indexed into the reduced index
	 * @param existingDataSupplierFactory factory that supplies the entity's current attributes, prices and references
	 * @param undoActionConsumer        if non-null, receives inverse operations for every change made
	 */
	private static void indexAllExistingData(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		int entityPrimaryKey,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final String entityType = entitySchema.getName();

		indexAllFacets(executor, referenceSchema, targetIndex, entityPrimaryKey, undoActionConsumer);

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
				locale, entitySchema, targetIndex, existingDataSupplierFactory
			);
		}

		indexAllPrices(
			executor, referenceSchema, targetIndex, existingDataSupplierFactory.getPriceSupplier(), undoActionConsumer);
		indexAllAttributes(executor, referenceSchema, targetIndex, existingDataSupplierFactory, undoActionConsumer);
		insertInitialSuiteOfSortableAttributeCompounds(
			executor, referenceSchema, targetIndex, null, existingDataSupplierFactory, undoActionConsumer);
	}

	/**
	 * Indexes all faceted references of the owning entity into `targetIndex`. Only references that are currently
	 * stored (not dropped) and whose schema marks them as faceted in the index's scope are processed.
	 *
	 * To avoid redundant schema lookups the method takes advantage of the fact that references in
	 * {@link ReferencesStoragePart} are stored sorted by reference name — the schema is re-fetched only when the
	 * reference name changes between iterations.
	 *
	 * This method is called from {@link #indexAllExistingData} when an entity is first inserted into a reduced index.
	 *
	 * @param executor            the mutation executor; provides the references storage part and schema access
	 * @param referenceSchema     the reference schema for which the reduced index was just created; used as a hint
	 *                            to avoid re-fetching when the current reference name matches
	 * @param targetIndex         the reduced entity index receiving the facet entries
	 * @param entityPrimaryKey    the primary key of the owning entity
	 * @param undoActionConsumer  if non-null, receives inverse `removeFacet` operations for rollback
	 */
	private static void indexAllFacets(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		int entityPrimaryKey,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final Scope scope = targetIndex.getIndexKey().scope();
		if (shouldIndexFacetToTargetIndex(targetIndex, referenceSchema, scope, executor)) {
			final ReferencesStoragePart referencesStorageContainer = executor.getReferencesStoragePart();

			ReferenceSchemaContract referenceKeySchema = null;
			for (ReferenceContract reference : referencesStorageContainer.getReferences()) {
				final ReferenceKey referenceKey = reference.getReferenceKey();
				final Optional<GroupEntityReference> groupReference = reference.getGroup();

				// we gain advantage of sorted references in the storage container to avoid unnecessary schema lookups
				referenceKeySchema = referenceKeySchema == null || !referenceKeySchema.getName().equals(referenceKey.referenceName()) ?
					getReferenceSchemaFor(referenceKey, referenceSchema, executor) : referenceKeySchema;
				if (reference.exists() && referenceKeySchema.isFacetedInScope(scope)) {
					final Integer groupId = groupReference
						.filter(Droppable::exists)
						.map(GroupEntityReference::getPrimaryKey)
						.orElse(null);
					targetIndex.addFacet(referenceSchema, referenceKey, groupId, entityPrimaryKey);
					if (undoActionConsumer != null) {
						undoActionConsumer.accept(
							() -> targetIndex.removeFacet(referenceSchema, referenceKey, groupId, entityPrimaryKey)
						);
					}
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
	 * @param undoActionConsumer    if non-null, receives inverse price removal operations for rollback
	 */
	private static void indexAllPrices(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull ExistingPriceSupplier existingPriceSupplier,
		@Nullable Consumer<Runnable> undoActionConsumer
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
						         PriceIndexMutator.createPriceProvider(price),
						         undoActionConsumer
					         )
				);
		}
	}

	/**
	 * Indexes all existing attributes of the owning entity (both entity-level and reference-level) into
	 * `targetIndex`.
	 *
	 * Entity-level attributes are only indexed when the reference schema is configured for
	 * {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING} in the index's scope. Reference-level attributes
	 * (those defined on the reference schema) are always indexed regardless of index level.
	 *
	 * For reference-level attributes of type {@link ReferencedEntityPredecessor}, the indexing is performed under
	 * the referenced entity's primary key via {@link #executeWithProperPrimaryKey}.
	 *
	 * Called from {@link #indexAllExistingData} when an entity is first inserted into a reduced index.
	 *
	 * @param executor                  the mutation executor coordinating attribute index updates
	 * @param referenceSchema           the reference schema; determines attribute scope and index level
	 * @param targetIndex               the reduced entity index into which attributes are inserted
	 * @param existingDataSupplierFactory factory for reading existing entity and reference attribute values
	 * @param undoActionConsumer        if non-null, receives inverse attribute removal operations for rollback
	 */
	private static void indexAllAttributes(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final RepresentativeReferenceKey rrk = extractRepresentativeReferenceKey(targetIndex);

		// if the reference is indexed for filtering and partitioning, we need to index attributes from the entity schema
		final Scope scope = targetIndex.getIndexKey().scope();
		if (isIndexedReferenceFor(referenceSchema, scope, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)) {
			final EntitySchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider = new EntitySchemaAttributeAndCompoundSchemaProvider(
				entitySchema
			);

			existingDataSupplierFactory.getEntityAttributeValueSupplier()
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
							false,
							undoActionConsumer
						)
				);
		}

		// and the second, we access attributes and sortable compounds from the reference schema
		final ReferenceSchemaAttributeAndCompoundSchemaProvider referenceSchemaAttributeProvider = new ReferenceSchemaAttributeAndCompoundSchemaProvider(
			entitySchema, referenceSchema
		);

		existingDataSupplierFactory.getReferenceAttributeValueSupplier(rrk)
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
						         false,
						         undoActionConsumer
					         )
				         )
			);
	}

	/**
	 * Removes all previously indexed data for the owning entity from the given reduced entity index. Called when an
	 * entity's primary key is removed from the reduced index for the last time — i.e. the last reference to this
	 * particular referenced entity has been deleted from the owning entity.
	 *
	 * This is the symmetric counterpart of {@link #indexAllExistingData} and follows the same ordering in reverse:
	 * facets, locales, prices, entity-level and reference-level attributes, and sortable attribute compounds.
	 *
	 * @param executor                  the mutation executor providing entity schema, container access and index ops
	 * @param targetIndex               the reduced entity index from which data is removed
	 * @param entitySchema              the entity schema of the owning entity
	 * @param referenceSchema           the schema of the reference being removed
	 * @param entityPrimaryKey          the primary key of the owning entity being de-indexed from the reduced index
	 * @param existingDataSupplierFactory factory supplying the entity's current attributes, prices and references
	 * @param undoActionConsumer        if non-null, receives inverse operations for every change made
	 */
	private static void removeAllExistingData(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		int entityPrimaryKey,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final String entityType = entitySchema.getName();
		removeAllFacets(executor, referenceSchema, targetIndex, entityPrimaryKey, undoActionConsumer);

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
				locale, entitySchema, targetIndex, existingDataSupplierFactory
			);
		}

		removeAllPrices(
			executor, referenceSchema, targetIndex, existingDataSupplierFactory.getPriceSupplier(), undoActionConsumer);
		removeAllAttributes(executor, referenceSchema, targetIndex, existingDataSupplierFactory, undoActionConsumer);
		removeEntireSuiteOfSortableAttributeCompounds(
			executor, referenceSchema, targetIndex, null, existingDataSupplierFactory, undoActionConsumer);
	}

	/**
	 * Removes all faceted references of the owning entity from `targetIndex`. The inverse of {@link #indexAllFacets}.
	 *
	 * Only references that are currently stored (not dropped) and whose schema marks them as faceted in the index's
	 * scope are processed. The same sorted-iteration schema-caching optimization as in {@link #indexAllFacets} is
	 * applied to avoid redundant schema look-ups.
	 *
	 * @param executor            the mutation executor; provides the references storage part and schema access
	 * @param referenceSchema     the reference schema for which the reduced index is being cleaned up; used as a hint
	 * @param targetIndex         the reduced entity index from which facet entries are removed
	 * @param entityPrimaryKey    the primary key of the owning entity
	 * @param undoActionConsumer  if non-null, receives inverse `addFacet` operations for rollback
	 */
	private static void removeAllFacets(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		int entityPrimaryKey,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final Scope scope = targetIndex.getIndexKey().scope();
		if (shouldIndexFacetToTargetIndex(targetIndex, referenceSchema, scope, executor)) {
			final ReferencesStoragePart referencesStorageContainer = executor.getReferencesStoragePart();

			ReferenceSchemaContract referenceKeySchema = null;
			for (ReferenceContract reference : referencesStorageContainer.getReferences()) {
				final ReferenceKey referenceKey = reference.getReferenceKey();
				// we gain advantage of sorted references in the storage container to avoid unnecessary schema lookups
				referenceKeySchema = referenceKeySchema == null || !referenceKeySchema.getName().equals(referenceKey.referenceName()) ?
					getReferenceSchemaFor(referenceKey, referenceSchema, executor) : referenceKeySchema;
				if (reference.exists() && referenceKeySchema.isFacetedInScope(scope)) {
					removeFacetInIndexInternal(
						targetIndex, referenceSchema, entityPrimaryKey, reference, undoActionConsumer
					);
				}
			}
		}
	}

	/**
	 * Removes a facet associated with a given entity in the provided index. Optionally, an undo action can
	 * be provided to reverse this operation.
	 *
	 * During cross-reference propagation, the facet may have already been moved to a different group by
	 * direct processing (e.g. via {@link #referenceInsertPerComponent}). In that case, the storage-derived
	 * group is stale and the facet does not exist in the old group bucket. The method handles this
	 * gracefully by checking facet presence before removal via {@link #isFacetPresentInGroup}.
	 *
	 * @param index the entity index where the facet is to be removed
	 * @param referenceSchema the schema of the reference that identifies the facet
	 * @param entityPrimaryKey the primary key of the entity whose facet is to be removed
	 * @param existingReference the existing reference containing the facet key and optional group information
	 * @param undoActionConsumer a consumer to handle undo actions; if not null, an operation to re-add the
	 *                           facet will be passed for potential execution
	 */
	private static void removeFacetInIndexInternal(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceSchemaContract referenceSchema,
		int entityPrimaryKey,
		@Nonnull ReferenceContract existingReference,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final ReferenceKey referenceKey = existingReference.getReferenceKey();
		final Integer groupId = existingReference.getGroup()
			.filter(Droppable::exists)
			.map(EntityReferenceContract::getPrimaryKey)
			.orElse(null);
		// only remove from the group if the facet is actually present there — during
		// cross-reference propagation the direct processing path may have already moved
		// the facet, making the storage-derived group stale
		if (isFacetPresentInGroup(index, referenceKey, groupId)) {
			index.removeFacet(referenceSchema, referenceKey, groupId, entityPrimaryKey);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(
					() -> index.addFacet(referenceSchema, referenceKey, groupId, entityPrimaryKey));
			}
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
	 * @param undoActionConsumer    if non-null, receives inverse price insertion operations for rollback
	 */
	private static void removeAllPrices(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull ExistingPriceSupplier existingPriceSupplier,
		@Nullable Consumer<Runnable> undoActionConsumer
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
						existingPriceSupplier,
						undoActionConsumer
					)
				);
		}
	}

	/**
	 * Removes all indexed attributes of the owning entity (both entity-level and reference-level) from
	 * `targetIndex`. The inverse of {@link #indexAllAttributes}.
	 *
	 * Entity-level attributes are only de-indexed when the reference schema is configured for
	 * {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING} in the index's scope. Reference-level attributes
	 * are always de-indexed regardless of index level.
	 *
	 * @param executor                  the mutation executor coordinating attribute index updates
	 * @param referenceSchema           the reference schema; determines attribute scope and index level
	 * @param targetIndex               the reduced entity index from which attributes are removed
	 * @param existingDataSupplierFactory factory for reading existing entity and reference attribute values
	 * @param undoActionConsumer        if non-null, receives inverse attribute insertion operations for rollback
	 */
	private static void removeAllAttributes(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex targetIndex,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final RepresentativeReferenceKey rrk = extractRepresentativeReferenceKey(targetIndex);

		// if the reference is indexed for filtering and partitioning, we need to index attributes from the entity schema
		final Scope scope = targetIndex.getIndexKey().scope();
		if (isIndexedReferenceFor(referenceSchema, scope, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)) {
			// first, we access attributes and sortable compounds from the entity schema
			final EntitySchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider = new EntitySchemaAttributeAndCompoundSchemaProvider(
				entitySchema);

			existingDataSupplierFactory.getEntityAttributeValueSupplier()
				.getAttributeValues()
				.forEach(attribute ->
					         AttributeIndexMutator.executeAttributeRemoval(
						         executor,
						         referenceSchema,
						         attributeSchemaProvider,
						         existingDataSupplierFactory.getEntityAttributeValueSupplier(),
						         targetIndex,
						         targetIndex,
						         attribute.key(),
						         false,
						         false,
						         undoActionConsumer
					         )
				);
		}

		// and the second, we access attributes and sortable compounds from the reference schema
		final ReferenceSchemaAttributeAndCompoundSchemaProvider referenceSchemaAttributeProvider = new ReferenceSchemaAttributeAndCompoundSchemaProvider(
			entitySchema, referenceSchema
		);

		final ExistingAttributeValueSupplier referenceAttributeValueSupplier = existingDataSupplierFactory
			.getReferenceAttributeValueSupplier(rrk);
		referenceAttributeValueSupplier
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
						         false,
						         undoActionConsumer
					         )
				         )
			);
	}

}
