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
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.IndexType;
import io.evitadb.index.ReferencedTypeEntityIndex;
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
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static io.evitadb.utils.Assert.isPremiseValid;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

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

	Supplier<AttributeValue> NON_EXISTING_SUPPLIER = () -> null;

	/**
	 * Method allows to process all attribute mutations in referenced entities of the entity. Method uses
	 * {@link EntityIndexLocalMutationExecutor#updateAttributes(AttributeMutation, Function, Supplier, EntityIndex, boolean)}
	 * to do that.
	 */
	static void attributeUpdate(
		int entityPrimaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferencedTypeEntityIndex referenceTypeIndex,
		@Nonnull EntityIndex referenceIndex,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull AttributeMutation attributeMutation
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(referenceKey.referenceName());

		// use different existing attribute value accessor - attributes needs to be looked up in ReferencesStoragePart
		final Supplier<AttributeValue> existingValueAccessorFactory = new ExistingReferenceAttributeAccessor(
			executor, entityType, entityPrimaryKey, referenceKey, attributeMutation.getAttributeKey()
		);
		// we need to index referenced entity primary key into the reference type index for all attributes
		executor.executeWithDifferentPrimaryKeyToIndex(
			indexType -> {
				if (indexType == IndexType.ATTRIBUTE_SORT_INDEX) {
					// only sort indexes here target primary key of the main entity - we need monotonic row of all entity primary keys for this type
					return entityPrimaryKey;
				} else {
					// all other indexes target referenced entity primary key - we use them for looking up referenced type indexes (type + referenced entity key)
					return referenceKey.primaryKey();
				}
			},
			() -> executor.updateAttributes(
				attributeMutation,
				attributeName -> referenceSchema.getAttribute(attributeName).orElse(null),
				existingValueAccessorFactory,
				referenceTypeIndex,
				false
			)
		);

		// we need to index entity primary key into the referenced entity index for all attributes
		executor.updateAttributes(
			attributeMutation,
			attributeName -> referenceSchema.getAttribute(attributeName).orElse(null),
			existingValueAccessorFactory,
			referenceIndex,
			false
		);
	}

	/**
	 * Methods creates reference to the reference type and referenced entity indexes.
	 */
	static void referenceInsert(
		int entityPrimaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex entityIndex,
		@Nonnull ReferencedTypeEntityIndex referenceTypeIndex,
		@Nullable EntityIndex referenceIndex,
		@Nonnull ReferenceKey referenceKey
	) {
		// we need to index referenced entity primary key into the reference type index
		referenceTypeIndex.insertPrimaryKeyIfMissing(referenceKey.primaryKey());

		// add facet to global index
		addFacetToIndex(entityIndex, referenceKey, null, executor, entityPrimaryKey);

		if (referenceIndex != null) {
			// we need to index entity primary key into the referenced entity index
			referenceIndex.insertPrimaryKeyIfMissing(entityPrimaryKey);
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
		@Nonnull EntityIndex entityIndex,
		@Nonnull ReferencedTypeEntityIndex referenceTypeIndex,
		@Nonnull EntityIndex referenceIndex,
		@Nonnull ReferenceKey referenceKey
	) {
		// we need to remove referenced entity primary key from the reference type index
		referenceTypeIndex.removePrimaryKey(referenceKey.primaryKey());

		// remove facet from global and index
		removeFacetInIndex(entityIndex, referenceKey, executor, entityType, entityPrimaryKey);

		// we need to remove entity primary key from the referenced entity index
		referenceIndex.removePrimaryKey(entityPrimaryKey);

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
					new RemoveAttributeMutation(attributeValue.getKey())
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
		@Nonnull EntityIndex targetIndex,
		@Nonnull String entityType,
		int entityPrimaryKey
	) {
		indexFacets(executor, targetIndex, entityType, entityPrimaryKey);

		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
		final EntityBodyStoragePart entityCnt = containerAccessor.getEntityStoragePart(entityType, entityPrimaryKey, EntityExistence.MUST_EXIST);

		for (Locale locale : entityCnt.getLocales()) {
			targetIndex.upsertLanguage(locale, entityPrimaryKey);
		}

		final AttributesStoragePart attributeCnt = containerAccessor.getAttributeStoragePart(entityType, entityPrimaryKey);
		for (AttributeValue attribute : attributeCnt.getAttributes()) {
			if (attribute.exists()) {
				AttributeIndexMutator.executeAttributeUpsert(
					executor,
					attributeName -> executor.getEntitySchema().getAttribute(attributeName).orElse(null),
					NON_EXISTING_SUPPLIER,
					targetIndex,
					attribute.getKey(), Objects.requireNonNull(attribute.getValue()), false
				);
			}
		}
		for (Locale locale : entityCnt.getAttributeLocales()) {
			final AttributesStoragePart localizedAttributeCnt = containerAccessor.getAttributeStoragePart(entityType, entityPrimaryKey, locale);
			for (AttributeValue attribute : localizedAttributeCnt.getAttributes()) {
				if (attribute.exists()) {
					AttributeIndexMutator.executeAttributeUpsert(
						executor,
						attributeName -> executor.getEntitySchema().getAttribute(attributeName).orElse(null),
						NON_EXISTING_SUPPLIER,
						targetIndex,
						attribute.getKey(), Objects.requireNonNull(attribute.getValue()), false
					);
				}
			}
		}
		final PricesStoragePart priceContainer = containerAccessor.getPriceStoragePart(entityType, entityPrimaryKey);
		for (PriceWithInternalIds price : priceContainer.getPrices()) {
			if (price.exists()) {
				PriceIndexMutator.priceUpsert(
					entityType, executor, targetIndex,
					price.getPriceKey(),
					price.getInnerRecordId(),
					price.getValidity(),
					price.getPriceWithoutTax(),
					price.getPriceWithTax(),
					price.isSellable(),
					null,
					priceContainer.getPriceInnerRecordHandling(),
					PriceIndexMutator.createPriceProvider(price)
				);
			}
		}
	}

	/**
	 * Method indexes all facets data for passed entity in passed index.
	 */
	private static void indexFacets(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex targetIndex,
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
	 * Method removes all indexed data for passed entity / referenced entity combination in passed indexes.
	 */
	private static void removeAllExistingData(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex targetIndex,
		@Nonnull String entityType,
		int entityPrimaryKey
	) {
		removeFacets(executor, targetIndex, entityType, entityPrimaryKey);

		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
		final EntityBodyStoragePart entityCnt = containerAccessor.getEntityStoragePart(entityType, entityPrimaryKey, EntityExistence.MUST_EXIST);

		for (Locale locale : entityCnt.getLocales()) {
			targetIndex.removeLanguage(locale, entityPrimaryKey);
		}

		final AttributesStoragePart attributeCnt = containerAccessor.getAttributeStoragePart(entityType, entityPrimaryKey);
		for (AttributeValue attribute : attributeCnt.getAttributes()) {
			if (attribute.exists()) {
				AttributeIndexMutator.executeAttributeRemoval(
					executor,
					attributeName -> executor.getEntitySchema().getAttribute(attributeName).orElse(null),
					() -> attribute,
					targetIndex,
					attribute.getKey(), false
				);
			}
		}
		for (Locale locale : entityCnt.getLocales()) {
			final AttributesStoragePart localizedAttributeCnt = containerAccessor.getAttributeStoragePart(entityType, entityPrimaryKey, locale);
			for (AttributeValue attribute : localizedAttributeCnt.getAttributes()) {
				if (attribute.exists()) {
					AttributeIndexMutator.executeAttributeRemoval(
						executor,
						attributeName -> executor.getEntitySchema().getAttribute(attributeName).orElse(null),
						() -> attribute,
						targetIndex,
						attribute.getKey(), false
					);
				}
			}
		}
		final PricesStoragePart priceContainer = containerAccessor.getPriceStoragePart(entityType, entityPrimaryKey);
		for (PriceContract price : priceContainer.getPrices()) {
			if (price.exists()) {
				PriceIndexMutator.priceRemove(
					entityType, executor, targetIndex,
					price.getPriceKey()
				);
			}
		}

		// if target index is empty, remove it completely
		if (targetIndex.isEmpty()) {
			executor.removeIndex(targetIndex.getIndexKey());
		}
	}

	/**
	 * Method removes all facets for passed entity from passed index.
	 */
	private static void removeFacets(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex targetIndex,
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
	 * Returns true if reference schema is configured and indexed.
	 */
	private static boolean isIndexed(@Nonnull ReferenceContract reference) {
		return reference.getReferenceSchema()
			.map(ReferenceSchemaContract::isFilterable)
			.orElse(false);
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
	@Data
	class ExistingReferenceAttributeAccessor implements Supplier<AttributeValue> {
		private final EntityIndexLocalMutationExecutor executor;
		private final String entityType;
		private final int entityPrimaryKey;
		private final ReferenceKey referenceKey;
		private final AttributeKey affectedAttribute;
		private AtomicReference<AttributeValue> memoizedValue;

		@Override
		public AttributeValue get() {
			if (memoizedValue == null) {
				final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
				final ReferencesStoragePart referencesStorageContainer = containerAccessor.getReferencesStoragePart(entityType, entityPrimaryKey);

				this.memoizedValue = new AtomicReference<>(
					of(referencesStorageContainer)
						.flatMap(it ->
							it.getReferencesAsCollection()
								.stream()
								.filter(ref -> Objects.equals(ref.getReferenceKey(), referenceKey))
								.findFirst()
						)
						.flatMap(it -> ofNullable(affectedAttribute.getLocale()).map(loc -> it.getAttributeValue(affectedAttribute.getAttributeName(), loc)).orElseGet(() -> it.getAttributeValue(affectedAttribute.getAttributeName())))
						.filter(Droppable::exists)
						.orElse(null)
				);
			}
			return memoizedValue.get();
		}
	}
}
