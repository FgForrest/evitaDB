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

package io.evitadb.api.query.require;

import io.evitadb.dataType.SupportedEnum;

/**
 * Defines the granularity level at which a {@link FacetRelationType} override applies when configuring facet
 * summary calculation rules via `facetGroupsConjunction`, `facetGroupsDisjunction`, `facetGroupsNegation`, or
 * `facetGroupsExclusivity` require constraints.
 *
 * evitaDB evaluates facet relations at two logical levels of the facet hierarchy:
 *
 * - `WITH_DIFFERENT_FACETS_IN_GROUP` — the relation type governs how multiple selected facets *within the same
 *   reference and the same group entity* are combined. By default this level uses `DISJUNCTION` (OR), meaning
 *   that selecting "Red" and "Blue" in a "Color" group yields products that are red **or** blue. A constraint at
 *   this level overrides that default for the specified reference/group combination.
 * - `WITH_DIFFERENT_GROUPS` — the relation type governs how selections from *different groups or different
 *   references* interact with each other. By default this level uses `CONJUNCTION` (AND), meaning that selecting
 *   "Red" from "Color" and "Large" from "Size" yields products that are red **and** large. A constraint at this
 *   level overrides that default for the specified reference.
 *
 * The two levels are orthogonal: specifying a conjunction at `WITH_DIFFERENT_FACETS_IN_GROUP` only affects
 * intra-group logic, while `WITH_DIFFERENT_GROUPS` only affects inter-group logic.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@SupportedEnum
public enum FacetGroupRelationLevel {

	/**
	 * Defines relation type between two facets in the same group and reference.
	 */
	WITH_DIFFERENT_FACETS_IN_GROUP,

	/**
	 * Defines relation type between two facets in the different groups or references.
	 */
	WITH_DIFFERENT_GROUPS

}
