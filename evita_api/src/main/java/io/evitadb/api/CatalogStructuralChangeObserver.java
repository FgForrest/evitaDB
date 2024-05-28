/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.api;

import javax.annotation.Nonnull;

/**
 * Interface defines contract allowing to react to structural catalog changes, such as:
 *
 * - new catalog is created ({@link #onCatalogCreate(String)})
 * - existing catalog is deleted ({@link #onCatalogDelete(String)})
 * - new collection creation ({@link #onEntityCollectionCreate(String, String)})
 * - existing collection creation ({@link #onEntityCollectionDelete(String, String)}
 * - catalog schema modification ({@link #onCatalogSchemaUpdate(String)}
 * - entity schema modification ({@link #onEntitySchemaUpdate(String, String)}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface CatalogStructuralChangeObserver {

	/**
	 * Implementation method is called when new catalog of passed `catalogName` is created.
	 *
	 * @param catalogName name of the catalog
	 */
	void onCatalogCreate(@Nonnull String catalogName);

	/**
	 * Implementation method is called when existing catalog of passed `catalogName` is deleted.
	 *
	 * @param catalogName name of the catalog
	 */
	void onCatalogDelete(@Nonnull String catalogName);

	/**
	 * Implementation method is called when new collection of passed `entityType` is created in the catalog.
	 *
 	 * @param catalogName name of the catalog
 	 * @param entityType name of the collection
	 */
	void onEntityCollectionCreate(@Nonnull String catalogName, @Nonnull String entityType);

	/**
	 * Implementation method is called when existing collection of passed `entityType` is deleted in the catalog.
	 *
	 * @param catalogName name of the catalog
	 * @param entityType name of the collection
	 */
	void onEntityCollectionDelete(@Nonnull String catalogName, @Nonnull String entityType);

	/**
	 * Implementation method is called catalog schema is updated and committed.
	 *
	 * @param catalogName name of the catalog
	 */
	void onCatalogSchemaUpdate(@Nonnull String catalogName);

	/**
	 * Implementation method is called when existing collection schema of passed `entityType` is updated and committed.
	 *
	 * @param catalogName name of the catalog
	 * @param entityType name of the collection
	 */
	void onEntitySchemaUpdate(@Nonnull String catalogName, @Nonnull String entityType);

}
