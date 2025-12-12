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

package io.evitadb.store.catalog.service;

import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.CatalogSchemaStoragePart;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.shared.service.StoragePartRegistry;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;

/**
 * Implementation provides registry of {@link StoragePart} for catalog model.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CatalogStoragePartRegistry implements StoragePartRegistry {

	@Nonnull
	@Override
	public Collection<StoragePartRecord> listStorageParts() {
		return List.of(
			new StoragePartRecord((byte) 50, CatalogHeader.class),
			new StoragePartRecord((byte) 51, EntityCollectionFileHeader.class),
			new StoragePartRecord((byte) 52, CatalogSchemaStoragePart.class)
		);
	}
}
