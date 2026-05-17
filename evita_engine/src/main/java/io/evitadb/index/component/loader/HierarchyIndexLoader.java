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

import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HierarchyIndexStoragePart;

import javax.annotation.Nonnull;

import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * Reloads the {@link HierarchyIndex} component shared by every `EntityIndex` subclass. Ports
 * `fetchHierarchyIndex` from `DefaultEntityCollectionPersistenceService`.
 *
 * The hierarchy index is special-cased: the manifest carries only a `hierarchyIndex` boolean
 * flag, not a key set. When `false`, the loader returns a fresh empty `HierarchyIndex` rather
 * than throwing; when `true`, a missing storage part is a fatal corruption signal.
 */
public final class HierarchyIndexLoader implements ComponentLoader {

	@Override
	@Nonnull
	public LoadedComponentBundle load(@Nonnull LoadContext context) {
		final EntityIndexStoragePart manifest = context.entityIndexStoragePart();
		if (!manifest.isHierarchyIndex()) {
			return new LoadedComponentBundle.Hierarchy(new HierarchyIndex());
		}
		final StoragePartPersistenceService<?> service = context.storagePartService();
		final int entityIndexId = context.entityIndexId();
		final HierarchyIndexStoragePart part = service.getStoragePart(
			context.catalogVersion(), entityIndexId, HierarchyIndexStoragePart.class
		);
		isPremiseValid(
			part != null,
			"Hierarchy index with id " + entityIndexId + " was not found in persistent storage!"
		);
		return new LoadedComponentBundle.Hierarchy(
			new HierarchyIndex(part.getRoots(), part.getLevelIndex(), part.getItemIndex(), part.getOrphans())
		);
	}

}
