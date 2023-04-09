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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer;

import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.filter.HierarchyWithinRoot;
import io.evitadb.api.query.require.HierarchyOfReference;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.PrefetchRequirementCollector;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.IntFunction;

/**
 * Context captures the context of the top {@link HierarchyOfSelf} / {@link HierarchyOfReference} constraint
 * evaluation context to be used in {@link AbstractHierarchyStatisticsComputer}.
 *
 * @param queryContext                  Reference to the query context that allows to access entity bodies.
 * @param entitySchema                  Target entity schema of the entity.
 * @param referenceSchema               Target entity schema of the entity.
 * @param hierarchyFilter               Contains {@link HierarchyWithin} or {@link HierarchyWithinRoot} filtering query if it was part of the query filter.
 * @param entityIndex                   Contains reference to the owner {@link EntityIndex} of the {@link HierarchyIndex}.
 * @param prefetchRequirementCollector  Reference to the collector of requirements for entity prefetch phase.
 * @param hierarchyReferencingEntityPks Contains a function that produces bitmap of queried entity ids connected with particular hierarchical entity.
 * @param removeEmptyResults            Contains true if hierarchy statistics should be stripped of results with zero occurrences.
 */
public record HierarchyProducerContext(
	@Nonnull QueryContext queryContext,
	@Nonnull EntitySchemaContract entitySchema,
	@Nullable ReferenceSchemaContract referenceSchema,
	@Nullable HierarchyFilterConstraint hierarchyFilter,
	@Nonnull EntityIndex entityIndex,
	@Nullable PrefetchRequirementCollector prefetchRequirementCollector,
	@Nonnull IntFunction<Formula> hierarchyReferencingEntityPks,
	boolean removeEmptyResults
) {

}
