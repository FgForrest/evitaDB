/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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


import io.evitadb.core.query.algebra.AbstractCacheableFormula;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.function.Consumer;

/**
 * Formula container that behaves exactly as {@link OrFormula}, but carries information about price lists, currency and
 * validIn date and time that was used for creating internal formulas of this container. Those information can be easily
 * located and reused in the future phases of query evaluation (for example for calculating discounts).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class PriceFilteringEnvelopeContainer extends AbstractCacheableFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractCacheableFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = -1722354238488401487L;
	/**
	 * Array of price list names used as filter criteria for the enclosed price formulas.
	 */
	@Nullable @Getter private final String[] priceLists;
	/**
	 * Currency used as filter criteria for the enclosed price formulas.
	 */
	@Nullable @Getter private final Currency currency;
	/**
	 * Date and time used to filter prices by their validity period.
	 */
	@Nullable @Getter private final OffsetDateTime validIn;
	/**
	 * Callback operator invoked when the formula computation result is available for caching.
	 */
	@Nullable Consumer<CacheableFormula> selfOperator;

	public PriceFilteringEnvelopeContainer(
		@Nullable String[] priceLists,
		@Nullable Currency currency,
		@Nullable OffsetDateTime validIn,
		@Nonnull Formula... innerFormulas
	) {
		super(null);
		this.selfOperator = null;
		this.priceLists = priceLists;
		this.currency = currency;
		this.validIn = validIn;
		this.initFields(innerFormulas);
	}

	private PriceFilteringEnvelopeContainer(
		@Nonnull Consumer<CacheableFormula> selfOperator,
		@Nullable String[] priceLists,
		@Nullable Currency currency,
		@Nullable OffsetDateTime validIn,
		@Nonnull Formula... innerFormulas
	) {
		super(selfOperator);
		this.priceLists = priceLists;
		this.currency = currency;
		this.validIn = validIn;
		this.initFields(innerFormulas);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		if (innerFormulas.length == 0) {
			return EmptyFormula.INSTANCE;
		} else {
			return new PriceFilteringEnvelopeContainer(this.priceLists, this.currency, this.validIn, innerFormulas);
		}
	}

	@Override
	public int getEstimatedCardinality() {
		int sum = 0;
		for (int i = 0; i < this.innerFormulas.length; i++) {
			sum += this.innerFormulas[i].getEstimatedCardinality();
		}
		return sum;
	}

	@Override
	public long getOperationCost() {
		return 11;
	}

	@Nonnull
	@Override
	public CacheableFormula getCloneWithComputationCallback(@Nonnull Consumer<CacheableFormula> selfOperator, @Nonnull Formula... innerFormulas) {
		return new PriceFilteringEnvelopeContainer(
			selfOperator,
			this.priceLists,
			this.currency,
			this.validIn,
			innerFormulas
		);
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return 0L;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		final Bitmap theResult;
		final RoaringBitmap[] theBitmaps = getRoaringBitmaps();
		if (theBitmaps.length == 0) {
			theResult = EmptyBitmap.INSTANCE;
		} else if (theBitmaps.length == 1) {
			theResult = new BaseBitmap(theBitmaps[0]);
		} else {
			theResult = new BaseBitmap(RoaringBitmap.or(theBitmaps));
		}
		return theResult.isEmpty() ? EmptyBitmap.INSTANCE : theResult;
	}

	@Override
	public String toString() {
		return "PRICE FILTER CONTAINER - OR (" +
			(this.priceLists == null ? "" : Arrays.toString(this.priceLists)) +
			(this.currency == null ? "" : this.currency.getCurrencyCode()) +
			(this.validIn == null ? "" : this.validIn) +
			")";
	}

	/*
		PRIVATE METHODS
	 */

	@Nonnull
	private RoaringBitmap[] getRoaringBitmaps() {
		final Formula[] formulas = getInnerFormulas();
		final RoaringBitmap[] bitmaps = new RoaringBitmap[formulas.length];
		for (int i = 0; i < formulas.length; i++) {
			bitmaps[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(formulas[i].compute());
		}
		return bitmaps;
	}

}
