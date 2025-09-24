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

import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.mutation.index.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.index.mutation.index.dataAccess.ExistingDataSupplierFactory;
import io.evitadb.index.mutation.index.dataAccess.ExistingPriceSupplier;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import io.evitadb.store.spi.model.storageParts.accessor.EntityStoragePartAccessor;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.evitadb.index.mutation.index.dataAccess.ExistingAttributeValueSupplier.NO_EXISTING_VALUE_SUPPLIER;
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
	 * Method executes logic in `referenceIndexConsumer` in new specific type of {@link EntityIndex} of type
	 * {@link EntityIndexType#REFERENCED_ENTITY} for all entities that are currently referenced.
	 */
	static void executeWithReferenceIndexes(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull BiConsumer<ReferenceSchemaContract, EntityIndex> referenceIndexConsumer,
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
	 * Method executes logic in `referenceIndexConsumer` in new specific type of {@link EntityIndex} of type
	 * {@link EntityIndexType#REFERENCED_ENTITY} for all entities that are currently referenced.
	 */
	static void executeWithReferenceIndexes(
		@Nonnull ReferenceIndexType indexType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull BiConsumer<ReferenceSchemaContract, EntityIndex> referenceIndexConsumer,
		@Nonnull Predicate<ReferenceContract> referencePredicate,
		boolean referencePresenceExpected
	) {
		final Scope scope = executor.getScope();
		final ReferencesStoragePart referencesStorageContainer = executor.getReferencesStoragePart();
		ReferenceSchemaContract referenceSchema = null;
		for (ReferenceContract reference : referencesStorageContainer.getReferences()) {
			referenceSchema = referenceSchema == null || !Objects.equals(referenceSchema.getName(), reference.getReferenceName()) ?
				reference.getReferenceSchemaOrThrow() : referenceSchema;
			if (reference.exists() && isIndexedReferenceFor(referenceSchema, scope, indexType) && referencePredicate.test(reference)) {
				final RepresentativeReferenceKey rrk = executor.getRepresentativeReferenceKey(reference.getReferenceKey(), referencePresenceExpected);
				final ReducedEntityIndex targetIndex = getOrCreateReferencedEntityIndex(executor, rrk, scope);
				referenceIndexConsumer.accept(referenceSchema, targetIndex);
			}
		}
	}

	/**
	 * Returns appropriate {@link EntityIndex} for passed `referenceKey`. Method returns
	 * {@link EntityIndexType#REFERENCED_ENTITY} index.
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
	 * Method allows to process all attribute mutations in referenced entities of the entity. Method uses
	 * {@link EntityIndexLocalMutationExecutor#updateAttribute(ReferenceSchemaContract, AttributeMutation, AttributeAndCompoundSchemaProvider, ExistingAttributeValueSupplier, EntityIndex, boolean, boolean)}
	 * to do that.
	 */
	static void attributeUpdate(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ExistingDataSupplierFactory attributeSupplierFactory,
		@Nonnull ReferencedTypeEntityIndex referenceTypeIndex,
		@Nonnull ReducedEntityIndex referenceIndex,
		@Nonnull RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeMutation attributeMutation
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(referenceKey.referenceName());

		// use different existing attribute value accessor - attributes needs to be looked up in ReferencesStoragePart
		final ExistingAttributeValueSupplier existingValueAccessorFactory = attributeSupplierFactory.getReferenceAttributeValueSupplier(referenceKey);

		// we access attributes and sortable compounds from the reference schema
		final ReferenceSchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider = new ReferenceSchemaAttributeAndCompoundSchemaProvider(
			entitySchema, referenceSchema
		);

		executor.executeWithDifferentPrimaryKeyToIndex(
			indexType -> referenceIndex.getPrimaryKey(),
			() -> executor.updateAttribute(
				referenceSchema,
				attributeMutation,
				attributeSchemaProvider,
				existingValueAccessorFactory,
				referenceTypeIndex,
				false,
				false
			)
		);

		executeWithProperPrimaryKey(
			executor, referenceKey,
			attributeMutation.getAttributeKey().attributeName(),
			attributeSchemaProvider::getAttributeSchema,
			() -> executor.updateAttribute(
				referenceSchema,
				attributeMutation,
				attributeSchemaProvider,
				existingValueAccessorFactory,
				referenceIndex,
				false,
				true
			)
		);
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
		@Nonnull ReducedEntityIndex referenceIndex,
		@Nonnull RepresentativeReferenceKey representativeReferenceKey,
		@Nullable Integer groupId,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		// we need to index reference index primary key into the reference type index
		final int pkForReferenceTypeIndex = referenceIndex.getPrimaryKey();
		final int referencedEntityPrimaryKey = representativeReferenceKey.primaryKey();
		if (referenceTypeIndex.insertPrimaryKeyIfMissing(pkForReferenceTypeIndex, referencedEntityPrimaryKey) && undoActionConsumer != null) {
			undoActionConsumer.accept(() -> referenceTypeIndex.removePrimaryKey(pkForReferenceTypeIndex, referencedEntityPrimaryKey));
		}

		// add facet to global index
		final ReferenceKey referenceKey = representativeReferenceKey.referenceKey();
		addFacetToIndex(entityIndex, referenceKey, groupId, executor, entityPrimaryKey, undoActionConsumer);

		// index all reference attributes to the reference type index
		final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(representativeReferenceKey.referenceName());

		// we access attributes and sortable compounds from the reference schema
		final ReferenceSchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider = new ReferenceSchemaAttributeAndCompoundSchemaProvider(
			entitySchema, referenceSchema
		);

		final ExistingAttributeValueSupplier referenceAttributeValueSupplier = existingDataSupplierFactory.getReferenceAttributeValueSupplier(representativeReferenceKey);
		executor.executeWithDifferentPrimaryKeyToIndex(
			indexType -> pkForReferenceTypeIndex,
			() -> referenceAttributeValueSupplier
				.getAttributeValues()
				.forEach(attributeValue -> AttributeIndexMutator.executeAttributeUpsert(
					executor,
					referenceSchema,
					attributeSchemaProvider,
					NO_EXISTING_VALUE_SUPPLIER,
					referenceTypeIndex,
					attributeValue.key(),
					Objects.requireNonNull(attributeValue.value()),
					false,
					false,
					undoActionConsumer
				))
		);

		// we need to index entity primary key into the referenced entity index
		if (referenceIndex.insertPrimaryKeyIfMissing(entityPrimaryKey)) {
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(() -> referenceIndex.removePrimaryKey(entityPrimaryKey));
			}
			// we need to index all previously added global entity attributes, prices and facets
			indexAllExistingData(
				executor, referenceIndex,
				entitySchema, referenceSchema,
				entityPrimaryKey,
				existingDataSupplierFactory,
				undoActionConsumer
			);
		}

		// add facet to reference index
		addFacetToIndex(
			referenceIndex, referenceKey, groupId, executor, entityPrimaryKey, undoActionConsumer
		);
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
		@Nonnull ReducedEntityIndex referenceIndex,
		@Nonnull RepresentativeReferenceKey representativeReferenceKey,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		// we need to remove referenced entity primary key from the reference type index
		final int pkForReferenceTypeIndex = referenceIndex.getPrimaryKey();
		final int referencedEntityPrimaryKey = representativeReferenceKey.primaryKey();
		if (referenceTypeIndex.removePrimaryKey(pkForReferenceTypeIndex, referencedEntityPrimaryKey) && undoActionConsumer != null) {
			undoActionConsumer.accept(() -> referenceTypeIndex.insertPrimaryKeyIfMissing(pkForReferenceTypeIndex, referencedEntityPrimaryKey));
		}

		// remove facet from global index
		final ReferenceKey referenceKey = representativeReferenceKey.referenceKey();
		removeFacetInIndex(entityIndex, referenceKey, executor, entityPrimaryKey, undoActionConsumer);

		// remove all reference attributes to the reference type index
		final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(representativeReferenceKey.referenceName());

		// we access attributes and sortable compounds from the reference schema
		final ReferenceSchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider = new ReferenceSchemaAttributeAndCompoundSchemaProvider(
			entitySchema, referenceSchema
		);
		final ExistingAttributeValueSupplier referenceAttributeValueSupplier = existingDataSupplierFactory.getReferenceAttributeValueSupplier(representativeReferenceKey);
		executor.executeWithDifferentPrimaryKeyToIndex(
			indexType -> pkForReferenceTypeIndex,
			() -> referenceAttributeValueSupplier
				.getAttributeValues()
				.forEach(attributeValue -> AttributeIndexMutator.executeAttributeRemoval(
					executor,
					referenceSchema,
					attributeSchemaProvider,
					referenceAttributeValueSupplier,
					referenceTypeIndex,
					attributeValue.key(),
					false,
					false,
					undoActionConsumer
				))
		);

		// we need to remove entity primary key from the referenced entity index
		if (referenceIndex.removePrimaryKey(entityPrimaryKey)) {
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(() -> referenceIndex.insertPrimaryKeyIfMissing(entityPrimaryKey));
			}

			// remove all entity attributes and prices
			removeAllExistingData(
				executor, referenceIndex,
				entitySchema,
				referenceSchema,
				entityPrimaryKey,
				existingDataSupplierFactory,
				undoActionConsumer
			);
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
		if (isFacetedReference(index.getIndexKey().scope(), referenceKey, executor)) {
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
		@Nonnull EntityIndexLocalMutationExecutor executor
	) {
		final ReferenceContract existingReference = executor.getReferencesStoragePart()
			.findReferenceOrThrowException(referenceKey);
		final Optional<Integer> existingGroupId = existingReference.getGroup()
			.filter(Droppable::exists)
			.map(EntityReferenceContract::getPrimaryKey);

		if (isFacetedReference(index.getIndexKey().scope(), referenceKey, executor)) {
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
		int entityPrimaryKey,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		if (isFacetedReference(index.getIndexKey().scope(), referenceKey, executor)) {
			final ReferenceContract existingReference = executor.getReferencesStoragePart()
				.findReferenceOrThrowException(referenceKey);

			removeFacetInIndex(index, referenceKey, entityPrimaryKey, existingReference, undoActionConsumer);
		}
	}

	/**
	 * Removes existing facet for the passed `referenceKey` and `entityPrimaryKey` but only if the referenced entity
	 * type is marked as `faceted` in reference schema.
	 */
	static void removeFacetInIndex(
		@Nonnull EntityIndex index,
		@Nonnull ReferenceKey referenceKey,
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
	}

	/**
	 * Removes group in existing facet in reference to the passed `referenceKey` and `entityPrimaryKey` but only if
	 * the referenced entity type is marked as `faceted` in reference schema.
	 */
	static void removeFacetGroupInIndex(
		int entityPrimaryKey,
		@Nonnull EntityIndex index,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull EntityIndexLocalMutationExecutor executor
	) {
		final ReferenceContract existingReference = executor.getReferencesStoragePart()
			.findReferenceOrThrowException(referenceKey);
		isPremiseValid(
			existingReference.getGroup().filter(Droppable::exists).isPresent(),
			"Group is expected to be non-null when RemoveReferenceGroupMutation is about to be executed."
		);
		final int groupId = existingReference.getGroup().map(GroupEntityReference::getPrimaryKey).orElseThrow();

		if (isFacetedReference(index.getIndexKey().scope(), referenceKey, executor)) {
			index.removeFacet(
				referenceKey,
				groupId,
				entityPrimaryKey
			);
			index.addFacet(referenceKey, null, entityPrimaryKey);
		}
	}

	/**
	 * Returns true if reference schema is configured and indexed for filtering or partitioning.
	 */
	static boolean isIndexedReferenceFor(@Nonnull ReferenceSchemaContract referenceSchema, @Nonnull Scope scope, @Nonnull ReferenceIndexType referenceIndexType) {
		return referenceSchema.getReferenceIndexType(scope).ordinal() >= referenceIndexType.ordinal();
	}

	/**
	 * Returns true if reference schema is configured and indexed for filtering or partitioning.
	 */
	static boolean isIndexedReferenceForFiltering(@Nonnull ReferenceContract reference, @Nonnull Scope scope) {
		return reference.getReferenceSchema()
			.map(it -> it.getReferenceIndexType(scope) != ReferenceIndexType.NONE)
			.orElse(false);
	}

	/**
	 * Returns true if reference schema is configured and indexed for filtering and partitioning.
	 */
	static boolean isIndexedReferenceForFilteringAndPartitioning(@Nonnull ReferenceSchemaContract referenceSchemaContract, @Nonnull Scope scope) {
		return referenceSchemaContract.getReferenceIndexType(scope) == ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING;
	}

	/**
	 * Returns TRUE if `referencedEntity` is marked as `faceted` in the entity schema.
	 */
	static boolean isFacetedReference(
		@Nonnull Scope scope,
		@Nonnull ReferenceKey referencedEntity,
		@Nonnull EntityIndexLocalMutationExecutor executor
	) {
		final ReferenceSchemaContract referenceSchema = executor.getEntitySchema().getReferenceOrThrowException(referencedEntity.referenceName());
		return referenceSchema.isFacetedInScope(scope);
	}

	/**
	 * Method indexes all sortable attribute compounds of the entity in passed index. When the `locale` parameter is
	 * not null, only the compounds for the given locale are created. Otherwise, all compounds that don't contain
	 * localized attribute value are created.
	 */
	static void insertInitialSuiteOfSortableAttributeCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReducedEntityIndex targetIndex,
		@Nullable Locale locale,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final EntityIndexKey indexKey = targetIndex.getIndexKey();
		final Serializable indexKeyDiscriminator = indexKey.discriminator();
		Assert.isPremiseValid(
			indexKeyDiscriminator instanceof RepresentativeReferenceKey,
			"Entity index key must be of type ReferenceKey to index attributes in reference index!"
		);
		final RepresentativeReferenceKey referenceKey = ((RepresentativeReferenceKey) indexKeyDiscriminator);

		// if the reference is indexed for filtering and partitioning, we need to index attributes from the entity schema
		if (isIndexedReferenceFor(referenceSchema, targetIndex.getIndexKey().scope(), ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)) {
			final EntitySchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider = new EntitySchemaAttributeAndCompoundSchemaProvider(entitySchema);
			final ExistingAttributeValueSupplier entityAttributeValueSupplier = existingDataSupplierFactory.getEntityAttributeValueSupplier();

			AttributeIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
				executor, referenceSchema, targetIndex, locale, attributeSchemaProvider, entitySchema,
				entityAttributeValueSupplier, undoActionConsumer
			);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(
					() -> AttributeIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
						executor, referenceSchema, targetIndex, locale, attributeSchemaProvider,
						entitySchema, entityAttributeValueSupplier, undoActionConsumer
					)
				);
			}
		}

		final ExistingAttributeValueSupplier referenceAttributeValueSupplier = existingDataSupplierFactory.getReferenceAttributeValueSupplier(referenceKey);

		// and the second, we access attributes and sortable compounds from the reference schema
		final ReferenceSchemaAttributeAndCompoundSchemaProvider referenceSchemaAttributeProvider = new ReferenceSchemaAttributeAndCompoundSchemaProvider(
			entitySchema, referenceSchema
		);

		AttributeIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
			executor, referenceSchema, targetIndex, locale, referenceSchemaAttributeProvider, referenceSchema,
			referenceAttributeValueSupplier, undoActionConsumer
		);
		if (undoActionConsumer != null) {
			undoActionConsumer.accept(
				() -> AttributeIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
					executor, referenceSchema, targetIndex, locale, referenceSchemaAttributeProvider, referenceSchema,
					referenceAttributeValueSupplier, undoActionConsumer
				)
			);
		}
	}

	/**
	 * Method removes all sortable attribute compounds of the entity from passed index.
	 */
	static void removeEntireSuiteOfSortableAttributeCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReducedEntityIndex targetIndex,
		@Nullable Locale locale,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final EntityIndexKey indexKey = targetIndex.getIndexKey();
		final Serializable indexKeyDiscriminator = indexKey.discriminator();
		Assert.isPremiseValid(
			indexKeyDiscriminator instanceof RepresentativeReferenceKey,
			"Entity index key must be of type RepresentativeReferenceKey to index attributes in reference index!"
		);
		final RepresentativeReferenceKey referenceKey = ((RepresentativeReferenceKey) indexKeyDiscriminator);

		// if the reference is indexed for filtering and partitioning, we need to index attributes from the entity schema
		if (isIndexedReferenceFor(referenceSchema, targetIndex.getIndexKey().scope(), ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)) {
			// first, we access attributes and sortable compounds from the entity schema
			final EntitySchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider = new EntitySchemaAttributeAndCompoundSchemaProvider(entitySchema);
			final ExistingAttributeValueSupplier entityAttributeValueSupplier = existingDataSupplierFactory.getEntityAttributeValueSupplier();

			AttributeIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
				executor, referenceSchema, targetIndex, locale, attributeSchemaProvider, entitySchema,
				entityAttributeValueSupplier, undoActionConsumer
			);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(
					() -> AttributeIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
						executor, referenceSchema, targetIndex, locale, attributeSchemaProvider, entitySchema,
						entityAttributeValueSupplier, undoActionConsumer
					)
				);
			}
		}

		// and the second, we access attributes and sortable compounds from the reference schema
		final ReferenceSchemaAttributeAndCompoundSchemaProvider referenceSchemaAttributeProvider = new ReferenceSchemaAttributeAndCompoundSchemaProvider(
			entitySchema, referenceSchema
		);
		final ExistingAttributeValueSupplier referenceAttributeValueSupplier = existingDataSupplierFactory.getReferenceAttributeValueSupplier(referenceKey);
		AttributeIndexMutator.removeEntireSuiteOfSortableAttributeCompounds(
			executor, referenceSchema, targetIndex, locale, referenceSchemaAttributeProvider, referenceSchema,
			referenceAttributeValueSupplier, undoActionConsumer
		);
		if (undoActionConsumer != null) {
			undoActionConsumer.accept(
				() -> AttributeIndexMutator.insertInitialSuiteOfSortableAttributeCompounds(
					executor, referenceSchema, targetIndex, locale, referenceSchemaAttributeProvider, referenceSchema,
					referenceAttributeValueSupplier, undoActionConsumer
				)
			);
		}
	}

	/**
	 * Executes a given action with the correct primary key for indexing, based on the attribute schema type.
	 * If the attribute schema represents a type assignable from {@code ReferencedEntityPredecessor},
	 * the execution is performed under a different primary key derived from the reference key.
	 * Otherwise, the supplied action is executed as is.
	 *
	 * @param executor                The {@link EntityIndexLocalMutationExecutor} used to manage and execute mutations.
	 * @param referenceKey            The {@link ReferenceKey} representing the reference entity and its primary key.
	 * @param attributeName           The name of the attribute being processed.
	 * @param attributeSchemaProvider A function that provides the {@link AttributeSchema} for a given attribute name.
	 * @param lambda                  The action to execute, typically an indexing operation or similar procedure.
	 */
	private static void executeWithProperPrimaryKey(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull RepresentativeReferenceKey referenceKey,
		@Nonnull String attributeName,
		@Nonnull Function<String, AttributeSchema> attributeSchemaProvider,
		@Nonnull Runnable lambda
	) {
		// we need to index entity primary key into the referenced entity index for all attributes
		final AttributeSchema attributeSchema = attributeSchemaProvider.apply(attributeName);
		if (ReferencedEntityPredecessor.class.isAssignableFrom(attributeSchema.getPlainType())) {
			executor.executeWithDifferentPrimaryKeyToIndex(
				indexType -> referenceKey.primaryKey(), lambda
			);
		} else {
			lambda.run();
		}
	}

	/**
	 * Method indexes all existing indexable data for passed entity / referenced entity combination in passed indexes.
	 */
	private static void indexAllExistingData(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReducedEntityIndex targetIndex,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		int entityPrimaryKey,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final String entityType = entitySchema.getName();

		indexAllFacets(executor, targetIndex, entityPrimaryKey, undoActionConsumer);

		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
		final EntityBodyStoragePart entityCnt = containerAccessor.getEntityStoragePart(entityType, entityPrimaryKey, EntityExistence.MUST_EXIST);

		for (Locale locale : entityCnt.getLocales()) {
			executor.upsertEntityLanguageInTargetIndex(
				entityPrimaryKey, locale, targetIndex,
				entitySchema, referenceSchema,
				existingDataSupplierFactory, undoActionConsumer
			);
		}

		indexAllPrices(executor, referenceSchema, targetIndex, existingDataSupplierFactory.getPriceSupplier(), undoActionConsumer);
		indexAllAttributes(executor, referenceSchema, targetIndex, existingDataSupplierFactory, undoActionConsumer);
		insertInitialSuiteOfSortableAttributeCompounds(executor, referenceSchema, targetIndex, null, existingDataSupplierFactory, undoActionConsumer);
	}

	/**
	 * Method indexes all facets data for passed entity in passed index.
	 */
	private static void indexAllFacets(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex targetIndex,
		int entityPrimaryKey,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final ReferencesStoragePart referencesStorageContainer = executor.getReferencesStoragePart();

		for (ReferenceContract reference : referencesStorageContainer.getReferences()) {
			final ReferenceKey referenceKey = reference.getReferenceKey();
			final Optional<GroupEntityReference> groupReference = reference.getGroup();
			if (reference.exists() && isFacetedReference(targetIndex.getIndexKey().scope(), referenceKey, executor)) {
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
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReducedEntityIndex targetIndex,
		@Nonnull ExistingPriceSupplier existingPriceSupplier,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		if (isIndexedReferenceFor(referenceSchema, targetIndex.getIndexKey().scope(), ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)) {
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
	 * Method indexes all attributes of the entity in passed index.
	 */
	private static void indexAllAttributes(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReducedEntityIndex targetIndex,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final EntityIndexKey indexKey = targetIndex.getIndexKey();
		final Serializable indexKeyDiscriminator = indexKey.discriminator();
		Assert.isPremiseValid(
			indexKeyDiscriminator instanceof RepresentativeReferenceKey,
			"Entity index key must be of type ReferenceKey to index attributes in reference index!"
		);
		final RepresentativeReferenceKey rrk = ((RepresentativeReferenceKey) indexKeyDiscriminator);

		// if the reference is indexed for filtering and partitioning, we need to index attributes from the entity schema
		if (isIndexedReferenceFor(referenceSchema, targetIndex.getIndexKey().scope(), ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)) {
			final EntitySchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider = new EntitySchemaAttributeAndCompoundSchemaProvider(
				entitySchema
			);

			existingDataSupplierFactory.getEntityAttributeValueSupplier()
				.getAttributeValues()
				.forEach(attribute ->
					AttributeIndexMutator.executeAttributeUpsert(
						executor,
						referenceSchema,
						attributeSchemaProvider,
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

		// and the second, we access attributes and sortable compounds from the reference schema
		final ReferenceSchemaAttributeAndCompoundSchemaProvider referenceSchemaAttributeProvider = new ReferenceSchemaAttributeAndCompoundSchemaProvider(
			entitySchema, referenceSchema
		);

		existingDataSupplierFactory.getReferenceAttributeValueSupplier(rrk)
			.getAttributeValues()
			.forEach(attribute ->
				executeWithProperPrimaryKey(
					executor, rrk, attribute.key().attributeName(),
					referenceSchemaAttributeProvider::getAttributeSchema,
					() -> AttributeIndexMutator.executeAttributeUpsert(
						executor,
						referenceSchema,
						referenceSchemaAttributeProvider,
						NO_EXISTING_VALUE_SUPPLIER,
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
	 * Method removes all indexed data for passed entity / referenced entity combination in passed indexes.
	 */
	private static void removeAllExistingData(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReducedEntityIndex targetIndex,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		int entityPrimaryKey,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final String entityType = entitySchema.getName();
		removeAllFacets(executor, targetIndex, entityPrimaryKey, undoActionConsumer);

		final EntityStoragePartAccessor containerAccessor = executor.getContainerAccessor();
		final EntityBodyStoragePart entityCnt = containerAccessor.getEntityStoragePart(entityType, entityPrimaryKey, EntityExistence.MUST_EXIST);

		for (Locale locale : entityCnt.getLocales()) {
			executor.removeEntityLanguageInTargetIndex(
				entityPrimaryKey, locale, targetIndex, entitySchema, referenceSchema,
				existingDataSupplierFactory,
				undoActionConsumer
			);
		}

		removeAllPrices(executor, referenceSchema, targetIndex, existingDataSupplierFactory.getPriceSupplier(), undoActionConsumer);
		removeAllAttributes(executor, referenceSchema, targetIndex, existingDataSupplierFactory, undoActionConsumer);
		removeEntireSuiteOfSortableAttributeCompounds(executor, referenceSchema, targetIndex, null, existingDataSupplierFactory, undoActionConsumer);
	}

	/**
	 * Method removes all facets for passed entity from passed index.
	 */
	private static void removeAllFacets(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex targetIndex,
		int entityPrimaryKey,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final ReferencesStoragePart referencesStorageContainer = executor.getReferencesStoragePart();

		for (ReferenceContract reference : referencesStorageContainer.getReferences()) {
			final ReferenceKey referenceKey = reference.getReferenceKey();
			if (reference.exists() && isFacetedReference(targetIndex.getIndexKey().scope(), referenceKey, executor)) {
				removeFacetInIndex(targetIndex, referenceKey, entityPrimaryKey, reference, undoActionConsumer);
			}
		}
	}

	/**
	 * Method removes all prices of the entity from passed index.
	 */
	private static void removeAllPrices(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReducedEntityIndex targetIndex,
		@Nonnull ExistingPriceSupplier existingPriceSupplier,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		if (isIndexedReferenceFor(referenceSchema, targetIndex.getIndexKey().scope(), ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)) {
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
	 * Method removes all attributes of the entity from passed index.
	 */
	private static void removeAllAttributes(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReducedEntityIndex targetIndex,
		@Nonnull ExistingDataSupplierFactory existingDataSupplierFactory,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final EntityIndexKey indexKey = targetIndex.getIndexKey();
		final Serializable indexKeyDiscriminator = indexKey.discriminator();
		Assert.isPremiseValid(
			indexKeyDiscriminator instanceof RepresentativeReferenceKey,
			"Entity index key must be of type RepresentativeReferenceKey to index attributes in reference index!"
		);
		final RepresentativeReferenceKey referenceKey = ((RepresentativeReferenceKey) indexKeyDiscriminator);

		// if the reference is indexed for filtering and partitioning, we need to index attributes from the entity schema
		if (isIndexedReferenceFor(referenceSchema, targetIndex.getIndexKey().scope(), ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)) {
			// first, we access attributes and sortable compounds from the entity schema
			final EntitySchemaAttributeAndCompoundSchemaProvider attributeSchemaProvider = new EntitySchemaAttributeAndCompoundSchemaProvider(entitySchema);

			existingDataSupplierFactory.getEntityAttributeValueSupplier()
				.getAttributeValues()
				.forEach(attribute ->
					AttributeIndexMutator.executeAttributeRemoval(
						executor,
						referenceSchema,
						attributeSchemaProvider,
						existingDataSupplierFactory.getEntityAttributeValueSupplier(),
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

		final ExistingAttributeValueSupplier referenceAttributeValueSupplier = existingDataSupplierFactory.getReferenceAttributeValueSupplier(referenceKey);
		referenceAttributeValueSupplier
			.getAttributeValues()
			.forEach(attribute ->
				executeWithProperPrimaryKey(
					executor, referenceKey, attribute.key().attributeName(),
					referenceSchemaAttributeProvider::getAttributeSchema,
					() -> AttributeIndexMutator.executeAttributeRemoval(
						executor,
						referenceSchema,
						referenceSchemaAttributeProvider,
						referenceAttributeValueSupplier,
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
