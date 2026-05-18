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

import io.evitadb.index.facet.FacetIndex;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FacetIndexStoragePart;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * Reloads the {@link FacetIndex} component shared by every `EntityIndex` subclass. When the
 * manifest carries no facet entries, the loader returns a fresh empty `FacetIndex`.
 */
public final class FacetIndexLoader implements ComponentLoader {

	@Override
	@Nonnull
	public LoadedComponentBundle load(@Nonnull LoadContext context) {
		final EntityIndexStoragePart manifest = context.entityIndexStoragePart();
		final Set<String> facetIndexes = manifest.getFacetIndexes();
		if (facetIndexes.isEmpty()) {
			return new LoadedComponentBundle.Facet(new FacetIndex());
		}
		final StoragePartPersistenceService<?> service = context.storagePartService();
		final int entityIndexId = context.entityIndexId();
		final long catalogVersion = context.catalogVersion();
		final List<FacetIndexStoragePart> parts = new ArrayList<>(facetIndexes.size());
		for (final String referenceName : facetIndexes) {
			final long primaryKey = FacetIndexStoragePart.computeUniquePartId(
				entityIndexId, referenceName, service.getReadOnlyKeyCompressor()
			);
			final FacetIndexStoragePart part = service.getStoragePart(
				catalogVersion, primaryKey, FacetIndexStoragePart.class
			);
			isPremiseValid(
				part != null,
				"Facet index with id " + entityIndexId + " (id=" + primaryKey + ") and key " +
					referenceName + " was not found in persistent storage!"
			);
			parts.add(part);
		}
		return new LoadedComponentBundle.Facet(new FacetIndex(parts));
	}

}
