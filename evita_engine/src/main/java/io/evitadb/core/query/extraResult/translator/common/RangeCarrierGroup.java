/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.extraResult.translator.common;

import io.evitadb.core.query.algebra.facet.FacetHavingFormula;
import io.evitadb.core.query.algebra.filter.AttributeRangeCarrierFormula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.PriceBetweenFormula;

/**
 * Identifier of one of the three cross-influencing groups of `userFilter` children whose "self" baseline must be
 * computed against a filter formula where **their own group's carriers** are stripped, while the other two groups'
 * carriers remain applied.
 *
 * The three groups arise because each corresponding extra-result projection answers a "what-if over the user's
 * current selection, but within the same domain" question. To be meaningful, each projection must hide the user's
 * current picks **in its own domain** and keep the user's picks **in the other two domains** applied.
 *
 * Passed as a parameter to {@link UserFilterRelaxer#relax(io.evitadb.core.query.algebra.Formula, RangeCarrierGroup)}
 * to select which carrier type is used to recognise formulas that must be stripped from the user filter.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum RangeCarrierGroup {

	/**
	 * Peels every {@link AttributeRangeCarrierFormula} carrier inside `userFilter`. Used by attribute-family
	 * histogram producers (plain attribute, reference-attribute, and referenced-entity-attribute histograms) so the
	 * user's current `attributeBetween` / `histogramHaving` slider pick does not contract the span of that same
	 * slider.
	 */
	ATTRIBUTE_HISTOGRAM,

	/**
	 * Peels every {@link FacetHavingFormula} inside `userFilter`. Used by the facet-impact projection so
	 * "what-if I add / toggle this facet" sees the baseline without the user's own facet picks. Facet **count**
	 * and facet **presence** paths still use the full filter.
	 */
	FACET_IMPACT,

	/**
	 * Peels every {@link PriceBetweenFormula} inside `userFilter`. Used by the price-histogram producer so the
	 * price slider's catalog-wide `[min, max]` does not contract under the user's own price handles.
	 */
	PRICE_HISTOGRAM

}
