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
import io.evitadb.utils.NamingConvention;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Thrown when attempting to create a new catalog with a name that conflicts with an existing catalog,
 * either as an exact match or when converted to a specific naming convention.
 *
 * evitaDB catalog names must be unique both as canonical names and across all naming conventions
 * (camelCase, snake_case, UPPER_CASE, etc.). This exception is thrown when attempting to create a catalog
 * whose name would be indistinguishable from an existing catalog in at least one naming convention. The
 * exception has two variants: one for simple name collisions and one that specifies which naming convention
 * caused the conflict.
 *
 * **When this is thrown:**
 * - When calling `defineCatalog()` with a name that already exists
 * - When renaming a catalog to a name that would conflict with another catalog
 * - During catalog schema evolution when name changes create conflicts
 * - Thrown by `CatalogSchema` and `ModifyCatalogSchemaNameMutationOperator`
 *
 * **Example conflict:**
 * - Existing catalog: `my-store`
 * - New catalog: `myStore`
 * - Both produce `myStore` in camelCase convention → conflict
 *
 * **Resolution:**
 * - Choose a different catalog name that doesn't conflict in any naming convention
 * - Delete or rename the existing catalog if it's no longer needed
 * - Check existing catalog names using `getCatalogNames()` before creating new catalogs
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CatalogAlreadyPresentException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -4492948359569503645L;
	/**
	 * The name of the catalog that was attempted to be created.
	 */
	@Getter private final String catalogName;
	/**
	 * The name of the existing catalog that conflicts.
	 */
	@Getter private final String existingCatalogName;

	/**
	 * Creates exception for a simple name collision between catalogs.
	 */
	public CatalogAlreadyPresentException(@Nonnull String catalogName, @Nonnull String existingCatalogName) {
		super(
			"Catalog with name `" + catalogName + "` already exists! " +
				"Please choose different catalog name."
		);
		this.catalogName = catalogName;
		this.existingCatalogName = existingCatalogName;
	}

	/**
	 * Creates exception for a naming convention collision, specifying which convention and resulting name
	 * caused the conflict.
	 */
	public CatalogAlreadyPresentException(
		@Nonnull String catalogName,
		@Nonnull String existingCatalogName,
		@Nonnull NamingConvention convention,
		@Nonnull String conflictingName) {
		super(
			"Catalog `" + catalogName + "` and existing " +
				"catalog `" + existingCatalogName + "` produce the same " +
				"name `" + conflictingName + "` in `" + convention + "` convention! " +
				"Please choose different catalog name."
		);
		this.catalogName = catalogName;
		this.existingCatalogName = existingCatalogName;
	}
}
