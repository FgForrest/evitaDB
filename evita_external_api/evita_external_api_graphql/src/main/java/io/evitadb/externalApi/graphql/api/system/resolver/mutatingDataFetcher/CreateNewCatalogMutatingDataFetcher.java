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

package io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher;

import graphql.schema.DataFetchingEnvironment;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.api.system.model.CatalogNameMutationHeaderDescriptor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher.EngineOperationArguments.getStringArgument;

/**
 * Creates a new catalog of the given name if it doesn't exist yet (`defineCatalog`) and returns it.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class CreateNewCatalogMutatingDataFetcher extends EngineOperationMutatingDataFetcher {

	public CreateNewCatalogMutatingDataFetcher(@Nonnull Evita evita) {
		super(evita);
	}

	@Nonnull
	@Override
	protected String executeOperation(@Nonnull DataFetchingEnvironment environment) {
		final String catalogName = getStringArgument(environment, CatalogNameMutationHeaderDescriptor.NAME);
		this.evita.defineCatalog(catalogName);
		return catalogName;
	}
}
