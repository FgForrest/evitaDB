/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.mutation;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
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
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.IndexMaintainer;
import io.evitadb.index.IndexType;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.mutation.AttributeIndexMutator.EntityAttributeValueSupplier;
import io.evitadb.index.mutation.AttributeIndexMutator.ExistingAttributeValueSupplier;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import io.evitadb.store.entity.model.entity.price.PriceWithInternalIds;
import io.evitadb.store.spi.model.storageParts.accessor.WritableEntityStorageContainerAccessor;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import static io.evitadb.index.mutation.HierarchyPlacementMutator.removeParent;
import static io.evitadb.index.mutation.HierarchyPlacementMutator.setParent;
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
	 * The accessor that allows to retrieve schema of other entities.
	 */
	private final Function<String, EntitySchema> otherEntitiesSchemaAccessor;
	/**
	 * List of all undo actions that must be executed in case of (semi) rollback.
	 */
	private final LinkedList<Runnable> undoActions;
	private final Consumer<Runnable> undoActionsAppender;

	public EntityIndexLocalMutationExecutor(
		@Nonnull WritableEntityStorageContainerAccessor containerAccessor,
		int entityPrimaryKey,
		@Nonnull IndexMaintainer<EntityIndexKey, EntityIndex> entityIndexCreatingAccessor,
		@Nonnull IndexMaintainer<CatalogIndexKey, CatalogIndex> catalogIndexCreatingAccessor,
		@Nonnull Supplier<EntitySchema> schemaAccessor,
		@Nonnull Function<String, EntitySchema> otherEntitiesSchemaAccessor,
		boolean undoOnError
	) {
		this.containerAccessor = containerAccessor;
		this.entityPrimaryKey.add(anyType -> entityPrimaryKey);
		this.entityIndexCreatingAccessor = entityIndexCreatingAccessor;
		this.catalogIndexCreatingAccessor = catalogIndexCreatingAccessor;
		this.schemaAccessor = schemaAccessor;
		this.otherEntitiesSchemaAccessor = otherEntitiesSchemaAccessor;
		this.entityType = schemaAccessor.get().getName();
		this.undoActions = undoOnError ? new LinkedList<>() : null;
		this.undoActionsAppender = undoOnError ? undoActions::add : null;
	}

	/**
	 * Removes entity itself from indexes.
	 */
	public void removeEntity(int primaryKey) {
		final EntityIndex globalIndex = getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL));
		if (globalIndex.removePrimaryKey(primaryKey)) {
			AttributeIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
				globalIndex,
				null,
				primaryKey,
				getEntitySchema(),
				null,
				containerAccessor,
				undoActionsAppender
			);
		}
	}

	@Override
	public void applyMutation(@Nonnull LocalMutation<?, ?> localMutation) {
		final EntityIndex globalIndex = getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL));
		final int recordId = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX);

		final boolean created = globalIndex.insertPrimaryKeyIfMissing(recordId);
		if (created) {
			if (undoActions != null) {
				undoActions.add(() -> globalIndex.removePrimaryKey(recordId));
			}
			AttributeIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
				globalIndex,
				null,
				recordId,
				getEntitySchema(),
				null,
				containerAccessor,
				undoActionsAppender
			);
		}

		if (created && schemaAccessor.get().isWithHierarchy()) {
			setParent(
				this, globalIndex,
				getPrimaryKeyToIndex(IndexType.HIERARCHY_INDEX),
				null,
				undoActionsAppender
			);
		}

		if (localMutation instanceof SetPriceInnerRecordHandlingMutation priceHandlingMutation) {
			updatePriceHandlingForEntity(priceHandlingMutation, globalIndex);
		} else if (localMutation instanceof PriceMutation priceMutation) {
			final Consumer<EntityIndex> priceUpdateApplicator = theIndex -> updatePriceIndex(priceMutation, theIndex);
			if (priceMutation instanceof RemovePriceMutation) {
				// removal must first occur on the reduced indexes, because they consult the super index
				ReferenceIndexMutator.executeWithReferenceIndexes(entityType, this, priceUpdateApplicator);
				priceUpdateApplicator.accept(globalIndex);
			} else {
				// upsert must first occur on super index, because reduced indexed rely on information in super index
				priceUpdateApplicator.accept(globalIndex);
				ReferenceIndexMutator.executeWithReferenceIndexes(entityType, this, priceUpdateApplicator);
			}
		} else if (localMutation instanceof ParentMutation parentMutation) {
			updateHierarchyPlacement(parentMutation, globalIndex);
		} else if (localMutation instanceof ReferenceMutation<?> referenceMutation) {
			final ReferenceKey referenceKey = referenceMutation.getReferenceKey();
			final ReferenceSchemaContract referenceSchema = getEntitySchema().getReferenceOrThrowException(referenceKey.referenceName());
			if (referenceSchema.isIndexed()) {
				updateReferences(referenceMutation, globalIndex);
				ReferenceIndexMutator.executeWithReferenceIndexes(
					entityType,
					this,
					referenceIndex -> updateReferencesInReferenceIndex(referenceMutation, referenceIndex),
					// avoid indexing the referenced index that got updated by updateReferences method
					referenceContract -> !referenceKey.equals(referenceContract.getReferenceKey())
				);
			}
		} else if (localMutation instanceof AttributeMutation attributeMutation) {
			final EntityAttributeValueSupplier existingAttributeAccessor = new EntityAttributeValueSupplier(
				this.getContainerAccessor(), entityType, recordId
			);
			final EntitySchema entitySchema = getEntitySchema();
			final BiConsumer<Boolean, EntityIndex> attributeUpdateApplicator = (updateGlobalIndex, targetIndex) -> updateAttributes(
				attributeMutation,
				attributeName -> entitySchema.getAttribute(attributeName).map(AttributeSchema.class::cast).orElse(null),
				attributeName -> entitySchema.getSortableAttributeCompoundsForAttribute(attributeName).stream().map(SortableAttributeCompoundSchema.class::cast),
				existingAttributeAccessor,
				targetIndex,
				updateGlobalIndex,
				true
			);
			attributeUpdateApplicator.accept(true, globalIndex);
			ReferenceIndexMutator.executeWithReferenceIndexes(
				entityType,
				this,
				entityIndex -> attributeUpdateApplicator.accept(false, entityIndex),
				Droppable::exists
			);
		} else if (localMutation instanceof AssociatedDataMutation) {
			// do nothing, associated data doesn't affect entity index directly
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new EvitaInternalError("Unknown mutation: " + localMutation.getClass());
		}
	}

	@Nonnull
	@Override
	public List<LocalMutation<?, ?>> popImplicitMutations() {
		return Collections.emptyList();
	}

	@Override
	public void commit() {
		final Set<Locale> addedLocales = containerAccessor.getAddedLocales();
		final Set<Locale> removedLocales = containerAccessor.getRemovedLocales();
		if (!(addedLocales.isEmpty() && removedLocales.isEmpty())) {
			final EntityBodyStoragePart entityStoragePart = containerAccessor.getEntityStoragePart(entityType, getPrimaryKeyToIndex(IndexType.ENTITY_INDEX), EntityExistence.MUST_EXIST);
			for (Locale locale : addedLocales) {
				upsertEntityLanguage(entityStoragePart, locale);
			}
			for (Locale locale : removedLocales) {
				removeEntityLanguage(entityStoragePart, locale);
			}
		}
	}

	@Override
	public void rollback() {
		// execute all undo actions in reverse order of how they have been registered
		if (undoActions != null) {
			for (int i = undoActions.size() - 1; i >= 0; i--) {
				undoActions.get(i).run();
			}
		}
	}

	/**
	 * Method removes existing index from the existing set.
	 */
	public void removeIndex(EntityIndexKey entityIndexKey) {
		entityIndexCreatingAccessor.removeIndex(entityIndexKey);
	}

	public CatalogIndex getCatalogIndex() {
		return Objects.requireNonNull(catalogIndexCreatingAccessor.getOrCreateIndex(CatalogIndexKey.INSTANCE));
	}

	/*
		FRIENDLY METHODS
	 */

	/**
	 * Returns current entity schema.
	 */
	@Nonnull
	EntitySchema getEntitySchema() {
		return schemaAccessor.get();
	}

	/**
	 * Returns current entity schema.
	 */
	@Nullable
	EntitySchema getEntitySchema(@Nonnull String entityType) {
		return otherEntitiesSchemaAccessor.apply(entityType);
	}

	/**
	 * Returns primary key that should be indexed by certain {@link IndexType}. Argument of index type is necessary
	 * because for example for {@link EntityIndexType#REFERENCED_ENTITY_TYPE} we need to index referenced entity id for
	 * {@link IndexType#ATTRIBUTE_FILTER_INDEX} and {@link IndexType#ATTRIBUTE_UNIQUE_INDEX}, but entity
	 * id for {@link IndexType#ATTRIBUTE_SORT_INDEX}.
	 */
	int getPrimaryKeyToIndex(@Nonnull IndexType indexType) {
		isPremiseValid(!entityPrimaryKey.isEmpty(), "Should not ever happen.");
		//noinspection ConstantConditions
		return entityPrimaryKey.peek().applyAsInt(indexType);
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
	EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey entityIndexKey) {
		return entityIndexCreatingAccessor.getOrCreateIndex(entityIndexKey);
	}

	/**
	 * Method processes all mutations that targets entity attributes - e.g. {@link AttributeMutation}.
	 */
	void updateAttributes(
		@Nonnull AttributeMutation attributeMutation,
		@Nonnull Function<String, AttributeSchema> attributeSchemaProvider,
		@Nonnull Function<String, Stream<SortableAttributeCompoundSchema>> compoundSchemaProvider,
		@Nonnull ExistingAttributeValueSupplier existingValueSupplier,
		@Nonnull EntityIndex index,
		boolean updateGlobalIndex,
		boolean updateCompounds
	) {
		final AttributeKey affectedAttribute = attributeMutation.getAttributeKey();

		if (attributeMutation instanceof UpsertAttributeMutation) {
			final Serializable attributeValue = ((UpsertAttributeMutation) attributeMutation).getAttributeValue();
			AttributeIndexMutator.executeAttributeUpsert(
				this, attributeSchemaProvider, compoundSchemaProvider, existingValueSupplier,
				index, affectedAttribute, attributeValue, updateGlobalIndex, updateCompounds,
				undoActionsAppender
			);
		} else if (attributeMutation instanceof RemoveAttributeMutation) {
			AttributeIndexMutator.executeAttributeRemoval(
				this, attributeSchemaProvider, compoundSchemaProvider, existingValueSupplier,
				index, affectedAttribute, updateGlobalIndex, updateCompounds,
				undoActionsAppender
			);
		} else if (attributeMutation instanceof ApplyDeltaAttributeMutation<?> applyDeltaAttributeMutation) {
			final Number attributeValue = applyDeltaAttributeMutation.getAttributeValue();
			AttributeIndexMutator.executeAttributeDelta(
				this, attributeSchemaProvider, compoundSchemaProvider, existingValueSupplier,
				index, affectedAttribute, attributeValue, undoActionsAppender
			);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new EvitaInternalError("Unknown mutation: " + attributeMutation.getClass());
		}
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Applies passed consumer function on all {@link io.evitadb.index.ReducedEntityIndex} related to currently existing
	 * {@link ReferenceContract} of the entity.
	 */
	private void applyOnReducedIndexes(int epk, @Nonnull Consumer<EntityIndex> entityIndexConsumer) {
		final ReferencesStoragePart referencesStoragePart = containerAccessor.getReferencesStoragePart(entityType, epk);
		referencesStoragePart
			.getReferencesAsCollection()
			.stream()
			.filter(Droppable::exists)
			.map(it -> {
				final ReferenceSchemaContract referenceSchema = schemaAccessor.get().getReferenceOrThrowException(it.getReferenceName());
				if (referenceSchema.isReferencedEntityTypeManaged()) {
					final EntitySchemaContract referencedEntity = otherEntitiesSchemaAccessor.apply(referenceSchema.getReferencedEntityType());
					if (referencedEntity.isWithHierarchy()) {
						return this.entityIndexCreatingAccessor.getIndexIfExists(new EntityIndexKey(EntityIndexType.REFERENCED_HIERARCHY_NODE, it.getReferenceKey()));
					}
				}
				return this.entityIndexCreatingAccessor.getIndexIfExists(new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, it.getReferenceKey()));
			})
			.filter(Objects::nonNull)
			.forEach(entityIndexConsumer);
	}

	/**
	 * Method processes all mutations that target entity references - e.g. {@link ReferenceMutation}. This method
	 * alters contents of the primary indexes - i.e. global index, reference type and referenced entity index for
	 * the particular referenced entity.
	 */
	private void updateReferences(@Nonnull ReferenceMutation<?> referenceMutation, @Nonnull EntityIndex entityIndex) {
		final ReferenceKey referenceKey = referenceMutation.getReferenceKey();
		final int theEntityPrimaryKey = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX);

		if (referenceMutation instanceof SetReferenceGroupMutation upsertReferenceGroupMutation) {
			final EntityIndex referenceIndex = ReferenceIndexMutator.getReferencedEntityIndex(this, referenceKey);
			ReferenceIndexMutator.setFacetGroupInIndex(
				theEntityPrimaryKey, entityIndex,
				upsertReferenceGroupMutation.getReferenceKey(),
				upsertReferenceGroupMutation.getGroupPrimaryKey(),
				this,
				entityType
			);
			ReferenceIndexMutator.setFacetGroupInIndex(
				theEntityPrimaryKey, referenceIndex,
				upsertReferenceGroupMutation.getReferenceKey(),
				upsertReferenceGroupMutation.getGroupPrimaryKey(),
				this,
				entityType
			);
		} else if (referenceMutation instanceof RemoveReferenceGroupMutation removeReferenceGroupMutation) {
			final EntityIndex referenceIndex = ReferenceIndexMutator.getReferencedEntityIndex(this, referenceKey);
			ReferenceIndexMutator.removeFacetGroupInIndex(
				theEntityPrimaryKey, entityIndex,
				removeReferenceGroupMutation.getReferenceKey(),
				this,
				entityType
			);
			ReferenceIndexMutator.removeFacetGroupInIndex(
				theEntityPrimaryKey, referenceIndex,
				removeReferenceGroupMutation.getReferenceKey(),
				this,
				entityType
			);
		} else if (referenceMutation instanceof ReferenceAttributeMutation referenceAttributesUpdateMutation) {
			final AttributeMutation attributeMutation = referenceAttributesUpdateMutation.getAttributeMutation();
			final ReferencedTypeEntityIndex referenceTypeIndex = (ReferencedTypeEntityIndex) getOrCreateIndex(
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, referenceKey.referenceName())
			);
			final EntityIndex referenceIndex = ReferenceIndexMutator.getReferencedEntityIndex(this, referenceKey);
			ReferenceIndexMutator.attributeUpdate(
				theEntityPrimaryKey, entityType, this, referenceTypeIndex, referenceIndex, referenceMutation.getReferenceKey(), attributeMutation
			);
		} else if (referenceMutation instanceof InsertReferenceMutation) {
			final ReferencedTypeEntityIndex referenceTypeIndex = (ReferencedTypeEntityIndex) getOrCreateIndex(
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, referenceKey.referenceName())
			);
			final EntityIndex referenceIndex = ReferenceIndexMutator.getReferencedEntityIndex(this, referenceKey);
			ReferenceIndexMutator.referenceInsert(
				theEntityPrimaryKey, entityType, this,
				entityIndex, referenceTypeIndex, referenceIndex, referenceKey,
				undoActionsAppender
			);
		} else if (referenceMutation instanceof RemoveReferenceMutation) {
			final EntityIndexKey referencedTypeIndexKey = new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, referenceKey.referenceName());
			final ReferencedTypeEntityIndex referenceTypeIndex = (ReferencedTypeEntityIndex) getOrCreateIndex(referencedTypeIndexKey);
			final EntityIndex referenceIndex = ReferenceIndexMutator.getReferencedEntityIndex(this, referenceKey);
			ReferenceIndexMutator.referenceRemoval(
				theEntityPrimaryKey, entityType, this,
				entityIndex, referenceTypeIndex, referenceIndex, referenceKey,
				undoActionsAppender
			);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new EvitaInternalError("Unknown mutation: " + referenceMutation.getClass());
		}
	}

	/**
	 * Method inserts language for entity if entity lacks information about used language.
	 */
	private void upsertEntityLanguage(
		@Nonnull EntityBodyStoragePart entityStoragePart,
		@Nonnull Locale locale
	) {
		if (entityStoragePart.getLocales().contains(locale)) {
			final int epk = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX);
			final EntityIndex globalIndex = this.entityIndexCreatingAccessor.getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL));
			final boolean added = upsertEntityLanguageInTargetIndex(epk, locale, globalIndex, undoActionsAppender);
			if (added && undoActions != null) {
				undoActions.add(() -> removeEntityLanguageInTargetIndex(epk, locale, globalIndex, null));
			}
			applyOnReducedIndexes(epk, index -> {
				final boolean addedInTargetIndex = upsertEntityLanguageInTargetIndex(epk, locale, index, undoActionsAppender);
				if (addedInTargetIndex && undoActions != null) {
					undoActions.add(() -> removeEntityLanguageInTargetIndex(epk, locale, index, null));
				}
			});
		}
	}

	/**
	 * Method removes language for entity.
	 */
	private void removeEntityLanguage(
		@Nonnull EntityBodyStoragePart entityStoragePart,
		@Nonnull Locale locale
	) {
		if (!entityStoragePart.getLocales().contains(locale)) {
			// locale was removed entirely - remove the information from index
			final int epk = getPrimaryKeyToIndex(IndexType.ENTITY_INDEX);
			final EntityIndex globalIndex = this.entityIndexCreatingAccessor.getOrCreateIndex(new EntityIndexKey(EntityIndexType.GLOBAL));
			final boolean removed = removeEntityLanguageInTargetIndex(epk, locale, globalIndex, undoActionsAppender);
			if (removed && undoActions != null) {
				undoActions.add(() -> upsertEntityLanguageInTargetIndex(epk, locale, globalIndex, null));
			}
			applyOnReducedIndexes(
				epk,
				index -> {
					final boolean removedInTargetIndex = removeEntityLanguageInTargetIndex(
						epk, locale, index, undoActionsAppender
					);
					if (removedInTargetIndex && undoActions != null) {
						undoActions.add(() -> upsertEntityLanguageInTargetIndex(epk, locale, index, null));
					}
				}
			);
		}
	}

	/**
	 * Remove the language for the specified record in the target index.
	 *
	 * @param recordId The ID of the record to remove the language for.
	 * @param locale The locale of the language to be removed.
	 * @param targetIndex The target index from which to remove the language.
	 * @return true if the language was removed, false otherwise.
	 */
	boolean upsertEntityLanguageInTargetIndex(
		int recordId,
		@Nonnull Locale locale,
		@Nonnull EntityIndex targetIndex,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		if (targetIndex.upsertLanguage(locale, recordId)) {
			AttributeIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
				targetIndex,
				locale,
				recordId,
				getEntitySchema(),
				null,
				containerAccessor,
				undoActionConsumer
			);
			final EntityIndexKey indexKey = targetIndex.getIndexKey();
			if (indexKey.getDiscriminator() instanceof ReferenceKey referenceKey) {
				AttributeIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
					targetIndex,
					locale,
					recordId,
					getEntitySchema(),
					referenceKey,
					containerAccessor,
					undoActionConsumer
				);
			}
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Remove the language for the specified record in the target index.
	 *
	 * @param recordId The ID of the record to remove the language for.
	 * @param locale The locale of the language to be removed.
	 * @param targetIndex The target index from which to remove the language.
	 * @return true if the language was removed, false otherwise.
	 */
	boolean removeEntityLanguageInTargetIndex(
		int recordId,
		@Nonnull Locale locale,
		@Nonnull EntityIndex targetIndex,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		if (targetIndex.removeLanguage(locale, recordId)) {
			AttributeIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
				targetIndex,
				locale,
				recordId,
				getEntitySchema(),
				null,
				containerAccessor,
				undoActionConsumer
			);
			final EntityIndexKey indexKey = targetIndex.getIndexKey();
			if (indexKey.getDiscriminator() instanceof ReferenceKey referenceKey) {
				AttributeIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
					targetIndex,
					locale,
					recordId,
					getEntitySchema(),
					referenceKey,
					containerAccessor,
					undoActionConsumer
				);
			}
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Method processes all mutations that target entity references - e.g. {@link ReferenceMutation}. This method
	 * alters contents of the secondary indexes - i.e. all referenced entity indexes that are used in the main entity
	 * except the referenced entity index that directly connects to {@link ReferenceMutation#getReferenceKey()} because
	 * this is altered in {@link #updateReferences(ReferenceMutation, EntityIndex)} method.
	 */
	private void updateReferencesInReferenceIndex(@Nonnull ReferenceMutation<?> referenceMutation, @Nonnull EntityIndex targetIndex) {
		final EntityIndexType targetIndexType = targetIndex.getIndexKey().getType();
		final int theEntityPrimaryKey;
		if (targetIndexType == EntityIndexType.REFERENCED_HIERARCHY_NODE) {
			theEntityPrimaryKey = getPrimaryKeyToIndex(IndexType.HIERARCHY_INDEX);
		} else if (targetIndexType == EntityIndexType.REFERENCED_ENTITY) {
			theEntityPrimaryKey = getPrimaryKeyToIndex(IndexType.REFERENCE_INDEX);
		} else {
			throw new EvitaInternalError("Unexpected type of index: " + targetIndexType);
		}
		if (referenceMutation instanceof SetReferenceGroupMutation upsertReferenceGroupMutation) {
			ReferenceIndexMutator.setFacetGroupInIndex(
				theEntityPrimaryKey, targetIndex,
				upsertReferenceGroupMutation.getReferenceKey(),
				upsertReferenceGroupMutation.getGroupPrimaryKey(),
				this,
				entityType
			);
		} else if (referenceMutation instanceof RemoveReferenceGroupMutation removeReferenceGroupMutation) {
			ReferenceIndexMutator.removeFacetGroupInIndex(
				theEntityPrimaryKey, targetIndex,
				removeReferenceGroupMutation.getReferenceKey(),
				this,
				entityType
			);
		} else if (referenceMutation instanceof ReferenceAttributeMutation) {
			// do nothing - attributes are not indexed in hierarchy index
		} else if (referenceMutation instanceof InsertReferenceMutation) {
			ReferenceIndexMutator.addFacetToIndex(
				targetIndex,
				referenceMutation.getReferenceKey(),
				null,
				this,
				theEntityPrimaryKey,
				undoActionsAppender
			);
		} else if (referenceMutation instanceof RemoveReferenceMutation) {
			ReferenceIndexMutator.removeFacetInIndex(
				targetIndex,
				referenceMutation.getReferenceKey(),
				this,
				entityType,
				theEntityPrimaryKey,
				undoActionsAppender
			);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new EvitaInternalError("Unknown mutation: " + referenceMutation.getClass());
		}
	}

	/**
	 * Method switches inner handling strategy for the entity - e.g. {@link SetPriceInnerRecordHandlingMutation}
	 */
	private void updatePriceHandlingForEntity(
		@Nonnull SetPriceInnerRecordHandlingMutation priceHandlingMutation,
		@Nonnull EntityIndex index
	) {
		final PricesStoragePart priceStorageContainer = getContainerAccessor().getPriceStoragePart(entityType, getPrimaryKeyToIndex(IndexType.PRICE_INDEX));
		final PriceInnerRecordHandling originalInnerRecordHandling = priceStorageContainer.getPriceInnerRecordHandling();
		final PriceInnerRecordHandling newPriceInnerRecordHandling = priceHandlingMutation.getPriceInnerRecordHandling();

		if (originalInnerRecordHandling != newPriceInnerRecordHandling) {

			final Consumer<EntityIndex> pricesRemoval = theIndex -> {
				for (PriceWithInternalIds price : priceStorageContainer.getPrices()) {
					PriceIndexMutator.priceRemove(
						this, theIndex, price.priceKey(),
						price,
						originalInnerRecordHandling,
						undoActionsAppender
					);
				}
			};

			final Consumer<EntityIndex> pricesInsertion = theIndex -> {
				for (PriceWithInternalIds price : priceStorageContainer.getPrices()) {
					PriceIndexMutator.priceUpsert(
						entityType, this, theIndex, price.priceKey(),
						price.innerRecordId(),
						price.validity(),
						price.priceWithoutTax(),
						price.priceWithTax(),
						price.sellable(),
						null,
						newPriceInnerRecordHandling,
						PriceIndexMutator.createPriceProvider(price),
						undoActionsAppender
					);
				}
			};

			// first remove data from reduced indexes
			ReferenceIndexMutator.executeWithReferenceIndexes(entityType, this, pricesRemoval);

			// now we can safely remove the data from super index
			pricesRemoval.accept(index);

			// next we need to add data to super index first
			pricesInsertion.accept(index);

			// and then we can add data to reduced indexes
			ReferenceIndexMutator.executeWithReferenceIndexes(entityType, this, pricesInsertion);
		}
	}

	/**
	 * Method processes all mutations that targets entity prices - e.g. {@link PriceMutation}.
	 */
	private void updatePriceIndex(@Nonnull PriceMutation priceMutation, @Nonnull EntityIndex index) {
		final PriceKey priceKey = priceMutation.getPriceKey();

		if (priceMutation instanceof final UpsertPriceMutation upsertPriceMutation) {
			final int theEntityPrimaryKey = this.getPrimaryKeyToIndex(IndexType.PRICE_INDEX);
			PriceIndexMutator.priceUpsert(
				this, index, entityType, theEntityPrimaryKey, priceKey,
				upsertPriceMutation.getInnerRecordId(),
				upsertPriceMutation.getValidity(),
				upsertPriceMutation.getPriceWithoutTax(),
				upsertPriceMutation.getPriceWithTax(),
				upsertPriceMutation.isSellable(),
				(thePriceKey, theInnerRecordId) -> containerAccessor.findExistingInternalIds(
					entityType, theEntityPrimaryKey, thePriceKey, theInnerRecordId
				),
				undoActionsAppender
			);
		} else if (priceMutation instanceof RemovePriceMutation) {
			PriceIndexMutator.priceRemove(
				entityType, this, index, priceKey, undoActionsAppender
			);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new EvitaInternalError("Unknown mutation: " + priceMutation.getClass());
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
				undoActionsAppender
			);
		} else if (parentMutation instanceof RemoveParentMutation) {
			removeParent(
				this, index,
				getPrimaryKeyToIndex(IndexType.HIERARCHY_INDEX),
				undoActionsAppender
			);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new EvitaInternalError("Unknown mutation: " + parentMutation.getClass());
		}
	}

}
