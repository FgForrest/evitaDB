/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.graphql.api.resolver.dataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Retrieves mutation type discriminator from the parent {@link Mutation} object.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MutationTypeDataFetcher implements DataFetcher<String> {

	private static MutationTypeDataFetcher INSTANCE = null;

	@Nonnull
	public static MutationTypeDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new MutationTypeDataFetcher();
		}
		return INSTANCE;
	}

	@Override
	public String get(DataFetchingEnvironment environment) throws Exception {
		final Mutation mutation = environment.getSource();
		Assert.isPremiseValid(
			mutation != null,
			() -> new GraphQLQueryResolvingInternalError("Missing parent mutation object.")
		);
		return mutation.getClass().getSimpleName();
	}
}
