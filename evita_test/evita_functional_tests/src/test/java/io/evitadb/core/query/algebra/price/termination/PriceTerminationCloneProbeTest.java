/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIndexContainerFormula;
import io.evitadb.core.query.algebra.price.termination.SumPriceTerminationFormula.SumPredicate;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.reflect.Constructor;
import java.util.Currency;
import java.util.function.ToIntFunction;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Exercises repeated computation of the discount predicate through real price terminations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Price termination discount predicate cloning")
@Tag(ENGINE)
@Tag(PRICE)
class PriceTerminationCloneProbeTest {
	/** A computed lowest-price termination must not advance its clone's discount cursor. */
	@Test
	@DisplayName("Restarts the lowest-price discount predicate in a clone")
	void shouldRestartLowestPriceDiscountPredicateInClone() {
		final Formula child = child(PriceInnerRecordHandling.LOWEST_PRICE);
		final PriceEvaluationContext context = new PriceEvaluationContext(0L);
		final LowestPriceTerminationFormula original = new LowestPriceTerminationFormula(
			child, context, QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
		).withIndividualPricePredicateFactory(PriceTerminationCloneProbeTest::discountPredicate);
		original.compute();
		final Formula clone = original.getCloneWithInnerFormulas(child);
		final LowestPriceTerminationFormula fresh = new LowestPriceTerminationFormula(
			child, context, QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
		).withIndividualPricePredicateFactory(PriceTerminationCloneProbeTest::discountPredicate);
		assertArrayEquals(fresh.compute().getArray(), clone.compute().getArray());
	}

	/** A computed sum termination must not advance its clone's discount cursor. */
	@Test
	@DisplayName("Restarts the sum discount predicate in a clone")
	void shouldRestartSumDiscountPredicateInClone() {
		final Formula child = child(PriceInnerRecordHandling.SUM);
		final PriceEvaluationContext context = new PriceEvaluationContext(0L);
		final SumPriceTerminationFormula original = new SumPriceTerminationFormula(
			child, context, QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
		).withIndividualPricePredicateFactory(PriceTerminationCloneProbeTest::discountPredicate);
		original.compute();
		final Formula clone = original.getCloneWithInnerFormulas(child);
		final SumPriceTerminationFormula fresh = new SumPriceTerminationFormula(
			child, context, QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
		).withIndividualPricePredicateFactory(PriceTerminationCloneProbeTest::discountPredicate);
		assertArrayEquals(fresh.compute().getArray(), clone.compute().getArray());
	}

	/** Builds the exact stateful predicate used by discount sorting, without altering its visibility. */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static SumPredicate<PriceRecordContract> discountPredicate() {
		try {
			final Class<?> type = Class.forName("io.evitadb.core.query.sort.price.translator.PriceDiscountTranslator" +
				"$DiscountIndexComparator$InnerRecordInSellingPricesMatchingPredicate");
			final Constructor<?> constructor = type.getDeclaredConstructor(PriceRecordContract[].class, ToIntFunction.class);
			constructor.setAccessible(true);
			return (SumPredicate<PriceRecordContract>) constructor.newInstance(
				new PriceRecordContract[]{record(1), record(2), record(3), record(99)},
				(ToIntFunction<PriceRecordContract>) PriceRecordContract::priceWithTax
			);
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError("Cannot construct the discount predicate.", ex);
		}
	}

	/** Supplies fresh lookups over real price records for each termination computation. */
	@Nonnull
	private static Formula child(@Nonnull PriceInnerRecordHandling handling) {
		final PriceListAndCurrencyPriceSuperIndex index = new PriceListAndCurrencyPriceSuperIndex(
			new PriceIndexKey("basic", Currency.getInstance("CZK"), handling)
		);
		for (int i = 1; i <= 3; i++) {
			index.addPrice(record(i), null);
		}
		return new PriceIndexContainerFormula(index, index, new ConstantFormula(new BaseBitmap(1, 2, 3)));
	}

	/** Uses one inner record per entity so cursor movement is observable across entities. */
	@Nonnull
	private static PriceRecordContract record(int entityId) {
		return new PriceRecordInnerRecordSpecific(entityId, entityId, entityId, 1, 121, 100);
	}
}
