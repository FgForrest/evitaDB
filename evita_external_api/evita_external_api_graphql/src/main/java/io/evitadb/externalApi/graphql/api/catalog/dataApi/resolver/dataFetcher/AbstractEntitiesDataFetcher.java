/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import graphql.GraphQLContext;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.head.Head;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.head;
import static io.evitadb.api.query.QueryConstraints.label;

/**
 * Ancestor for data fetchers handling entities in specific collection.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AbstractEntitiesDataFetcher<T> implements DataFetcher<T> {

	/**
	 * Schema of collection to which this fetcher is mapped to.
	 */
	@Nonnull protected final EntitySchemaContract entitySchema;

	@Nullable
	protected Head buildHead(@Nonnull DataFetchingEnvironment environment) {
		final List<HeadConstraint> headConstraints = new ArrayList<>(3);
		headConstraints.add(collection(entitySchema.getName()));

		final GraphQLContext graphQlContext = environment.getGraphQlContext();
		final UUID sourceRecordingId = graphQlContext.get(GraphQLContextKey.TRAFFIC_SOURCE_QUERY_RECORDING_ID);
		if (sourceRecordingId != null) {
			headConstraints.add(label(Label.LABEL_SOURCE_QUERY, sourceRecordingId));
		}

		return head(headConstraints.toArray(HeadConstraint[]::new));
	}
}
