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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.StripList;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Returns page of records as {@link StripList} if {@link io.evitadb.api.query.require.Strip} was used in query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RecordStripDataFetcher implements DataFetcher<StripList<? extends EntityClassifier>> {

	@Nullable
	private static RecordStripDataFetcher INSTANCE;

	@Nonnull
	public static RecordStripDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new RecordStripDataFetcher();
		}
		return INSTANCE;
	}

	@Nonnull
	@Override
	public StripList<? extends EntityClassifier> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final EvitaResponse<? extends EntityClassifier> response = environment.getSource();
		final DataChunk<? extends EntityClassifier> records = response.getRecordPage();
		Assert.isPremiseValid(
			records instanceof StripList,
			() -> new GraphQLQueryResolvingInternalError("Expected strip list but was `" + records.getClass() + "`.")
		);
		return (StripList<? extends EntityClassifier>) records;
	}
}
