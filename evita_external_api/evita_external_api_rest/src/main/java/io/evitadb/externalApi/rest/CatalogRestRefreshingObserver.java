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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.rest;

import io.evitadb.api.CatalogStructuralChangeObserver;

import javax.annotation.Nonnull;

/**
 * This observer allows to react on changes in Catalog's structure and reload OpenAPI and REST handlers if necessary.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CatalogRestRefreshingObserver implements CatalogStructuralChangeObserver {

	private final RESTApiManager restApiManager;

	public CatalogRestRefreshingObserver(RESTApiManager restApiManager) {
		this.restApiManager = restApiManager;
	}

	@Override
	public void onCatalogCreate(@Nonnull String catalogName) {
		restApiManager.registerNewCatalog(catalogName);
	}

	@Override
	public void onCatalogDelete(@Nonnull String catalogName) {
		restApiManager.unregisterCatalog(catalogName);
	}

	@Override
	public void onEntityCollectionCreate(@Nonnull String catalogName, @Nonnull String entityType) {
		restApiManager.updateExistingCatalog(catalogName);
	}

	@Override
	public void onEntityCollectionDelete(@Nonnull String catalogName, @Nonnull String entityType) {
		restApiManager.updateExistingCatalog(catalogName);
	}

	@Override
	public void onCatalogSchemaUpdate(@Nonnull String catalogName) {
		restApiManager.updateExistingCatalog(catalogName);
	}

	@Override
	public void onEntitySchemaUpdate(@Nonnull String catalogName, @Nonnull String entityType) {
		restApiManager.updateExistingCatalog(catalogName);
	}
}
