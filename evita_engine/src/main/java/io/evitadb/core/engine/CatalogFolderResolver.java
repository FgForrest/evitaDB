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

import io.evitadb.spi.store.engine.model.CatalogFolderId;

import javax.annotation.Nonnull;

/**
 * Answers the single question "which folder holds the data of catalog `X`?" — as an opaque
 * {@link CatalogFolderId}, never as a filesystem path.
 *
 * This interface is the **sole** sanctioned way to obtain a catalog's folder binding. It exists so that the
 * catalog name stops being the catalog's on-disk identity: historically every caller computed
 * `storageDirectory.resolve(catalogName)` inline, which made a rename or a replace impossible to perform
 * without physically renaming directories — a multi-step, non-atomic filesystem sequence that cannot be used
 * as a commit protocol and that fails routinely on Windows whenever any handle inside the directory is still
 * open. See issue #649.
 *
 * Callers must treat the returned token as a *snapshot*: a catalog's folder may be reassigned by a rename or
 * a replace, so it must be looked up at the point of use and never cached across an engine-state change.
 * Conversely, once a component has been handed the token for the catalog it serves, it keeps using that
 * token — components below the engine never re-resolve, which is what keeps the engine state the single
 * authority for the mapping.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@FunctionalInterface
public interface CatalogFolderResolver {

	/**
	 * Returns the folder token the passed catalog is currently bound to.
	 *
	 * The folder is not guaranteed to exist — callers that materialise a brand-new catalog receive the token
	 * the catalog *will* occupy. Callers that load an existing catalog can rely on the binding being
	 * consistent with what the engine state records.
	 *
	 * @param catalogName name of the catalog to look the folder token up for
	 * @return token identifying the folder holding the catalog data
	 */
	@Nonnull
	CatalogFolderId folderIdFor(@Nonnull String catalogName);

	/**
	 * Creates the identity resolver, binding every catalog to a folder token equal to its own name.
	 *
	 * This reproduces the historical, pre-#649 mapping and is in force until the engine state carries an
	 * explicit name-to-folder map, at which point {@link io.evitadb.core.Evita} wires that mapping instead
	 * and this factory is deleted outright. Do not wire new production code to it — a component bound this
	 * way keeps working only for as long as folder token and catalog name happen to coincide, which is the
	 * very assumption this interface exists to retire.
	 *
	 * @return resolver binding a catalog name onto a folder token of the same name
	 */
	@Nonnull
	static CatalogFolderResolver identity() {
		return CatalogFolderId::new;
	}

}
