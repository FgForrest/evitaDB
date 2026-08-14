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
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A folder found on disk that no catalog is bound to and that boot classification judged adoptable, paired with
 * the catalog name it is to be registered under.
 *
 * The two travel together rather than the name alone because adoption is the one path where they are free to
 * disagree. They happen to coincide today — the name is taken from the directory, since reading it from the
 * catalog's own header needs an open offset index that boot classification does not have — but that is a
 * limitation to be lifted, not an invariant to build on. Once the header becomes the authority, a folder called
 * `products` holding a catalog named `orders` must adopt as `orders`, and any code that re-derived one field from
 * the other would keep working while being wrong.
 *
 * @param catalogName name the catalog is to be registered under
 * @param folderId    token naming the folder as it exists on disk at the moment of discovery
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
@Immutable
public record AdoptableCatalogFolder(
	@Nonnull String catalogName,
	@Nonnull CatalogFolderId folderId
) {
}
