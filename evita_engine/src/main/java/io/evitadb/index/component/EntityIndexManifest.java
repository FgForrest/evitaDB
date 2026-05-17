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

package io.evitadb.index.component;

import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Mutable accumulator that aggregates the sub-index keys announced by every
 * {@link IndexComponent} registered with an {@link io.evitadb.index.EntityIndex}. Each
 * commit cycle the parent index creates one of these, hands it to the components in
 * order, and reads the populated state back to decide whether a fresh
 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart}
 * needs to be written.
 *
 * Intentionally a mutable class (not a record) so the parent index can pass a single
 * instance through every component on the hot path without allocating an intermediate
 * tuple per component. The contents are not thread-safe — they are scoped to one
 * commit cycle on the owning thread.
 */
public final class EntityIndexManifest {

	/**
	 * Storage keys for `AttributeIndex` sub-indexes (UNIQUE / FILTER / SORT / CHAIN)
	 * plus any subclass-specific attribute index types such as CARDINALITY.
	 */
	private final Set<AttributeIndexStorageKey> attributeKeys = CollectionUtils.createHashSet(16);
	/**
	 * Storage keys for price-list-and-currency sub-indexes carried by `PriceIndex`.
	 */
	private final Set<PriceIndexKey> priceKeys = CollectionUtils.createHashSet(8);
	/**
	 * Referenced entity types tracked by `FacetIndex`.
	 */
	private final Set<String> facetReferencedEntities = CollectionUtils.createHashSet(8);
	/**
	 * Storage keys for histogram sub-indexes carried by subclasses implementing
	 * `HistogramCapableEntityIndex`.
	 */
	private final Set<HistogramIndexStorageKey> histogramKeys = CollectionUtils.createHashSet(8);
	/**
	 * Whether any hierarchy data is present in the index. Mirrors the
	 * `!hierarchyIndex.isHierarchyIndexEmpty()` predicate used by
	 * `EntityIndexStoragePart.hierarchyIndex`.
	 */
	private boolean hierarchyPresent;

	/**
	 * Announces a single `AttributeIndex` storage key to the manifest.
	 *
	 * @param key the key to add
	 */
	public void addAttributeKey(@Nonnull AttributeIndexStorageKey key) {
		this.attributeKeys.add(key);
	}

	/**
	 * Announces a single price-list-and-currency storage key to the manifest.
	 *
	 * @param key the key to add
	 */
	public void addPriceKey(@Nonnull PriceIndexKey key) {
		this.priceKeys.add(key);
	}

	/**
	 * Announces a single referenced entity type tracked by `FacetIndex`.
	 *
	 * @param entityType the entity type to add
	 */
	public void addFacetReferencedEntity(@Nonnull String entityType) {
		this.facetReferencedEntities.add(entityType);
	}

	/**
	 * Announces a single histogram storage key to the manifest.
	 *
	 * @param key the key to add
	 */
	public void addHistogramKey(@Nonnull HistogramIndexStorageKey key) {
		this.histogramKeys.add(key);
	}

	/**
	 * Marks the manifest as containing hierarchy data. Called by `HierarchyIndex` whenever
	 * it currently carries at least one node.
	 */
	public void markHierarchyPresent() {
		this.hierarchyPresent = true;
	}

	/**
	 * @return the announced `AttributeIndex` storage keys, read by the parent `EntityIndex` and by
	 * components that mutate the set directly during their flush step
	 */
	@Nonnull
	public Set<AttributeIndexStorageKey> getAttributeKeys() {
		return this.attributeKeys;
	}

	/**
	 * @return the announced price-list-and-currency storage keys, read by the parent `EntityIndex`
	 */
	@Nonnull
	public Set<PriceIndexKey> getPriceKeys() {
		return this.priceKeys;
	}

	/**
	 * @return the announced facet referenced entity types, read by the parent `EntityIndex`
	 */
	@Nonnull
	public Set<String> getFacetReferencedEntities() {
		return this.facetReferencedEntities;
	}

	/**
	 * @return the announced histogram storage keys, read by the parent `EntityIndex` and by
	 * components that mutate the set directly during their flush step
	 */
	@Nonnull
	public Set<HistogramIndexStorageKey> getHistogramKeys() {
		return this.histogramKeys;
	}

	/**
	 * @return `true` if any component announced hierarchy data is present.
	 */
	public boolean isHierarchyPresent() {
		return this.hierarchyPresent;
	}

}
