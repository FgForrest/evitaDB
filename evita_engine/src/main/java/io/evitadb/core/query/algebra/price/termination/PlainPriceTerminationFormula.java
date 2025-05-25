/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.query.algebra.price.termination;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.predicate.PriceAmountPredicate;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * PlainPriceTerminationFormula is a simplified variant of {@link PlainPriceTerminationFormulaWithPriceFilter} for cases
 * when the price filter is not required (which is rather common). In such case we don't need to greedily resolve
 * the entity price, and we may postpone this to the latter stages and when sorting by price is not required avoid such
 * pricey computation altogether.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PlainPriceTerminationFormula extends AbstractFormula implements PriceTerminationFormula {
	private static final long CLASS_ID = -6690961703571256783L;

	/**
	 * Price evaluation context allows optimizing formula tree in the such way, that terminating formula with same
	 * context will be replaced by single instance - taking advantage of result memoization.
	 */
	@Getter private final PriceEvaluationContext priceEvaluationContext;

	public PlainPriceTerminationFormula(@Nonnull Formula containerFormula, @Nonnull PriceEvaluationContext priceEvaluationContext) {
		this.priceEvaluationContext = priceEvaluationContext;
		this.initFields(containerFormula);
	}

	/**
	 * Returns delegate formula of this container.
	 */
	@Nonnull
	public Formula getDelegate() {
		return this.innerFormulas[0];
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		getDelegate().initialize(executionContext);
		super.initialize(executionContext);
	}

	@Nullable
	@Override
	public PriceAmountPredicate getRequestedPredicate() {
		return PriceAmountPredicate.ALL;
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(innerFormulas.length == 1, "Expected exactly single delegate inner formula!");
		return new PlainPriceTerminationFormula(
			innerFormulas[0], this.priceEvaluationContext
		);
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Nonnull
	@Override
	public Formula getCloneWithPricePredicateFilteredOutResults() {
		return this;
	}

	@Override
	public String toString() {
		return PricePredicate.ALL_RECORD_FILTER.toString();
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		return getDelegate().compute();
	}

	@Override
	public int getEstimatedCardinality() {
		return getDelegate().getEstimatedCardinality();
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return this.priceEvaluationContext.computeHash(hashFunction);
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

}
