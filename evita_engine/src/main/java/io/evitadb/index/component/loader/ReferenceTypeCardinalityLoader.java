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

import io.evitadb.index.EntityIndexKey;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexStoragePart;

import javax.annotation.Nonnull;
import java.util.Objects;

import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * Reloads the cross-reference cardinality bookkeeping carried by `ReferencedTypeEntityIndex`.
 *
 * The reference name is extracted from the `EntityIndexKey.discriminator()` which is always a
 * `String` for `REFERENCED_ENTITY_TYPE` and `REFERENCED_GROUP_ENTITY_TYPE` keys — the dispatcher
 * has already validated this before invoking the loader, so a cast-failure here is a
 * programming error.
 */
public final class ReferenceTypeCardinalityLoader implements ComponentLoader {

	@Override
	@Nonnull
	public LoadedComponentBundle load(@Nonnull LoadContext context) {
		final EntityIndexKey entityIndexKey = context.entityIndexKey();
		final String referenceName = Objects.requireNonNull((String) entityIndexKey.discriminator());
		final StoragePartPersistenceService<?> service = context.storagePartService();
		final int entityIndexId = context.entityIndexId();
		final long primaryKey = ReferenceTypeCardinalityIndexStoragePart.computeUniquePartId(
			entityIndexId, referenceName, service.getReadOnlyKeyCompressor()
		);
		final ReferenceTypeCardinalityIndexStoragePart part = service.getStoragePart(
			context.catalogVersion(), primaryKey, ReferenceTypeCardinalityIndexStoragePart.class
		);
		isPremiseValid(
			part != null,
			"Cardinality index with id `" + entityIndexId + "` with key `" + referenceName +
				"` was not found in persistent storage!"
		);
		return new LoadedComponentBundle.ReferenceTypeCardinality(part.getCardinalityIndex());
	}

}
