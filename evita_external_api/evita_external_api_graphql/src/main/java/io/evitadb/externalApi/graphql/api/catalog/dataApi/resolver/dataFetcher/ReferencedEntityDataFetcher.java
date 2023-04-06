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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher;

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Fetches referenced entity from parent {@link ReferenceContract}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class ReferencedEntityDataFetcher implements DataFetcher<DataFetcherResult<SealedEntity>> {

	@Nonnull
	@Override
	public DataFetcherResult<SealedEntity> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final EntityQueryContext context = environment.getLocalContext();

		final ReferenceContract reference = environment.getSource();
		final SealedEntity referencedEntity = reference.getReferencedEntity().orElse(null);

		return DataFetcherResult.<SealedEntity>newResult()
			.data(referencedEntity)
			.localContext(context)
			.build();
	}
}
