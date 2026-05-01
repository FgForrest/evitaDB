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

package io.evitadb.core.query.algebra.facet;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.ChildrenDependentFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.NonCacheableFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * Pass-through wrapper emitted by `FacetHavingTranslator` around the composite formula it builds for a
 * `facetHaving(...)` constraint. The wrapper exists solely so the FACET_IMPACT relaxer can locate the facet
 * selection by **type** and peel it from `userFilter` — see
 * {@link io.evitadb.core.query.extraResult.translator.common.UserFilterRelaxer}.
 *
 * A dedicated wrapper is used rather than tagging the concrete `FacetGroupAndFormula` /
 * `FacetGroupOrFormula` / `CombinedFacetFormula` shapes because those same classes are also emitted by
 * `ImpactFormulaGenerator` and `FacetFormulaGenerator` when synthesising "what-if" probes whose impact is being
 * projected. Tagging the concrete classes would leak the marker into impact synthesis and the relaxer would strip
 * its own probes. Tagging only the outermost translator wrapper keeps the two contexts disjoint.
 *
 * All cost methods delegate to the single inner formula so the wrapper is invisible to the query planner.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FacetHavingFormula extends AbstractFormula implements ChildrenDependentFormula, NonCacheableFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = -3385966190864050315L;
	/**
	 * Error message thrown when the formula is constructed with anything other than a single inner formula.
	 */
	private static final String ERROR_SINGLE_FORMULA_EXPECTED = "Exactly one inner formula is expected!";
	/**
	 * Name of the reference that this `facetHaving` constraint targets (e.g. `"brands"`). Mirrors
	 * {@link io.evitadb.api.query.filter.FacetHaving#getReferenceName()}, which is `@Nonnull` and validated
	 * non-empty by the `@Classifier` contract — facets never apply to the queried entity itself, so this field
	 * is always populated. Used by downstream planners — most notably hierarchy-statistics — to identify and
	 * strip user-filter facet selections that target the same reference for which statistics are being computed,
	 * preserving the `COMPLETE_FILTER_EXCLUDING_SELF_IN_USER_FILTER` semantics.
	 */
	@Nonnull private final String referenceName;

	/**
	 * Creates a new {@link FacetHavingFormula} wrapping the given composite facet formula and remembering which
	 * reference name (`facetHaving(referenceName, …)`) it represents.
	 *
	 * @param referenceName name of the reference this wrapper targets; never {@code null} or empty (matches
	 *                      {@link io.evitadb.api.query.filter.FacetHaving#getReferenceName()})
	 * @param innerFormula  the single child formula to delegate to
	 */
	public FacetHavingFormula(@Nonnull String referenceName, @Nonnull Formula innerFormula) {
		this.referenceName = referenceName;
		this.initFields(innerFormula);
	}

	/**
	 * Returns the name of the reference this `facetHaving` wrapper targets — never {@code null} or empty.
	 */
	@Nonnull
	public String getReferenceName() {
		return this.referenceName;
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isTrue(innerFormulas.length == 1, ERROR_SINGLE_FORMULA_EXPECTED);
		return new FacetHavingFormula(this.referenceName, innerFormulas[0]);
	}

	@Override
	public long getOperationCost() {
		return this.innerFormulas[0].getOperationCost();
	}

	@Override
	public int getEstimatedCardinality() {
		return this.innerFormulas[0].getEstimatedCardinality();
	}

	@Override
	protected long getEstimatedCostInternal() {
		return this.innerFormulas[0].getEstimatedCost();
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return CLASS_ID + hashFunction.hashChars(this.referenceName) * 31;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		// single-formula invariant is guaranteed by the constructor and getCloneWithInnerFormulas
		return this.innerFormulas[0].compute();
	}

	@Override
	public String toString() {
		return "FACET HAVING";
	}

	@Nonnull
	@Override
	public String toStringVerbose() {
		return "FACET HAVING (" + this.innerFormulas[0].toStringVerbose() + ")";
	}
}
