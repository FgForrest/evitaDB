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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.query.require.HierarchyParentsBehaviour;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

/**
 * Returns the flattened parent chain of a particular entity exactly as it was fetched, sorted from the root down to
 * the immediate parent.
 *
 * Unlike {@link ParentsDataFetcher} it hides nothing: an ancestor whose requested body could not be materialized is
 * reported as the bodyless pointer the {@link HierarchyParentsBehaviour#COMPLETE} behaviour substituted for it, and
 * the ancestors above it - which may well carry bodies - follow it in the list. Telling the two apart is the union
 * type resolver's job, see {@link ParentUnionTypeResolver}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ParentsCompleteDataFetcher implements DataFetcher<Deque<EntityClassifierWithParent>> {

	@Nullable
	private static ParentsCompleteDataFetcher INSTANCE;

	@Nonnull
	public static ParentsCompleteDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new ParentsCompleteDataFetcher();
		}
		return INSTANCE;
	}

	@Nonnull
	@Override
	public Deque<EntityClassifierWithParent> get(DataFetchingEnvironment environment) throws Exception {
		final List<EntityClassifierWithParent> ancestors = ParentsDataFetcher.collectAncestors(environment.getSource());

		// the chain is collected from the immediate parent upwards and prepended,
		// so that the result reads from the root
		final Deque<EntityClassifierWithParent> parents = new LinkedList<>();
		for (EntityClassifierWithParent ancestor : ancestors) {
			parents.addFirst(ancestor);
		}
		return parents;
	}
}
