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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Returns {@link EntityContract#getParentEntity()} as nullable int.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ParentPrimaryKeyDataFetcher implements DataFetcher<Integer> {

	@Nullable
	private static ParentPrimaryKeyDataFetcher INSTANCE;

	@Nonnull
	public static ParentPrimaryKeyDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new ParentPrimaryKeyDataFetcher();
		}
		return INSTANCE;
	}

	@Nullable
	@Override
	public Integer get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final SealedEntity entity = environment.getSource();
		return entity.parentAvailable()
			? entity.getParentEntity().map(EntityClassifier::getPrimaryKey).orElse(null)
			: null;
	}
}
