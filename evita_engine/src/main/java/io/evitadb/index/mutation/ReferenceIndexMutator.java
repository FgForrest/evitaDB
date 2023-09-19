/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
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
import io.evitadb.index.mutation.AttributeIndexMutator.EntityAttributeValueSupplier;
import io.evitadb.index.mutation.AttributeIndexMutator.ExistingAttributeValueSupplier;
import io.evitadb.store.entity.model.entity.AttributesStoragePart;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import io.evitadb.store.entity.model.entity.price.PriceWithInternalIds;
import io.evitadb.store.spi.model.storageParts.accessor.EntityStoragePartAccessor;
import io.evitadb.utils.Assert;
import lombok.Data;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.utils.Assert.isPremiseValid;
import static java.util.Optional.of;

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
	 * Empty implementation of the {@link ExistingAttributeValueSupplier} interface.
	 */
	ExistingAttributeValueSupplier NON_EXISTING_SUPPLIER = new ExistingAttributeValueSupplier() {
		@Nonnull
		@Override
		public Set<Locale> getEntityAttributeLocales() {
			return Collections.emptySet();
		}

		@Nonnull
		@Override
		public Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey) {
			return Optional.empty();
		}
	};

	/**
	 * Method allows to process all attribute mutations in referenced entities of the entity. Method uses
	 * {@link EntityIndexLocalMutationExecutor#updateAttributes(AttributeMutation, Function, Function, ExistingAttributeValueSupplier, EntityIndex, boolean, boolean)}
	 * to do that.
	 */
	static void attributeUpdate(
		int entityPrimaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferencedTypeEntityIndex referenceTypeIndex,
		@Nonnull EntityIndex<?> referenceIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull AttributeMutation attributeMutation
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(referenceKey.referenceName());

		// use different existing attribute value accessor - attributes needs to be looked up in ReferencesStoragePart
		final ReferenceAttributeValueSupplier existingValueAccessorFactory = new ReferenceAttributeValueSupplier(
			executor.getContainerAccessor(), referenceKey, entityType, entityPrimaryKey
		);
		// we need to index referenced entity primary key into the reference type index for all attributes
		final Function<String, AttributeSchema> attributeSchemaProvider =
			attributeName -> referenceSchema.getAttribute(attributeName).map(AttributeSchema.class::cast).orElse(null);
		final Function<String, Stream<SortableAttributeCompoundSchema>> compoundSchemaProvider =
			attributeName -> referenceSchema.getSortableAttributeCompoundsForAttribute(attributeName).stream().map(SortableAttributeCompoundSchema.class::cast);

		executor.executeWithDifferentPrimaryKeyToIndex(
			// the sort index of reference type index is not maintained, because the entity might reference multiple
			// entities and the sort index couldn't handle multiple values
			indexType -> indexType != IndexType.ATTRIBUTE_SORT_INDEX,
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

	/**
	 * Methods creates reference to the reference type and referenced entity indexes.
	 */
	static void referenceInsert(
		int entityPrimaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex<?> entityIndex,
		@Nonnull ReferencedTypeEntityIndex referenceTypeIndex,
		@Nullable EntityIndex<?> referenceIndex,
		@Nonnull ReferenceKey referenceKey
	) {
		// we need to index referenced entity primary key into the reference type index
		referenceTypeIndex.insertPrimaryKeyIfMissing(referenceKey.primaryKey(), executor.getContainerAccessor());

		// add facet to global index
		addFacetToIndex(entityIndex, referenceKey, null, executor, entityPrimaryKey);

		if (referenceIndex != null) {
			// we need to index entity primary key into the referenced entity index
			referenceIndex.insertPrimaryKeyIfMissing(entityPrimaryKey, executor.getContainerAccessor());
			addFacetToIndex(referenceIndex, referenceKey, null, executor, entityPrimaryKey);

			// we need to index all previously added global entity attributes and prices
			indexAllExistingData(executor, referenceIndex, entityType, entityPrimaryKey);
		}
	}

	/**
	 * Methods removes reference from the reference type and referenced entity indexes.
	 */
	static void referenceRemoval(
		int entityPrimaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex<?> entityIndex,
		@Nonnull ReferencedTypeEntityIndex referenceTypeIndex,
		@Nonnull EntityIndex<?> referenceIndex,
		@Nonnull ReferenceKey referenceKey
	) {
		// we need to remove referenced entity primary key from the reference type index
		referenceTypeIndex.removePrimaryKey(referenceKey.primaryKey(), executor.getContainerAccessor());

		// remove facet from global and index
		removeFacetInIndex(entityIndex, referenceKey, executor, entityType, entityPrimaryKey);

		// we need to remove entity primary key from the referenced entity index
		referenceIndex.removePrimaryKey(entityPrimaryKey, executor.getContainerAccessor());

		// remove all attributes that are present on the reference relation
		final ReferencesStoragePart referencesStorageContainer = executor.getContainerAccessor().getReferencesStoragePart(entityType, entityPrimaryKey);
		Assert.notNull(referencesStorageContainer, "References storage container for entity " + entityPrimaryKey + " was unexpectedly not found!");
		final ReferenceContract theReference = Arrays.stream(referencesStorageContainer.getReferences())
			.filter(Droppable::exists)
			.filter(it -> Objects.equals(it.getReferenceKey(), referenceKey))
			.findFirst()
			.orElse(null);
		Assert.notNull(theReference, "Reference " + referenceKey + " for entity " + entityPrimaryKey + " was unexpectedly not found!");
		for (AttributeValue attributeValue : theReference.getAttributeValues()) {
			if (attributeValue.exists()) {
				attributeUpdate(
					entityPrimaryKey, entityType, executor, referenceTypeIndex, referenceIndex, referenceKey,
					new RemoveAttributeMutation(attributeValue.key())
				);
			}
		}

		// if referenced type index is empty remove it completely
		if (referenceTypeIndex.isEmpty()) {
			executor.removeIndex(referenceTypeIndex.getIndexKey());
		}

		// remove all global entity attributes and prices
		removeAllExistingData(executor, referenceIndex, entityType, entityPrimaryKey);
	}

	/**
	 * Method indexes all existing indexable data for passed entity / referenced entity combination in passed indexes.
	 */
	private static void indexAllExistingData(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex<?> targetIndex,
		@Nonnull String entityType,
		int entityPrimaryKey
	) {
		indexAllFacets(executor, targetIndex, entityType, entityPrimaryKey);

		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
		final EntityBodyStoragePart entityCnt = containerAccessor.getEntityStoragePart(entityType, entityPrimaryKey, EntityExistence.MUST_EXIST);

		for (Locale locale : entityCnt.getLocales()) {
			targetIndex.upsertLanguage(locale, entityPrimaryKey, containerAccessor);
		}

		indexAllCompounds(executor, targetIndex, entityPrimaryKey);
		indexAllAttributes(executor, targetIndex, entityType, entityPrimaryKey, entityCnt);
		indexAllPrices(executor, targetIndex, entityType, entityPrimaryKey);
	}

	/**
	 * Method indexes all facets data for passed entity in passed index.
	 */
	private static void indexAllFacets(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex<?> targetIndex,
		@Nonnull String entityType,
		int entityPrimaryKey
	) {
		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
		final ReferencesStoragePart referencesStorageContainer = containerAccessor.getReferencesStoragePart(entityType, entityPrimaryKey);

		for (ReferenceContract reference : referencesStorageContainer.getReferences()) {
			final ReferenceKey referenceKey = reference.getReferenceKey();
			final Optional<GroupEntityReference> groupReference = reference.getGroup();
			if (reference.exists() && isFacetedReference(referenceKey, executor)) {
				targetIndex.addFacet(
					referenceKey,
					groupReference
						.filter(Droppable::exists)
						.map(GroupEntityReference::getPrimaryKey)
						.orElse(null),
					entityPrimaryKey
				);
			}
		}
	}

	/**
	 * Method indexes all prices of the entity in passed index.
	 */
	private static void indexAllPrices(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex<?>targetIndex,
		@Nonnull String entityType,
		int entityPrimaryKey
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
					price.sellable(),
					null,
					priceContainer.getPriceInnerRecordHandling(),
					PriceIndexMutator.createPriceProvider(price)
				);
			}
		}
	}

	/**
	 * Method indexes all sortable attribute compounds of the entity in passed index.
	 */
	private static void indexAllCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex<?> targetIndex,
		int entityPrimaryKey
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();

		AttributeIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
			targetIndex, null, entityPrimaryKey, entitySchema, null, containerAccessor
		);
	}

	/**
	 * Method indexes all attributes of the entity in passed index.
	 */
	private static void indexAllAttributes(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex<?> targetIndex,
		@Nonnull String entityType,
		int entityPrimaryKey,
		@Nonnull EntityBodyStoragePart entityCnt
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
		final AttributesStoragePart attributeCnt = containerAccessor.getAttributeStoragePart(entityType, entityPrimaryKey);
		final Function<String, AttributeSchema> attributeSchemaProvider =
			attributeName -> entitySchema.getAttribute(attributeName).map(AttributeSchema.class::cast).orElse(null);
		final Function<String, Stream<SortableAttributeCompoundSchema>> compoundsSchemaProvider =
			attributeName -> entitySchema.getSortableAttributeCompoundsForAttribute(attributeName).stream().map(SortableAttributeCompoundSchema.class::cast);

		for (AttributeValue attribute : attributeCnt.getAttributes()) {
			if (attribute.exists()) {
				AttributeIndexMutator.executeAttributeUpsert(
					executor,
					attributeSchemaProvider,
					compoundsSchemaProvider,
					NON_EXISTING_SUPPLIER,
					targetIndex,
					attribute.key(), Objects.requireNonNull(attribute.value()),
					false,
					false
				);
			}
		}
		for (Locale locale : entityCnt.getAttributeLocales()) {
			final AttributesStoragePart localizedAttributeCnt = containerAccessor.getAttributeStoragePart(entityType, entityPrimaryKey, locale);
			for (AttributeValue attribute : localizedAttributeCnt.getAttributes()) {
				if (attribute.exists()) {
					AttributeIndexMutator.executeAttributeUpsert(
						executor,
						attributeSchemaProvider,
						compoundsSchemaProvider,
						NON_EXISTING_SUPPLIER,
						targetIndex,
						attribute.key(), Objects.requireNonNull(attribute.value()),
						false,
						false
					);
				}
			}
		}
	}

	/**
	 * Method removes all indexed data for passed entity / referenced entity combination in passed indexes.
	 */
	private static void removeAllExistingData(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex<?> targetIndex,
		@Nonnull String entityType,
		int entityPrimaryKey
	) {
		removeAllFacets(executor, targetIndex, entityType, entityPrimaryKey);

		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
		final EntityBodyStoragePart entityCnt = containerAccessor.getEntityStoragePart(entityType, entityPrimaryKey, EntityExistence.MUST_EXIST);

		for (Locale locale : entityCnt.getLocales()) {
			targetIndex.removeLanguage(locale, entityPrimaryKey, executor.getContainerAccessor());
		}

		removeAllAttributes(executor, targetIndex, entityType, entityPrimaryKey);
		removeAllPrices(executor, targetIndex, entityType, entityPrimaryKey);
		removeAllCompounds(executor, targetIndex, entityPrimaryKey);

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
		@Nonnull EntityIndex<?> targetIndex,
		@Nonnull String entityType,
		int entityPrimaryKey
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
			}
		}
	}

	/**
	 * Method removes all attributes of the entity from passed index.
	 */
	private static void removeAllAttributes(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex<?> targetIndex,
		@Nonnull String entityType,
		int entityPrimaryKey
	) {
		final EntityAttributeValueSupplier attributeValueSupplier = new EntityAttributeValueSupplier(executor.getContainerAccessor(), entityType, entityPrimaryKey);
		final Function<String, AttributeSchema> attributeSchemaProvider =
			attributeName -> executor.getEntitySchema().getAttribute(attributeName).map(AttributeSchema.class::cast).orElse(null);
		final Function<String, Stream<SortableAttributeCompoundSchema>> compoundsSchemaProvider =
			attributeName -> executor.getEntitySchema().getSortableAttributeCompoundsForAttribute(attributeName).stream().map(SortableAttributeCompoundSchema.class::cast);

		attributeValueSupplier.getAttributeValues()
			.forEach(
				attribute -> AttributeIndexMutator.executeAttributeRemoval(
					executor,
					attributeSchemaProvider,
					compoundsSchemaProvider,
					attributeValueSupplier,
					targetIndex,
					attribute.key(),
					false,
					false
				));

		for (Locale locale : attributeValueSupplier.getEntityAttributeLocales()) {
			attributeValueSupplier.getAttributeValues(locale)
				.forEach(
					attribute -> AttributeIndexMutator.executeAttributeRemoval(
						executor,
						attributeSchemaProvider,
						compoundsSchemaProvider,
						attributeValueSupplier,
						targetIndex,
						attribute.key(),
						false,
						false
					));
		}
	}

	/**
	 * Method removes all prices of the entity from passed index.
	 */
	private static void removeAllPrices(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex<?> targetIndex,
		@Nonnull String entityType,
		int entityPrimaryKey
	) {
		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
		final PricesStoragePart priceContainer = containerAccessor.getPriceStoragePart(entityType, entityPrimaryKey);
		for (PriceContract price : priceContainer.getPrices()) {
			if (price.exists()) {
				PriceIndexMutator.priceRemove(
					entityType, executor, targetIndex,
					price.priceKey()
				);
			}
		}
	}

	/**
	 * Method removes all sortable attribute compounds of the entity from passed index.
	 */
	private static void removeAllCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex<?> targetIndex,
		int entityPrimaryKey
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();

		AttributeIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
			targetIndex, null, entityPrimaryKey, entitySchema, null, containerAccessor
		);
	}

	/**
	 * Returns appropriate {@link EntityIndex} for passed `referenceKey`. If the entity refers to the another Evita
	 * entity which has hierarchical structure {@link EntityIndexType#REFERENCED_HIERARCHY_NODE} is returned otherwise
	 * {@link EntityIndexType#REFERENCED_ENTITY} index is returned.
	 */
	@Nonnull
	static EntityIndex<?> getReferencedEntityIndex(
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
			entityIndexKey = new EntityIndexKey(EntityIndexType.REFERENCED_HIERARCHY_NODE, referenceKey);
		} else {
			entityIndexKey = new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, referenceKey);
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
		@Nonnull Consumer<EntityIndex<?>> referenceIndexConsumer
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
		@Nonnull Consumer<EntityIndex<?>> referenceIndexConsumer,
		@Nonnull Predicate<ReferenceContract> referencePredicate
	) {
		final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX);
		final ReferencesStoragePart referencesStorageContainer = executor.getContainerAccessor().getReferencesStoragePart(entityType, entityPrimaryKey);
		for (ReferenceContract reference : referencesStorageContainer.getReferences()) {
			if (reference.exists() && isIndexed(reference) && referencePredicate.test(reference)) {
				final EntityIndex<?> targetIndex = getReferencedEntityIndex(executor, reference.getReferenceKey());
				referenceIndexConsumer.accept(targetIndex);
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

	/**
	 * Registers new facet for the passed `referenceKey` and `entityPrimaryKey` but only if the referenced entity
	 * type is marked as `faceted` in reference schema.
	 */
	static void addFacetToIndex(
		@Nonnull EntityIndex<?> index,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		int entityPrimaryKey
	) {
		if (isFacetedReference(referenceKey, executor)) {
			index.addFacet(
				referenceKey,
				groupId,
				entityPrimaryKey
			);
		}
	}

	/**
	 * Sets group in existing facet in reference to the passed `referenceKey` and `entityPrimaryKey` but only if
	 * the referenced entity type is marked as `faceted` in reference schema.
	 */
	static void setFacetGroupInIndex(
		int entityPrimaryKey,
		@Nonnull EntityIndex<?> index,
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
		@Nonnull EntityIndex<?> index,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull String entityType,
		int entityPrimaryKey
	) {
		if (isFacetedReference(referenceKey, executor)) {
			final ReferenceContract existingReference = executor.getContainerAccessor().getReferencesStoragePart(entityType, entityPrimaryKey)
				.findReferenceOrThrowException(referenceKey);

			index.removeFacet(
				referenceKey,
				existingReference.getGroup()
					.filter(Droppable::exists)
					.map(EntityReferenceContract::getPrimaryKey)
					.orElse(null),
				entityPrimaryKey
			);
			if (index.isEmpty()) {
				// if the result index is empty, we should drop track of it in global entity index
				// it was probably registered before, and it has been emptied by the consumer lambda just now
				executor.removeIndex(index.getIndexKey());
			}
		}
	}

	/**
	 * Removes group in existing facet in reference to the passed `referenceKey` and `entityPrimaryKey` but only if
	 * the referenced entity type is marked as `faceted` in reference schema.
	 */
	static void removeFacetGroupInIndex(
		int entityPrimaryKey,
		@Nonnull EntityIndex<?> index,
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
	private static boolean isFacetedReference(
		@Nonnull ReferenceKey referencedEntity,
		@Nonnull EntityIndexLocalMutationExecutor executor
	) {
		final ReferenceSchemaContract referenceSchema = executor.getEntitySchema().getReferenceOrThrowException(referencedEntity.referenceName());
		return referenceSchema.isFaceted();
	}

	/**
	 * This implementation of attribute accessor looks up for attribute in {@link ReferencesStoragePart}.
	 */
	@NotThreadSafe
	@Data
	class ReferenceAttributeValueSupplier implements ExistingAttributeValueSupplier {
		private final EntityStoragePartAccessor containerAccessor;
		private final ReferenceKey referenceKey;
		private final String entityType;
		private final int entityPrimaryKey;
		private Set<Locale> memoizedLocales;
		private AttributeKey memoizedKey;
		@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
		private Optional<AttributeValue> memoizedValue;

		@Nonnull
		@Override
		public Set<Locale> getEntityAttributeLocales() {
			if (memoizedLocales == null) {
				this.memoizedLocales = containerAccessor.getEntityStoragePart(
					entityType, entityPrimaryKey, EntityExistence.MUST_EXIST
				).getAttributeLocales();
			}
			return memoizedLocales;
		}

		@Nonnull
		@Override
		public Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey) {
			if (!Objects.equals(memoizedKey, attributeKey)) {
				final ReferencesStoragePart referencesStorageContainer = containerAccessor.getReferencesStoragePart(entityType, entityPrimaryKey);

				this.memoizedKey = attributeKey;
				this.memoizedValue = of(referencesStorageContainer)
					.flatMap(it ->
						it.getReferencesAsCollection()
							.stream()
							.filter(ref -> Objects.equals(ref.getReferenceKey(), referenceKey))
							.findFirst()
					)
					.flatMap(it -> it.getAttributeValue(attributeKey))
					.filter(Droppable::exists);
			}
			return memoizedValue;
		}
	}
}
