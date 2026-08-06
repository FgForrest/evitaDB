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

package io.evitadb.core.engine;

import io.evitadb.api.CatalogState;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.catalog.UnusableCatalog.UnusableCatalogExceptionFactory;
import io.evitadb.spi.store.engine.CatalogFolderOperations;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.nio.file.Path;

/**
 * Everything the engine is allowed to know about catalog storage folders, in one place.
 *
 * The engine may not derive a catalog's directory (see {@link CatalogFolderId} for the boundary rule), yet it
 * still has three legitimate needs around folders: look up which folder a catalog is bound to, ask the storage
 * layer to act on a folder as a whole, and name a location in an error message. Bundling them means an engine
 * component takes one collaborator rather than three, and — more usefully — that there is a single type to
 * inspect when asking "what does the engine still know about layout?".
 *
 * The storage root is carried because it is *configuration*, not layout: reporting it beside a folder token
 * lets an operator locate a folder from an error message without the engine ever performing the join.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class CatalogFolderContext {
	/**
	 * Resolves which folder token a catalog is currently bound to.
	 */
	@Getter private final CatalogFolderResolver folderResolver;
	/**
	 * Whole-folder operations, performed by the storage layer on the engine's behalf.
	 */
	@Getter private final CatalogFolderOperations folderOperations;
	/**
	 * Configured root directory holding all catalog folders — reported in diagnostics, never joined.
	 */
	private final Path storageRoot;

	/**
	 * Returns the folder token the passed catalog is currently bound to.
	 *
	 * @param catalogName name of the catalog
	 * @return token identifying the catalog's folder
	 */
	@Nonnull
	public CatalogFolderId folderIdFor(@Nonnull String catalogName) {
		return this.folderResolver.folderIdFor(catalogName);
	}

	/**
	 * Creates the placeholder standing in for a catalog that cannot be used, resolving its folder binding.
	 *
	 * @param catalogName  name of the catalog the placeholder stands for
	 * @param catalogState state to report for the unusable catalog
	 * @param cause        factory producing the exception every operation on the placeholder throws
	 * @return placeholder to be installed in the engine state
	 */
	@Nonnull
	public UnusableCatalog createUnusableCatalog(
		@Nonnull String catalogName,
		@Nonnull CatalogState catalogState,
		@Nonnull UnusableCatalogExceptionFactory cause
	) {
		return createUnusableCatalog(catalogName, folderIdFor(catalogName), catalogState, cause);
	}

	/**
	 * Creates the placeholder standing in for a catalog that cannot be used, for a folder binding the caller
	 * already holds — used where the token was looked up earlier in the same operation and must not be
	 * re-resolved, because an intervening engine-state change could move it.
	 *
	 * @param catalogName  name of the catalog the placeholder stands for
	 * @param folderId     token identifying the catalog's folder
	 * @param catalogState state to report for the unusable catalog
	 * @param cause        factory producing the exception every operation on the placeholder throws
	 * @return placeholder to be installed in the engine state
	 */
	@Nonnull
	public UnusableCatalog createUnusableCatalog(
		@Nonnull String catalogName,
		@Nonnull CatalogFolderId folderId,
		@Nonnull CatalogState catalogState,
		@Nonnull UnusableCatalogExceptionFactory cause
	) {
		return new UnusableCatalog(
			catalogName, catalogState, folderId, this.storageRoot, this.folderOperations, cause
		);
	}

}
