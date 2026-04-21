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

package io.evitadb.core.query.filter.translator.histogram;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;

/**
 * Plan-time resolution of a single `histogramHaving` carrier produced by {@link HistogramHavingTranslator}
 * during filter translation and consumed by `ReferenceHistogramStatisticsTranslator` during extra-result
 * planning. Storing the fully resolved tuple on {@link io.evitadb.core.query.QueryPlanningContext}
 * eliminates a second walk of the filter tree and a duplicate group-selector bitmap resolution in the
 * extractor — the translator has already paid that cost once while building the rewrite.
 *
 * All fields are final, materialised at the moment of translation, and independent of any further filter
 * manipulation performed downstream.
 *
 * @param referenceName the reference hosting the histogram slot
 * @param histogramName the resolved histogram name — the single-histogram shorthand has already been
 *                      resolved to the concrete name, so this is never null
 * @param groupPk       the resolved group primary key, or {@link #NON_GROUPED_SENTINEL} when the
 *                      `histogramHaving` carried no `groupSelector` (ungrouped slot)
 * @param from          inclusive lower bound in {@link BigDecimal} form; `null` means "no lower bound"
 * @param to            inclusive upper bound in {@link BigDecimal} form; `null` means "no upper bound"
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ResolvedHistogramHaving(
	@Nonnull String referenceName,
	@Nonnull String histogramName,
	int groupPk,
	@Nullable BigDecimal from,
	@Nullable BigDecimal to
) {

	/**
	 * Sentinel group primary key used when a `histogramHaving` omits its `groupSelector`. Kept in sync with
	 * {@code ReferenceSummaryProducer.HistogramRequest#NON_GROUPED_SENTINEL}; a negative value guarantees
	 * disjointness from any legitimate entity primary key (entity PKs are non-negative).
	 */
	public static final int NON_GROUPED_SENTINEL = -1;

}
