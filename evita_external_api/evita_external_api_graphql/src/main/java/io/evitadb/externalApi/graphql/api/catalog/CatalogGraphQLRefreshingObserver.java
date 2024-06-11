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

package io.evitadb.externalApi.graphql.api.catalog;

import io.evitadb.api.CatalogStructuralChangeObserver;
import io.evitadb.externalApi.graphql.GraphQLManager;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Updates GraphQL API endpoints and their GraphQL instances based on Evita updates.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
// TOBEDONE LHO: consider more efficient GraphQL schema updating when only part of Evita schema is updated
@RequiredArgsConstructor
public class CatalogGraphQLRefreshingObserver implements CatalogStructuralChangeObserver {

	private final GraphQLManager graphQLManager;

	@Override
	public void onCatalogCreate(@Nonnull String catalogName) {
		graphQLManager.registerCatalog(catalogName);
		graphQLManager.emitObservabilityEvents(catalogName);
	}

	@Override
	public void onCatalogDelete(@Nonnull String catalogName) {
		graphQLManager.unregisterCatalog(catalogName);
		graphQLManager.emitObservabilityEvents(catalogName);
	}

	@Override
	public void onEntityCollectionCreate(@Nonnull String catalogName, @Nonnull String entityType) {
		graphQLManager.refreshCatalog(catalogName);
		graphQLManager.emitObservabilityEvents(catalogName);
	}

	@Override
	public void onEntityCollectionDelete(@Nonnull String catalogName, @Nonnull String entityType) {
		graphQLManager.refreshCatalog(catalogName);
		graphQLManager.emitObservabilityEvents(catalogName);
	}

	@Override
	public void onCatalogSchemaUpdate(@Nonnull String catalogName) {
		graphQLManager.refreshCatalog(catalogName);
		graphQLManager.emitObservabilityEvents(catalogName);
	}

	@Override
	public void onEntitySchemaUpdate(@Nonnull String catalogName, @Nonnull String entityType) {
		graphQLManager.refreshCatalog(catalogName);
		graphQLManager.emitObservabilityEvents(catalogName);
	}
}
