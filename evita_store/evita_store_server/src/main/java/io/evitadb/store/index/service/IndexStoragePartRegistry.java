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
		return Arrays.asList(
			new StoragePartRecord((byte) 20, EntityIndexStoragePart.class),
			new StoragePartRecord((byte) 21, UniqueIndexStoragePart.class),
			new StoragePartRecord((byte) 22, FilterIndexStoragePart.class),
			new StoragePartRecord((byte) 23, SortIndexStoragePart.class),
			new StoragePartRecord((byte) 24, ChainIndexStoragePart.class),
			new StoragePartRecord((byte) 25, AttributeCardinalityIndexStoragePart.class),
			new StoragePartRecord((byte) 26, PriceListAndCurrencySuperIndexStoragePart.class),
			new StoragePartRecord((byte) 27, PriceListAndCurrencyRefIndexStoragePart.class),
			new StoragePartRecord((byte) 28, HierarchyIndexStoragePart.class),
			new StoragePartRecord((byte) 29, FacetIndexStoragePart.class),
			new StoragePartRecord((byte) 30, CatalogIndexStoragePart.class),
			new StoragePartRecord((byte) 31, GlobalUniqueIndexStoragePart.class),
			new StoragePartRecord((byte) 32, ReferenceTypeCardinalityIndexStoragePart.class),
			new StoragePartRecord((byte) 33, GroupCardinalityIndexStoragePart.class)
		);
	}

}
