/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when attempting to access a catalog that does not exist in the evitaDB
 * instance.
 *
 * This exception indicates that the requested catalog name has not been created yet, or it
 * may have been deleted. Catalog names are case-sensitive.
 *
 * **Typical Causes:**
 * - Typo in the catalog name (remember: names are case-sensitive)
 * - Catalog was never created via `defineCatalog()`
 * - Catalog was previously deleted
 * - Attempting to access a catalog from a different evitaDB instance
 *
 * **Resolution:**
 * - Verify the catalog name is spelled correctly and matches the case
 * - Use `getCatalogNames()` to list all available catalogs
 * - Create the catalog using `defineCatalog()` if it doesn't exist
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CatalogNotFoundException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 7361926915837712504L;

	/**
	 * Creates a new exception for a catalog that does not exist.
	 *
	 * @param catalogName name of the catalog that was not found
	 */
	public CatalogNotFoundException(@Nonnull String catalogName) {
		super(
			"Catalog with name `" + catalogName + "` doesn't exist.",
			"Catalog with name `" + catalogName + "` doesn't exist."
		);
	}

}
