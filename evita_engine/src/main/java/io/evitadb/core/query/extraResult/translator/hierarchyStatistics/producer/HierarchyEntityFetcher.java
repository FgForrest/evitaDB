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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer;

import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.query.QueryExecutionContext;

import javax.annotation.Nonnull;
import java.util.function.BiFunction;

/**
 * Symbolic interface for fetching proper instance of {@link EntityClassifier} according to the {@link EntityFetch}
 * requirement.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface HierarchyEntityFetcher extends BiFunction<QueryExecutionContext, Integer, EntityClassifier> {

	/**
	 * Returns the classifier standing for the hierarchy node of the given primary key. The result is never `null`:
	 * a node whose requested body cannot be materialized is represented by a bodiless {@link EntityReference}
	 * rather than dropped, so the statistics tree keeps the shape the hierarchy index actually has.
	 *
	 * @param executionContext the context the entity is fetched in
	 * @param entityPk         primary key of the hierarchy node to represent
	 * @return a {@link SealedEntity} when the requested body could be materialized, a bodiless
	 *         {@link EntityReference} otherwise
	 */
	@Nonnull
	@Override
	EntityClassifier apply(QueryExecutionContext executionContext, Integer entityPk);

}
