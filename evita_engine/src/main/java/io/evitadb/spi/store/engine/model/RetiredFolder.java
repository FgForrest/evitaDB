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
 * Tombstone recording a folder that no catalog points at any more and that is therefore owned by the engine to
 * delete.
 *
 * The tombstone is what makes deletion **non-blocking**: an operation that stops using a folder commits the
 * engine state with the folder retired and then merely *attempts* the delete. A filesystem that refuses — the
 * Windows case, where a directory entry survives until the last handle inside it closes — postpones the delete
 * to the next boot instead of failing the operation.
 *
 * A tombstone is positive evidence of ownership. It is the only thing (alongside a `.provisional` marker) that
 * authorises the engine to destroy a directory it finds unreferenced; an unexplained folder is warned about and
 * left alone, because deleting an operator's hand-placed copy is unrecoverable.
 *
 * The catalog name is carried alongside the token rather than parsed back out of it. The token's textual shape
 * is deliberately unconstrained — an adopted foreign folder keeps its bare name — so it cannot be relied upon
 * to name its catalog, and the name is needed to decide when a catalog's generation sequence may be retired.
 *
 * Unlike {@link CatalogFolderBinding}, a catalog name may appear more than once: several of a catalog's former
 * folders can await deletion at the same time. The tombstone array is therefore kept strictly ascending by
 * folder token, which is unique.
 *
 * @param catalogName name of the catalog the folder used to hold
 * @param folderId    token identifying the folder awaiting deletion
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record RetiredFolder(
	@Nonnull String catalogName,
	@Nonnull CatalogFolderId folderId
) implements Serializable {
	@Serial private static final long serialVersionUID = 4507845128097631120L;

	@Nonnull
	@Override
	public String toString() {
		return this.folderId.id() + " (was " + this.catalogName + ')';
	}

}
