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

package io.evitadb.store.index.service;

import io.evitadb.api.statistics.StoragePartGroup;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.*;
import io.evitadb.store.shared.service.StoragePartRegistry;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collection;

/**
 * Implementation provides registry of {@link StoragePart} for indexes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class IndexStoragePartRegistry implements StoragePartRegistry {

	@Nonnull
	@Override
	public Collection<StoragePartRecord> listStorageParts() {
		// every type declares the group it belongs to - see StoragePartRecord for why that is declared rather than
		// derived. Two entries here are the reason a name test cannot do this job: EntityIdsStoragePart and
		// HistogramCardinalityStoragePart are index parts whose class names carry no `Index` at all. Leaf-page parts
		// are charged to the family whose tree they page, because `PAGED` versus `SINGLE` is a storage-format choice
		// rather than a different kind of data
		return Arrays.asList(
			new StoragePartRecord((byte) 20, EntityIndexStoragePart.class, StoragePartGroup.INDEX_MANIFEST),
			new StoragePartRecord((byte) 21, UniqueIndexStoragePart.class, StoragePartGroup.ATTRIBUTE_INDEX),
			new StoragePartRecord((byte) 22, FilterIndexStoragePart.class, StoragePartGroup.ATTRIBUTE_INDEX),
			new StoragePartRecord((byte) 23, SortIndexStoragePart.class, StoragePartGroup.ATTRIBUTE_INDEX),
			new StoragePartRecord((byte) 24, ChainIndexStoragePart.class, StoragePartGroup.ATTRIBUTE_INDEX),
			new StoragePartRecord((byte) 25, AttributeCardinalityIndexStoragePart.class, StoragePartGroup.ATTRIBUTE_INDEX),
			new StoragePartRecord((byte) 26, PriceListAndCurrencySuperIndexStoragePart.class, StoragePartGroup.PRICE_INDEX),
			new StoragePartRecord((byte) 27, PriceListAndCurrencyRefIndexStoragePart.class, StoragePartGroup.PRICE_INDEX),
			new StoragePartRecord((byte) 28, HierarchyIndexStoragePart.class, StoragePartGroup.HIERARCHY_INDEX),
			new StoragePartRecord((byte) 29, FacetIndexStoragePart.class, StoragePartGroup.FACET_INDEX),
			new StoragePartRecord((byte) 30, CatalogIndexStoragePart.class, StoragePartGroup.INDEX_MANIFEST),
			new StoragePartRecord((byte) 31, GlobalUniqueIndexStoragePart.class, StoragePartGroup.ATTRIBUTE_INDEX),
			new StoragePartRecord((byte) 32, ReferenceTypeCardinalityIndexStoragePart.class, StoragePartGroup.REFERENCE_INDEX),
			new StoragePartRecord((byte) 33, GroupCardinalityIndexStoragePart.class, StoragePartGroup.REFERENCE_INDEX),
			new StoragePartRecord((byte) 34, HistogramIndexStoragePart.class, StoragePartGroup.REFERENCE_HISTOGRAM_INDEX),
			new StoragePartRecord((byte) 35, FilterIndexLeafPagePart.class, StoragePartGroup.ATTRIBUTE_INDEX),
			new StoragePartRecord((byte) 36, RangeIndexLeafPagePart.class, StoragePartGroup.ATTRIBUTE_INDEX),
			new StoragePartRecord((byte) 37, EntityIdsStoragePart.class, StoragePartGroup.INDEX_MANIFEST),
			new StoragePartRecord((byte) 38, PriceListAndCurrencySuperIndexLeafPagePart.class, StoragePartGroup.PRICE_INDEX),
			new StoragePartRecord((byte) 39, GlobalUniqueIndexLeafPagePart.class, StoragePartGroup.ATTRIBUTE_INDEX),
			new StoragePartRecord((byte) 40, UniqueIndexLeafPagePart.class, StoragePartGroup.ATTRIBUTE_INDEX),
			new StoragePartRecord((byte) 41, ReferenceTypeCardinalityIndexLeafPagePart.class, StoragePartGroup.REFERENCE_INDEX),
			new StoragePartRecord((byte) 42, SortIndexLeafPagePart.class, StoragePartGroup.ATTRIBUTE_INDEX),
			new StoragePartRecord((byte) 43, ChainIndexLeafPagePart.class, StoragePartGroup.ATTRIBUTE_INDEX),
			new StoragePartRecord((byte) 44, HistogramIndexLeafPagePart.class, StoragePartGroup.REFERENCE_HISTOGRAM_INDEX),
			new StoragePartRecord((byte) 45, HistogramRangeIndexLeafPagePart.class, StoragePartGroup.REFERENCE_HISTOGRAM_INDEX),
			new StoragePartRecord((byte) 46, HistogramCardinalityStoragePart.class, StoragePartGroup.REFERENCE_HISTOGRAM_INDEX)
		);
	}

}
