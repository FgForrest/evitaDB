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

package io.evitadb.spi.store.engine.model;

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Opaque identity of the directory holding a catalog's data. Carries no path semantics of its own.
 *
 * The engine records `catalogName -> CatalogFolderId` in {@link EngineState} and passes the token across the
 * storage SPI; only `evita_store_server` knows that a token denotes a directory, or how to join it onto the
 * storage root. This is what allows a catalog to be renamed or replaced by committing a new binding rather
 * than by physically renaming directories — see issue #649.
 *
 * **The boundary rule this type establishes is not "no `java.nio.file.Path` in the engine".** It is: *the
 * engine must never hold a path derived from a catalog's identity*. Paths that are configuration or exchange
 * artifacts remain legitimately engine-visible — the configured storage root, the backup archive handed to a
 * restore, an export target directory. Only the catalog-to-directory mapping is off limits, because that is
 * the mapping the engine state owns and the one a rename changes.
 *
 * The token's textual shape is deliberately unconstrained beyond the safety check below. The design requires
 * folder names that are *not* of the form `name_generation`: an adopted foreign folder keeps its bare name,
 * and a legacy folder whose boot-time rename failed stays recorded under the name it still has on disk.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CatalogFolderId(@Nonnull String id) implements Serializable {

	/**
	 * Validates that the token names a single directory and cannot escape the storage root.
	 *
	 * Tokens are persisted in the engine state and round-trip through its serializer before being joined onto
	 * the storage root by the storage layer, so a token able to smuggle a path traversal would be a *stored*
	 * vulnerability — checking once here is cheaper and safer than trusting every join site.
	 *
	 * @param id textual form of the token; must be a non-blank single path segment
	 */
	public CatalogFolderId {
		Assert.isPremiseValid(
			!id.isBlank() && !id.contains("/") && !id.contains("\\") && !id.contains(".."),
			() -> "Catalog folder id `" + id + "` is not a valid single directory name!"
		);
	}

	@Nonnull
	@Override
	public String toString() {
		return this.id;
	}

}
