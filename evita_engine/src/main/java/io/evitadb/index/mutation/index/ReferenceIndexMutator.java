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
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.IndexType;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.mutation.index.attributeSupplier.ExistingAttributeValueSupplier;
import io.evitadb.index.mutation.index.attributeSupplier.ExistingAttributeValueSupplierFactory;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import io.evitadb.store.entity.model.entity.price.PriceWithInternalIds;
import io.evitadb.store.spi.model.storageParts.accessor.EntityStoragePartAccessor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.index.mutation.index.attributeSupplier.ExistingAttributeValueSupplier.NO_EXISTING_VALUE_SUPPLIER;
import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * This interface is used to co-locate reference mutating routines which are rather procedural and long to avoid excessive
 * amount of code in {@link EntityIndexLocalMutationExecutor}.
 *
 * References are indexed in two special indexes:
 *
 * ## Reference type entity index
 *
 * For each referenced entity TYPE (like brand, category and so on) there is special index that contains all attributes
 * that were used in combination with that type and instead of primary key of the entity, contain primary key of the
 * REFERENCED entity.
 *
 * This referenced entity primary key along with type leads us to the second type of the index:
 *
 * ## Referenced entity index
 *
 * For each referenced entity TYPE and PRIMARY KEY there is special index that contains all attributes that were used
 * in entities linked to this same referenced entity instance. This kind of index is optimal to use for queries that
 * try to list all entities of particular brand/category etc., because the index contains information just about that.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface ReferenceIndexMutator {

	/**
	 * Method allows to process all attribute mutations in referenced entities of the entity. Method uses
	 * {@link EntityIndexLocalMutationExecutor#updateAttributes(AttributeMutation, Function, Function, ExistingAttributeValueSupplier, EntityIndex, boolean, boolean)}
	 * to do that.
	 */
	static void attributeUpdate(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ExistingAttributeValueSupplierFactory attributeSupplierFactory,
		@Nonnull ReferencedTypeEntityIndex referenceTypeIndex,
		@Nullable EntityIndex referenceIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull AttributeMutation attributeMutation
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(referenceKey.referenceName());

		// use different existing attribute value accessor - attributes needs to be looked up in ReferencesStoragePart
		final ExistingAttributeValueSupplier existingValueAccessorFactory = attributeSupplierFactory.getReferenceAttributeValueSupplier(referenceKey);
		// we need to index referenced entity primary key into the reference type index for all attributes
		final Function<String, AttributeSchema> attributeSchemaProvider =
			attributeName -> referenceSchema.getAttribute(attributeName).map(AttributeSchema.class::cast).orElse(null);
		final Function<String, Stream<SortableAttributeCompoundSchema>> compoundSchemaProvider =
			attributeName -> referenceSchema.getSortableAttributeCompoundsForAttribute(attributeName).stream().map(SortableAttributeCompoundSchema.class::cast);

		executor.executeWithDifferentPrimaryKeyToIndex(
			indexType -> referenceKey.primaryKey(),
			() -> executor.updateAttributes(
				attributeMutation,
				attributeSchemaProvider,
				compoundSchemaProvider,
				existingValueAccessorFactory,
				referenceTypeIndex,
				false,
				false
			)
		);

		if (referenceIndex != null) {
			// we need to index entity primary key into the referenced entity index for all attributes
			executor.updateAttributes(
				attributeMutation,
				attributeSchemaProvider,
				compoundSchemaProvider,
				existingValueAccessorFactory,
				referenceIndex,
				false,
				true
			);
		}
	}

	/**
	 * Methods creates reference to the reference type and referenced entity indexes.
	 */
	static void referenceInsert(
		int entityPrimaryKey,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex entityIndex,
		@Nonnull ReferencedTypeEntityIndex referenceTypeIndex,
		@Nullable EntityIndex referenceIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ExistingAttributeValueSupplierFactory existingAttributeSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		// we need to index referenced entity primary key into the reference type index
		if (referenceTypeIndex.insertPrimaryKeyIfMissing(referenceKey.primaryKey()) && undoActionConsumer != null) {
			undoActionConsumer.accept(() -> referenceTypeIndex.removePrimaryKey(referenceKey.primaryKey()));
		}

		// add facet to global index
		addFacetToIndex(entityIndex, referenceKey, null, executor, entityPrimaryKey, undoActionConsumer);

		if (referenceIndex != null) {
			// we need to index entity primary key into the referenced entity index
			if (referenceIndex.insertPrimaryKeyIfMissing(entityPrimaryKey)) {
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(() -> referenceIndex.removePrimaryKey(entityPrimaryKey));
				}
				// we need to index all previously added global entity attributes and prices
				indexAllExistingData(
					executor, referenceIndex,
					entitySchema, entityPrimaryKey, existingAttributeSupplierFactory,
					undoActionConsumer
				);
			}

			// add facet to reference index
			addFacetToIndex(referenceIndex, referenceKey, null, executor, entityPrimaryKey, undoActionConsumer);
		}
	}

	/**
	 * Methods removes reference from the reference type and referenced entity indexes.
	 */
	static void referenceRemoval(
		int entityPrimaryKey,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex entityIndex,
		@Nonnull ReferencedTypeEntityIndex referenceTypeIndex,
		@Nonnull EntityIndex referenceIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ExistingAttributeValueSupplierFactory existingAttributeSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final String entityType = entitySchema.getName();

		// we need to remove referenced entity primary key from the reference type index
		if (referenceTypeIndex.removePrimaryKey(referenceKey.primaryKey()) && undoActionConsumer != null) {
			undoActionConsumer.accept(() -> referenceTypeIndex.insertPrimaryKeyIfMissing(referenceKey.primaryKey()));
		}

		// remove facet from global index
		removeFacetInIndex(entityIndex, referenceKey, executor, entityType, entityPrimaryKey, undoActionConsumer);

		// we need to remove entity primary key from the referenced entity index
		if (referenceIndex.removePrimaryKey(entityPrimaryKey)) {
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(() -> referenceIndex.insertPrimaryKeyIfMissing(entityPrimaryKey));
			}

			// remove all entity attributes and prices
			removeAllExistingData(
				executor, referenceIndex,
				entitySchema, entityPrimaryKey,
				existingAttributeSupplierFactory,
				undoActionConsumer
			);
		}

		// remove all attributes that are present on the reference relation form type index
		final ExistingAttributeValueSupplier existingAttributeSupplier = existingAttributeSupplierFactory.getReferenceAttributeValueSupplier(referenceKey);
		existingAttributeSupplier.getAttributeValues()
			.forEach(
				attributeValue -> attributeUpdate(
					executor, existingAttributeSupplierFactory, referenceTypeIndex, null, referenceKey,
					new RemoveAttributeMutation(attributeValue.key())
				)
			);

		// if referenced type index is empty remove it completely
		if (referenceTypeIndex.isEmpty()) {
			executor.removeIndex(referenceTypeIndex.getIndexKey());
		}
	}

	/**
	 * Returns appropriate {@link EntityIndex} for passed `referenceKey`. If the entity refers to the another Evita
	 * entity which has hierarchical structure {@link EntityIndexType#REFERENCED_HIERARCHY_NODE} is returned otherwise
	 * {@link EntityIndexType#REFERENCED_ENTITY} index is returned.
	 */
	@Nonnull
	static EntityIndex getReferencedEntityIndex(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceKey referenceKey
	) {
		final String referenceName = referenceKey.referenceName();
		final ReferenceSchemaContract referenceSchema = executor.getEntitySchema().getReferenceOrThrowException(referenceName);
		final boolean referencesHierarchy;
		if (referenceSchema.isReferencedEntityTypeManaged()) {
			final EntitySchemaContract referencedEntitySchema = executor.getEntitySchema(referenceSchema.getReferencedEntityType());
			isPremiseValid(referencedEntitySchema != null, "Referenced entity `" + referenceName + "` schema was not found!");
			referencesHierarchy = referencedEntitySchema.isWithHierarchy();
		} else {
			referencesHierarchy = false;
		}
		// in order to save memory the data are indexed either to hierarchical or referenced entity index
		final EntityIndexKey entityIndexKey;
		if (referencesHierarchy) {
			entityIndexKey = new EntityIndexKey(EntityIndexType.REFERENCED_HIERARCHY_NODE, executor.getScope(), referenceKey);
		} else {
			entityIndexKey = new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, executor.getScope(), referenceKey);
		}
		return executor.getOrCreateIndex(entityIndexKey);
	}

	/**
	 * Method executes logic in `referenceIndexConsumer` in new specific type of {@link EntityIndex} of type
	 * {@link EntityIndexType#REFERENCED_ENTITY} for all entities that are currently referenced.
	 */
	static void executeWithReferenceIndexes(
		@Nonnull String entityType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull Consumer<EntityIndex> referenceIndexConsumer
	) {
		executeWithReferenceIndexes(entityType, executor, referenceIndexConsumer, referenceContract -> true);
	}

	/**
	 * Method executes logic in `referenceIndexConsumer` in new specific type of {@link EntityIndex} of type
	 * {@link EntityIndexType#REFERENCED_ENTITY} for all entities that are currently referenced.
	 */
	static void executeWithReferenceIndexes(
		@Nonnull String entityType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull Consumer<EntityIndex> referenceIndexConsumer,
		@Nonnull Predicate<ReferenceContract> referencePredicate
	) {
		final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX);
		final ReferencesStoragePart referencesStorageContainer = executor.getContainerAccessor().getReferencesStoragePart(entityType, entityPrimaryKey);
		for (ReferenceContract reference : referencesStorageContainer.getReferences()) {
			if (reference.exists() && isIndexed(reference) && referencePredicate.test(reference)) {
				final EntityIndex targetIndex = getReferencedEntityIndex(executor, reference.getReferenceKey());
				referenceIndexConsumer.accept(targetIndex);
			}
		}
	}

	/**
	 * Registers new facet for the passed `referenceKey` and `entityPrimaryKey` but only if the referenced entity
	 * type is marked as `faceted` in reference schema.
	 */
	static void addFacetToIndex(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		int entityPrimaryKey,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		if (isFacetedReference(referenceKey, executor)) {
			index.addFacet(referenceKey, groupId, entityPrimaryKey);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(() -> index.removeFacet(referenceKey, groupId, entityPrimaryKey));
			}
		}
	}

	/**
	 * Sets group in existing facet in reference to the passed `referenceKey` and `entityPrimaryKey` but only if
	 * the referenced entity type is marked as `faceted` in reference schema.
	 */
	static void setFacetGroupInIndex(
		int entityPrimaryKey,
		@Nonnull EntityIndex index,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull Integer groupId,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull String entityType
	) {
		final ReferenceContract existingReference = executor.getContainerAccessor().getReferencesStoragePart(entityType, entityPrimaryKey)
			.findReferenceOrThrowException(referenceKey);
		final Optional<Integer> existingGroupId = existingReference.getGroup()
			.filter(Droppable::exists)
			.map(EntityReferenceContract::getPrimaryKey);

		if (isFacetedReference(referenceKey, executor)) {
			index.removeFacet(
				referenceKey,
				existingGroupId.orElse(null),
				entityPrimaryKey
			);
			index.addFacet(
				referenceKey,
				groupId,
				entityPrimaryKey
			);
		}
	}

	/**
	 * Removes existing facet for the passed `referenceKey` and `entityPrimaryKey` but only if the referenced entity
	 * type is marked as `faceted` in reference schema.
	 */
	static void removeFacetInIndex(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull String entityType,
		int entityPrimaryKey,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		if (isFacetedReference(referenceKey, executor)) {
			final ReferenceContract existingReference = executor.getContainerAccessor().getReferencesStoragePart(entityType, entityPrimaryKey)
				.findReferenceOrThrowException(referenceKey);

			removeFacetInIndex(index, referenceKey, executor, entityPrimaryKey, existingReference, undoActionConsumer);
		}
	}

	/**
	 * Removes existing facet for the passed `referenceKey` and `entityPrimaryKey` but only if the referenced entity
	 * type is marked as `faceted` in reference schema.
	 */
	static void removeFacetInIndex(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		int entityPrimaryKey,
		@Nonnull ReferenceContract existingReference,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final Integer groupId = existingReference.getGroup()
			.filter(Droppable::exists)
			.map(EntityReferenceContract::getPrimaryKey)
			.orElse(null);
		index.removeFacet(referenceKey, groupId, entityPrimaryKey);
		if (undoActionConsumer != null) {
			undoActionConsumer.accept(() -> index.addFacet(referenceKey, groupId, entityPrimaryKey));
		}
		if (index.isEmpty()) {
			// if the result index is empty, we should drop track of it in global entity index
			// it was probably registered before, and it has been emptied by the consumer lambda just now
			executor.removeIndex(index.getIndexKey());
		}
	}

	/**
	 * Removes group in existing facet in reference to the passed `referenceKey` and `entityPrimaryKey` but only if
	 * the referenced entity type is marked as `faceted` in reference schema.
	 */
	static void removeFacetGroupInIndex(
		int entityPrimaryKey,
		@Nonnull EntityIndex index,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull String entityType
	) {
		final ReferenceContract existingReference = executor.getContainerAccessor().getReferencesStoragePart(entityType, entityPrimaryKey)
			.findReferenceOrThrowException(referenceKey);
		isPremiseValid(
			existingReference.getGroup().filter(Droppable::exists).isPresent(),
			"Group is expected to be non-null when RemoveReferenceGroupMutation is about to be executed."
		);
		final int groupId = existingReference.getGroup().map(GroupEntityReference::getPrimaryKey).orElseThrow();

		if (isFacetedReference(referenceKey, executor)) {
			index.removeFacet(
				referenceKey,
				groupId,
				entityPrimaryKey
			);
			index.addFacet(referenceKey, null, entityPrimaryKey);
		}
	}

	/**
	 * Returns TRUE if `referencedEntity` is marked as `faceted` in the entity schema.
	 */
	static boolean isFacetedReference(
		@Nonnull ReferenceKey referencedEntity,
		@Nonnull EntityIndexLocalMutationExecutor executor
	) {
		final ReferenceSchemaContract referenceSchema = executor.getEntitySchema().getReferenceOrThrowException(referencedEntity.referenceName());
		return referenceSchema.isFaceted();
	}

	/**
	 * Method indexes all existing indexable data for passed entity / referenced entity combination in passed indexes.
	 */
	private static void indexAllExistingData(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex targetIndex,
		@Nonnull EntitySchemaContract entitySchema,
		int entityPrimaryKey,
		@Nonnull ExistingAttributeValueSupplierFactory existingAttributeSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final String entityType = entitySchema.getName();

		indexAllFacets(executor, targetIndex, entityType, entityPrimaryKey, undoActionConsumer);

		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
		final EntityBodyStoragePart entityCnt = containerAccessor.getEntityStoragePart(entityType, entityPrimaryKey, EntityExistence.MUST_EXIST);

		for (Locale locale : entityCnt.getLocales()) {
			executor.upsertEntityLanguageInTargetIndex(
				entityPrimaryKey, locale, targetIndex, entitySchema, existingAttributeSupplierFactory, undoActionConsumer
			);
		}

		indexAllPrices(executor, targetIndex, entityType, entityPrimaryKey, undoActionConsumer);
		indexAllAttributes(executor, targetIndex, existingAttributeSupplierFactory, undoActionConsumer);
		indexAllCompounds(executor, targetIndex, null, existingAttributeSupplierFactory, undoActionConsumer);
	}

	/**
	 * Method indexes all facets data for passed entity in passed index.
	 */
	private static void indexAllFacets(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex targetIndex,
		@Nonnull String entityType,
		int entityPrimaryKey,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
		final ReferencesStoragePart referencesStorageContainer = containerAccessor.getReferencesStoragePart(entityType, entityPrimaryKey);

		for (ReferenceContract reference : referencesStorageContainer.getReferences()) {
			final ReferenceKey referenceKey = reference.getReferenceKey();
			final Optional<GroupEntityReference> groupReference = reference.getGroup();
			if (reference.exists() && isFacetedReference(referenceKey, executor)) {
				final Integer groupId = groupReference
					.filter(Droppable::exists)
					.map(GroupEntityReference::getPrimaryKey)
					.orElse(null);
				targetIndex.addFacet(referenceKey, groupId, entityPrimaryKey);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(
						() -> targetIndex.removeFacet(referenceKey, groupId, entityPrimaryKey)
					);
				}
			}
		}
	}

	/**
	 * Method indexes all prices of the entity in passed index.
	 */
	private static void indexAllPrices(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex targetIndex,
		@Nonnull String entityType,
		int entityPrimaryKey,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
		final PricesStoragePart priceContainer = containerAccessor.getPriceStoragePart(entityType, entityPrimaryKey);
		for (PriceWithInternalIds price : priceContainer.getPrices()) {
			if (price.exists()) {
				PriceIndexMutator.priceUpsert(
					entityType, executor, targetIndex,
					price.priceKey(),
					price.innerRecordId(),
					price.validity(),
					price.priceWithoutTax(),
					price.priceWithTax(),
					price.indexed(),
					null,
					priceContainer.getPriceInnerRecordHandling(),
					PriceIndexMutator.createPriceProvider(price),
					undoActionConsumer
				);
			}
		}
	}

	/**
	 * Method indexes all attributes of the entity in passed index.
	 */
	private static void indexAllAttributes(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex targetIndex,
		@Nonnull ExistingAttributeValueSupplierFactory existingAttributeValueSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();

		final Function<String, AttributeSchema> attributeSchemaProvider =
			attributeName -> entitySchema.getAttribute(attributeName).map(AttributeSchema.class::cast).orElse(null);
		final Function<String, Stream<SortableAttributeCompoundSchema>> compoundsSchemaProvider =
			attributeName -> entitySchema.getSortableAttributeCompoundsForAttribute(attributeName).stream().map(SortableAttributeCompoundSchema.class::cast);

		existingAttributeValueSupplierFactory.getEntityAttributeValueSupplier()
			.getAttributeValues()
			.forEach(attribute ->
				AttributeIndexMutator.executeAttributeUpsert(
					executor,
					attributeSchemaProvider,
					compoundsSchemaProvider,
					NO_EXISTING_VALUE_SUPPLIER,
					targetIndex,
					attribute.key(),
					Objects.requireNonNull(attribute.value()),
					false,
					false,
					undoActionConsumer
				)
			);

		final EntityIndexKey indexKey = targetIndex.getIndexKey();
		if (indexKey.discriminator() instanceof ReferenceKey referenceKey) {
			final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(referenceKey.referenceName());
			final Function<String, AttributeSchema> referenceAttributeSchemaProvider =
				attributeName -> referenceSchema.getAttribute(attributeName).map(AttributeSchema.class::cast).orElse(null);
			final Function<String, Stream<SortableAttributeCompoundSchema>> referenceCompoundsSchemaProvider =
				attributeName -> referenceSchema.getSortableAttributeCompoundsForAttribute(attributeName).stream().map(SortableAttributeCompoundSchema.class::cast);

			existingAttributeValueSupplierFactory.getReferenceAttributeValueSupplier(referenceKey)
				.getAttributeValues()
				.forEach(attribute ->
					AttributeIndexMutator.executeAttributeUpsert(
						executor,
						referenceAttributeSchemaProvider,
						referenceCompoundsSchemaProvider,
						NO_EXISTING_VALUE_SUPPLIER,
						targetIndex,
						attribute.key(),
						Objects.requireNonNull(attribute.value()),
						false,
						false,
						undoActionConsumer
					)
				);
		}
	}

	/**
	 * Method indexes all sortable attribute compounds of the entity in passed index. When the `locale` parameter is
	 * not null, only the compounds for the given locale are created. Otherwise, all compounds that don't contain
	 * localized attribute value are created.
	 */
	static void indexAllCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex targetIndex,
		@Nullable Locale locale,
		@Nonnull ExistingAttributeValueSupplierFactory existingAttributeValueSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();

		final ExistingAttributeValueSupplier entityAttributeValueSupplier = existingAttributeValueSupplierFactory.getEntityAttributeValueSupplier();
		AttributeIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
			executor, targetIndex, locale, entitySchema,
			entityAttributeValueSupplier, undoActionConsumer
		);
		if (undoActionConsumer != null) {
			undoActionConsumer.accept(
				() -> AttributeIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
					executor, targetIndex, locale, entitySchema, entityAttributeValueSupplier, undoActionConsumer
				)
			);
		}

		final EntityIndexKey indexKey = targetIndex.getIndexKey();
		if (indexKey.discriminator() instanceof ReferenceKey referenceKey) {
			final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(referenceKey.referenceName());
			final ExistingAttributeValueSupplier referenceAttributeValueSupplier = existingAttributeValueSupplierFactory.getReferenceAttributeValueSupplier(referenceKey);
			AttributeIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
				executor, targetIndex, locale, referenceSchema,
				referenceAttributeValueSupplier, undoActionConsumer
			);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(
					() -> AttributeIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
						executor, targetIndex, locale, referenceSchema,
						referenceAttributeValueSupplier, undoActionConsumer
					)
				);
			}
		}
	}

	/**
	 * Method removes all indexed data for passed entity / referenced entity combination in passed indexes.
	 */
	private static void removeAllExistingData(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex targetIndex,
		@Nonnull EntitySchemaContract entitySchema,
		int entityPrimaryKey,
		@Nonnull ExistingAttributeValueSupplierFactory existingAttributeSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final String entityType = entitySchema.getName();
		removeAllFacets(executor, targetIndex, entityType, entityPrimaryKey, undoActionConsumer);

		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
		final EntityBodyStoragePart entityCnt = containerAccessor.getEntityStoragePart(entityType, entityPrimaryKey, EntityExistence.MUST_EXIST);

		for (Locale locale : entityCnt.getLocales()) {
			executor.removeEntityLanguageInTargetIndex(
				entityPrimaryKey, locale, targetIndex, entitySchema,
				existingAttributeSupplierFactory,
				undoActionConsumer
			);
		}

		removeAllPrices(executor, targetIndex, entityType, entityPrimaryKey, undoActionConsumer);
		removeAllAttributes(executor, targetIndex, existingAttributeSupplierFactory, undoActionConsumer);
		removeAllCompounds(executor, targetIndex, null, existingAttributeSupplierFactory, undoActionConsumer);

		// if target index is empty, remove it completely
		if (targetIndex.isEmpty()) {
			executor.removeIndex(targetIndex.getIndexKey());
		}
	}

	/**
	 * Method removes all facets for passed entity from passed index.
	 */
	private static void removeAllFacets(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex targetIndex,
		@Nonnull String entityType,
		int entityPrimaryKey,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
		final ReferencesStoragePart referencesStorageContainer = containerAccessor.getReferencesStoragePart(entityType, entityPrimaryKey);

		for (ReferenceContract reference : referencesStorageContainer.getReferences()) {
			final ReferenceKey referenceKey = reference.getReferenceKey();
			if (reference.exists() && isFacetedReference(referenceKey, executor)) {
				final Integer groupId = reference.getGroup()
					.filter(Droppable::exists)
					.map(EntityReferenceContract::getPrimaryKey)
					.orElse(null);
				targetIndex.removeFacet(referenceKey, groupId, entityPrimaryKey);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(
						() -> targetIndex.addFacet(referenceKey, groupId, entityPrimaryKey)
					);
				}
			}
		}
	}

	/**
	 * Method removes all prices of the entity from passed index.
	 */
	private static void removeAllPrices(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex targetIndex,
		@Nonnull String entityType,
		int entityPrimaryKey,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
		final PricesStoragePart priceContainer = containerAccessor.getPriceStoragePart(entityType, entityPrimaryKey);
		for (PriceContract price : priceContainer.getPrices()) {
			if (price.exists()) {
				PriceIndexMutator.priceRemove(
					entityType, executor, targetIndex,
					price.priceKey(),
					undoActionConsumer
				);
			}
		}
	}

	/**
	 * Method removes all attributes of the entity from passed index.
	 */
	private static void removeAllAttributes(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex targetIndex,
		@Nonnull ExistingAttributeValueSupplierFactory existingAttributeValueSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();

		final Function<String, AttributeSchema> attributeSchemaProvider =
			attributeName -> entitySchema.getAttribute(attributeName).map(AttributeSchema.class::cast).orElse(null);
		final Function<String, Stream<SortableAttributeCompoundSchema>> compoundsSchemaProvider =
			attributeName -> entitySchema.getSortableAttributeCompoundsForAttribute(attributeName).stream().map(SortableAttributeCompoundSchema.class::cast);

		existingAttributeValueSupplierFactory.getEntityAttributeValueSupplier()
			.getAttributeValues()
			.forEach(attribute ->
				AttributeIndexMutator.executeAttributeRemoval(
					executor,
					attributeSchemaProvider,
					compoundsSchemaProvider,
					existingAttributeValueSupplierFactory.getEntityAttributeValueSupplier(),
					targetIndex,
					attribute.key(),
					false,
					false,
					undoActionConsumer
				)
			);

		final EntityIndexKey indexKey = targetIndex.getIndexKey();
		if (indexKey.discriminator() instanceof ReferenceKey referenceKey) {
			final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(referenceKey.referenceName());
			final Function<String, AttributeSchema> referenceAttributeSchemaProvider =
				attributeName -> referenceSchema.getAttribute(attributeName).map(AttributeSchema.class::cast).orElse(null);
			final Function<String, Stream<SortableAttributeCompoundSchema>> referenceCompoundsSchemaProvider =
				attributeName -> referenceSchema.getSortableAttributeCompoundsForAttribute(attributeName).stream().map(SortableAttributeCompoundSchema.class::cast);

			final ExistingAttributeValueSupplier referenceAttributeValueSupplier = existingAttributeValueSupplierFactory.getReferenceAttributeValueSupplier(referenceKey);
			referenceAttributeValueSupplier
				.getAttributeValues()
				.forEach(attribute ->
					AttributeIndexMutator.executeAttributeRemoval(
						executor,
						referenceAttributeSchemaProvider,
						referenceCompoundsSchemaProvider,
						referenceAttributeValueSupplier,
						targetIndex,
						attribute.key(),
						false,
						false,
						undoActionConsumer
					)
				);
		}
	}

	/**
	 * Method removes all sortable attribute compounds of the entity from passed index.
	 */
	static void removeAllCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex targetIndex,
		@Nullable Locale locale,
		@Nonnull ExistingAttributeValueSupplierFactory existingAttributeValueSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();

		final ExistingAttributeValueSupplier entityAttributeValueSupplier = existingAttributeValueSupplierFactory.getEntityAttributeValueSupplier();
		AttributeIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
			executor, targetIndex, locale, entitySchema,
			entityAttributeValueSupplier, undoActionConsumer
		);
		if (undoActionConsumer != null) {
			undoActionConsumer.accept(
				() -> AttributeIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
					executor, targetIndex, locale, entitySchema, entityAttributeValueSupplier, undoActionConsumer
				)
			);
		}

		final EntityIndexKey indexKey = targetIndex.getIndexKey();
		if (indexKey.discriminator() instanceof ReferenceKey referenceKey) {
			final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(referenceKey.referenceName());
			final ExistingAttributeValueSupplier referenceAttributeValueSupplier = existingAttributeValueSupplierFactory.getReferenceAttributeValueSupplier(referenceKey);
			AttributeIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
				executor, targetIndex, locale, referenceSchema,
				referenceAttributeValueSupplier, undoActionConsumer
			);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(
					() -> AttributeIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
						executor, targetIndex, locale, referenceSchema,
						referenceAttributeValueSupplier, undoActionConsumer
					)
				);
			}
		}
	}

	/**
	 * Returns true if reference schema is configured and indexed.
	 */
	private static boolean isIndexed(@Nonnull ReferenceContract reference) {
		return reference.getReferenceSchema()
			.map(ReferenceSchemaContract::isIndexed)
			.orElse(false);
	}

}
