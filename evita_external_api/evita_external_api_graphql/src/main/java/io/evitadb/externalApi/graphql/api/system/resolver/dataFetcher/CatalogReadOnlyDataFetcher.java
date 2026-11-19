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

package io.evitadb.externalApi.graphql.api.system.resolver.dataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.CatalogContract;
import io.evitadb.core.Evita;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Resolves whether a catalog - usable or not - is read-only. The flag is read from the engine state, the same source
 * the engine itself consults before it opens a read-write session: unlike the catalog instance it knows the flag also
 * for an inactive catalog.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class CatalogReadOnlyDataFetcher implements DataFetcher<Boolean> {

	private final Evita evita;

	@Nonnull
	@Override
	public Boolean get(DataFetchingEnvironment environment) throws Exception {
		final CatalogContract catalog = environment.getSource();
		return this.evita.getEngineState().isReadOnly(catalog.getName());
	}
}
