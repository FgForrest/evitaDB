/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Returns page of records as {@link PaginatedList} if {@link io.evitadb.api.query.require.Page} was used in query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RecordPageDataFetcher implements DataFetcher<PaginatedList<? extends EntityClassifier>> {

	@Nullable
	private static RecordPageDataFetcher INSTANCE;

	@Nonnull
	public static RecordPageDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new RecordPageDataFetcher();
		}
		return INSTANCE;
	}

	@Nonnull
	@Override
	public PaginatedList<? extends EntityClassifier> get(DataFetchingEnvironment environment) throws Exception {
		final EvitaResponse<? extends EntityClassifier> response = Objects.requireNonNull(environment.getSource());
		final DataChunk<? extends EntityClassifier> records = response.getRecordPage();
		Assert.isPremiseValid(
			records instanceof PaginatedList,
			() -> new GraphQLQueryResolvingInternalError("Expected paginated list but was `" + records.getClass() + "`.")
		);
		return (PaginatedList<? extends EntityClassifier>) records;
	}
}
