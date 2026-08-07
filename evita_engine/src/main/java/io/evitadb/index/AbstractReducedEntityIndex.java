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
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.ReferenceAttributeIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.component.PriceIndexComponent;
import io.evitadb.index.component.loader.AttributeIndexLoader;
import io.evitadb.index.component.loader.FacetIndexLoader;
import io.evitadb.index.component.loader.HierarchyIndexLoader;
import io.evitadb.index.component.loader.IndexReloadPlan;
import io.evitadb.index.component.loader.PriceRefIndexLoader;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.price.PriceIndexReadContract;
import io.evitadb.index.price.PriceRefIndex;
import io.evitadb.index.price.PriceSuperIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencyRefIndexStoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.VMLayout;
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
	implements VoidTransactionMemoryProducer<AbstractReducedEntityIndex> {

	/**
	 * This part of index collects information about prices of the entities. It provides data that are necessary for
	 * constructing {@link Formula} tree for the constraints related to the prices.
	 */
	@Delegate(types = PriceIndexReadContract.class)
	@Getter private final PriceRefIndex priceIndex;

	/**
	 * Creates a new empty reduced entity index.
	 *
	 * This constructor intentionally leaves the change-detection baseline empty; terminal
	 * subclasses (`ReducedEntityIndex`, `ReducedGroupEntityIndex`) must call
	 * `captureOriginalsFromComponents()` after registering their own subclass-owned components.
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
		addComponent(new PriceIndexComponent(this.priceIndex));
	}

	/**
	 * Creates a reduced entity index from persisted data.
	 *
	 * This constructor intentionally leaves the change-detection baseline empty; terminal
	 * subclasses (`ReducedEntityIndex`, `ReducedGroupEntityIndex`) must call
	 * `captureOriginalsFromComponents()` after registering their own subclass-owned components.
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
		@Nonnull ReferenceAttributeIndex attributeIndex,
		@Nonnull PriceRefIndex priceIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex
	) {
		super(
			primaryKey, entityIndexKey, version,
			entityIds, entityIdsByLanguage,
			attributeIndex, hierarchyIndex, facetIndex
		);
		this.priceIndex = priceIndex;
		addComponent(new PriceIndexComponent(this.priceIndex));
		// baseline capture is deferred to terminal subclasses (ReducedEntityIndex /
		// ReducedGroupEntityIndex) so it runs after every subclass-owned component is registered
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
	public boolean isEmpty() {
		return super.isEmpty() && this.priceIndex.isPriceIndexEmpty();
	}

	/**
	 * Returns the heap this base occupies, in bytes — everything a reduced index inherits, so a subclass adds only
	 * what it declares itself.
	 *
	 * A reduced index's {@link PriceRefIndex} stores **the very instances** the collection's super index holds; it is
	 * charged here for the spine that references them and not for the bodies, which the super index owns. That
	 * ruling is made inside the price index itself, so nothing is needed here beyond calling it.
	 *
	 * @param ownFieldBytes the field bytes the concrete subclass adds
	 * @return the owned heap footprint of the inherited state, in bytes, including alignment padding
	 */
	protected final long getReducedBaseHeapSizeInBytes(long ownFieldBytes) {
		final VMLayout layout = VMLayout.current();
		// the priceIndex slot
		return getBaseHeapSizeInBytes(layout.referenceSize() + ownFieldBytes)
			+ this.priceIndex.getHeapSizeInBytes()
			// the price component this class registers, holding the price index alone
			+ layout.sizeOfObject(layout.referenceSize());
	}

	@Nonnull
	@Override
	protected Class<? extends StoragePart> getPriceRootStoragePartType() {
		return PriceListAndCurrencyRefIndexStoragePart.class;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// the price index is removed by the component-loop inside the super call — no extra hop
		super.removeTransactionalMemoryOfReferencedProducers(transactionalLayer);
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
		int priceWithTax,
		@Nonnull PriceSuperIndex superPriceIndex
	) {
		assertPartitioningIndex(referenceSchema);
		return this.priceIndex.addPrice(
			referenceSchema, entityPrimaryKey, internalPriceId, priceKey, innerRecordHandling, innerRecordId,
			validity, priceWithoutTax, priceWithTax, superPriceIndex
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
		int priceWithTax,
		@Nonnull PriceSuperIndex superPriceIndex
	) {
		assertPartitioningIndex(referenceSchema);
		this.priceIndex.priceRemove(
			referenceSchema, entityPrimaryKey, internalPriceId, priceKey, innerRecordHandling, innerRecordId,
			validity, priceWithoutTax, priceWithTax, superPriceIndex
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
		int recordId,
		boolean foldedUnique
	) {
		super.insertFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId, foldedUnique);
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
		int recordId,
		boolean foldedUnique
	) {
		super.addDeltaFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId, foldedUnique);
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
	protected io.evitadb.index.attribute.AttributeIndex.UniquenessEnforcement delegateInsertUniqueAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull io.evitadb.dataType.Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		return super.insertUniqueAttribute(referenceSchema, attributeSchema, allowedLocales, scope, locale, value, recordId);
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

	/**
	 * Registers the four reload-side loaders shared by every reduced-index subclass: attribute,
	 * reference-price, hierarchy, and facet. Mirrors `EntityIndex.registerBaseComponents()` on
	 * the read side. Subclasses call this from their `reloadPlan()` static initializer and then
	 * append their subclass-owned loaders (cardinality, histogram, group-cardinality) before
	 * binding the finalizer.
	 *
	 * Order matters for deterministic reload sequencing: attribute first (it sizes the maps via
	 * per-type counts), then prices (super or ref depending on caller), then hierarchy, then
	 * facet.
	 *
	 * @param builder the in-progress plan builder to append to
	 * @return the same `builder` for chaining
	 */
	@Nonnull
	protected static IndexReloadPlan.Builder appendCommon(@Nonnull IndexReloadPlan.Builder builder) {
		return builder
			.add(new AttributeIndexLoader())
			.add(new PriceRefIndexLoader())
			.add(new HierarchyIndexLoader())
			.add(new FacetIndexLoader());
	}

}
