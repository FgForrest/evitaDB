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

package io.evitadb.api.query.require;

import io.evitadb.dataType.SupportedEnum;

/**
 * Controls the depth of statistics computed for each facet option in a {@link FacetSummary} extra result. The two
 * values represent a cost/detail trade-off:
 *
 * - `COUNTS` (default) — only the number of entities that have the facet selected is computed. This is the cheaper
 *   option because it only requires intersecting the base result set with each facet bitmap. The count is useful for
 *   displaying how many results each facet option represents without any user selection applied.
 * - `IMPACT` — in addition to counts, the engine also computes the *selection impact* for every facet option that
 *   is not already selected. The impact is the predicted change in result count if the user were to add that facet
 *   to the current selection. This allows UIs to show "adding this option would yield N results" next to each facet
 *   checkbox. Impact computation is more expensive because it requires a separate result-set evaluation per facet.
 *
 * The `IMPACT` mode is only meaningful in combination with a `userFilter` that already contains some facet
 * selections; with an empty user filter the impact equals the count.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@SupportedEnum
public enum FacetStatisticsDepth {

	/**
	 * Only counts of facets will be computed.
	 */
	COUNTS,
	/**
	 * Counts and selection impact for non-selected facets will be computed.
	 */
	IMPACT

}
