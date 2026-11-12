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

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.price.CacheablePriceFormula;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serializable;

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
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class PriceListCombinationFormula extends NotFormula implements CacheablePriceFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = -379304706891548493L;
	/**
	 * Name of the price list whose entity ids are subtracted from the superset formula result.
	 */
	@Getter private final Serializable subtractedPriceListName;
	/**
	 * Name of the price list whose entity ids form the superset formula result.
	 */
	@Getter private final Serializable priceListName;
	/**
	 * Price evaluation context carrying information about price lists and currency used for price evaluation.
	 */
	private final PriceEvaluationContext priceEvaluationContext;

	public PriceListCombinationFormula(@Nonnull Serializable subtractedPriceListName, @Nonnull Serializable priceListName, @Nonnull PriceEvaluationContext priceEvaluationContext, @Nonnull Formula subtractedFormula, @Nonnull Formula supersetFormula) {
		super(subtractedFormula, supersetFormula);
		this.subtractedPriceListName = subtractedPriceListName;
		this.priceListName = priceListName;
		this.priceEvaluationContext = priceEvaluationContext;
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		return new PriceListCombinationFormula(this.subtractedPriceListName, this.priceListName, this.priceEvaluationContext, innerFormulas[0], innerFormulas[1]);
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
