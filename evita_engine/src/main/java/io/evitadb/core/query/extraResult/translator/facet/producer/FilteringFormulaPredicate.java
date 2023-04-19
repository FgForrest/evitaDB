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

package io.evitadb.core.query.extraResult.translator.facet.producer;

import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.deferred.FormulaWrapper;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.index.bitmap.Bitmap;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * TODO JNO alter documentation
 *
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
	@Getter @Nonnull private final ConstraintContainer<FilterConstraint> filterBy;
	/**
	 * Formula computes id of all hierarchical entities that match input filter by constraint.
	 */
	@Getter @Nonnull private final Formula filteringFormula;

	public FilteringFormulaPredicate(
		@Nonnull QueryContext queryContext,
		@Nonnull FilterBy filterBy,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String entityType
	) {
		this.filterBy = filterBy;
		try {
			final Supplier<String> stepDescriptionSupplier = () -> "Facet summary of `" + referenceSchema.getName() + "`: " +
				Arrays.stream(filterBy.getChildren()).map(Object::toString).collect(Collectors.joining(", "));
			queryContext.pushStep(
				QueryPhase.PLANNING_FILTER_NESTED_QUERY,
				stepDescriptionSupplier
			);
			// create a visitor
			final FilterByVisitor theFilterByVisitor = new FilterByVisitor(
				queryContext,
				Collections.emptyList(),
				TargetIndexes.EMPTY,
				false
			);

			// now analyze the filter by in a nested context with exchanged primary entity index
			final Formula theFormula = theFilterByVisitor.executeInContext(
				Collections.singletonList(
					queryContext.getGlobalEntityIndex(entityType)
				),
				null,
				referenceSchema,
				null,
				null,
				new AttributeSchemaAccessor(queryContext.getCatalogSchema(), queryContext.getSchema(entityType)),
				AttributesContract::getAttribute,
				() -> {
					filterBy.accept(theFilterByVisitor);
					// get the result and clear the visitor internal structures
					return theFilterByVisitor.getFormulaAndClear();
				}
			);
			// create a deferred formula that will log the execution time to query telemetry
			this.filteringFormula = new DeferredFormula(
				new FormulaWrapper(
					theFormula,
					formula -> {
						try {
							queryContext.pushStep(QueryPhase.EXECUTION_FILTER_NESTED_QUERY, stepDescriptionSupplier);
							return formula.compute();
						} finally {
							queryContext.popStep();
						}
					}
				)
			);
		} finally {
			queryContext.popStep();
		}
	}

	@Override
	public boolean test(int entityPrimaryKey) {
		return filteringFormula.compute().contains(entityPrimaryKey);
	}

}
