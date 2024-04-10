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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.entity.service;

import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart;
import io.evitadb.store.entity.model.entity.AttributesStoragePart;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import io.evitadb.store.entity.model.schema.EntitySchemaStoragePart;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.StoragePartRegistry;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collection;

/**
 * Implementation provides registry of {@link StoragePart} for entity related model.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityStoragePartRegistry implements StoragePartRegistry {

	@Nonnull
	@Override
	public Collection<StoragePartRecord> listStorageParts() {
		return Arrays.asList(
			new StoragePartRecord((byte) 1, EntitySchemaStoragePart.class),
			new StoragePartRecord((byte) 2, EntityBodyStoragePart.class),
			new StoragePartRecord((byte) 3, AttributesStoragePart.class),
			new StoragePartRecord((byte) 4, AssociatedDataStoragePart.class),
			new StoragePartRecord((byte) 5, PricesStoragePart.class),
			new StoragePartRecord((byte) 6, ReferencesStoragePart.class)
		);
	}
}
