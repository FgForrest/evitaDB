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

package io.evitadb.index;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.CatalogRelatedDataStructure;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceIndexReadContract;
import io.evitadb.index.price.PriceRefIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Abstract base class for reduced entity indexes. Reduced indexes are "helper" indexes that maintain primarily
 * bitmaps of primary keys connected to a limited scope view of the data. All memory-expensive objects are
 * referred to and maintained in {@link GlobalEntityIndex} so that they exist solely on the heap.
 *
 * This class contains shared infrastructure for both {@link ReducedEntityIndex} (which handles
 * {@link EntityIndexType#REFERENCED_ENTITY} indexes for individual referenced entities) and
 * {@link ReducedGroupEntityIndex} (which handles {@link EntityIndexType#REFERENCED_GROUP_ENTITY} indexes
 * for entity groups with cardinality tracking).
 *
 * Both subtypes share:
 * - price index management ({@link PriceRefIndex})
 * - reference key resolution
 * - facet, price, and hierarchy node operations
 * - attribute partitioning validation
 *
 * Subtypes must implement all attribute mutation methods (filter, sort, unique) as well as
 * transactional copy methods, because each subtype has different attribute handling semantics.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see ReducedEntityIndex
 * @see ReducedGroupEntityIndex
 */
public abstract class AbstractReducedEntityIndex extends EntityIndex
	implements VoidTransactionMemoryProducer<AbstractReducedEntityIndex>,
	CatalogRelatedDataStructure<AbstractReducedEntityIndex> {

	/**
	 * This part of index collects information about prices of the entities. It provides data that are necessary for
	 * constructing {@link Formula} tree for the constraints related to the prices.
	 */
	@Delegate(types = PriceIndexReadContract.class)
	@Getter private final PriceRefIndex priceIndex;

	/**
	 * Creates a new empty reduced entity index.
	 *
	 * @param primaryKey     the primary key of this index
	 * @param entityType     the type of entity being indexed
	 * @param entityIndexKey the key identifying this index
	 */
	protected AbstractReducedEntityIndex(
		int primaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexKey entityIndexKey
	) {
		super(primaryKey, entityType, entityIndexKey);
		this.priceIndex = new PriceRefIndex(this.getIndexKey().scope());
	}

	/**
	 * Creates a reduced entity index from persisted data.
	 *
	 * @param primaryKey          the primary key of this index
	 * @param entityIndexKey      the key identifying this index
	 * @param version             the version of this index
	 * @param entityIds           bitmap of entity primary keys in this index
	 * @param entityIdsByLanguage entity primary keys grouped by locale
	 * @param attributeIndex      the attribute index
	 * @param priceIndex          the price reference index
	 * @param hierarchyIndex      the hierarchy index
	 * @param facetIndex          the facet index
	 */
	protected AbstractReducedEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey entityIndexKey,
		int version,
		@Nonnull Bitmap entityIds,
		@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull AttributeIndex attributeIndex,
		@Nonnull PriceRefIndex priceIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex
	) {
		super(
			primaryKey, entityIndexKey, version,
			entityIds, entityIdsByLanguage,
			attributeIndex, hierarchyIndex, facetIndex, priceIndex
		);
		this.priceIndex = priceIndex;
	}

	/**
	 * Creates a reduced entity index as a transactional copy that preserves original storage part state.
	 * Used internally by {@link ReducedEntityIndex#createCopyForNewCatalogAttachment(io.evitadb.api.CatalogState)}.
	 *
	 * @param primaryKey                  the primary key of this index
	 * @param indexKey                    the key identifying this index
	 * @param version                     the version of this index
	 * @param entityIds                   transactional bitmap of entity primary keys
	 * @param entityIdsByLanguage         transactional map of entity primary keys by locale
	 * @param attributeIndex              the attribute index
	 * @param hierarchyIndex              the hierarchy index
	 * @param facetIndex                  the facet index
	 * @param originalHierarchyIndexEmpty whether the hierarchy index was originally empty
	 * @param originalAttributeIndexes    original attribute index storage keys
	 * @param originalPriceIndexes        original price index keys
	 * @param originalFacetIndexes        original facet index referenced entities
	 * @param priceIndex                  the price reference index
	 */
	protected AbstractReducedEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey indexKey,
		int version,
		@Nonnull TransactionalBitmap entityIds,
		@Nonnull TransactionalMap<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull AttributeIndex attributeIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex,
		boolean originalHierarchyIndexEmpty,
		@Nonnull Set<AttributeIndexStorageKey> originalAttributeIndexes,
		@Nonnull Set<PriceIndexKey> originalPriceIndexes,
		@Nonnull Set<String> originalFacetIndexes,
		@Nonnull PriceRefIndex priceIndex
	) {
		super(
			primaryKey, indexKey, version, entityIds,
			entityIdsByLanguage, attributeIndex, hierarchyIndex, facetIndex,
			originalHierarchyIndexEmpty,
			originalAttributeIndexes, originalPriceIndexes, originalFacetIndexes
		);
		this.priceIndex = priceIndex;
	}

	/**
	 * Retrieves the reference key associated with the current entity index.
	 * The reference key is derived from the discriminator of the index key.
	 *
	 * @return the non-null {@link ReferenceKey} uniquely identifying a reference within the entity index
	 */
	@Nonnull
	public ReferenceKey getReferenceKey() {
		return getRepresentativeReferenceKey().referenceKey();
	}

	/**
	 * Retrieves a representative reference key associated with the current entity index.
	 * The representative reference key is derived from the discriminator of the index key.
	 *
	 * @return the {@link RepresentativeReferenceKey} if it exists, otherwise null
	 */
	@Nonnull
	public RepresentativeReferenceKey getRepresentativeReferenceKey() {
		return Objects.requireNonNull((RepresentativeReferenceKey) this.indexKey.discriminator());
	}

	@Override
	public void attachToCatalog(@Nullable String entityType, @Nonnull Catalog catalog) {
		this.priceIndex.attachToCatalog(entityType, catalog);
	}

	@Override
	public boolean isEmpty() {
		return super.isEmpty() && this.priceIndex.isPriceIndexEmpty();
	}

	@Override
	public void getModifiedStorageParts(@Nonnull TrappedChanges trappedChanges) {
		super.getModifiedStorageParts(trappedChanges);
		this.priceIndex.getModifiedStorageParts(this.primaryKey, trappedChanges);
	}

	@Override
	public void removeTransactionalMemoryOfReferencedProducers(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		super.removeTransactionalMemoryOfReferencedProducers(transactionalLayer);
		this.priceIndex.removeLayer(transactionalLayer);
	}

	@Override
	public void resetDirty() {
		super.resetDirty();
		this.priceIndex.resetDirty();
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		super.removeTransactionalMemoryOfReferencedProducers(transactionalLayer);
		this.priceIndex.removeLayer(transactionalLayer);
	}

	@Override
	public int addPrice(
		@Nullable ReferenceSchemaContract referenceSchema,
		int entityPrimaryKey,
		int internalPriceId,
		@Nonnull PriceKey priceKey,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		int priceWithoutTax,
		int priceWithTax
	) {
		assertPartitioningIndex(referenceSchema);
		return this.priceIndex.addPrice(
			referenceSchema, entityPrimaryKey, internalPriceId, priceKey, innerRecordHandling, innerRecordId,
			validity, priceWithoutTax, priceWithTax
		);
	}

	@Override
	public void priceRemove(
		@Nullable ReferenceSchemaContract referenceSchema,
		int entityPrimaryKey,
		int internalPriceId,
		@Nonnull PriceKey priceKey,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		int priceWithoutTax,
		int priceWithTax
	) {
		assertPartitioningIndex(referenceSchema);
		this.priceIndex.priceRemove(
			referenceSchema, entityPrimaryKey, internalPriceId, priceKey, innerRecordHandling, innerRecordId,
			validity, priceWithoutTax, priceWithTax
		);
	}

	@Override
	public void addFacet(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId,
		int entityPrimaryKey
	) {
		assertPartitioningIndex(referenceSchema);
		super.addFacet(referenceSchema, referenceKey, groupId, entityPrimaryKey);
	}

	@Override
	public void removeFacet(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		@Nullable Integer groupId,
		int entityPrimaryKey
	) {
		assertPartitioningIndex(referenceSchema);
		super.removeFacet(referenceSchema, referenceKey, groupId, entityPrimaryKey);
	}

	@Override
	public void addNode(int entityPrimaryKey, Integer parentPrimaryKey) {
		throw new GenericEvitaInternalError(
			"Reduced entity indexes are not expected to maintain hierarchical nodes!"
		);
	}

	@Override
	public Integer removeNode(int entityPrimaryKey) {
		throw new GenericEvitaInternalError(
			"Reduced entity indexes are not expected to maintain hierarchical nodes!"
		);
	}

	@Override
	protected boolean isRequireLocaleRemoval() {
		// reduced indexes may not have all the entity locales indexed, because they may contain only reference
		// attributes when index type is set to FOR_FILTERING, so we cannot guarantee removing locale for
		// particular entity primary key
		return false;
	}

	/**
	 * Delegates to {@link EntityIndex#insertFilterAttribute} bypassing any overrides in this class hierarchy.
	 * Used by subtypes that need direct access to the base implementation.
	 */
	protected void delegateInsertFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		super.insertFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	/**
	 * Delegates to {@link EntityIndex#removeFilterAttribute} bypassing any overrides in this class hierarchy.
	 * Used by subtypes that need direct access to the base implementation.
	 */
	protected void delegateRemoveFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		super.removeFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	/**
	 * Delegates to {@link EntityIndex#addDeltaFilterAttribute} bypassing any overrides in this class hierarchy.
	 * Used by subtypes that need direct access to the base implementation.
	 */
	protected void delegateAddDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		super.addDeltaFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	/**
	 * Delegates to {@link EntityIndex#removeDeltaFilterAttribute} bypassing any overrides in this class hierarchy.
	 * Used by subtypes that need direct access to the base implementation.
	 */
	protected void delegateRemoveDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		super.removeDeltaFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	/**
	 * Delegates to {@link EntityIndex#insertSortAttribute} bypassing any overrides in this class hierarchy.
	 * Used by subtypes that need direct access to the base implementation.
	 */
	protected void delegateInsertSortAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		super.insertSortAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	/**
	 * Delegates to {@link EntityIndex#removeSortAttribute} bypassing any overrides in this class hierarchy.
	 * Used by subtypes that need direct access to the base implementation.
	 */
	protected void delegateRemoveSortAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		super.removeSortAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	/**
	 * Delegates to {@link EntityIndex#insertSortAttributeCompound} bypassing any overrides in this class hierarchy.
	 * Used by subtypes that need direct access to the base implementation.
	 */
	protected void delegateInsertSortAttributeCompound(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nonnull Function<String, Class<?>> attributeTypeProvider,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		super.insertSortAttributeCompound(
			entitySchema, referenceSchema, compoundSchemaContract, attributeTypeProvider, locale, value, recordId
		);
	}

	/**
	 * Delegates to {@link EntityIndex#removeSortAttributeCompound} bypassing any overrides in this class hierarchy.
	 * Used by subtypes that need direct access to the base implementation.
	 */
	protected void delegateRemoveSortAttributeCompound(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		super.removeSortAttributeCompound(
			entitySchema, referenceSchema, compoundSchemaContract, locale, value, recordId
		);
	}

	/**
	 * Delegates to {@link EntityIndex#insertUniqueAttribute} bypassing any overrides in this class hierarchy.
	 * Used by subtypes that need direct access to the base implementation.
	 */
	protected void delegateInsertUniqueAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull io.evitadb.dataType.Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		super.insertUniqueAttribute(referenceSchema, attributeSchema, allowedLocales, scope, locale, value, recordId);
	}

	/**
	 * Delegates to {@link EntityIndex#removeUniqueAttribute} bypassing any overrides in this class hierarchy.
	 * Used by subtypes that need direct access to the base implementation.
	 */
	protected void delegateRemoveUniqueAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull io.evitadb.dataType.Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		super.removeUniqueAttribute(referenceSchema, attributeSchema, allowedLocales, scope, locale, value, recordId);
	}

	/**
	 * Validates that the attribute is either a reference attribute or that the reference schema has index level
	 * set to FOR_FILTERING_AND_PARTITIONING. Global entity attributes are indexed only on this particular
	 * indexing level.
	 *
	 * @param referenceSchema the schema contract related to the reference
	 * @param attributeSchema the schema contract for the attribute to be validated
	 */
	protected void assertPartitioningIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema
	) {
		Assert.isPremiseValid(
			referenceSchema != null,
			() -> "The reference schema must be provided index data in reduced entity index!"
		);
		Assert.isPremiseValid(
			!(attributeSchema instanceof EntityAttributeSchemaContract)
				|| referenceSchema.getReferenceIndexType(this.indexKey.scope()) ==
				ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
			() -> "This operation is allowed only for indexes that are used for filtering and partitioning!" +
				" Current index type is: " +
				Objects.requireNonNull(referenceSchema).getReferenceIndexType(this.indexKey.scope())
		);
	}

	/**
	 * Validates that the compound attribute is either a reference attribute compound or that the reference schema
	 * has index level set to FOR_FILTERING_AND_PARTITIONING. Global entity attribute compounds are indexed only
	 * on this particular indexing level.
	 *
	 * @param referenceSchema the schema contract related to the reference
	 * @param compoundSchema  the schema contract for the compound attribute to be validated
	 */
	protected void assertPartitioningIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema
	) {
		Assert.isPremiseValid(
			referenceSchema != null,
			() -> "The reference schema must be provided index data in reduced entity index!"
		);
		Assert.isPremiseValid(
			!(compoundSchema instanceof EntitySortableAttributeCompoundSchemaContract)
				|| referenceSchema.getReferenceIndexType(this.indexKey.scope()) ==
				ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
			() -> "This operation is allowed only for indexes that are used for filtering and partitioning!" +
				" Current index type is: " +
				Objects.requireNonNull(referenceSchema).getReferenceIndexType(this.indexKey.scope())
		);
	}

	/**
	 * Validates that the reference schema has index level set to FOR_FILTERING_AND_PARTITIONING.
	 *
	 * @param referenceSchema the schema contract related to the reference
	 */
	protected void assertPartitioningIndex(
		@Nullable ReferenceSchemaContract referenceSchema
	) {
		Assert.isPremiseValid(
			referenceSchema != null,
			() -> "The reference schema must be provided index data in reduced entity index!"
		);
		Assert.isPremiseValid(
			referenceSchema.getReferenceIndexType(this.indexKey.scope()) ==
				ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
			() -> "This operation is allowed only for indexes that are used for filtering and partitioning!" +
				" Current index type is: " +
				Objects.requireNonNull(referenceSchema).getReferenceIndexType(this.indexKey.scope())
		);
	}

}
