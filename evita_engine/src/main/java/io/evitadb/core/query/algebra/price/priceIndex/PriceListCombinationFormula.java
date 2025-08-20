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

package io.evitadb.core.query.algebra.price.priceIndex;

import io.evitadb.core.cache.payload.FlattenedFormula;
import io.evitadb.core.cache.payload.FlattenedFormulaWithFilteredPrices;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.price.CacheablePriceFormula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.index.bitmap.Bitmap;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * This is formula that is an extension of {@link NotFormula} that captures the relation between two price lists.
 * Price evaluation is based on price list priority that is derived from the order they are places inside
 * {@link io.evitadb.api.query.filter.PriceInPriceLists} query. Then the lookup for query PRICE_LIST_A,
 * PRICE_LIST_B, PRICE_LIST_C looks like this:
 *
 * - first try ty find price in PRICE_LIST_A
 * - then lookup for price in PRICE_LIST_B, but only for those entities that weren't resolved on previous line
 * - then lookup for price in PRICE_LIST_C, but only for those entities that weren't resolved on previous two lines
 *
 * This formula captures the negated relation for the negated formulas composition of PRICE_LIST_B and PRICE_LIST_C.
 * The formula is cacheable and the idea is that repeated queries that share the partial combination of price lists
 * order can be satisfied from fast cache. I.e. for these queries:
 *
 * - PRICE_LIST_A, PRICE_LIST_B, PRICE_LIST_C
 * - PRICE_LIST_A, PRICE_LIST_B, PRICE_LIST_D
 * - PRICE_LIST_A, PRICE_LIST_B, PRICE_LIST_E
 *
 * We can serve the result of PRICE_LIST_A, PRICE_LIST_B negation from the cache, because the computation logic is
 * shared.
 *
 * TOBEDONE JNO - evaluate effectivnes of the cache because the price list combinations are usually in inverted way:
 *
 * - PRICE_LIST_C, PRICE_LIST_A, PRICE_LIST_B
 * - PRICE_LIST_D, PRICE_LIST_A, PRICE_LIST_B
 * - PRICE_LIST_E, PRICE_LIST_A, PRICE_LIST_B
 *
 * And this disqualifies the real benefit of cacheability of this formula
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class PriceListCombinationFormula extends NotFormula implements CacheablePriceFormula {
	private static final long CLASS_ID = -379304706891548493L;
	@Getter private final Serializable subtractedPriceListName;
	@Getter private final Serializable priceListName;
	@Getter private final PriceEvaluationContext priceEvaluationContext;

	public PriceListCombinationFormula(@Nonnull Serializable subtractedPriceListName, @Nonnull Serializable priceListName, @Nonnull PriceEvaluationContext priceEvaluationContext, @Nonnull Formula subtractedFormula, @Nonnull Formula supersetFormula) {
		super(subtractedFormula, supersetFormula);
		this.subtractedPriceListName = subtractedPriceListName;
		this.priceListName = priceListName;
		this.priceEvaluationContext = priceEvaluationContext;
	}

	protected PriceListCombinationFormula(@Nonnull Consumer<CacheableFormula> computationCallback, @Nonnull Serializable subtractedPriceListName, @Nonnull Serializable priceListName, @Nonnull PriceEvaluationContext priceEvaluationContext, @Nonnull Formula subtractedFormula, @Nonnull Formula supersetFormula) {
		super(computationCallback, subtractedFormula, supersetFormula);
		this.subtractedPriceListName = subtractedPriceListName;
		this.priceListName = priceListName;
		this.priceEvaluationContext = priceEvaluationContext;
	}

	@Override
	public FlattenedFormula toSerializableFormula(long formulaHash, @Nonnull LongHashFunction hashFunction) {
		final Bitmap computationalResult = compute();
		// by this time computation result should be already memoized
		return new FlattenedFormulaWithFilteredPrices(
			formulaHash,
			getTransactionalIdHash(),
			Arrays.stream(gatherTransactionalIds())
				.distinct()
				.sorted()
				.toArray(),
			computationalResult,
			FilteredPriceRecords.createFromFormulas(this, computationalResult, this.executionContext),
			this.priceEvaluationContext
		);
	}

	@Override
	public int getSerializableFormulaSizeEstimate() {
		return FlattenedFormulaWithFilteredPrices.estimateSize(
			gatherTransactionalIds(),
			compute(),
			getPriceEvaluationContext()
		);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		return new PriceListCombinationFormula(this.subtractedPriceListName, this.priceListName, this.priceEvaluationContext, innerFormulas[0], innerFormulas[1]);
	}

	@Nonnull
	@Override
	public CacheableFormula getCloneWithComputationCallback(@Nonnull Consumer<CacheableFormula> selfOperator, @Nonnull Formula... innerFormulas) {
		return new PriceListCombinationFormula(
			selfOperator,
			this.subtractedPriceListName, this.priceListName, this.priceEvaluationContext, innerFormulas[0], innerFormulas[1]
		);
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	public String getCombinedPriceListNames() {
		return this.priceListName + ", " + this.subtractedPriceListName;
	}

	@Override
	public String toString() {
		return "WITH PRICE IN " + this.priceListName + " WHEN NO PRICE EXISTS IN " + this.subtractedPriceListName;
	}
}
