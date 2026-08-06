/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.store.engine;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The verdict {@link CatalogFolderClassifier} reaches for one directory under the storage root.
 *
 * @param folderName  name of the directory, relative to the storage root — the same token a
 *                    {@link io.evitadb.spi.store.engine.model.CatalogFolderId} carries
 * @param state       what the directory turned out to be
 * @param catalogName catalog the engine state associates with the folder, or `null` when it associates none.
 *                    Populated only for {@link CatalogFolderState#REFERENCED} and
 *                    {@link CatalogFolderState#RETIRED}, where the name comes from the engine state itself.
 *                    It stays `null` for {@link CatalogFolderState#FOREIGN} on purpose: a foreign folder's
 *                    catalog name must be read from its bootstrap header at adoption time, and taking the
 *                    directory name for it is precisely the hole that lets an import shadow a live catalog
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CatalogFolderClassification(
	@Nonnull String folderName,
	@Nonnull CatalogFolderState state,
	@Nullable String catalogName
) {

	@Nonnull
	@Override
	public String toString() {
		return this.folderName + ": " + this.state +
			(this.catalogName == null ? "" : " (" + this.catalogName + ')');
	}

}
