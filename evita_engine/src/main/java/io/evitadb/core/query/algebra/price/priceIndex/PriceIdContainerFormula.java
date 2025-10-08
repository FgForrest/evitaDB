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

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.SortingForm;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.utils.Assert;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * This {@link Formula} wraps {@link #getDelegate()} formula that works with entity prices from certain
 * {@link PriceListAndCurrencyPriceIndex}. This formula provides access to the original {@link PriceListAndCurrencyPriceIndex}
 * the data comes from and also provides access to the filtered prices via. {@link #getFilteredPriceRecords()}
 * that links to the price ids that are outputted by this formula.
 *
 * Beware output of this formula are integer price ids (not entity ids)! These price ids needs to be translated to
 * the entity ids later.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PriceIdContainerFormula extends AbstractFormula implements PriceIndexProvidingFormula {
	private static final long CLASS_ID = -1448590239158197683L;

	/**
	 * Contains reference to the {@link PriceListAndCurrencyPriceIndex} the prices (ids or full records) produced by
	 * this formula comes from.
	 */
	@Getter private final PriceListAndCurrencyPriceIndex<?,?> priceIndex;

	public PriceIdContainerFormula(@Nonnull PriceListAndCurrencyPriceIndex<?,?> priceIndex, @Nonnull Formula delegate) {
		this.priceIndex = priceIndex;
		this.initFields(delegate);
	}

	@Nonnull
	@Override
	public Formula getDelegate() {
		return this.innerFormulas[0];
	}

	@Nonnull
	@Override
	public FilteredPriceRecords getFilteredPriceRecords(@Nonnull QueryExecutionContext context) {
		return new ResolvedFilteredPriceRecords(
			this.priceIndex.getPriceRecords(compute()),
			SortingForm.NOT_SORTED
		);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(innerFormulas.length == 1, "Expected exactly single delegate inner formula!");
		return new PriceIdContainerFormula(this.priceIndex, innerFormulas[0]);
	}

	@Override
	public long getOperationCost() {
		return 2067;
	}

	@Override
	public String toString() {
		return "DO WITH PRICES IN INDEX " + this.priceIndex.getPriceIndexKey();
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
		return CLASS_ID;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

}
