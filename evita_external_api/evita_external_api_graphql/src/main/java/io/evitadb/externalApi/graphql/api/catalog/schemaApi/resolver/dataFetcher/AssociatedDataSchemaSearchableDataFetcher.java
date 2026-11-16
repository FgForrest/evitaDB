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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.dataType.Scope;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;

/** Reads the scopes in which an associated-data schema is searchable, in the stable order of the scope enum. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AssociatedDataSchemaSearchableDataFetcher implements DataFetcher<Object> {
	/** Shared stateless field fetcher. */
	@Nonnull
	@Getter private static final AssociatedDataSchemaSearchableDataFetcher instance =
		new AssociatedDataSchemaSearchableDataFetcher();

	@Nonnull
	@Override
	public Object get(@Nonnull DataFetchingEnvironment environment) {
		final AssociatedDataSchemaContract schema = environment.getSource();
		return Arrays.stream(Scope.values()).filter(schema::isSearchableInScope).toList();
	}
}
