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

package io.evitadb.core.query.algebra.price.predicate;

import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate.NoPriceContractPredicate;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate.NoPriceRecordPredicate;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate.PriceContractPredicate;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate.PriceRecordPredicate;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * This class extends standard Java {@link Predicate} with custom {@link #toString()} implementation that is set from
 * outside. This text describes the logic hidden in the predicate so that it could be used in generic price formulas
 * {@link Formula} toString methods.
 */
@RequiredArgsConstructor
public sealed abstract class PricePredicate implements PricePredicateContract
	permits NoPriceContractPredicate, NoPriceRecordPredicate, PriceContractPredicate, PriceRecordPredicate {
	public static final io.evitadb.core.query.algebra.price.predicate.PriceContractPredicate ALL_CONTRACT_FILTER = new NoPriceContractPredicate();
	public static final io.evitadb.core.query.algebra.price.predicate.PriceRecordPredicate ALL_RECORD_FILTER = new NoPriceRecordPredicate();

	/**
	 * Contains brief description of what predicate does.
	 */
	private final String description;
	@Getter private final QueryPriceMode queryPriceMode;
	@Getter private final BigDecimal from;
	@Getter private final BigDecimal to;
	@Getter private final int indexedPricePlaces;
	private final int fromAsInt;
	private final int toAsInt;
	protected final Function<PriceContract, BigDecimal> priceExtractor;
	protected final ToIntFunction<PriceRecordContract> priceRecordExtractor;
	protected final PriceAmountPredicate amountPredicate;
	protected final IntPredicate intAmountPredicate;

	protected PricePredicate(
		@Nullable BigDecimal from,
		@Nullable BigDecimal to,
		@Nullable QueryPriceMode queryPriceMode,
		int indexedPricePlaces
	) {
		this.queryPriceMode = queryPriceMode;
		this.fromAsInt = from == null ? Integer.MIN_VALUE : NumberUtils.convertExternalNumberToInt(from, indexedPricePlaces);
		this.toAsInt = to == null ? Integer.MAX_VALUE : NumberUtils.convertExternalNumberToInt(to, indexedPricePlaces);
		this.indexedPricePlaces = indexedPricePlaces;
		if (queryPriceMode == null) {
			this.description = "NO FILTER PREDICATE";
			this.priceExtractor = priceContract -> BigDecimal.ZERO;
			this.priceRecordExtractor = value -> 0;
		} else {
			switch (queryPriceMode) {
				case WITH_TAX:
					this.description = "ENTITY PRICE WITH TAX BETWEEN " + from + " AND " + to;
					this.priceExtractor = PriceContract::priceWithTax;
					this.priceRecordExtractor = PriceRecordContract::priceWithTax;
					break;
				case WITHOUT_TAX:
					this.description = "ENTITY PRICE WITHOUT TAX BETWEEN " + from + " AND " + to;
					this.priceExtractor = PriceContract::priceWithoutTax;
					this.priceRecordExtractor = PriceRecordContract::priceWithoutTax;
					break;
				default:
					throw new GenericEvitaInternalError(
						"Unsupported query price mode: " + queryPriceMode,
						"Unsupported query price mode."
					);
			}
		}
		this.from = from;
		this.to = to;
		this.amountPredicate = new PriceAmountPredicate(
			queryPriceMode, from, to, indexedPricePlaces,
			amount ->  {
				final int amountAsInt = NumberUtils.convertExternalNumberToInt(amount, indexedPricePlaces);
				return amountAsInt >= this.fromAsInt && amountAsInt <= this.toAsInt;
			}
		);
		this.intAmountPredicate = amount -> amount >= this.fromAsInt && amount <= this.toAsInt;
	}

	@Override
	@Nonnull
	public PriceAmountPredicate getRequestedPredicate() {
		return this.amountPredicate;
	}

	@Override
	public long computeHash(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashInts(
			new int[] {
				this.queryPriceMode == null ? 0 : this.queryPriceMode.ordinal(),
				this.indexedPricePlaces,
				this.fromAsInt,
				this.toAsInt
			}
		);
	}

	@Override
	public String toString() {
		return this.description;
	}

	/**
	 * A private static class that represents a predicate for filtering {@link PriceRecordContract} that always returns
	 * true - which effectively fiters out no records.
	 */
	static final class NoPriceRecordPredicate extends PricePredicate implements io.evitadb.core.query.algebra.price.predicate.PriceRecordPredicate {

		public NoPriceRecordPredicate() {
			super(null, null, null, 0);
		}

		@Override
		public boolean test(PriceRecordContract priceRecordContract) {
			return true;
		}
	}

	/**
	 * A private static class that represents a predicate for filtering {@link PriceRecordContract} that always returns
	 * true - which effectively fiters out no records.
	 */
	static final class NoPriceContractPredicate extends PricePredicate implements io.evitadb.core.query.algebra.price.predicate.PriceContractPredicate {

		public NoPriceContractPredicate() {
			super(null, null, null, 0);
		}

		@Override
		public boolean test(PriceContract priceRecordContract) {
			return true;
		}
	}

	/**
	 * This implementation provides a predicate for filtering {@link PriceContract} based on the price amount.
	 */
	public static final class PriceContractPredicate extends PricePredicate implements io.evitadb.core.query.algebra.price.predicate.PriceContractPredicate {

		public PriceContractPredicate(
			@Nullable BigDecimal from,
			@Nullable BigDecimal to,
			@Nonnull QueryPriceMode queryPriceMode,
			int indexedPricePlaces
		) {
			super(from, to, queryPriceMode, indexedPricePlaces);
		}

		@Override
		public boolean test(PriceContract priceContract) {
			return this.amountPredicate.test(this.priceExtractor.apply(priceContract));
		}

	}

	/**
	 * This implementation provides a predicate for filtering {@link PriceRecordContract} based on the price amount.
	 */
	public static final class PriceRecordPredicate extends PricePredicate implements io.evitadb.core.query.algebra.price.predicate.PriceRecordPredicate {

		public PriceRecordPredicate(
			@Nullable BigDecimal from,
			@Nullable BigDecimal to,
			@Nonnull QueryPriceMode queryPriceMode,
			int indexedPricePlaces
		) {
			super(from, to, queryPriceMode, indexedPricePlaces);
		}

		@Override
		public boolean test(PriceRecordContract priceRecordContract) {
			return this.intAmountPredicate.test(this.priceRecordExtractor.applyAsInt(priceRecordContract));
		}

	}

}
