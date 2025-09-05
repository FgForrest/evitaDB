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

package io.evitadb.index.mutation.index;

import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
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
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.TriConsumer;
import io.evitadb.index.*;
import io.evitadb.index.mutation.index.dataAccess.EntityExistingDataFactory;
import io.evitadb.index.mutation.index.dataAccess.EntityIndexedReferenceSupplier;
import io.evitadb.index.mutation.index.dataAccess.EntityPriceSupplier;
import io.evitadb.index.mutation.index.dataAccess.EntityStoragePartExistingDataFactory;
import io.evitadb.index.mutation.index.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.index.mutation.index.dataAccess.ExistingDataSupplierFactory;
import io.evitadb.index.mutation.index.dataAccess.ReferenceSupplier;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.price.PriceWithInternalIds;
import io.evitadb.store.spi.model.storageParts.accessor.WritableEntityStorageContainerAccessor;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import static io.evitadb.index.mutation.index.HierarchyPlacementMutator.removeParent;
import static io.evitadb.index.mutation.index.HierarchyPlacementMutator.setParent;
import static io.evitadb.utils.Assert.isPremiseValid;

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
	private final LinkedList<ToIntFunction<IndexType>> entityPrimaryKey = new LinkedList<>();
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
	private final Consumer<Runnable> undoActionsAppender;
	/**
	 * Supplier that allows to retrieve full entity body. It's used only when entity changes its scope.
	 */
	private final Supplier<Entity> fullEntitySupplier;
	/**
	 * Memoized factory that allows to retrieve existing attribute values from the current storage part.
	 */
	private ExistingDataSupplierFactory storagePartExistingDataFactory;
	/**
	 * Set of keys of indexes that were created in this particular entity upsert.
	 */
	private Set<ReferenceKey> createdReferences;
	/**
	 * Set contains keys of indexes that were accessed in this particular entity upsert / removal.
	 */
	private final Set<EntityIndexKey> accessedIndexes = CollectionUtils.createHashSet(12);
	/**
	 * Memoized scope of the current entity.
	 */
	private Scope memoizedScope;
	/**
	 * Flag indicating that processed entity is newly created.
	 */
	private boolean created;

	/**
	 * Constructs an {@link EntityIndexKey} for a referenced entity type using the specified reference name.
	 *
	 * @param referenceName The name of the reference for which the index key should be created.
	 * @return An instance of {@link EntityIndexKey} corresponding to the referenced entity type.
	 */
	@Nonnull
	private static EntityIndexKey getReferencedTypeIndexKey(@Nonnull String referenceName, @Nonnull Scope scope) {
		return new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName);
	}

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
		this.entityPrimaryKey.add(anyType -> entityPrimaryKey);
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
					this.entityType, getPrimaryKeyToIndex(IndexType.ENTITY_INDEX), EntityExistence.MUST_EXIST
				)
				.getScope();
		}
		return this.memoizedScope;
	}

	@Override
	public void prepare(@Nullable Integer entityPrimaryKey, @Nonnull EntityExistence expectation) {
		final EntityIndex globalIndex = getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, getScope()));
		final int recordId = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX);
		this.created = globalIndex.insertPrimaryKeyIfMissing(recordId);
		if (this.created) {
			if (this.undoActions != null) {
				this.undoActions.add(() -> globalIndex.removePrimaryKey(recordId));
			}

			// we need to set-up all the entity compounds that rely on non-localized attributes
			// they will exist even if the attributes are not present (i.e. compounds contain only NULL values)
			final EntitySchema entitySchema = getEntitySchema();
			AttributeIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
				this,
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
					getPrimaryKeyToIndex(IndexType.HIERARCHY_INDEX),
					null,
					this.undoActionsAppender
				);
			}
		}
	}

	@Override
	public void applyMutation(@Nonnull LocalMutation<?, ?> localMutation) {
		final EntityIndex globalIndex = getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, getScope()));
		if (localMutation instanceof SetPriceInnerRecordHandlingMutation priceHandlingMutation) {
			updatePriceHandlingForEntity(priceHandlingMutation, globalIndex);
		} else if (localMutation instanceof PriceMutation priceMutation) {
			final BiConsumer<ReferenceSchemaContract, EntityIndex> priceUpdateApplicator = (referenceSchema, theIndex) -> updatePriceIndex(referenceSchema, priceMutation, theIndex);
			if (priceMutation instanceof RemovePriceMutation ||
				// when new upserted price is not indexed, it is removed from indexes, so we need to behave like removal
				(priceMutation instanceof UpsertPriceMutation upsertPriceMutation && !upsertPriceMutation.isIndexed())) {
				// removal must first occur on the reduced indexes, because they consult the super index
				ReferenceIndexMutator.executeWithReferenceIndexes(this.entityType, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING, this, priceUpdateApplicator);
				priceUpdateApplicator.accept(null, globalIndex);
			} else {
				// upsert must first occur on super index, because reduced indexed rely on information in super index
				priceUpdateApplicator.accept(null, globalIndex);
				ReferenceIndexMutator.executeWithReferenceIndexes(this.entityType, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING, this, priceUpdateApplicator);
			}
		} else if (localMutation instanceof ParentMutation parentMutation) {
			updateHierarchyPlacement(parentMutation, globalIndex);
		} else if (localMutation instanceof ReferenceMutation<?> referenceMutation) {
			final ReferenceKey referenceKey = referenceMutation.getReferenceKey();
			final ReferenceSchemaContract referenceSchema = getEntitySchema().getReferenceOrThrowException(referenceKey.referenceName());
			if (referenceSchema.isIndexedInScope(getScope())) {
				updateReferences(referenceMutation, globalIndex);
				ReferenceIndexMutator.executeWithReferenceIndexes(
					this.entityType,
					ReferenceIndexType.FOR_FILTERING,
					this,
					(theReferenceSchema, referenceIndex) -> updateReferencesInReferenceIndex(referenceMutation, referenceIndex),
					// avoid indexing the referenced index that got updated by updateReferences method
					referenceContract -> !referenceKey.equals(referenceContract.getReferenceKey())
				);
			}
		} else if (localMutation instanceof AttributeMutation attributeMutation) {
			final ExistingAttributeValueSupplier entityAttributeValueSupplier = getStoragePartExistingDataFactory().getEntityAttributeValueSupplier();
			final BiConsumer<Boolean, EntityIndex> attributeUpdateApplicator = (updateglobalIndex, targetIndex) -> updateAttribute(
				attributeMutation,
				new EntitySchemaAttributeAndCompoundSchemaProvider(getEntitySchema()),
				entityAttributeValueSupplier,
				targetIndex,
				updateglobalIndex,
				true
			);
			attributeUpdateApplicator.accept(true, globalIndex);
			ReferenceIndexMutator.executeWithReferenceIndexes(
				this.entityType,
				ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
				this,
				(theReferenceSchema,entityIndex) -> attributeUpdateApplicator.accept(false, entityIndex),
				Droppable::exists
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
		final Set<Locale> addedLocales = this.containerAccessor.getAddedLocales();
		final Set<Locale> removedLocales = this.containerAccessor.getRemovedLocales();

		final int primaryKeyToIndex = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX);
		final EntityBodyStoragePart entityStoragePart = this.containerAccessor.getEntityStoragePart(
			this.entityType,
			primaryKeyToIndex,
			EntityExistence.MUST_EXIST
		);

		if (!(addedLocales.isEmpty() && removedLocales.isEmpty())) {
			final ExistingDataSupplierFactory existingAttributeFactory = getStoragePartExistingDataFactory();
			final EntitySchema entitySchema = getEntitySchema();
			for (Locale locale : addedLocales) {
				if (entityStoragePart.getLocales().contains(locale)) {
					upsertEntityLanguage(locale, entitySchema, existingAttributeFactory);
				}
			}
			for (Locale locale : removedLocales) {
				if (!entityStoragePart.getLocales().contains(locale)) {
					// locale was removed entirely - remove the information from index
					removeEntityLanguage(
						locale,
						entitySchema,
						existingAttributeFactory
					);
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
	 */
	int getPrimaryKeyToIndex(@Nonnull IndexType indexType) {
		isPremiseValid(!this.entityPrimaryKey.isEmpty(), "Should not ever happen.");
		//noinspection ConstantConditions
		return this.entityPrimaryKey.peek().applyAsInt(indexType);
	}

	/**
	 * Method allows overloading default implementation that returns entity primary key for all {@link IndexType} values.
	 */
	void executeWithDifferentPrimaryKeyToIndex(
		@Nonnull ToIntFunction<IndexType> entityPrimaryKeyResolver,
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
	 * Method processes all mutations that targets entity attributes - e.g. {@link AttributeMutation}.
	 */
	void updateAttribute(
		@Nonnull AttributeMutation attributeMutation,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull ExistingAttributeValueSupplier existingValueSupplier,
		@Nonnull EntityIndex index,
		boolean updateglobalIndex,
		boolean updateCompounds
	) {
		final AttributeKey affectedAttribute = attributeMutation.getAttributeKey();

		if (attributeMutation instanceof UpsertAttributeMutation) {
			final Serializable attributeValue = ((UpsertAttributeMutation) attributeMutation).getAttributeValue();
			AttributeIndexMutator.executeAttributeUpsert(
				this, attributeSchemaProvider, existingValueSupplier,
				index, affectedAttribute, attributeValue, updateglobalIndex, updateCompounds,
				this.undoActionsAppender
			);
		} else if (attributeMutation instanceof RemoveAttributeMutation) {
			AttributeIndexMutator.executeAttributeRemoval(
				this, attributeSchemaProvider, existingValueSupplier,
				index, affectedAttribute, updateglobalIndex, updateCompounds,
				this.undoActionsAppender
			);
		} else if (attributeMutation instanceof ApplyDeltaAttributeMutation<?> applyDeltaAttributeMutation) {
			final Number attributeValue = applyDeltaAttributeMutation.getAttributeValue();
			AttributeIndexMutator.executeAttributeDelta(
				this, attributeSchemaProvider, existingValueSupplier,
				index, affectedAttribute, attributeValue, this.undoActionsAppender
			);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new GenericEvitaInternalError("Unknown mutation: " + attributeMutation.getClass());
		}
	}

	/**
	 * Remove the language for the specified record in the target index.
	 *
	 * @param recordId           The ID of the record to remove the language for.
	 * @param locale             The locale of the language to be removed.
	 * @param targetIndex        The target index from which to remove the language.
	 * @param entitySchema       The schema of the entity.
	 * @param undoActionConsumer consumer that consolidates undo actions
	 */
	void upsertEntityLanguageInTargetIndex(
		int recordId,
		@Nonnull Locale locale,
		@Nonnull EntityIndex targetIndex,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		if (targetIndex.upsertLanguage(locale, recordId, entitySchema)) {
			insertInitialSuiteOfSortableAttributeCompounds(locale, targetIndex, existingDataSupplierFactory, undoActionConsumer);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(
					() -> removeEntityLanguageInTargetIndex(
						recordId, locale, targetIndex,
						entitySchema, existingDataSupplierFactory,
						null
					)
				);
			}
		}
	}

	/**
	 * Remove the language for the specified record in the target index.
	 *
	 * @param recordId           The ID of the record to remove the language for.
	 * @param locale             The locale of the language to be removed.
	 * @param entitySchema       The schema of the entity.
	 * @param targetIndex        The target index from which to remove the language.
	 * @param undoActionConsumer consumer that consolidates undo actions
	 */
	void removeEntityLanguageInTargetIndex(
		int recordId,
		@Nonnull Locale locale,
		@Nonnull EntityIndex targetIndex,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		if (targetIndex.removeLanguage(locale, recordId)) {
			removeEntireSuiteOfSortableAttributeCompounds(locale, targetIndex, existingDataSupplierFactory, undoActionConsumer);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(
					() -> upsertEntityLanguageInTargetIndex(
						recordId, locale, targetIndex, entitySchema, existingDataSupplierFactory, null
					)
				);
			}
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
		@Nonnull EntityIndex targetIndex,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = getEntitySchema();
		if (targetIndex instanceof ReducedEntityIndex reducedEntityIndex && targetIndex.getIndexKey().discriminator() instanceof ReferenceKey referenceKey) {
			final String referenceName = referenceKey.referenceName();
			final ReferenceSchemaContract referenceSchema = entitySchema.getReference(referenceName)
				.orElseThrow(() -> new ReferenceNotFoundException(referenceName, entitySchema));
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
		@Nonnull EntityIndex targetIndex,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = getEntitySchema();
		if (targetIndex instanceof ReducedEntityIndex reducedEntityIndex && targetIndex.getIndexKey().discriminator() instanceof ReferenceKey referenceKey) {
			final String referenceName = referenceKey.referenceName();
			final ReferenceSchemaContract referenceSchema = entitySchema.getReference(referenceName)
				.orElseThrow(() -> new ReferenceNotFoundException(referenceName, entitySchema));
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
	private ExistingDataSupplierFactory getStoragePartExistingDataFactory() {
		if (this.storagePartExistingDataFactory == null) {
			this.storagePartExistingDataFactory = new EntityStoragePartExistingDataFactory(
				this.containerAccessor,
				this.entityType,
				this.entityPrimaryKey.getFirst().applyAsInt(IndexType.ENTITY_INDEX)
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
		final EntitySchemaContract entitySchema = entity.getSchema();
		final EntityExistingDataFactory existingDataSupplierFactory = new EntityExistingDataFactory(entity);
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
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull EntityExistingDataFactory existingDataSupplierFactory
	) {
		final int epk = entity.getPrimaryKeyOrThrowException();
		for (Locale locale : entity.getLocales()) {
			removeEntityLanguageInTargetIndex(
				epk, locale, globalIndex, entitySchema, existingDataSupplierFactory, this.undoActionsAppender
			);
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
	 * - {@link ReducedEntityIndex}
	 *
	 * @param entity                      The entity whose global attributes are to be unindexed.
	 * @param entitySchema                The schema contract defining the attributes and their metadata for the entity.
	 * @param globalIndex                 The global index from which attributes are to be removed.
	 * @param existingDataSupplierFactory A factory providing access to existing data needed for attribute removal.
	 */
	private void unindexAllGlobalAttributes(
		@Nonnull Entity entity,
		@Nonnull EntitySchemaContract entitySchema,
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
						new EntitySchemaAttributeAndCompoundSchemaProvider(entitySchema),
						existingDataSupplierFactory.getEntityAttributeValueSupplier(),
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
		removeEntireSuiteOfSortableAttributeCompounds(null, globalIndex, existingDataSupplierFactory, this.undoActionsAppender);
	}

	/**
	 * Unindexes all references and their attributes for the given entity within the specified scope. Reference
	 * attributes are held in these indexes:
	 *
	 * - {@link ReferencedTypeEntityIndex}
	 * - {@link ReducedEntityIndex}
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
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull EntityExistingDataFactory existingDataSupplierFactory
	) {
		final int epk = entity.getPrimaryKeyOrThrowException();
		for (ReferenceContract reference : entity.getReferences()) {
			if (reference.exists()) {
				final ReferenceKey referenceKey = reference.getReferenceKey();
				if (ReferenceIndexMutator.isIndexedReferenceForFiltering(reference, scope)) {
					final EntityIndexKey referencedTypeIndexKey = getReferencedTypeIndexKey(referenceKey.referenceName(), scope);
					final ReferencedTypeEntityIndex referenceTypeIndex = (ReferencedTypeEntityIndex) getOrCreateIndex(referencedTypeIndexKey);
					final ReducedEntityIndex mainReferenceIndex = ReferenceIndexMutator.getOrCreateReferencedEntityIndex(this, referenceKey, scope);
					ReferenceIndexMutator.referenceRemoval(
						epk, entitySchema, this,
						globalIndex, referenceTypeIndex, mainReferenceIndex, referenceKey,
						existingDataSupplierFactory,
						this.undoActionsAppender
					);
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
					applyOnReducedIndexes(
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
		final EntitySchemaContract entitySchema = entity.getSchema();
		final EntityExistingDataFactory existingDataSupplierFactory = new EntityExistingDataFactory(entity);
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
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull EntityExistingDataFactory existingDataSupplierFactory
	) {
		final int epk = entity.getPrimaryKeyOrThrowException();
		for (Locale locale : entity.getLocales()) {
			upsertEntityLanguageInTargetIndex(
				epk, locale, globalIndex, entitySchema, existingDataSupplierFactory, this.undoActionsAppender
			);
		}
	}

	/**
	 * Indexes all global attributes for a given entity. Attributes are held in these indexes:
	 *
	 * - {@link GlobalEntityIndex}
	 * - {@link ReducedEntityIndex}
	 *
	 * @param entity                      The entity containing attributes to be indexed.
	 * @param entitySchema                The schema contract of the entity providing attribute definitions.
	 * @param globalIndex                 The global index where attributes will be upserted.
	 * @param existingDataSupplierFactory The factory supplying existing data for the entity.
	 */
	private void indexAllGlobalAttributes(
		@Nonnull Entity entity,
		@Nonnull EntitySchemaContract entitySchema,
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
						new EntitySchemaAttributeAndCompoundSchemaProvider(entitySchema),
						ExistingAttributeValueSupplier.NO_EXISTING_VALUE_SUPPLIER,
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
		insertInitialSuiteOfSortableAttributeCompounds(null, globalIndex, existingDataSupplierFactory, this.undoActionsAppender);
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
					applyOnReducedIndexes(
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
	 * - {@link ReducedEntityIndex}
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
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull EntityExistingDataFactory existingDataSupplierFactory
	) {
		final int epk = entity.getPrimaryKeyOrThrowException();
		for (ReferenceContract reference : entity.getReferences()) {
			if (reference.exists()) {
				final ReferenceKey referenceKey = reference.getReferenceKey();
				if (ReferenceIndexMutator.isIndexedReferenceForFiltering(reference, scope)) {
					final EntityIndexKey referencedTypeIndexKey = getReferencedTypeIndexKey(referenceKey.referenceName(), scope);
					final ReferencedTypeEntityIndex referenceTypeIndex = (ReferencedTypeEntityIndex) getOrCreateIndex(referencedTypeIndexKey);
					final ReducedEntityIndex mainReferenceIndex = ReferenceIndexMutator.getOrCreateReferencedEntityIndex(this, referenceKey, scope);
					final Integer groupId = reference.getGroup().filter(Droppable::exists).map(GroupEntityReference::getPrimaryKey).orElse(null);
					ReferenceIndexMutator.referenceInsert(
						epk, entitySchema, this,
						globalIndex, referenceTypeIndex, mainReferenceIndex, referenceKey, groupId,
						existingDataSupplierFactory,
						this.undoActionsAppender
					);
					if (ReferenceIndexMutator.isFacetedReference(scope, referenceKey, this)) {
						for (ReferenceContract otherReferences : entity.getReferences()) {
							if (!referenceKey.equals(otherReferences.getReferenceKey())) {
								final ReducedEntityIndex referenceIndex = ReferenceIndexMutator.getOrCreateReferencedEntityIndex(this, referenceKey, scope);
								ReferenceIndexMutator.addFacetToIndex(
									referenceIndex,
									referenceKey,
									groupId,
									this,
									epk,
									this.undoActionsAppender
								);
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Applies passed consumer function on all {@link ReducedEntityIndex} related to currently existing
	 * {@link ReferenceContract} of the entity.
	 */
	private void applyOnReducedIndexes(
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
				final EntityIndex index = getOrCreateIndex(new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, scope, refKey));
				entityIndexConsumer.accept(theReferenceSchema, index);
			});
	}

	/**
	 * Method processes all mutations that target entity references - e.g. {@link ReferenceMutation}. This method
	 * alters contents of the primary indexes - i.e. global index, reference type and referenced entity index for
	 * the particular referenced entity.
	 */
	private void updateReferences(@Nonnull ReferenceMutation<?> referenceMutation, @Nonnull EntityIndex entityIndex) {
		final ReferenceKey referenceKey = referenceMutation.getReferenceKey();
		final Scope scope = getScope();
		final int theEntityPrimaryKey = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX);

		if (referenceMutation instanceof SetReferenceGroupMutation upsertReferenceGroupMutation) {
			final ReducedEntityIndex referenceIndex = ReferenceIndexMutator.getOrCreateReferencedEntityIndex(this, referenceKey, scope);
			ReferenceIndexMutator.setFacetGroupInIndex(
				theEntityPrimaryKey, entityIndex,
				upsertReferenceGroupMutation.getReferenceKey(),
				upsertReferenceGroupMutation.getGroupPrimaryKey(),
				this,
				this.entityType
			);
			ReferenceIndexMutator.setFacetGroupInIndex(
				theEntityPrimaryKey, referenceIndex,
				upsertReferenceGroupMutation.getReferenceKey(),
				upsertReferenceGroupMutation.getGroupPrimaryKey(),
				this,
				this.entityType
			);
		} else if (referenceMutation instanceof RemoveReferenceGroupMutation removeReferenceGroupMutation) {
			final ReducedEntityIndex referenceIndex = ReferenceIndexMutator.getOrCreateReferencedEntityIndex(this, referenceKey, scope);
			ReferenceIndexMutator.removeFacetGroupInIndex(
				theEntityPrimaryKey, entityIndex,
				removeReferenceGroupMutation.getReferenceKey(),
				this,
				this.entityType
			);
			ReferenceIndexMutator.removeFacetGroupInIndex(
				theEntityPrimaryKey, referenceIndex,
				removeReferenceGroupMutation.getReferenceKey(),
				this,
				this.entityType
			);
		} else {
			if (referenceMutation instanceof ReferenceAttributeMutation referenceAttributesUpdateMutation) {
				final AttributeMutation attributeMutation = referenceAttributesUpdateMutation.getAttributeMutation();
				final ReferencedTypeEntityIndex referenceTypeIndex = (ReferencedTypeEntityIndex) getOrCreateIndex(
					getReferencedTypeIndexKey(referenceKey.referenceName(), scope)
				);
				final ReducedEntityIndex referenceIndex = ReferenceIndexMutator.getOrCreateReferencedEntityIndex(this, referenceKey, scope);
				ReferenceIndexMutator.attributeUpdate(
					this, getStoragePartExistingDataFactory(),
					referenceTypeIndex, referenceIndex, referenceMutation.getReferenceKey(),
					attributeMutation
				);
			} else {
				final EntitySchema entitySchema = getEntitySchema();
				if (referenceMutation instanceof InsertReferenceMutation) {
					final ReferencedTypeEntityIndex referenceTypeIndex = (ReferencedTypeEntityIndex) getOrCreateIndex(
						getReferencedTypeIndexKey(referenceKey.referenceName(), scope)
					);
					final ReducedEntityIndex referenceIndex = ReferenceIndexMutator.getOrCreateReferencedEntityIndex(this, referenceKey, scope);
					ReferenceIndexMutator.referenceInsert(
						theEntityPrimaryKey, entitySchema, this,
						entityIndex, referenceTypeIndex, referenceIndex, referenceKey, null,
						getStoragePartExistingDataFactory(),
						this.undoActionsAppender
					);
					if (this.createdReferences == null) {
						this.createdReferences = CollectionUtils.createHashSet(16);
					}
					this.createdReferences.add(referenceKey);
				} else if (referenceMutation instanceof RemoveReferenceMutation) {
					final EntityIndexKey referencedTypeIndexKey = getReferencedTypeIndexKey(referenceKey.referenceName(), scope);
					final ReferencedTypeEntityIndex referenceTypeIndex = (ReferencedTypeEntityIndex) getOrCreateIndex(referencedTypeIndexKey);
					final ReducedEntityIndex referenceIndex = ReferenceIndexMutator.getOrCreateReferencedEntityIndex(this, referenceKey, scope);
					ReferenceIndexMutator.referenceRemoval(
						theEntityPrimaryKey, entitySchema, this,
						entityIndex, referenceTypeIndex, referenceIndex, referenceKey,
						getStoragePartExistingDataFactory(),
						this.undoActionsAppender
					);
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
	private void upsertEntityLanguage(
		@Nonnull Locale locale,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		final int epk = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX);
		final EntityIndex globalIndex = getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, getScope()));
		upsertEntityLanguageInTargetIndex(epk, locale, globalIndex, entitySchema, existingDataSupplierFactory, this.undoActionsAppender);
		applyOnReducedIndexes(
			getScope(),
			existingDataSupplierFactory.getReferenceSupplier(),
			(referenceSchema, index) -> upsertEntityLanguageInTargetIndex(
				epk, locale, index, entitySchema, existingDataSupplierFactory, this.undoActionsAppender
			)
		);
	}

	/**
	 * Method removes language for entity.
	 */
	private void removeEntityLanguage(
		@Nonnull Locale locale,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory
	) {
		final int epk = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX);
		final EntityIndex globalIndex = getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL, getScope()));
		removeEntityLanguageInTargetIndex(
			epk, locale, globalIndex,
			entitySchema,
			existingDataSupplierFactory,
			this.undoActionsAppender
		);
		applyOnReducedIndexes(
			getScope(),
			existingDataSupplierFactory.getReferenceSupplier(),
			(referenceSchema, index) -> {
				// removal mutations happen before indexes are created and thus the created indexes will not have
				// the language set (entity already lacks the language) - so we cannot remove it for those indexes
				final ReferenceKey referenceKey = (ReferenceKey) index.getIndexKey().discriminator();
				if (this.createdReferences == null || !this.createdReferences.contains(referenceKey)) {
					removeEntityLanguageInTargetIndex(
						epk, locale, index,
						entitySchema, existingDataSupplierFactory,
						this.undoActionsAppender
					);
				}
			}
		);
	}

	/**
	 * Method processes all mutations that target entity references - e.g. {@link ReferenceMutation}. This method
	 * alters contents of the secondary indexes - i.e. all referenced entity indexes that are used in the main entity
	 * except the referenced entity index that directly connects to {@link ReferenceMutation#getReferenceKey()} because
	 * this is altered in {@link #updateReferences(ReferenceMutation, EntityIndex)} method.
	 */
	private void updateReferencesInReferenceIndex(
		@Nonnull ReferenceMutation<?> referenceMutation,
		@Nonnull EntityIndex targetIndex
	) {
		if (referenceMutation instanceof SetReferenceGroupMutation upsertReferenceGroupMutation) {
			ReferenceIndexMutator.setFacetGroupInIndex(
				getPrimaryKeyToIndex(IndexType.FACET_INDEX),
				targetIndex,
				upsertReferenceGroupMutation.getReferenceKey(),
				upsertReferenceGroupMutation.getGroupPrimaryKey(),
				this,
				this.entityType
			);
		} else if (referenceMutation instanceof RemoveReferenceGroupMutation removeReferenceGroupMutation) {
			ReferenceIndexMutator.removeFacetGroupInIndex(
				getPrimaryKeyToIndex(IndexType.FACET_INDEX),
				targetIndex,
				removeReferenceGroupMutation.getReferenceKey(),
				this,
				this.entityType
			);
		} else if (referenceMutation instanceof ReferenceAttributeMutation) {
			// do nothing - attributes are not indexed in hierarchy index
		} else if (referenceMutation instanceof InsertReferenceMutation) {
			ReferenceIndexMutator.addFacetToIndex(
				targetIndex,
				referenceMutation.getReferenceKey(),
				null,
				this,
				getPrimaryKeyToIndex(IndexType.FACET_INDEX),
				this.undoActionsAppender
			);
		} else if (referenceMutation instanceof RemoveReferenceMutation) {
			ReferenceIndexMutator.removeFacetInIndex(
				targetIndex,
				referenceMutation.getReferenceKey(),
				this,
				this.entityType,
				getPrimaryKeyToIndex(IndexType.FACET_INDEX),
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
		@Nonnull EntityIndex index
	) {
		final PricesStoragePart priceStorageContainer = getContainerAccessor().getPriceStoragePart(this.entityType, getPrimaryKeyToIndex(IndexType.PRICE_INDEX));
		final PriceInnerRecordHandling originalInnerRecordHandling = priceStorageContainer.getPriceInnerRecordHandling();
		final PriceInnerRecordHandling newPriceInnerRecordHandling = priceHandlingMutation.getPriceInnerRecordHandling();

		if (originalInnerRecordHandling != newPriceInnerRecordHandling) {

			final BiConsumer<ReferenceSchemaContract, EntityIndex> pricesRemoval = (referenceSchema, theIndex) -> {
				for (PriceWithInternalIds price : priceStorageContainer.getPrices()) {
					PriceIndexMutator.priceRemove(
						this, referenceSchema, theIndex, price.priceKey(),
						price,
						originalInnerRecordHandling,
						this.undoActionsAppender
					);
				}
			};

			final BiConsumer<ReferenceSchemaContract, EntityIndex> pricesInsertion = (referenceSchema,theIndex) -> {
				for (PriceWithInternalIds price : priceStorageContainer.getPrices()) {
					PriceIndexMutator.priceUpsert(
						this,
						referenceSchema, theIndex, price.priceKey(),
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

			// first remove data from reduced indexes
			ReferenceIndexMutator.executeWithReferenceIndexes(this.entityType, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING, this, pricesRemoval);

			// now we can safely remove the data from super index
			pricesRemoval.accept(null, index);

			// next we need to add data to super index first
			pricesInsertion.accept(null, index);

			// and then we can add data to reduced indexes
			ReferenceIndexMutator.executeWithReferenceIndexes(this.entityType, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING, this, pricesInsertion);
		}
	}

	/**
	 * Method processes all mutations that targets entity prices - e.g. {@link PriceMutation}.
	 */
	private void updatePriceIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull PriceMutation priceMutation,
		@Nonnull EntityIndex index
	) {
		final PriceKey priceKey = priceMutation.getPriceKey();

		if (priceMutation instanceof final UpsertPriceMutation upsertPriceMutation) {
			final int theEntityPrimaryKey = this.getPrimaryKeyToIndex(IndexType.PRICE_INDEX);
			PriceIndexMutator.priceUpsert(
				this,
				referenceSchema, index, priceKey,
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
				referenceSchema, index, priceKey,
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
				getPrimaryKeyToIndex(IndexType.HIERARCHY_INDEX),
				setMutation.getParentPrimaryKey(),
				this.undoActionsAppender
			);
		} else if (parentMutation instanceof RemoveParentMutation) {
			removeParent(
				this, index,
				getPrimaryKeyToIndex(IndexType.HIERARCHY_INDEX),
				this.undoActionsAppender
			);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new GenericEvitaInternalError("Unknown mutation: " + parentMutation.getClass());
		}
	}

}
