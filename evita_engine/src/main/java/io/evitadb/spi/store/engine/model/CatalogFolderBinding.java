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

import javax.annotation.Nonnull;

import java.io.Serial;
import java.io.Serializable;

/**
 * Binds one catalog name to the folder currently holding its data.
 *
 * {@link EngineState} carries these bindings as its **sole** authority for the question "which folder is catalog
 * `X`?" — nothing on disk outside the engine bootstrap may be consulted to answer it. That is what allows a
 * rename or a replace to be committed by publishing a new binding rather than by physically renaming
 * directories; see {@link CatalogFolderId}.
 *
 * A name appears at most once, so the binding array is kept strictly ascending by {@link #catalogName()}.
 * A folder that a catalog stops pointing at is not deleted here — it moves to {@link RetiredFolder}.
 *
 * @param catalogName name of the catalog the binding belongs to
 * @param folderId    token identifying the folder holding the catalog's data
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CatalogFolderBinding(
	@Nonnull String catalogName,
	@Nonnull CatalogFolderId folderId
) implements Serializable {
	@Serial private static final long serialVersionUID = -6398654712095344651L;

	@Nonnull
	@Override
	public String toString() {
		return this.catalogName + " -> " + this.folderId.id();
	}

}
