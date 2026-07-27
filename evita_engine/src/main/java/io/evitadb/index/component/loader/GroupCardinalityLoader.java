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

import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GroupCardinalityIndexStoragePart;

import javax.annotation.Nonnull;
import java.util.Objects;

import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * Reloads the group-cardinality bookkeeping carried by `ReducedGroupEntityIndex`.
 *
 * The reference name is extracted from the discriminator, which for `REFERENCED_GROUP_ENTITY`
 * keys is always a `RepresentativeReferenceKey`. A `null` here is a programming error.
 */
public final class GroupCardinalityLoader implements ComponentLoader {

	@Override
	@Nonnull
	public LoadedComponentBundle load(@Nonnull LoadContext context) {
		final EntityIndexKey entityIndexKey = context.entityIndexKey();
		final String referenceName = Objects.requireNonNull(
			((RepresentativeReferenceKey) entityIndexKey.discriminator()).referenceName()
		);
		final StoragePartPersistenceService<?> service = context.storagePartService();
		final int entityIndexId = context.entityIndexId();
		final long primaryKey = GroupCardinalityIndexStoragePart.computeUniquePartId(
			entityIndexId, referenceName, service.getReadOnlyKeyCompressor()
		);
		final GroupCardinalityIndexStoragePart part = service.getStoragePart(
			context.catalogVersion(), primaryKey, GroupCardinalityIndexStoragePart.class
		);
		isPremiseValid(
			part != null,
			"Group cardinality index with id `" + entityIndexId + "` with key `" + referenceName +
				"` was not found in persistent storage!"
		);
		return new LoadedComponentBundle.GroupCardinality(
			part.getPkCardinalities(),
			part.getReferencedPrimaryKeysIndex()
		);
	}

}
