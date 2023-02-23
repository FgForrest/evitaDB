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

import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaVisitor;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.innerRecordHandling.PriceHandlingContainerFormula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIndexProvidingFormula;
import io.evitadb.core.query.algebra.price.termination.FirstVariantPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.termination.PlainPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.termination.PlainPriceTerminationFormulaWithPriceFilter;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.core.query.algebra.price.termination.PricePredicate;
import io.evitadb.core.query.algebra.price.termination.SumPriceTerminationFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static java.util.Optional.ofNullable;

/**
 * PriceListCompositionTerminationVisitor traverses tree of {@link Formula}, finds {@link PriceHandlingContainerFormula}
 * containers and envelops them to the terminating formulas based on their {@link PriceHandlingContainerFormula#getInnerRecordHandling()}.
 * These formulas can provide {@link io.evitadb.index.bitmap.Bitmap} of output entity ids along with accessing
 * {@link FilteredPriceRecordAccessor#getFilteredPriceRecords()} prices that led to those. The latter might be needed
 * in case ordering by price is involved in the query.
 */
@RequiredArgsConstructor
class PriceListCompositionTerminationVisitor implements FormulaVisitor {
	private static final Formula[] EMPTY_FORMULA = new Formula[0];
	/**
	 * Contains query price mode of the current query.
	 */
	@Getter private final QueryPriceMode queryPriceMode;
	/**
	 * Map contains set of formulas that has been already visited by this visitor. Formula tree could deliberately contain
	 * same instance of the formula on multiple places to get advantage of memoized results. We need to alter usually only
	 * the first occurrence of such formula.
	 */
	private final Set<Formula> alreadyProcessedFormulas = new HashSet<>();
	/**
	 * Stack is used to collect formulas, that should represent "the current level" of the output formula tree. They are
	 * used to recognizing whether the container formula needs to be reconstructed with new children or old formula can
	 * be reused (nothing has changed in its children).
	 */
	private final Deque<List<Formula>> stack = new LinkedList<>();
	/**
	 * Price filter is used to filter out entities which price doesn't match the predicate.
	 */
	private final PricePredicate priceFilter;
	/**
	 * Field is initialized when visitor walks through entire input formula and produces modified result.
	 */
	private Formula resultFormula;

	/**
	 * Preferred way of invoking the visitor, accepts input formula and produces altered one. Arguments `priceFilter`
	 * and `queryPriceMode` are used for handling filter by price in interval logic - i.e. to filter out produced entity
	 * ids that has not resulted price within the specified interval.
	 */
	public static Formula translate(
		@Nonnull List<Formula> formula,
		@Nonnull QueryPriceMode queryPriceMode,
		@Nullable PricePredicate priceFilter
	) {
		final Formula[] result = new Formula[formula.size()];
		for (int i = 0; i < formula.size(); i++) {
			final Formula singleFormula = formula.get(i);
			final PriceListCompositionTerminationVisitor visitor = new PriceListCompositionTerminationVisitor(
				queryPriceMode, priceFilter
			);
			singleFormula.accept(visitor);
			result[i] = visitor.getResultFormula();
		}
		return FormulaFactory.or(result);
	}

	@Override
	public void visit(Formula formula) {
		final boolean notProcessedYet = !alreadyProcessedFormulas.contains(formula);
		final Formula processedFormula;

		// if formula is not processed - process it
		if (notProcessedYet) {
			// mark the formula as visited
			alreadyProcessedFormulas.add(formula);
			try {
				// push new level to the stack
				stack.push(new LinkedList<>());

				// walk through all inner formulas
				for (Formula innerFormula : formula.getInnerFormulas()) {
					innerFormula.accept(this);
				}

			} finally {

				// extract the level from the stack
				final Formula[] newInnerFormulas = stack.pop().toArray(EMPTY_FORMULA);
				// if there are exactly the same formulas as before
				if (Arrays.equals(formula.getInnerFormulas(), newInnerFormulas)) {
					// just produce the input formula
					processedFormula = formula;
				} else {
					// recreate the container formula
					if (newInnerFormulas.length == 0) {
						// produce empty formula
						processedFormula = EmptyFormula.INSTANCE;
					} else {
						// produce clone of the container formula with new children
						processedFormula = formula.getCloneWithInnerFormulas(newInnerFormulas);
					}
				}
			}
		} else {
			// else just produce the input formula
			processedFormula = formula;
		}

		final Formula convertedFormula;
		// if the formula is PriceHandlingContainerFormula
		if (processedFormula instanceof final PriceHandlingContainerFormula containerFormula) {
			final PriceInnerRecordHandling innerRecordHandling = ((PriceHandlingContainerFormula) processedFormula).getInnerRecordHandling();
			final PriceEvaluationContext priceEvaluationContext = new PriceEvaluationContext(
				FormulaFinder.find(
						containerFormula, PriceIndexProvidingFormula.class, LookUp.SHALLOW
					)
					.stream()
					.map(it -> it.getPriceIndex().getPriceIndexKey())
					.distinct()
					.toArray(PriceIndexKey[]::new)
			);

			// wrap it into the terminating formula
			convertedFormula = switch (innerRecordHandling) {
				case NONE -> priceFilter == null ?
					new PlainPriceTerminationFormula(containerFormula, priceEvaluationContext) :
					new PlainPriceTerminationFormulaWithPriceFilter(containerFormula, priceEvaluationContext, priceFilter);
				case FIRST_OCCURRENCE -> new FirstVariantPriceTerminationFormula(
					containerFormula, priceEvaluationContext, queryPriceMode,
					ofNullable(priceFilter).orElse(PricePredicate.NO_FILTER)
				);
				case SUM -> new SumPriceTerminationFormula(
					containerFormula, priceEvaluationContext, queryPriceMode,
					ofNullable(priceFilter).orElse(PricePredicate.NO_FILTER)
				);
				case UNKNOWN -> throw new EvitaInternalError("Can't handle unknown price inner record handling!");
			};
		} else {
			// otherwise, use the produced formula
			convertedFormula = processedFormula;
		}

		if (stack.isEmpty()) {
			// if stack is empty we have our result
			this.resultFormula = convertedFormula;
		} else {
			// otherwise, add the converted formula to the current level of inner formulas
			stack.peek().add(convertedFormula);
		}

	}

	/**
	 * Returns altered formula with added price termination formulas in it.
	 */
	public Formula getResultFormula() {
		Assert.notNull(resultFormula, "Result formula was not computed!");
		return resultFormula;
	}

}
