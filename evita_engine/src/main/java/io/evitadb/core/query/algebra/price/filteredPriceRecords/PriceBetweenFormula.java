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

package io.evitadb.core.query.algebra.price.filteredPriceRecords;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.ChildrenDependentFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * Pass-through wrapper emitted by `PriceBetweenTranslator` around the composite formula it builds for a
 * `priceBetween(...)` constraint. The wrapper exists solely so the PRICE_HISTOGRAM relaxer can locate the
 * price-range selection by **type** and peel it from `userFilter` — see
 * {@link io.evitadb.core.query.extraResult.translator.common.UserFilterRelaxer}.
 *
 * A dedicated wrapper is used rather than tagging the concrete price-filter shapes because the concrete shapes
 * (`FilteredPriceRecords` terminators, `PriceListCompositionTerminationVisitor` output, optional
 * `SelectionFormula` prefetch wrapping, `EntityFilteringFormula` when the entity type is unknown) are also emitted
 * by other price-filter translators such as `priceInPriceLists` / `priceValidIn`. Tagging them globally would leak
 * the marker to those other constraints and the relaxer would strip their contributions, breaking price resolution
 * completely.
 *
 * All cost methods delegate to the single inner formula so the wrapper is invisible to the query planner.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class PriceBetweenFormula extends AbstractFormula implements ChildrenDependentFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = -8018064784634525540L;
	/**
	 * Error message thrown when the formula is constructed with anything other than a single inner formula.
	 */
	private static final String ERROR_SINGLE_FORMULA_EXPECTED = "Exactly one inner formula is expected!";

	/**
	 * Creates a new {@link PriceBetweenFormula} wrapping the given price-filter subtree.
	 *
	 * @param innerFormula the single child formula to delegate to
	 */
	public PriceBetweenFormula(@Nonnull Formula innerFormula) {
		this.initFields(innerFormula);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isTrue(innerFormulas.length == 1, ERROR_SINGLE_FORMULA_EXPECTED);
		return new PriceBetweenFormula(innerFormulas[0]);
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
		return CLASS_ID;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		if (this.innerFormulas.length == 1) {
			return this.innerFormulas[0].compute();
		}
		throw new GenericEvitaInternalError(ERROR_SINGLE_FORMULA_EXPECTED);
	}

	@Override
	public String toString() {
		return "PRICE BETWEEN";
	}

	@Nonnull
	@Override
	public String toStringVerbose() {
		return "PRICE BETWEEN (" + this.innerFormulas[0].toStringVerbose() + ")";
	}
}
