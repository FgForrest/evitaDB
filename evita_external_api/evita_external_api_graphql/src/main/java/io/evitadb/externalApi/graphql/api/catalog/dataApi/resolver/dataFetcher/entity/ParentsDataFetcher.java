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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Deque;
import java.util.LinkedList;

/**
 * Returns flattened list of all parents of particular entity.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ParentsDataFetcher implements DataFetcher<Deque<EntityClassifierWithParent>> {

	@Nullable
	private static ParentsDataFetcher INSTANCE;

	@Nonnull
	public static ParentsDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new ParentsDataFetcher();
		}
		return INSTANCE;
	}

	@Nonnull
	@Override
	public Deque<EntityClassifierWithParent> get(DataFetchingEnvironment environment) throws Exception {
		EntityClassifierWithParent entity = environment.getSource();

		// gather all recursive parents and flatten it into list sorted from root
		final Deque<EntityClassifierWithParent> parents = new LinkedList<>();
		EntityClassifierWithParent parent;
		while ((parent = entity.getParentEntity().orElse(null)) != null) {
			parents.addFirst(parent);
			entity = parent;
		}

		return parents;
	}
}
