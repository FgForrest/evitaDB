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

package io.evitadb.index;

import io.evitadb.api.CatalogState;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.core.Catalog;
import io.evitadb.core.CatalogRelatedDataStructure;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceIndexContract;
import io.evitadb.index.price.PriceRefIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.utils.StringUtils;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reduced entity index is a "helper" index that maintains primarily bitmaps of primary keys that are connected to
 * a limited scope view of the data. All memory expensive objects are referred and maintained in {@link GlobalEntityIndex}
 * so that it's ensured they exist solely on the heap.
 *
 * Reduced indexes are used for handling queries that target {@link ReferenceContract}
 * of the entities. In such case we may prefer using data from reduced entity index because it may substantially limit
 * the amount of operations to answer the query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReducedEntityIndex extends EntityIndex
	implements VoidTransactionMemoryProducer<ReducedEntityIndex>, CatalogRelatedDataStructure<ReducedEntityIndex> {
	/**
	 * This part of index collects information about prices of the entities. It provides data that are necessary for
	 * constructing {@link Formula} tree for the constraints related to the prices.
	 */
	@Delegate(types = PriceIndexContract.class)
	@Getter private final PriceRefIndex priceIndex;

	public ReducedEntityIndex(
		int primaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexKey entityIndexKey
	) {
		super(primaryKey, entityType, entityIndexKey);
		this.priceIndex = new PriceRefIndex(this.getIndexKey().scope());
	}

	public ReducedEntityIndex(
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

	public ReducedEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey indexKey,
		int version,
		@Nonnull TransactionalBitmap entityIds,
		@Nonnull TransactionalMap<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull AttributeIndex attributeIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex,
		boolean originalHierarchyIndexEmpty,
		@Nonnull Integer originalInternalPriceIdSequence,
		@Nonnull Set<AttributeIndexStorageKey> originalAttributeIndexes,
		@Nonnull Set<PriceIndexKey> originalPriceIndexes,
		@Nonnull Set<String> originalFacetIndexes,
		@Nonnull PriceRefIndex priceIndex
	) {
		super(
			primaryKey, indexKey, version, entityIds,
			entityIdsByLanguage, attributeIndex, hierarchyIndex, facetIndex,
			originalHierarchyIndexEmpty, originalInternalPriceIdSequence,
			originalAttributeIndexes, originalPriceIndexes, originalFacetIndexes
		);
		this.priceIndex = priceIndex;
	}

	@Override
	public void attachToCatalog(@Nullable String entityType, @Nonnull Catalog catalog) {
		this.priceIndex.attachToCatalog(entityType, catalog);
	}

	@Nonnull
	@Override
	public ReducedEntityIndex createCopyForNewCatalogAttachment(@Nonnull CatalogState catalogState) {
		return new ReducedEntityIndex(
			this.primaryKey, this.indexKey, this.version,
			this.entityIds, this.entityIdsByLanguage,
			this.attributeIndex,
			this.hierarchyIndex,
			this.facetIndex,
			this.originalHierarchyIndexEmpty,
			this.originalInternalPriceIdSequence,
			this.originalAttributeIndexes,
			this.originalPriceIndexes,
			this.originalFacetIndexes,
			this.priceIndex.createCopyForNewCatalogAttachment(catalogState)
		);
	}

	@Override
	public boolean isEmpty() {
		return super.isEmpty() && this.priceIndex.isPriceIndexEmpty();
	}

	@Nonnull
	@Override
	public Collection<StoragePart> getModifiedStorageParts() {
		final Collection<StoragePart> dirtyList = super.getModifiedStorageParts();
		dirtyList.addAll(this.priceIndex.getModifiedStorageParts(this.primaryKey));
		return dirtyList;
	}

	@Override
	public void removeTransactionalMemoryOfReferencedProducers(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		super.removeTransactionalMemoryOfReferencedProducers(transactionalLayer);
		this.priceIndex.removeLayer(transactionalLayer);
	}

	@Override
	public void resetDirty() {
		super.resetDirty();
		this.priceIndex.resetDirty();
	}

	@Nonnull
	@Override
	public ReducedEntityIndex createCopyWithMergedTransactionalMemory(@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// we can safely throw away dirty flag now
		final Boolean wasDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		return new ReducedEntityIndex(
			this.primaryKey, this.indexKey, this.version + (wasDirty ? 1 : 0),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIds),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIdsByLanguage),
			transactionalLayer.getStateCopyWithCommittedChanges(this.attributeIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.priceIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.hierarchyIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.facetIndex)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		super.removeTransactionalMemoryOfReferencedProducers(transactionalLayer);
		this.priceIndex.removeLayer(transactionalLayer);
	}

	@Override
	public String toString() {
		return "ReducedEntityIndex (" + StringUtils.uncapitalize(getIndexKey().toString()) + ")";
	}
}
