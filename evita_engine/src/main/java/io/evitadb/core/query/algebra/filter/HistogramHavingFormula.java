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

package io.evitadb.core.query.algebra.filter;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * Pass-through wrapper emitted by `HistogramHavingTranslator` around the equivalent
 * `referenceHaving(...) / entityHaving(...) / attributeBetween(...)` subtree it rewrites the user-facing
 * `histogramHaving(...)` constraint into. The wrapper implements {@link AttributeRangeCarrierFormula} so the
 * attribute-histogram relaxer peels it — together with every other {@link AttributeRangeCarrierFormula} carrier in
 * the same `userFilter` — when computing the histogram's `[min, max]` baseline, preventing the user's current slider
 * pick from contracting its own span.
 *
 * All cost methods delegate to the single inner formula so the wrapper is invisible to the query planner.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class HistogramHavingFormula extends AbstractFormula implements AttributeRangeCarrierFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = 5381325896905773047L;
	/**
	 * Error message thrown when the formula is constructed with anything other than a single inner formula.
	 */
	private static final String ERROR_SINGLE_FORMULA_EXPECTED = "Exactly one inner formula is expected!";

	/**
	 * Creates a new {@link HistogramHavingFormula} wrapping the given rewritten subtree. The subtree must evaluate
	 * to the same bitmap that the original `histogramHaving(...)` constraint would narrow the result set to.
	 *
	 * @param innerFormula the single child formula to delegate to
	 */
	public HistogramHavingFormula(@Nonnull Formula innerFormula) {
		this.initFields(innerFormula);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isTrue(innerFormulas.length == 1, ERROR_SINGLE_FORMULA_EXPECTED);
		return new HistogramHavingFormula(innerFormulas[0]);
	}

	@Override
	public long getOperationCost() {
		// pass-through wrapper — forward the child's operation cost so the planner sees no overhead
		return this.innerFormulas[0].getOperationCost();
	}

	@Override
	public int getEstimatedCardinality() {
		// pass-through wrapper — cardinality is whatever the child produces
		return this.innerFormulas[0].getEstimatedCardinality();
	}

	@Override
	protected long getEstimatedCostInternal() {
		// pass-through wrapper — cost is whatever the child's cost is, with no additional term
		return this.innerFormulas[0].getEstimatedCost();
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return CLASS_ID;
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
		return "HISTOGRAM HAVING";
	}

	@Nonnull
	@Override
	public String toStringVerbose() {
		return "HISTOGRAM HAVING (" + this.innerFormulas[0].toStringVerbose() + ")";
	}
}
