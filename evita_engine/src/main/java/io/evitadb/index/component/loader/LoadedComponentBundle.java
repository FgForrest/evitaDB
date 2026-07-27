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

package io.evitadb.index.component.loader;

import io.evitadb.index.HistogramIndex;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.price.PriceListAndCurrencyPriceRefIndex;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Sealed family of result envelopes returned by {@link ComponentLoader} implementations. Each
 * concrete record carries the already-rehydrated sub-index state that the subclass-specific
 * {@link IndexReloadPlan} finalizer needs to feed into one of the public `EntityIndex` subclass
 * constructors.
 *
 * The sealed hierarchy is intentionally a closed enum of shapes — anything a finalizer can
 * possibly receive must be one of these. Subclass plans declare which shapes they consume in
 * their static `reloadPlan()` builders. This keeps the dispatcher tightly typed without
 * forcing a single bag-of-fields envelope.
 *
 * Bundles are short-lived: the dispatcher builds them once during catalog boot / restart and
 * drops them immediately after the finalizer runs.
 */
public sealed interface LoadedComponentBundle
	permits LoadedComponentBundle.AttributeIndexes,
	LoadedComponentBundle.PriceSuper,
	LoadedComponentBundle.PriceRef,
	LoadedComponentBundle.Hierarchy,
	LoadedComponentBundle.Facet,
	LoadedComponentBundle.AttributeCardinalityIndexes,
	LoadedComponentBundle.Histograms,
	LoadedComponentBundle.ReferenceTypeCardinality,
	LoadedComponentBundle.GroupCardinality {

	/**
	 * The reloaded {@link io.evitadb.index.attribute.AttributeIndex} component, broken into its
	 * four flat per-attribute maps (UNIQUE / FILTER / SORT / CHAIN). The cardinality map carried
	 * by `ReferencedTypeEntityIndex` and `ReducedGroupEntityIndex` lives in a separate
	 * {@link AttributeCardinalityIndexes} bundle so that the two subclass plans without
	 * cardinalities don't have to declare the empty map.
	 *
	 * @param uniqueIndexes UNIQUE-typed entries for standalone (owner) unique attributes, keyed by the unique key
	 * @param filterIndexes FILTER VIEW entries keyed by attribute name/locale (carry attributeType)
	 * @param uniqueViewIndexes folded-unique VIEW entries (view-mode {@link UniqueIndex}) keyed by the foldable key
	 * @param sortIndexes   SORT-typed entries keyed by attribute name/locale
	 * @param chainIndexes  CHAIN-typed entries keyed by attribute name/locale
	 * @param sharedValueIndexes shared value→ValueToRecord trees keyed by the FILTER attribute key
	 * @param sharedRangeIndexes shared range structures keyed by the FILTER attribute key
	 */
	record AttributeIndexes(
		@Nonnull Map<AttributeIndexKey, UniqueIndex> uniqueIndexes,
		@Nonnull Map<AttributeIndexKey, FilterIndex> filterIndexes,
		@Nonnull Map<AttributeIndexKey, UniqueIndex> uniqueViewIndexes,
		@Nonnull Map<AttributeIndexKey, SortIndex> sortIndexes,
		@Nonnull Map<AttributeIndexKey, ChainIndex> chainIndexes,
		@Nonnull Map<AttributeIndexKey, InvertedIndex> sharedValueIndexes,
		@Nonnull Map<AttributeIndexKey, RangeIndex> sharedRangeIndexes
	) implements LoadedComponentBundle {
	}

	/**
	 * The reloaded super-price index map for `GlobalEntityIndex`.
	 *
	 * @param priceIndexes per-price-list-and-currency super indexes keyed by `PriceIndexKey`
	 */
	record PriceSuper(
		@Nonnull Map<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> priceIndexes
	) implements LoadedComponentBundle {
	}

	/**
	 * The reloaded reference-price index map for `ReducedEntityIndex` / `ReducedGroupEntityIndex`.
	 *
	 * @param priceIndexes per-price-list-and-currency reference indexes keyed by `PriceIndexKey`
	 */
	record PriceRef(
		@Nonnull Map<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> priceIndexes
	) implements LoadedComponentBundle {
	}

	/**
	 * The reloaded {@link HierarchyIndex} component. Always non-null even when no hierarchy data
	 * was persisted — in that case it carries a fresh empty index.
	 *
	 * @param hierarchyIndex the rehydrated hierarchy index
	 */
	record Hierarchy(@Nonnull HierarchyIndex hierarchyIndex) implements LoadedComponentBundle {
	}

	/**
	 * The reloaded {@link FacetIndex} component. Always non-null; empty when no facet data was
	 * persisted.
	 *
	 * @param facetIndex the rehydrated facet index
	 */
	record Facet(@Nonnull FacetIndex facetIndex) implements LoadedComponentBundle {
	}

	/**
	 * The reloaded per-attribute CARDINALITY index map carried by `ReferencedTypeEntityIndex` and
	 * `ReducedGroupEntityIndex`. Empty for indexes that have no cardinality entries.
	 *
	 * @param cardinalityIndexes per-attribute cardinality indexes keyed by `AttributeIndexKey`
	 */
	record AttributeCardinalityIndexes(
		@Nonnull Map<AttributeIndexKey, AttributeCardinalityIndex> cardinalityIndexes
	) implements LoadedComponentBundle {
	}

	/**
	 * The reloaded histogram map carried by `ReferencedTypeEntityIndex` and
	 * `ReducedGroupEntityIndex`.
	 *
	 * @param histogramIndexes histogram indexes keyed by histogram name
	 */
	record Histograms(
		@Nonnull Map<String, HistogramIndex> histogramIndexes
	) implements LoadedComponentBundle {
	}

	/**
	 * The reloaded reference-type cardinality index carried by `ReferencedTypeEntityIndex`.
	 *
	 * @param referenceTypeCardinalityIndex the rehydrated cross-reference cardinality tracker
	 */
	record ReferenceTypeCardinality(
		@Nonnull ReferenceTypeCardinalityIndex referenceTypeCardinalityIndex
	) implements LoadedComponentBundle {
	}

	/**
	 * The reloaded group-cardinality bookkeeping for `ReducedGroupEntityIndex`, decomposed into
	 * the two transactional maps the constructor expects.
	 *
	 * @param pkCardinalities            cardinality tracking for entity primary keys
	 * @param referencedPrimaryKeysIndex maps referenced entity PKs to bitmaps of entity PKs
	 */
	record GroupCardinality(
		@Nonnull Map<Integer, Integer> pkCardinalities,
		@Nonnull Map<Integer, TransactionalBitmap> referencedPrimaryKeysIndex
	) implements LoadedComponentBundle {
	}

}
