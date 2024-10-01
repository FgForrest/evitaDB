/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.query.filter.translator.price;

import io.evitadb.api.exception.EntityHasNoPricesException;
import io.evitadb.api.query.filter.PriceBetween;
import io.evitadb.api.query.filter.PriceInCurrency;
import io.evitadb.api.query.filter.PriceInPriceLists;
import io.evitadb.api.query.filter.PriceValidIn;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.infra.SkipFormula;
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.price.alternative.SellingPriceAvailableBitmapFilter;
import io.evitadb.core.query.sort.price.translator.PriceDiscountTranslator;
import io.evitadb.function.TriFunction;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Currency;
import java.util.List;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link PriceInPriceLists} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PriceInPriceListsTranslator extends AbstractPriceRelatedConstraintTranslator<PriceInPriceLists> {
	static final PriceInPriceListsTranslator INSTANCE = new PriceInPriceListsTranslator();

	@Nonnull
	@Override
	public Formula translate(@Nonnull PriceInPriceLists priceInPriceLists, @Nonnull FilterByVisitor filterByVisitor) {
		if (filterByVisitor.isEntityTypeKnown()) {
			final EntitySchemaContract schema = filterByVisitor.getSchema();
			Assert.isTrue(
				schema.isWithPrice(),
				() -> new EntityHasNoPricesException(schema.getName())
			);
		}

		// if there are any more specific constraints - skip itself
		//noinspection unchecked
		if (filterByVisitor.isAnyConstraintPresentInConjunctionScopeExcludingUserFilter(PriceValidIn.class, PriceBetween.class)) {
			return SkipFormula.INSTANCE;
		} else {
			final String[] priceLists = priceInPriceLists.getPriceLists();
			if (priceLists.length == 0) {
				return EmptyFormula.INSTANCE;
			}

			final Currency currency = ofNullable(filterByVisitor.findInConjunctionTree(PriceInCurrency.class))
				.map(PriceInCurrency::getCurrency)
				.orElse(null);

			if (filterByVisitor.isEntityTypeKnown()) {
				final List<Formula> priceListFormula = createFormula(filterByVisitor, priceLists, currency);
				final Formula filteringFormula = PriceListCompositionTerminationVisitor.translate(
					priceListFormula, priceLists, currency, null, filterByVisitor.getQueryPriceMode(), null
				);
				if (filterByVisitor.isPrefetchPossible()) {
					return new SelectionFormula(
						filteringFormula,
						new SellingPriceAvailableBitmapFilter(
							filterByVisitor.getEvitaRequest().getFetchesAdditionalPriceLists()
						)
					);
				} else {
					return filteringFormula;
				}
			} else {
				return new EntityFilteringFormula(
					"price in price lists filter",
					new SellingPriceAvailableBitmapFilter(
						filterByVisitor.getEvitaRequest().getFetchesAdditionalPriceLists()
					)
				);
			}
		}
	}

	/**
	 * Method creates formula for {@link PriceInPriceLists} filtering query.
	 * Method is reused from {@link PriceBetweenTranslator} that builds upon this translator and {@link PriceDiscountTranslator}
	 * that uses it to search for alternative price that makes up the discount.
	 */
	@Nonnull
	public static List<Formula> createFormula(@Nonnull FilterByVisitor filterByVisitor, @Nonnull String[] priceLists, @Nullable Currency currency) {
		final TriFunction<String, Currency, PriceInnerRecordHandling, Formula> priceListFormulaComputer;
		if (currency == null) {
			// we don't have currency - we need to join records for all currencies in a single OR query
			priceListFormulaComputer = (priceList, curr, innerRecordHandling) -> filterByVisitor.applyOnIndexes(
				entityIndex -> FormulaFactory.or(
					entityIndex
						.getPriceListAndCurrencyIndexes()
						.stream()
						.filter(it -> {
							final PriceIndexKey priceIndexKey = it.getPriceIndexKey();
							return priceList.equals(priceIndexKey.getPriceList()) &&
								innerRecordHandling.equals(priceIndexKey.getRecordHandling());
						})
						.map(PriceListAndCurrencyPriceIndex::createPriceIndexFormulaWithAllRecords)
						.toArray(Formula[]::new)
				)
			);
		} else {
			// this is the easy way - we have both price list name and currency, we may use data from the specialized index
			priceListFormulaComputer = (priceList, curr, innerRecordHandling) -> filterByVisitor.applyOnIndexes(
				entityIndex -> ofNullable(entityIndex.getPriceIndex(priceList, currency, innerRecordHandling))
					.map(PriceListAndCurrencyPriceIndex::createPriceIndexFormulaWithAllRecords)
					.orElse(EmptyFormula.INSTANCE)
			);
		}

		return createPriceListFormula(priceLists, currency, null, priceListFormulaComputer);
	}

}
