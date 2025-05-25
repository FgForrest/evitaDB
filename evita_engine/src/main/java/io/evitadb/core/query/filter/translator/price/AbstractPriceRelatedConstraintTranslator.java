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

package io.evitadb.core.query.filter.translator.price;

import io.evitadb.api.exception.EntityHasNoPricesException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.exception.PriceNotIndexedException;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.price.innerRecordHandling.PriceHandlingContainerFormula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIndexProvidingFormula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceListCombinationFormula;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.core.query.algebra.price.translate.PriceIdToEntityIdTranslateFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.algebra.utils.visitor.FormulaLocator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.function.TriFunction;
import io.evitadb.index.Index;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/**
 * Abstract superclass for price related {@link FilteringConstraintTranslator} implementations that unifies the shared
 * logic together.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
abstract class AbstractPriceRelatedConstraintTranslator<T extends FilterConstraint> implements FilteringConstraintTranslator<T> {

	/**
	 * Method creates formula structure where second, third and additional formulas subtract the result of the previous
	 * formula results in the chain and then get combined with them by OR query the aim is there to create distinct
	 * result where for each record only its price from most prioritized price list is taken into an account.
	 */
	@Nonnull
	protected static List<Formula> createPriceListFormula(
		@Nullable String[] priceLists,
		@Nullable Currency currency,
		@Nullable OffsetDateTime validIn,
		@Nonnull TriFunction<String, Currency, PriceInnerRecordHandling, Formula> priceListFormulaComputer
	) {
		final List<Formula> formulas = new ArrayList<>(PriceInnerRecordHandling.values().length);
		for (PriceInnerRecordHandling innerRecordHandling : PriceInnerRecordHandling.values()) {
			final CompositeObjectArray<Formula> priceListFormulas = new CompositeObjectArray<>(Formula.class);
			if (priceLists == null) {
				//noinspection DataFlowIssue
				final Formula initialFormula = priceListFormulaComputer.apply(null, currency, innerRecordHandling);
				if (!(initialFormula instanceof EmptyFormula)) {
					priceListFormulas.add(translateFormula(initialFormula));
				}
			} else {
				Serializable firstFormulaPriceList = null;
				Formula lastFormula = null;
				for (String priceList : priceLists) {
					//noinspection DataFlowIssue
					final Formula priceListFormula = priceListFormulaComputer.apply(priceList, currency, innerRecordHandling);
					if (!(priceListFormula instanceof EmptyFormula)) {
						final Formula translatedFormula = translateFormula(priceListFormula);

						if (lastFormula == null) {
							firstFormulaPriceList = priceList;
							lastFormula = translatedFormula;
						} else {
							final PriceEvaluationContext priceContext = new PriceEvaluationContext(
								validIn,
								FormulaFinder.find(
									translatedFormula, PriceIndexProvidingFormula.class, LookUp.SHALLOW
								)
									.stream()
									.map(it -> it.getPriceIndex().getPriceIndexKey())
									.distinct()
									.toArray(PriceIndexKey[]::new)
							);
							lastFormula = new PriceListCombinationFormula(
								lastFormula instanceof PriceListCombinationFormula ?
									((PriceListCombinationFormula)lastFormula).getCombinedPriceListNames() : firstFormulaPriceList, priceList,
								priceContext, lastFormula, translatedFormula
							);
						}
						priceListFormulas.add(lastFormula);
					}
				}
			}
			if (!priceListFormulas.isEmpty()) {
				formulas.add(
					new PriceHandlingContainerFormula(
						innerRecordHandling,
						FormulaFactory.or(priceListFormulas.toArray())
					)
				);
			}
		}

		return formulas;
	}

	/**
	 * Verifies that the prices for the entities being processed by the given FilterByVisitor are indexed.
	 * If the entity type is known, the method checks if the schema allows prices and ensures that
	 * all the processing scopes are indexed for prices in the schema.
	 *
	 * @param filterByVisitor the visitor that processes the filter by which entities are being filtered
	 *                        and checked for price indexing
	 */
	protected static void verifyEntityPricesAreIndexed(@Nonnull FilterByVisitor filterByVisitor) {
		if (filterByVisitor.isEntityTypeKnown()) {
			final EntitySchemaContract schema = filterByVisitor.getSchema();
			Assert.isTrue(
				schema.isWithPrice(),
				() -> new EntityHasNoPricesException(schema.getName())
			);
			final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
			Assert.isTrue(
				processingScope.getScopes().stream().allMatch(schema::isPriceIndexedInScope),
				() -> new PriceNotIndexedException(schema)
			);
		}
	}

	/**
	 * Translates an initial formula into a new formula if it contains a {@link PriceIdContainerFormula}.
	 * If the initial formula contains a {@link PriceIdContainerFormula}, it wraps the initial formula
	 * in a {@link PriceIdToEntityIdTranslateFormula} to translate price IDs to entity IDs.
	 * Otherwise, it returns the initial formula unchanged.
	 *
	 * @param initialFormula the formula to be translated
	 * @return the translated formula or the initial formula if no translation is needed
	 */
	@Nonnull
	private static Formula translateFormula(@Nonnull Formula initialFormula) {
		final Formula translatedFormula;
		if (FormulaLocator.contains(initialFormula, PriceIdContainerFormula.class)) {
			translatedFormula = new PriceIdToEntityIdTranslateFormula(initialFormula);
		} else {
			translatedFormula = initialFormula;
		}
		return translatedFormula;
	}

}
