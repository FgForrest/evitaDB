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
 * Defines the logical relation type applied between selected facet options when computing facet summary statistics
 * and filtering results. The relation type can be configured at two granularity levels (see {@link FacetGroupRelationLevel}):
 * between facets within the same group, and between facets that belong to different groups or references.
 *
 * The default evitaDB facet behaviour without any overrides is:
 * - within the same group: `DISJUNCTION` (OR) — multiple selected options of the same attribute are alternatives.
 * - across different groups: `CONJUNCTION` (AND) — selections from different attributes must all match.
 *
 * This default can be overridden globally via {@link FacetCalculationRules}, or per reference/group via
 * `facetGroupsConjunction`, `facetGroupsDisjunction`, `facetGroupsNegation`, and `facetGroupsExclusivity`
 * require constraints that each accept a {@link FacetGroupRelationLevel}.
 *
 * **Relation semantics**
 *
 * - `DISJUNCTION` — logical OR: the result set includes entities matching *any* of the selected facets in the group.
 *   Example: "Red OR Blue" — show products that are red or blue.
 * - `CONJUNCTION` — logical AND: the result set includes only entities matching *all* of the selected facets.
 *   Example: "Waterproof AND Breathable" — show products that are both waterproof and breathable simultaneously.
 * - `NEGATION` — logical AND NOT: entities matching the selected facets are *excluded* from the result set.
 *   Example: "NOT Discontinued" — hide products that are marked discontinued.
 * - `EXCLUSIVITY` — mutually exclusive selection: when a facet in the group is selected, all other facets in the
 *   same group at the same level are automatically deselected. Only one option from the group can be active at
 *   a time. Example: a "Sort by" group where selecting "Price ascending" deselects "Price descending".
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@SupportedEnum
public enum FacetRelationType {

	/**
	 * Logical OR relation.
	 */
	DISJUNCTION,
	/**
	 * Logical AND relation.
	 */
	CONJUNCTION,
	/**
	 * Logical AND NOT relation.
	 */
	NEGATION,
	/**
	 * Exclusive relations to other facets on the same level, when selected no other facet on that level can be selected.
	 */
	EXCLUSIVITY

}
