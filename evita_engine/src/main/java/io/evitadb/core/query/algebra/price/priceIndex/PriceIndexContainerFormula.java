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

import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import io.evitadb.core.query.algebra.identity.TokenRole;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.AbstractCacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.LazyEvaluatedEntityPriceRecords;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.AbstractPriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.utils.Assert;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * Simple delegating formula that doesn't compute anything (computational logic is delegated to {@link #getDelegate()}
 * but maintains reference to the {@link PriceListAndCurrencyPriceIndex index} that was used for computation
 * of the {@link #getDelegate()} result.
 *
 * Formula can be used only for {@link Formula} delegate.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PriceIndexContainerFormula extends AbstractCacheableFormula implements PriceIndexProvidingFormula, Formula {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = 1785319770058715404L;

	/**
	 * Contains reference to the {@link PriceListAndCurrencyPriceIndex index} that was used for computation
	 * of the {@link #getDelegate()} result
	 */
	@Getter private final PriceListAndCurrencyPriceIndex<?> priceIndex;

	/**
	 * The index the lazily evaluated price records are looked up in - always a
	 * {@link io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex}, because only a super index owns the
	 * entity-to-prices mapping needed to answer
	 * {@link PriceListAndCurrencyPriceIndex#getLowestPriceRecordsForEntity(int)}.
	 *
	 * For a super {@link #priceIndex} this is that very index; for a reduced (ref) one it is the super index of the same
	 * price-list / currency combination, resolved by the caller that built this formula from the GLOBAL entity index it
	 * is already pinned to. Keeping it as a separate field is what lets a reduced index stop holding a super-index
	 * pointer of its own.
	 */
	@Getter private final PriceListAndCurrencyPriceIndex<?> lowestPriceRecordsSource;

	public PriceIndexContainerFormula(
		@Nonnull PriceListAndCurrencyPriceIndex<?> priceIndex,
		@Nonnull PriceListAndCurrencyPriceIndex<?> lowestPriceRecordsSource,
		@Nonnull Formula delegate
	) {
		this.priceIndex = priceIndex;
		this.lowestPriceRecordsSource = lowestPriceRecordsSource;
		this.initFields(delegate);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(innerFormulas.length == 1, "Expected exactly single delegate inner formula!");
		return new PriceIndexContainerFormula(
			this.priceIndex, this.lowestPriceRecordsSource, innerFormulas[0]
		);
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Nonnull
	@Override
	public Formula getDelegate() {
		return this.innerFormulas[0];
	}

	@Nonnull
	@Override
	public FilteredPriceRecords getFilteredPriceRecords(@Nonnull QueryExecutionContext context) {
		return new LazyEvaluatedEntityPriceRecords(this.lowestPriceRecordsSource);
	}

	@Override
	public String toString() {
		return "DO WITH PRICE INDEX: " + this.priceIndex.getPriceIndexKey();
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

	/** Returns detached computation inputs, with formula operands carried by the ordered child keys. */
	@Nonnull
	@Override
	protected IdentityRecord identity() {
		return new Identity(new IdentityToken.ProducerId(
				((AbstractPriceListAndCurrencyPriceIndex<?>) this.priceIndex).getId(), TokenRole.DIRECT
			),
			new IdentityToken.ProducerId(
				((AbstractPriceListAndCurrencyPriceIndex<?>) this.lowestPriceRecordsSource).getId(), TokenRole.DIRECT
			));
	}

	/**
	 * Detached computation inputs.
	 *
	 * @param priceIndex version token of the price index read by the formula
	 * @param lowestPriceRecordsSource version token of the index supplying entity price records
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Identity(@Nonnull IdentityToken priceIndex,
		@Nonnull IdentityToken lowestPriceRecordsSource) implements IdentityRecord {
		/** Returns the declaring holder identifier. */
		@Override
		public long classId() {
			return CLASS_ID;
		}

		/** Includes every component in declaration order. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.priceIndex);
			accumulator.add(this.lowestPriceRecordsSource);
		}
	}

}
