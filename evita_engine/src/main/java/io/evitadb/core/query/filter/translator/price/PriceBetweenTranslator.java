/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.filter.translator.price;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.EntityHasNoPricesException;
import io.evitadb.api.query.filter.PriceBetween;
import io.evitadb.api.query.filter.PriceInCurrency;
import io.evitadb.api.query.filter.PriceInPriceLists;
import io.evitadb.api.query.filter.PriceValidIn;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.algebra.price.termination.PricePredicate;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.price.alternative.SellingPriceAvailableBitmapFilter;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.function.Predicate;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link PriceBetween} to {@link Formula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PriceBetweenTranslator extends AbstractPriceRelatedConstraintTranslator<PriceBetween> {

	@Nonnull
	@Override
	public Formula translate(@Nonnull PriceBetween priceBetween, @Nonnull FilterByVisitor filterByVisitor) {
		if (filterByVisitor.isEntityTypeKnown()) {
			final EntitySchemaContract schema = filterByVisitor.getSchema();
			Assert.isTrue(
				schema.isWithPrice(),
				() -> new EntityHasNoPricesException(schema.getName())
			);
		}

		final int indexedPricePlaces = filterByVisitor.getSchema().getIndexedPricePlaces();
		final QueryPriceMode queryPriceMode = filterByVisitor.getQueryPriceMode();

		final OffsetDateTime theMoment = ofNullable(filterByVisitor.findInConjunctionTree(PriceValidIn.class))
			.map(PriceValidIn::getTheMoment)
			.orElse(null);
		final String[] priceLists = ofNullable(filterByVisitor.findInConjunctionTree(PriceInPriceLists.class))
			.map(PriceInPriceLists::getPriceLists)
			.orElse(null);
		final Currency currency = ofNullable(filterByVisitor.findInConjunctionTree(PriceInCurrency.class))
			.map(PriceInCurrency::getCurrency)
			.orElse(null);

		// if no price lists are defined we can't interpret between query - without price lists we cannot choose
		// the selling price to compare with the limited interval
		if (priceLists == null) {
			throw new ContextMissingException(
				"Query context is missing. You cannot use price between query without specifying price lists" +
					" and currency that should be taken into account."
			);
		}

		if (filterByVisitor.isEntityTypeKnown()) {
			// this is hell of the combinatorics - but we need to inject price between logic inside the formulas to stay effective
			// we try hard to reuse original formulas and thus take advantage of memoization effect of already computed sub-results
			final Formula filteringFormula;
			if (currency != null && theMoment != null) {
				final List<Formula> formula = PriceValidInTranslator.INSTANCE.createFormula(filterByVisitor, theMoment, priceLists, currency);
				filteringFormula = applyVisitorAndReturnModifiedResult(
					priceBetween.getFrom(), priceBetween.getTo(), indexedPricePlaces,
					queryPriceMode, formula, filterByVisitor
				);
			} else if (currency == null && theMoment != null) {
				final List<Formula> formula = PriceValidInTranslator.INSTANCE.createFormula(filterByVisitor, theMoment, priceLists, null);
				filteringFormula = applyVisitorAndReturnModifiedResult(
					priceBetween.getFrom(), priceBetween.getTo(), indexedPricePlaces,
					queryPriceMode, formula, filterByVisitor
				);
			} else if (currency == null) {
				final List<Formula> formula = PriceInPriceListsTranslator.INSTANCE.createFormula(filterByVisitor, priceLists, null);
				filteringFormula = applyVisitorAndReturnModifiedResult(
					priceBetween.getFrom(), priceBetween.getTo(), indexedPricePlaces,
					queryPriceMode, formula, filterByVisitor
				);
			} else {
				final List<Formula> formula = PriceInPriceListsTranslator.INSTANCE.createFormula(filterByVisitor, priceLists, currency);
				filteringFormula = applyVisitorAndReturnModifiedResult(
					priceBetween.getFrom(), priceBetween.getTo(), indexedPricePlaces,
					queryPriceMode, formula, filterByVisitor
				);
			}

			// there is no default return so that we completed all combinations in previous if-else hell
			if (filterByVisitor.isPrefetchPossible()) {
				return new SelectionFormula(
					filterByVisitor,
					filteringFormula,
					new SellingPriceAvailableBitmapFilter(
						createPredicate(priceBetween.getFrom(), priceBetween.getTo(), queryPriceMode, indexedPricePlaces)
					)
				);
			} else {
				return filteringFormula;
			}
		} else {
			return new EntityFilteringFormula(
				"price between filter",
				filterByVisitor,
				new SellingPriceAvailableBitmapFilter(
					createPredicate(priceBetween.getFrom(), priceBetween.getTo(), queryPriceMode, indexedPricePlaces)
				)
			);
		}
	}

	/**
	 * Creates predicate used to filter entities by price span.
	 */
	@Nonnull
	public static Predicate<PriceContract> createPredicate(@Nullable BigDecimal from, @Nullable BigDecimal to, @Nonnull QueryPriceMode queryPriceMode, int indexedPricePlaces) {
		final int fromAsInt = ofNullable(from)
			.map(it -> NumberUtils.convertToInt(it, indexedPricePlaces))
			.orElse(Integer.MIN_VALUE);
		final int toAsInt = ofNullable(to)
			.map(it -> NumberUtils.convertToInt(it, indexedPricePlaces))
			.orElse(Integer.MAX_VALUE);

		return queryPriceMode == QueryPriceMode.WITH_TAX ?
			priceContract -> {
				final int priceWithTax = NumberUtils.convertToInt(priceContract.priceWithTax(), indexedPricePlaces);
				return fromAsInt <= priceWithTax && toAsInt >= priceWithTax;
			} :
			priceContract -> {
				final int priceWithoutTax = NumberUtils.convertToInt(priceContract.priceWithoutTax(), indexedPricePlaces);
				return fromAsInt <= priceWithoutTax && toAsInt >= priceWithoutTax;
			};
	}

	/**
	 * Modifies original formula in a way that it incorporates price between formula in it.
	 */
	private Formula applyVisitorAndReturnModifiedResult(
		@Nullable BigDecimal from,
		@Nullable BigDecimal to,
		int indexedPricePlaces,
		@Nonnull QueryPriceMode queryPriceMode,
		@Nonnull List<Formula> formula,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final PricePredicate priceFilter = queryPriceMode == QueryPriceMode.WITH_TAX ?
			new PricePredicateBetweenPriceWithTax(from, to, indexedPricePlaces) :
			new PricePredicateBetweenPriceWithoutTax(from, to, indexedPricePlaces);

		return PriceListCompositionTerminationVisitor.translate(
			formula, filterByVisitor.getQueryPriceMode(), priceFilter
		);
	}

	private static class PricePredicateBetweenPriceWithTax extends PricePredicate {
		@Getter private final BigDecimal from;
		@Getter private final BigDecimal to;
		private final int fromAsInt;
		private final int toAsInt;

		public PricePredicateBetweenPriceWithTax(@Nullable BigDecimal from, @Nullable BigDecimal to, int indexedPricePlaces) {
			super("ENTITY PRICE WITH TAX BETWEEN " + from + " AND " + to);
			this.from = from;
			this.to = to;
			this.fromAsInt = ofNullable(from)
				.map(it -> NumberUtils.convertToInt(it, indexedPricePlaces))
				.orElse(Integer.MIN_VALUE);
			this.toAsInt = ofNullable(to)
				.map(it -> NumberUtils.convertToInt(it, indexedPricePlaces))
				.orElse(Integer.MAX_VALUE);
		}

		@Override
		public boolean test(PriceRecordContract priceRecord) {
			return priceRecord.priceWithTax() >= fromAsInt && priceRecord.priceWithTax() <= toAsInt;
		}

		@Override
		public long computeHash(@Nonnull LongHashFunction hashFunction) {
			return hashFunction.hashInts(new int[]{fromAsInt, toAsInt});
		}

	}

	private static class PricePredicateBetweenPriceWithoutTax extends PricePredicate {
		@Getter private final BigDecimal from;
		@Getter private final BigDecimal to;
		private final int fromAsInt;
		private final int toAsInt;

		public PricePredicateBetweenPriceWithoutTax(@Nullable BigDecimal from, @Nullable BigDecimal to, int indexedPricePlaces) {
			super("ENTITY PRICE WITHOUT TAX BETWEEN " + from + " AND " + to);
			this.from = from;
			this.to = to;
			this.fromAsInt = ofNullable(from)
				.map(it -> NumberUtils.convertToInt(it, indexedPricePlaces))
				.orElse(Integer.MIN_VALUE);
			this.toAsInt = ofNullable(to)
				.map(it -> NumberUtils.convertToInt(it, indexedPricePlaces))
				.orElse(Integer.MAX_VALUE);
		}

		@Override
		public boolean test(PriceRecordContract priceRecord) {
			return priceRecord.priceWithoutTax() >= fromAsInt && priceRecord.priceWithoutTax() <= toAsInt;
		}

		@Override
		public long computeHash(@Nonnull LongHashFunction hashFunction) {
			return hashFunction.hashInts(new int[]{fromAsInt, toAsInt});
		}
	}
}
