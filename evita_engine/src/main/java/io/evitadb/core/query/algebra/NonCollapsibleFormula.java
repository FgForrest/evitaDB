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
package io.evitadb.core.query.algebra;

import io.evitadb.core.query.algebra.base.EmptyFormula;

/**
 * Formulas implementing this interface signalize that they carry information later phases of query processing read
 * off the **structure** of the planned formula tree - facet selections, histogram range carriers, the `userFilter`
 * marker - and not merely off the record set the tree computes.
 *
 * This interface will ensure that no conjunctive container holding such a formula anywhere beneath it is replaced by
 * {@link EmptyFormula} when another of its children collapses. The container is kept and left to compute empty on its
 * own, which it still does: a conjunction with an empty child is unsatisfiable regardless of how many children it has.
 *
 * **Why the collapse has to be given up rather than made smarter.** Replacing the container is a pure win for
 * filtering - the record set is identical and the tree is smaller - which is exactly why it is easy to miss that it
 * destroys anything reading the structure. A facet selection that vanishes this way does not surface as an error or
 * an empty summary; it surfaces as a facet reported `requested = false` that the user did in fact request.
 *
 * **The cost of keeping it is close to nothing.** `AbstractFormula#computeSortedConjunctionBitmaps` evaluates a
 * conjunction's children in ascending estimated-cost order and short-circuits on the first empty result, and
 * {@link EmptyFormula} reports zero cost and zero cardinality - so it sorts first, is computed first, and the
 * carrier beside it is never computed at all.
 *
 * This is the optimizer's analogue of {@link NonCacheableFormula}, which protects the same kind of side channel from
 * the cache. **The two sets are deliberately not the same** and must not be merged: `HierarchyFormula` and
 * `CombinedFacetFormula` are non-cacheable without being carriers, while
 * {@link io.evitadb.core.query.algebra.filter.AttributeRangeCarrierFormula} and `PriceBetweenFormula` are carriers
 * that cache perfectly well.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface NonCollapsibleFormula extends Formula {
}
