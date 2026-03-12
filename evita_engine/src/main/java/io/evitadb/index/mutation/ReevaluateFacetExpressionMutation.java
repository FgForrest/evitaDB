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

package io.evitadb.index.mutation;

import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;

/**
 * Signals that a cross-entity change occurred that may affect the facet indexing expression for the
 * given reference. The source executor detected a relevant attribute change (old != new) on a group
 * or referenced entity but does NOT evaluate the expression or determine add/remove direction.
 *
 * The target-side executor (`ReevaluateFacetExpressionExecutor`):
 *
 * 1. Resolves affected owner entity PKs from local indexes
 * 2. Translates the full expression to a parameterized FilterBy query
 * 3. Evaluates the query against current indexes
 * 4. Compares with current facet state and performs add/remove operations
 *
 * @param referenceName  reference with the `facetedPartially` expression
 * @param mutatedEntityPK the group/referenced entity PK that changed
 * @param dependencyType how the mutated entity relates to the owner
 * @param scope          scope of the expression to re-evaluate
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ReevaluateFacetExpressionMutation(
	@Nonnull String referenceName,
	int mutatedEntityPK,
	@Nonnull DependencyType dependencyType,
	@Nonnull Scope scope
) implements IndexMutation {
}
