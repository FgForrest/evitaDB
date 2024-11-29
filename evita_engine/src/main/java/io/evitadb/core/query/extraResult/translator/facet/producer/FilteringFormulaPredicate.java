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

package io.evitadb.core.query.extraResult.translator.facet.producer;

import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.deferred.FormulaWrapper;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.Bitmap;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

import static io.evitadb.core.query.filter.FilterByVisitor.createFormulaForTheFilter;

/**
 * The predicate evaluates the nested query filter function to get the {@link Bitmap} of all hierarchy entity primary
 * keys that match the passed filtering constraint. It uses the result bitmap to resolve to decide output of the
 * predicate test method - for each key matching the computed result returns true, otherwise false.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class FilteringFormulaPredicate implements IntPredicate {
	/**
	 * Field contains the original filter by constraint the {@link #filteringFormula} was created by.
	 */
	@Getter @Nonnull private final FilterBy filterBy;
	/**
	 * Formula computes id of all entities that match input filter by constraint.
	 */
	@Getter @Nonnull private final Formula filteringFormula;

	public FilteringFormulaPredicate(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull Set<Scope> requestedScopes,
		@Nonnull FilterBy filterBy,
		@Nonnull String entityType,
		@Nonnull Supplier<String> stepDescriptionSupplier
	) {
		this.filterBy = filterBy;
		// create a deferred formula that will log the execution time to query telemetry
		this.filteringFormula = new DeferredFormula(
			new FormulaWrapper(
				createFormulaForTheFilter(
					queryContext,
					requestedScopes,
					filterBy,
					entityType,
					stepDescriptionSupplier
				),
				(executionContext, formula) -> {
					try {
						executionContext.pushStep(QueryPhase.EXECUTION_FILTER_NESTED_QUERY, stepDescriptionSupplier);
						return formula.compute();
					} finally {
						executionContext.popStep();
					}
				}
			)
		);
		// we need to initialize formula immediately with new execution context - the results are needed in planning phase already
		this.filteringFormula.initialize(queryContext.getInternalExecutionContext());
	}

	@Override
	public boolean test(int entityPrimaryKey) {
		return filteringFormula.compute().contains(entityPrimaryKey);
	}

}
