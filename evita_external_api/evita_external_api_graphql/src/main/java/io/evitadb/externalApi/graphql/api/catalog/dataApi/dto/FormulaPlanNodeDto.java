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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.dto;

import io.evitadb.externalApi.api.catalog.dataApi.dto.FormulaPlanDto;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * GraphQL DTO for one node of a formula plan, which this API returns flattened into a pre-order list - see
 * {@link io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.FormulaPlanNodeDescriptor}.
 *
 * @param id            identity of the formula instance, stable across its occurrences in the plan
 * @param refTo         `null` on the occurrence that describes the instance, equal to `id` on every later one
 * @param level         depth of this node with the plan root at `1`
 * @param hash          structural hash of the formula, i.e. what the cache keys on
 * @param description   human readable description, `null` on a back-reference node
 * @param estimatedCost cost the planner estimated before running anything
 * @param actualCost    cost the formula really incurred, `null` when it was never computed
 * @param resultCount   number of records the formula produced, `null` when it was never computed
 * @param childrenCount number of inner formulas, always zero on a back-reference node
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record FormulaPlanNodeDto(
	int level,
	int id,
	@Nullable Integer refTo,
	long hash,
	@Nullable String description,
	long estimatedCost,
	@Nullable Long actualCost,
	@Nullable Integer resultCount,
	int childrenCount
) {

	/**
	 * Flattens a converted plan into the pre-order list this API publishes.
	 *
	 * @param root root of the plan, or `null` when the step carries none
	 * @return every node of the plan, parents before their children, or `null` when nothing was passed - `null`
	 *         rather than an empty list, so that "no plan was requested" stays distinct from "the plan is empty"
	 */
	@Nullable
	public static List<FormulaPlanNodeDto> flatten(@Nullable FormulaPlanDto root) {
		if (root == null) {
			return null;
		}
		final List<FormulaPlanNodeDto> flattened = new ArrayList<>();
		flatten(flattened, root, 1);
		return flattened;
	}

	/**
	 * Appends a single node and then everything below it, depth first.
	 *
	 * @param flattened list being accumulated into
	 * @param node      node to append
	 * @param level     depth of `node`, with the root of the plan at `1`
	 */
	private static void flatten(@Nonnull List<FormulaPlanNodeDto> flattened,
	                            @Nonnull FormulaPlanDto node,
	                            int level) {
		flattened.add(
			new FormulaPlanNodeDto(
				level,
				node.id(),
				node.refTo(),
				node.hash(),
				node.description(),
				node.estimatedCost(),
				node.actualCost(),
				node.resultCount(),
				node.children().size()
			)
		);
		for (final FormulaPlanDto child : node.children()) {
			flatten(flattened, child, level + 1);
		}
	}
}
