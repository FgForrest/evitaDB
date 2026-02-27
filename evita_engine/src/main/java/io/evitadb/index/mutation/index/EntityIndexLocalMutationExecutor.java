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

import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutationExecutor;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
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
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.RepresentativeAttributeDefinition;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.map.LazyHashMap;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.QuadriConsumer;
import io.evitadb.function.TriConsumer;
import io.evitadb.index.*;
import io.evitadb.index.mutation.index.dataAccess.EntityExistingDataFactory;
import io.evitadb.index.mutation.index.dataAccess.EntityIndexedReferenceSupplier;
import io.evitadb.index.mutation.index.dataAccess.EntityPriceSupplier;
import io.evitadb.index.mutation.index.dataAccess.EntityStoragePartExistingDataFactory;
import io.evitadb.index.mutation.index.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.index.mutation.index.dataAccess.ExistingDataSupplierFactory;
import io.evitadb.index.mutation.index.dataAccess.ReferenceSupplier;
import io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor.LocaleScope;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor.LocaleWithScope;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.PricesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.spi.store.catalog.shared.model.PriceWithInternalIds;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntBiFunction;

import static io.evitadb.index.mutation.index.HierarchyPlacementMutator.removeParent;
import static io.evitadb.index.mutation.index.HierarchyPlacementMutator.setParent;
import static io.evitadb.utils.Assert.isPremiseValid;
import static java.util.Optional.of;

/**
 * This class applies changes in {@link LocalMutation} to one or multiple {@link EntityIndex} so that changes are reflected
 * in next filtering / sorting query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class EntityIndexLocalMutationExecutor implements LocalMutationExecutor {
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
	 * The sequence service that assigns new price internal ids.
	 */
	@Nonnull private final IntSupplier priceInternalIdSupplier;
	/**
	 * List of all undo actions that must be executed in case of (semi) rollback.
	 */
	private final LinkedList<Runnable> undoActions;
	/**
	 * Consumer that collects lambdas allowing to execute undo actions.
	 */
	@Nullable private final Consumer<Runnable> undoActionsAppender;
	/**
	 * Supplier that allows to retrieve full entity body. It's used only when entity changes its scope.
	 */
	private final Supplier<Entity> fullEntitySupplier;
	/**
	 * Memoized factory that allows to retrieve existing attribute values from the current storage part.
	 */
	private EntityStoragePartExistingDataFactory storagePartExistingDataFactory;
	/**
	 * Set of keys of indexes that were created in this particular entity upsert.
	 */
	private Set<RepresentativeReferenceKey> createdReferences;
	/**
	 * Set contains keys of indexes that were accessed in this particular entity upsert / removal.
	 */
	private final Set<EntityIndexKey> accessedIndexes = CollectionUtils.createHashSet(32);
	/**
	 * Contains index of calculated {@link RepresentativeReferenceKey} that include representative attribute values
	 * for each reference that allows duplicates. This prevents from recalculating these values multiple times
	 * during a single entity upsert (calculation is quite expensive). Map contains two key variants in case any
	 * of the representative attributes changes during the upsert.
	 */
	private final Map<ComparableReferenceKey, RepresentativeReferenceKeys> memoizedRepresentativeAttributes = new LazyHashMap<>(8);
	/**
	 * List of all mutations that are being processed right now.
	 */
	@Nullable private List<? extends LocalMutation<?, ?>> localMutations;
	/**
	 * Memoized scope of the current entity.
	 */
	private Scope memoizedScope;

	public EntityIndexLocalMutationExecutor(
		@Nonnull WritableEntityStorageContainerAccessor containerAccessor,
		int entityPrimaryKey,
		@Nonnull IndexMaintainer<EntityIndexKey, EntityIndex> entityIndexCreatingAccessor,
		@Nonnull IndexMaintainer<CatalogIndexKey, CatalogIndex> catalogIndexCreatingAccessor,
		@Nonnull Supplier<EntitySchema> schemaAccessor,
		@Nonnull IntSupplier priceInternalIdSupplier,
		boolean undoOnError,
		@Nonnull Supplier<Entity> fullEntitySupplier
	) {
		this.containerAccessor = containerAccessor;
		this.entityPrimaryKey.add((anyType, anyPurpose) -> entityPrimaryKey);
		this.entityIndexCreatingAccessor = entityIndexCreatingAccessor;
		this.catalogIndexCreatingAccessor = catalogIndexCreatingAccessor;
		this.schemaAccessor = schemaAccessor;
		this.priceInternalIdSupplier = priceInternalIdSupplier;
		this.entityType = schemaAccessor.get().getName();
		this.undoActions = undoOnError ? new LinkedList<>() : null;
		this.undoActionsAppender = undoOnError ? this.undoActions::add : null;
		this.fullEntitySupplier = fullEntitySupplier;
	}

	/**
	 * Returns the scope of the current entity. If the scope has already been retrieved and memoized, it returns the
	 * memoized value. Otherwise, it fetches the scope from the main entity storage part, memoizes it, and returns it.
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
	 * Prepares the necessary initial setup for the provided local mutations, ensuring
	 * appropriate indexes and configurations are in place for further processing.
	 * This involves maintaining undo actions when modifications occur and
	 * initializing required components such as global indexes and sortable attribute compounds.
	 *
	 * @param localMutations the list of mutations that are applied locally; these mutations
	 *                        are used to make changes to the current state and may include
	 *                        accompanying undo actions for reversibility
	 */
	public void prepare(@Nonnull List<? extends LocalMutation<?, ?>> localMutations) {
		this.localMutations = localMutations;

		final EntityIndex globalIndex = getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, getScope()));
		final int recordId = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.NEW);
		final boolean created = globalIndex.insertPrimaryKeyIfMissing(recordId);
		if (created) {
			if (this.undoActions != null) {
				this.undoActions.add(() -> globalIndex.removePrimaryKey(recordId));
			}

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
				getStoragePartExistingDataFactory().getEntityAttributeValueSupplier(),
				this.undoActionsAppender
			);

			if (this.schemaAccessor.get().isWithHierarchy()) {
				setParent(
					this, globalIndex,
					getPrimaryKeyToIndex(IndexType.HIERARCHY_INDEX, Target.NEW),
					null,
					this.undoActionsAppender
				);
			}
		}
	}

	@Override
	public void applyMutation(@Nonnull LocalMutation<?, ?> localMutation) {
		final GlobalEntityIndex globalIndex = (GlobalEntityIndex) getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, getScope()));
		if (localMutation instanceof SetPriceInnerRecordHandlingMutation priceHandlingMutation) {
			updatePriceHandlingForEntity(
				priceHandlingMutation,
				globalIndex
			);
		} else if (localMutation instanceof PriceMutation priceMutation) {
			if (priceMutation instanceof RemovePriceMutation ||
				// when new upserted price is not indexed, it is removed from indexes, so we need to behave like removal
				(priceMutation instanceof UpsertPriceMutation upsertPriceMutation && !upsertPriceMutation.isIndexed())) {
				// removal must first occur on the reduced indexes, because they consult the super index
				final ReferenceIndexConsumer priceRemovalConsumer =
					(referenceSchema, indexForRemoval, indexForUpsert) ->
						updatePriceIndex(referenceSchema, priceMutation, indexForRemoval, indexForUpsert);
				ReferenceIndexMutator.executeWithAllReferenceIndexes(
					ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING, this, priceRemovalConsumer, true
				);
				updatePriceIndex(null, priceMutation, globalIndex, globalIndex);
			} else {
				// upsert must first occur on super index, because reduced indexed rely on information in super index
				updatePriceIndex(null, priceMutation, globalIndex, globalIndex);
				final ReferenceIndexConsumer priceUpsertConsumer =
					(referenceSchema, indexForRemoval, indexForUpsert) ->
						updatePriceIndex(referenceSchema, priceMutation, indexForRemoval, indexForUpsert);
				ReferenceIndexMutator.executeWithAllReferenceIndexes(
					ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING, this, priceUpsertConsumer, true
				);
			}
		} else if (localMutation instanceof ParentMutation parentMutation) {
			updateHierarchyPlacement(parentMutation, globalIndex);
		} else if (localMutation instanceof ReferenceMutation<?> referenceMutation) {
			final ReferenceKey referenceKey = referenceMutation.getReferenceKey();
			final ReferenceSchemaContract referenceSchema = getEntitySchema().getReferenceOrThrowException(referenceKey.referenceName());
			if (referenceSchema.isIndexedInScope(getScope())) {
				updateReferences(referenceMutation, globalIndex);
				final ReferenceIndexConsumer crossRefConsumer =
					(theReferenceSchema, indexForRemoval, indexForUpsert) -> updateReferencesInReferenceIndex(
						referenceMutation, theReferenceSchema, indexForRemoval, indexForUpsert
					);
				final Predicate<ReferenceContract> crossRefPredicate =
					// avoid indexing the referenced index that got updated by updateReferences method
					referenceContract -> !referenceKey.equalsInGeneral(referenceContract.getReferenceKey());
				final boolean presenceExpected = !(referenceMutation instanceof InsertReferenceMutation);
				ReferenceIndexMutator.executeWithAllReferenceIndexes(
					ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING, this,
					crossRefConsumer, crossRefPredicate, presenceExpected
				);
			}
		} else if (localMutation instanceof AttributeMutation attributeMutation) {
			final ExistingAttributeValueSupplier entityAttributeValueSupplier = getStoragePartExistingDataFactory().getEntityAttributeValueSupplier();
			final QuadriConsumer<Boolean, EntityIndex, EntityIndex, ReferenceSchemaContract> attributeUpdateApplicator =
				(updateGlobalIndex, indexForRemoval, indexForUpsert, theReferenceSchema) -> updateAttribute(
					theReferenceSchema,
					attributeMutation,
					new EntitySchemaAttributeAndCompoundSchemaProvider(getEntitySchema()),
					entityAttributeValueSupplier,
					indexForRemoval,
					indexForUpsert,
					updateGlobalIndex,
					true
				);
			//noinspection DataFlowIssue
			attributeUpdateApplicator.accept(true, globalIndex, globalIndex, null);
			final ReferenceIndexConsumer attrConsumer =
				(theReferenceSchema, indexForRemoval, indexForUpsert) ->
					attributeUpdateApplicator.accept(false, indexForRemoval, indexForUpsert, theReferenceSchema);
			ReferenceIndexMutator.executeWithAllReferenceIndexes(
				ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING, this,
				attrConsumer, Droppable::exists, true
			);
		} else if (localMutation instanceof AssociatedDataMutation) {
			// do nothing, associated data doesn't affect entity index directly
		} else if (localMutation instanceof SetEntityScopeMutation setEntityScopeMutation) {
			final Entity entity = this.fullEntitySupplier.get();
			if (entity.getScope() == setEntityScopeMutation.getScope()) {
				// do nothing, the scope is already set
			} else {
				// remove the entity from the indexes
				Assert.isPremiseValid(
					Objects.equals(entity.getScope(), getScope()),
					"Scope between entity and latest entity body container must be the same!"
				);
				removeEntityFromIndexes(entity, entity.getScope());
				addEntityToIndexes(entity, setEntityScopeMutation.getScope());
				// reset memoized scope, it has just changed
				this.memoizedScope = setEntityScopeMutation.getScope();
			}
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new GenericEvitaInternalError("Unknown mutation: " + localMutation.getClass());
		}
	}

	@Override
	public void commit() {
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
				if (scope.contains(LocaleScope.ENTITY)) {
					upsertEntityAttributeLocale(locale, entitySchema, existingAttributeFactory);
				}
			}
			for (LocaleWithScope localeWithScope : entityRemovedLocales) {
				final EnumSet<LocaleScope> scope = localeWithScope.scope();
				final Locale locale = localeWithScope.locale();
				if (scope.contains(LocaleScope.ENTITY)) {
					removeEntityLocale(locale, entitySchema, existingAttributeFactory);
				}
				if (scope.contains(LocaleScope.ENTITY)) {
					removeEntityAttributeLocale(locale, entitySchema, existingAttributeFactory);
				}
			}
		}

		if (this.containerAccessor.isEntityRemovedEntirely()) {
			// remove the entity itself from the indexes
			removeEntity(primaryKeyToIndex);
		}

		// remove all empty indexes after this executor is committed
		for (EntityIndexKey accessedIndexKey : this.accessedIndexes) {
			// global live index is never removed and is always present (even if empty)
			if (!(accessedIndexKey.type() == EntityIndexType.GLOBAL && accessedIndexKey.scope() == Scope.LIVE)) {
				final EntityIndex entityIndex = this.entityIndexCreatingAccessor.getIndexIfExists(accessedIndexKey);
				if (entityIndex != null && entityIndex.isEmpty()) {
					this.entityIndexCreatingAccessor.removeIndex(accessedIndexKey);
				}
			}
		}
	}

	@Override
	public void rollback() {
		// execute all undo actions in reverse order of how they have been registered
		if (this.undoActions != null) {
			for (int i = this.undoActions.size() - 1; i >= 0; i--) {
				this.undoActions.get(i).run();
			}
		}
	}

	@Nonnull
	public CatalogIndex getCatalogIndex(@Nonnull Scope scope) {
		return this.catalogIndexCreatingAccessor.getOrCreateIndex(new CatalogIndexKey(scope));
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
	 * Removes the entity from group-level indexes associated with the given reference key.
	 * Looks up the reference from storage and delegates to
	 * {@link #removeFromGroupIndexes(int, EntitySchema, ReferenceSchemaContract, RepresentativeReferenceKey, ReferenceKey, ReferenceContract, Scope, ExistingDataSupplierFactory)}.
	 *
	 * @param epk                    the entity primary key
	 * @param entitySchema           the entity schema
	 * @param referenceSchema        the reference schema
	 * @param rrk                    the entity-level representative reference key
	 * @param referenceKey           the reference key
	 * @param scope                  the current scope
	 * @param existingDataFactory    factory supplying existing data for the removal
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
	 * @param epk                    the entity primary key
	 * @param entitySchema           the entity schema
	 * @param referenceSchema        the reference schema
	 * @param rrk                    the entity-level representative reference key
	 * @param referenceKey           the reference key
	 * @param existingReference      the existing reference contract (already resolved)
	 * @param scope                  the current scope
	 * @param existingDataFactory    factory supplying existing data for the removal
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
			// use rrk (with referenced entity PK) as the discriminator — the type index
			// mapping is keyed by groupPK separately via the referencedPrimaryKey parameter
			final AbstractReducedEntityIndex groupIndex = ReferenceIndexMutator.getOrCreateReferencedGroupEntityIndex(
				this, rrk, scope
			);
			ReferenceIndexMutator.referenceRemovalPerComponent(
				epk, entitySchema, referenceSchema, this,
				groupTypeIndex, groupIndex, referenceKey, groupPK,
				existingDataFactory, this.undoActionsAppender
			);
		}
	}

	/**
	 * Inserts the entity into group-level indexes for the given group primary key.
	 * Creates or retrieves the group type index and group reduced index, then delegates
	 * to {@link ReferenceIndexMutator#referenceInsertPerComponent}.
	 *
	 * @param epk                        the entity primary key
	 * @param entitySchema               the entity schema
	 * @param referenceSchema            the reference schema
	 * @param rrk                        the entity-level representative reference key
	 * @param referenceKey               the reference key
	 * @param groupPK                    the group primary key
	 * @param scope                      the current scope
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
		// use rrk (with referenced entity PK) as the discriminator — the type index
		// mapping is keyed by groupPK separately via the referencedPrimaryKey parameter
		final AbstractReducedEntityIndex groupIndex =
			ReferenceIndexMutator.getOrCreateReferencedGroupEntityIndex(
				this, rrk, scope
			);
		ReferenceIndexMutator.referenceInsertPerComponent(
			epk, entitySchema, referenceSchema, this,
			groupTypeIndex, groupIndex, referenceKey,
			groupPK, groupPK,
			existingDataSupplierFactory,
			this.undoActionsAppender
		);
	}

	/**
	 * Retrieves the ReferencesStoragePart instance associated with the entity type and primary key index.
	 *
	 * @return a non-null ReferencesStoragePart instance containing reference storage data
	 */
	@Nonnull
	ReferencesStoragePart getReferencesStoragePart() {
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
	 * @param entityPrimaryKey the primary key of the entity for which the representative reference
	 *                         key is being retrieved
	 * @param globalEntityIndex the global index containing information about all entities
	 * @param referenceKey the key identifying the specific referenced entity
	 * @param referenceSchema the schema contract of the reference to provide structural details
	 * @param referencePresenceExpected a flag indicating whether the presence of the reference is expected
	 *                                  or optional
	 * @return the representative reference key, which may carry additional representative attributes
	 *         if the reference supports duplicates
	 */
	@Nonnull
	RepresentativeReferenceKey getRepresentativeReferenceKey(
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
	 * Retrieves the representative reference keys for the provided parameters, adjusting the relevant references
	 * within the system as necessary. This method ensures that references are properly managed between their
	 * current and stored states.
	 *
	 * @param entityPrimaryKey the primary key of the entity for which references are being managed
	 * @param globalEntityIndex a reference to the global index of all entities in scope
	 * @param referenceKey the unique key identifying the specific reference being managed
	 * @param comparableReferenceKey a comparable key that assists in identifying and differentiating references
	 * @param referenceSchema the schema that defines the contract and structure of the reference
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
		if (bothKeys.differ() && (this.createdReferences == null || !this.createdReferences.contains(bothKeys.current()))) {
			final EntitySchema entitySchema = getEntitySchema();
			existingStoragePartFactory.executeWithRepresentativeReferenceKeyAlias(
				bothKeys.current(), bothKeys.stored(),
				() -> {
					// global: always — migrate facet between representative keys
					ReferenceIndexMutator.referenceRemovalGlobal(
						entityPrimaryKey, referenceSchema, globalEntityIndex,
						referenceKey, this, this.undoActionsAppender
					);
					ReferenceIndexMutator.referenceInsertGlobal(
						entityPrimaryKey, referenceSchema, globalEntityIndex,
						referenceKey, null, this, this.undoActionsAppender
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
							existingStoragePartFactory, this.undoActionsAppender
						);
						ReferenceIndexMutator.referenceInsertPerComponent(
							entityPrimaryKey, entitySchema, referenceSchema, this,
							referenceTypeIndex, newReferenceIndex, referenceKey,
							referenceKey.primaryKey(), null,
							existingStoragePartFactory, this.undoActionsAppender
						);
					}
					// group component migration (if group indexing enabled and group exists)
					if (ReferenceIndexMutator.isIndexedForGroupComponent(referenceSchema, scope)) {
						final ReferenceContract reference = getReferencesStoragePart()
							.findReference(referenceKey)
							.orElse(null);
						if (reference != null) {
							final Integer groupPK = extractActiveGroupPrimaryKey(reference);
							if (groupPK != null) {
								// use entity-level RRKs (with referenced entity PK) as group index discriminator
								final ReferencedTypeEntityIndex groupTypeIndex =
									ReferenceIndexMutator.getOrCreateReferencedGroupTypeEntityIndex(
										this, referenceKey.referenceName(), scope
									);
								final ReducedGroupEntityIndex formerGroupIndex =
									ReferenceIndexMutator.getOrCreateReferencedGroupEntityIndex(
										this, bothKeys.stored(), scope
									);
								final ReducedGroupEntityIndex newGroupIndex =
									ReferenceIndexMutator.getOrCreateReferencedGroupEntityIndex(
										this, bothKeys.current(), scope
									);
								ReferenceIndexMutator.referenceRemovalPerComponent(
									entityPrimaryKey, entitySchema, referenceSchema, this,
									groupTypeIndex, formerGroupIndex, referenceKey, groupPK,
									existingStoragePartFactory, this.undoActionsAppender
								);
								ReferenceIndexMutator.referenceInsertPerComponent(
									entityPrimaryKey, entitySchema, referenceSchema, this,
									groupTypeIndex, newGroupIndex, referenceKey, groupPK, groupPK,
									existingStoragePartFactory, this.undoActionsAppender
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
	 * Retrieves the representative reference key for the given reference key. Depending on the cardinality
	 * of the reference schema, the method calculates representative attributes for all existing references
	 * of the specified type if duplicates are allowed. If no duplicates are allowed, a representative key
	 * is created directly based on the specified reference key.
	 *
	 * @param referenceKey the key of the reference for which the representative key is to be retrieved
	 * @return the representative reference key, which may carry additional representative attributes
	 *         if the reference supports duplicates
	 */
	@Nonnull
	RepresentativeReferenceKeys getRepresentativeReferenceKeys(
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
	EntitySchema getEntitySchema() {
		return this.schemaAccessor.get();
	}

	/*
		FRIENDLY METHODS
	 */

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
	int getPrimaryKeyToIndex(@Nonnull IndexType indexType, @Nonnull Target target) {
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
	EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey entityIndexKey) {
		this.accessedIndexes.add(entityIndexKey);
		return this.entityIndexCreatingAccessor.getOrCreateIndex(entityIndexKey);
	}

	/**
	 * Method returns existing index by primary key and adds it to the changed set of indexes that needs persisting.
	 */
	@Nonnull
	Optional<EntityIndex> getIndexByPrimaryKey(int indexPrimaryKey) {
		final EntityIndex index = this.entityIndexCreatingAccessor.getIndexByPrimaryKey(indexPrimaryKey);
		this.accessedIndexes.add(index.getIndexKey());
		return of(index);
	}

	/**
	 * Method processes all mutations that targets entity attributes - e.g. {@link AttributeMutation}.
	 */
	void updateAttribute(
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
				indexForRemoval, indexForUpsert, affectedAttribute, attributeValue, updateGlobalIndex, updateCompounds,
				this.undoActionsAppender
			);
		} else if (attributeMutation instanceof RemoveAttributeMutation) {
			AttributeIndexMutator.executeAttributeRemoval(
				this, referenceSchema, attributeSchemaProvider, existingValueSupplier,
				indexForRemoval, indexForUpsert, affectedAttribute, updateGlobalIndex, updateCompounds,
				this.undoActionsAppender
			);
		} else if (attributeMutation instanceof ApplyDeltaAttributeMutation<?> applyDeltaAttributeMutation) {
			final Number attributeValue = applyDeltaAttributeMutation.getAttributeValue();
			AttributeIndexMutator.executeAttributeDelta(
				this, referenceSchema, attributeSchemaProvider, existingValueSupplier,
				indexForRemoval, indexForUpsert, affectedAttribute, attributeValue, this.undoActionsAppender
			);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new GenericEvitaInternalError("Unknown mutation: " + attributeMutation.getClass());
		}
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Inserts an initial suite of sortable attribute compounds into the specified target index.
	 *
	 * This method determines whether the target index is associated with a reference key or not
	 * and delegates to the appropriate mutator. It utilizes the existing data supplier factory
	 * to source any required pre-existing data and optionally records undo actions.
	 *
	 * @param locale the locale context for the operation, which may be null;
	 *               if null, the operation is performed without locale-specific considerations
	 * @param targetIndex the target entity index where the sortable attribute compounds will be inserted;
	 *                    it must not be null
	 * @param existingDataSupplierFactory the factory to create suppliers that provide existing data used during the insert;
	 *                                    it must not be null
	 * @param undoActionConsumer an optional consumer to store runnable actions for undoing changes;
	 *                           may be null if undo actions are not required
	 */
	private void insertInitialSuiteOfSortableAttributeCompounds(
		@Nullable Locale locale,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityIndex targetIndex,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		if (targetIndex instanceof AbstractReducedEntityIndex reducedEntityIndex &&
			targetIndex.getIndexKey().discriminator() instanceof RepresentativeReferenceKey referenceKey) {
			final ReferenceSchemaContract referenceSchema = entitySchema
				.getReferenceOrThrowException(referenceKey.referenceName());
			if (ReferenceIndexMutator.isIndexedReferenceForFilteringAndPartitioning(referenceSchema, getScope())) {
				ReferenceIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
					this,
					referenceSchema,
					reducedEntityIndex,
					locale,
					existingDataSupplierFactory,
					undoActionConsumer
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
				existingDataSupplierFactory.getEntityAttributeValueSupplier(),
				undoActionConsumer
			);
		}
	}

	/**
	 * Removes an entire suite of sortable attribute compounds from the specified entity index.
	 *
	 * @param locale The locale for which the attribute compounds should be removed. This may be null if locale-specific processing is not required.
	 * @param targetIndex The target entity index from which the sortable attribute compounds will be removed. Must not be null.
	 * @param existingDataSupplierFactory A factory for creating suppliers of existing data that may be used to assist in the removal process. Must not be null.
	 * @param undoActionConsumer An optional consumer that can accept a Runnable to perform undo actions, if needed. This may be null if undo functionality is not required.
	 */
	private void removeEntireSuiteOfSortableAttributeCompounds(
		@Nullable Locale locale,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityIndex targetIndex,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		ReferenceSchemaContract referenceSchema = null;
		if (targetIndex instanceof AbstractReducedEntityIndex reducedEntityIndex &&
			targetIndex.getIndexKey().discriminator() instanceof RepresentativeReferenceKey referenceKey) {
			final String referenceName = referenceKey.referenceName();
			referenceSchema = entitySchema.getReference(referenceName)
			                              .orElseThrow(() -> new ReferenceNotFoundException(
				                              referenceName, entitySchema));
			if (ReferenceIndexMutator.isIndexedReferenceForFilteringAndPartitioning(referenceSchema, getScope())) {
				ReferenceIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
					this,
					referenceSchema, reducedEntityIndex,
					locale,
					existingDataSupplierFactory,
					undoActionConsumer
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
				existingDataSupplierFactory.getEntityAttributeValueSupplier(),
				undoActionConsumer
			);
		}
	}

	/**
	 * Retrieves or creates an instance of ExistingDataSupplierFactory for the current storage part.
	 * If the factory is not already created, it initializes the factory using the root primary key and the type.
	 *
	 * @return An instance of ExistingDataSupplierFactory associated with the current storage part.
	 */
	@Nonnull
	private EntityStoragePartExistingDataFactory getStoragePartExistingDataFactory() {
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
				getStoragePartExistingDataFactory().getEntityAttributeValueSupplier(),
				this.undoActionsAppender
			);
		}
	}

	/**
	 * Removes entity from all indexes with passed scope.
	 *
	 * @param entity entity to be removed
	 * @param scope  scope of the entity
	 */
	private void removeEntityFromIndexes(@Nonnull Entity entity, @Nonnull Scope scope) {
		final GlobalEntityIndex globalIndex = (GlobalEntityIndex) getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, scope));
		final int entityPrimaryKey = entity.getPrimaryKeyOrThrowException();
		final EntitySchema entitySchema = this.getEntitySchema();
		final EntityExistingDataFactory existingDataSupplierFactory = new EntityExistingDataFactory(entity, entitySchema);
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
			"Scope of the global index must match the provided scope! Provided scope: " + scope + ", global index scope: " + globalIndex.getIndexKey().scope() + "."
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
				entityPrimaryKey,
				this.undoActionsAppender
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
		entity.getAttributeValues()
			.stream()
			.filter(Droppable::exists)
			.forEach(
				attributeValue -> {
					final AttributeKey key = attributeValue.key();
					AttributeIndexMutator.executeAttributeRemoval(
						this,
						null,
						new EntitySchemaAttributeAndCompoundSchemaProvider(entitySchema),
						existingDataSupplierFactory.getEntityAttributeValueSupplier(),
						globalIndex,
						globalIndex,
						key,
						true,
						true,
						this.undoActionsAppender
					);
					existingDataSupplierFactory.registerRemoval(key);
				}
			);

		// unindex all non-localized attribute compounds
		removeEntireSuiteOfSortableAttributeCompounds(
			null, entitySchema, globalIndex, existingDataSupplierFactory, this.undoActionsAppender
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
		for (ReferenceContract reference : entity.getReferences()) {
			if (reference.exists()) {
				final ReferenceKey referenceKey = reference.getReferenceKey();
				final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(referenceKey.referenceName());
				final RepresentativeReferenceKey rrk = getRepresentativeReferenceKey(
					epk, globalIndex, referenceKey, referenceSchema, true
				);
				if (ReferenceIndexMutator.isIndexedReferenceForFiltering(referenceSchema, scope)) {
					// global: always — remove facet from global index
					ReferenceIndexMutator.referenceRemovalGlobal(
						epk, referenceSchema, globalIndex, referenceKey,
						this, this.undoActionsAppender
					);
					// entity component: entity type index + entity reduced index (only when configured)
					if (ReferenceIndexMutator.isIndexedForEntityComponent(referenceSchema, scope)) {
						final ReferencedTypeEntityIndex referenceTypeIndex =
							ReferenceIndexMutator.getOrCreateReferencedTypeEntityIndex(
								this, referenceKey.referenceName(), scope
							);
						final ReducedEntityIndex mainReferenceIndex =
							ReferenceIndexMutator.getOrCreateReferencedEntityIndex(
								this, rrk, scope
							);
						ReferenceIndexMutator.referenceRemovalPerComponent(
							epk, entitySchema, referenceSchema, this,
							referenceTypeIndex, mainReferenceIndex, referenceKey,
							referenceKey.primaryKey(),
							existingDataSupplierFactory,
							this.undoActionsAppender
						);
					}
					// group component: independent — group type index + group reduced index
					if (ReferenceIndexMutator.isIndexedForGroupComponent(referenceSchema, scope)) {
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
				entity.getPriceInnerRecordHandling(),
				this.undoActionsAppender
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
							if (ReferenceIndexMutator.isIndexedReferenceFor(referenceSchema, scope, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)) {
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
	private void addEntityToIndexes(@Nonnull Entity entity, @Nonnull Scope scope) {
		final GlobalEntityIndex globalIndex = (GlobalEntityIndex) getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, scope));
		final int entityPrimaryKey = entity.getPrimaryKeyOrThrowException();
		final EntitySchema entitySchema = this.getEntitySchema();
		final EntityExistingDataFactory existingDataSupplierFactory = new EntityExistingDataFactory(entity, entitySchema);
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
	 * Attempts to index an entity by inserting its primary key into the global index if it is missing.
	 *
	 * @param entityPrimaryKey the primary key of the entity to be indexed
	 * @param globalIndex an instance of {@code GlobalEntityIndex} used to check and insert the primary key
	 * @return {@code true} if the primary key was successfully inserted; {@code false} if the primary key was already present
	 */
	private static boolean indexEntity(
		int entityPrimaryKey,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		return globalIndex.insertPrimaryKeyIfMissing(entityPrimaryKey);
	}

	/**
	 * Indexes all locales for the given entity by invoking the method to upsert language-specific entities
	 * into the target index.
	 *
	 * @param entity The entity containing locales to be indexed.
	 * @param entitySchema The schema of the entity providing structure and constraints.
	 * @param globalIndex The global index where language-specific entries will be upserted.
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
		entity.getAttributeValues()
			.stream()
			.filter(Droppable::exists)
			.forEach(
				attributeValue -> {
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
						false,
						this.undoActionsAppender
					);
				}
			);

		// index all non-localized attribute compounds
		insertInitialSuiteOfSortableAttributeCompounds(
			null, entitySchema, globalIndex, existingDataSupplierFactory, this.undoActionsAppender
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
				entity.getParent().getAsInt(),
				this.undoActionsAppender
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

		final TriConsumer<ReferenceSchemaContract, EntityIndex, PriceWithInternalIds> priceUpsertOperation = (referenceSchema, index, price) -> PriceIndexMutator.priceUpsert(
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
			PriceIndexMutator.createPriceProvider(price),
			this.undoActionsAppender
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
							if (ReferenceIndexMutator.isIndexedReferenceFor(referenceSchema, scope, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)) {
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
		for (ReferenceContract reference : entity.getReferences()) {
			if (reference.exists()) {
				final ReferenceKey referenceKey = reference.getReferenceKey();
				final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(referenceKey.referenceName());
				final RepresentativeReferenceKey rrk = getRepresentativeReferenceKey(
					epk, globalIndex, referenceKey, referenceSchema, true
				);
				if (ReferenceIndexMutator.isIndexedReferenceForFiltering(referenceSchema, scope)) {
					final Integer groupId = extractActiveGroupPrimaryKey(reference);
					// global: always — add facet to global index
					ReferenceIndexMutator.referenceInsertGlobal(
						epk, referenceSchema, globalIndex, referenceKey, groupId,
						this, this.undoActionsAppender
					);
					// entity component: entity type index + entity reduced index (only when configured)
					if (ReferenceIndexMutator.isIndexedForEntityComponent(referenceSchema, scope)) {
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
							existingDataSupplierFactory,
							this.undoActionsAppender
						);
						// cross-reference facet indexing: only when entity component is enabled
						if (referenceSchema.isFacetedInScope(scope)) {
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
										this,
										this.undoActionsAppender
									);
								}
							}
						}
					}
					// group component: independent — group type index + group reduced index
					if (groupId != null &&
						ReferenceIndexMutator.isIndexedForGroupComponent(referenceSchema, scope)) {
						insertIntoGroupIndexes(
							epk, entitySchema, referenceSchema, rrk, referenceKey,
							groupId, scope, existingDataSupplierFactory
						);
					}
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
				if (theReferenceSchema == null || !Objects.equals(refKey.referenceName(), theReferenceSchema.getName())) {
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
									epk -> getIndexByPrimaryKey(epk).map(ReducedGroupEntityIndex.class::cast).orElse(null),
									scope,
									theReferenceSchema.getName(),
									groupPK,
									// always use type index path — group index discriminator uses referenced entity PK,
									// not group PK, so direct key construction would fail
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
	 * Method processes all mutations that target entity references - e.g. {@link ReferenceMutation}. This method
	 * alters contents of the primary indexes - i.e. global index, reference type and referenced entity index for
	 * the particular referenced entity.
	 */
	private void updateReferences(@Nonnull ReferenceMutation<?> referenceMutation, @Nonnull GlobalEntityIndex entityIndex) {
		final Scope scope = getScope();
		final int epk = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.NEW);
		final ReferenceKey referenceKey = referenceMutation.getReferenceKey();
		final ReferenceSchema referenceSchema = getEntitySchema().getReferenceOrThrowException(referenceKey.referenceName());
		final boolean entityComponentEnabled = ReferenceIndexMutator.isIndexedForEntityComponent(referenceSchema, scope);
		final boolean groupIndexingEnabled = ReferenceIndexMutator.isIndexedForGroupComponent(referenceSchema, scope);

		if (referenceMutation instanceof SetReferenceGroupMutation upsertReferenceGroupMutation) {
			final RepresentativeReferenceKey rrk = getRepresentativeReferenceKey(epk, entityIndex, referenceKey, referenceSchema, true);
			// update facet group in global index (always)
			ReferenceIndexMutator.setFacetGroupInIndex(
				epk, entityIndex, referenceSchema,
				upsertReferenceGroupMutation.getReferenceKey(),
				upsertReferenceGroupMutation.getGroupPrimaryKey(),
				this
			);
			// entity component: update facet group in entity reduced index
			if (entityComponentEnabled) {
				final ReducedEntityIndex referenceIndex =
					ReferenceIndexMutator.getOrCreateReferencedEntityIndex(this, rrk, scope);
				ReferenceIndexMutator.setFacetGroupInIndex(
					epk, referenceIndex, referenceSchema,
					upsertReferenceGroupMutation.getReferenceKey(),
					upsertReferenceGroupMutation.getGroupPrimaryKey(),
					this
				);
			}
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
				final int newGroupPK = upsertReferenceGroupMutation.getGroupPrimaryKey();
				insertIntoGroupIndexes(
					epk, entitySchema, referenceSchema, rrk, referenceKey,
					newGroupPK, scope, existingStoragePartFactory
				);
			}
		} else if (referenceMutation instanceof RemoveReferenceGroupMutation removeReferenceGroupMutation) {
			final RepresentativeReferenceKey rrk = getRepresentativeReferenceKey(epk, entityIndex, referenceKey, referenceSchema, true);
			// update facet group in global index (always)
			ReferenceIndexMutator.removeFacetGroupInIndex(
				epk, entityIndex, referenceSchema,
				removeReferenceGroupMutation.getReferenceKey(),
				this
			);
			// entity component: update facet group in entity reduced index
			if (entityComponentEnabled) {
				final ReducedEntityIndex referenceIndex =
					ReferenceIndexMutator.getOrCreateReferencedEntityIndex(this, rrk, scope);
				ReferenceIndexMutator.removeFacetGroupInIndex(
					epk, referenceIndex, referenceSchema,
					removeReferenceGroupMutation.getReferenceKey(),
					this
				);
			}
			// group component: remove from group indexes
			if (groupIndexingEnabled) {
				removeFromGroupIndexes(
					epk, getEntitySchema(), referenceSchema, rrk, referenceKey,
					scope, getStoragePartExistingDataFactory()
				);
			}
		} else {
			if (referenceMutation instanceof ReferenceAttributeMutation referenceAttributesUpdateMutation) {
				final AttributeMutation attributeMutation = referenceAttributesUpdateMutation.getAttributeMutation();
				final RepresentativeReferenceKey rrk = getRepresentativeReferenceKey(epk, entityIndex, referenceKey, referenceSchema, true);
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
							// use rrk (with referenced entity PK) as the group index discriminator
							final ReferencedTypeEntityIndex groupTypeIndex =
								ReferenceIndexMutator.getOrCreateReferencedGroupTypeEntityIndex(
									this, referenceKey.referenceName(), scope
								);
							final ReducedGroupEntityIndex groupIndex =
								ReferenceIndexMutator.getOrCreateReferencedGroupEntityIndex(
									this, rrk, scope
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
			} else {
				final EntitySchema entitySchema = getEntitySchema();
				if (referenceMutation instanceof InsertReferenceMutation) {
					// groupId is null at insert time — group component is handled by SetReferenceGroupMutation
					final RepresentativeReferenceKey rrk = getRepresentativeReferenceKey(
						epk, entityIndex, referenceKey, referenceSchema, false
					);
					// global: always — add facet to global index
					ReferenceIndexMutator.referenceInsertGlobal(
						epk, referenceSchema, entityIndex, referenceKey, null,
						this, this.undoActionsAppender
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
							getStoragePartExistingDataFactory(),
							this.undoActionsAppender
						);
					}
				} else if (referenceMutation instanceof RemoveReferenceMutation) {
					final RepresentativeReferenceKey rrk = getRepresentativeReferenceKey(
						epk, entityIndex, referenceKey, referenceSchema, true
					);
					// global: always — remove facet from global index
					ReferenceIndexMutator.referenceRemovalGlobal(
						epk, referenceSchema, entityIndex, referenceKey,
						this, this.undoActionsAppender
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
							getStoragePartExistingDataFactory(),
							this.undoActionsAppender
						);
					}
					// group component: remove from group indexes (if reference had a group)
					if (groupIndexingEnabled) {
						removeFromGroupIndexes(
							epk, entitySchema, referenceSchema, rrk, referenceKey,
							scope, getStoragePartExistingDataFactory()
						);
					}
				} else {
					// SHOULD NOT EVER HAPPEN
					throw new GenericEvitaInternalError("Unknown mutation: " + referenceMutation.getClass());
				}
			}
		}
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
	 * Ensures the specified language is added to the target index for the given entity.
	 * If the language already exists in the target index for the entity, an exception is thrown.
	 * Additionally, provides an undo operation to revert the language addition, if needed.
	 *
	 * @param locale the locale representing the language to be added
	 * @param entitySchema the schema of the entity to which the language is being added
	 * @param targetIndex the target index where the language should be updated
	 * @param epk the primary key of the entity being updated
	 */
	public void upsertEntityLocaleInTargetIndex(
		@Nonnull Locale locale,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull EntityIndex targetIndex,
		int epk
	) {
		targetIndex.upsertLanguage(locale, epk, entitySchema);
		if (this.undoActionsAppender != null) {
			this.undoActionsAppender.accept(
				() -> targetIndex.removeLanguage(locale, epk)
			);
		}
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
				final RepresentativeReferenceKey referenceKey = Objects.requireNonNull((RepresentativeReferenceKey) index.getIndexKey().discriminator());
				if (this.createdReferences == null || !this.createdReferences.contains(referenceKey)) {
					removeEntityLocaleInTargetIndex(locale, entitySchema, index, epk);
				}
			}
		);
	}

	/**
	 * Ensures the specified language is removed from the target index for the given entity.
	 * If the language already exists in the target index for the entity, an exception is thrown.
	 * Additionally, provides an undo operation to revert the language addition, if needed.
	 *
	 * @param locale the locale representing the language to be removed
	 * @param entitySchema the schema of the entity to which the language is being removed
	 * @param targetIndex the target index where the language should be removed
	 * @param epk the primary key of the entity being updated
	 */
	public void removeEntityLocaleInTargetIndex(
		@Nonnull Locale locale,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull EntityIndex targetIndex,
		int epk
	) {
		targetIndex.removeLanguage(locale, epk);
		if (this.undoActionsAppender != null) {
			this.undoActionsAppender.accept(
				() -> targetIndex.upsertLanguage(locale, epk, entitySchema)
			);
		}
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
	 * Updates or inserts (upserts) the specified entity attribute locale into the target index.
	 *
	 * @param locale the locale information that needs to be updated or inserted
	 * @param entitySchema the schema of the entity the locale information belongs to
	 * @param targetIndex the target index where the locale data should be upserted
	 * @param existingDataSupplierFactory the factory supplying existing data supplier methods for lookups
	 */
	public void upsertEntityAttributeLocaleInTargetIndex(
		@Nonnull Locale locale,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityIndex targetIndex,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		insertInitialSuiteOfSortableAttributeCompounds(locale, entitySchema, targetIndex, existingDataSupplierFactory, this.undoActionsAppender);
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
				final RepresentativeReferenceKey referenceKey = Objects.requireNonNull((RepresentativeReferenceKey) index.getIndexKey().discriminator());
				if (this.createdReferences == null || !this.createdReferences.contains(referenceKey)) {
					removeEntityAttributeLocaleInTargetIndex(locale, entitySchema, index, existingDataSupplierFactory);
				}
			}
		);
	}

	/**
	 * Removes a specific locale's data from the target entity index.
	 *
	 * @param locale the locale that should be removed from the target index
	 * @param entitySchema the schema of the entity defining its structure and constraints
	 * @param targetIndex the target index from which the locale data should be removed
	 * @param existingDataSupplierFactory a factory for supplying existing data related to the entity
	 */
	public void removeEntityAttributeLocaleInTargetIndex(
		@Nonnull Locale locale,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityIndex targetIndex,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		removeEntireSuiteOfSortableAttributeCompounds(locale, entitySchema, targetIndex, existingDataSupplierFactory, this.undoActionsAppender);
	}

	/**
	 * Method processes all mutations that target entity references - e.g. {@link ReferenceMutation}. This method
	 * alters contents of the secondary indexes - i.e. all referenced entity indexes that are used in the main entity
	 * except the referenced entity index that directly connects to {@link ReferenceMutation#getReferenceKey()} because
	 * this is altered in {@link #updateReferences(ReferenceMutation, GlobalEntityIndex)} method.
	 */
	private void updateReferencesInReferenceIndex(
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
				this,
				this.undoActionsAppender
			);
		} else if (referenceMutation instanceof RemoveReferenceMutation) {
			ReferenceIndexMutator.removeFacetInIndex(
				indexForRemoval,
				referenceSchema,
				referenceMutation.getReferenceKey(),
				getPrimaryKeyToIndex(IndexType.FACET_INDEX, Target.EXISTING),
				this,
				this.undoActionsAppender
			);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new GenericEvitaInternalError("Unknown mutation: " + referenceMutation.getClass());
		}
	}

	/**
	 * Method switches inner handling strategy for the entity - e.g. {@link SetPriceInnerRecordHandlingMutation}
	 */
	private void updatePriceHandlingForEntity(
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
						originalInnerRecordHandling,
						this.undoActionsAppender
					);
				}
			};

			final TriConsumer<ReferenceSchemaContract, EntityIndex, EntityIndex> pricesInsertion =
				(referenceSchema,indexForRemoval, indexForUpsert) -> {
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
						PriceIndexMutator.createPriceProvider(price),
						this.undoActionsAppender
					);
				}
			};

			// first remove data from reduced indexes (entity + group)
			ReferenceIndexMutator.executeWithAllReferenceIndexes(
				ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING, this,
				pricesRemoval::accept, true
			);

			// now we can safely remove the data from a super index
			//noinspection DataFlowIssue
			pricesRemoval.accept(null, index, index);

			// next we need to add data to super index first
			//noinspection DataFlowIssue
			pricesInsertion.accept(null, index, index);

			// and then we can add data to reduced indexes (entity + group)
			ReferenceIndexMutator.executeWithAllReferenceIndexes(
				ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING, this,
				pricesInsertion::accept, true
			);
		}
	}

	/**
	 * Method processes all mutations that targets entity prices - e.g. {@link PriceMutation}.
	 */
	private void updatePriceIndex(
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
						this.containerAccessor.registerAssignedPriceId(theEntityPrimaryKey, thePriceKey, newlyAssignedId);
						return newlyAssignedId;
					}
				},
				this.undoActionsAppender
			);
		} else if (priceMutation instanceof RemovePriceMutation) {
			PriceIndexMutator.priceRemove(
				this,
				referenceSchema, indexForRemoval, priceKey,
				this.getStoragePartExistingDataFactory().getPriceSupplier(),
				this.undoActionsAppender
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
	private void updateHierarchyPlacement(@Nonnull ParentMutation parentMutation, @Nonnull EntityIndex index) {
		if (parentMutation instanceof final SetParentMutation setMutation) {
			setParent(
				this, index,
				getPrimaryKeyToIndex(IndexType.HIERARCHY_INDEX, Target.NEW),
				setMutation.getParentPrimaryKey(),
				this.undoActionsAppender
			);
		} else if (parentMutation instanceof RemoveParentMutation) {
			removeParent(
				this, index,
				getPrimaryKeyToIndex(IndexType.HIERARCHY_INDEX, Target.EXISTING),
				this.undoActionsAppender
			);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new GenericEvitaInternalError("Unknown mutation: " + parentMutation.getClass());
		}
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
	 *                   the stored state.
	 * - {@code current}: A non-null reference to a {@link RepresentativeReferenceKey} representing
	 *                    the current state.
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
		 *         {@code false} otherwise.
		 */
		public boolean differ() {
			return !this.stored.equals(this.current);
		}
	}

	/**
	 * Enumeration representing the desired target of an indexing operation (either existing an index, or a new index).
	 */
	public enum Target {
		EXISTING,
		NEW
	}

}
