/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.extraResult.translator.reference.producer;

import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.deferred.FormulaWrapper;
import io.evitadb.dataType.Scope;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.usage.SchemaCapabilityUsage;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

import static io.evitadb.core.query.filter.FilterByVisitor.createFormulaForTheFilter;

/**
 * The predicate evaluates the nested query filter function to get the {@link Bitmap} of all hierarchy entity primary
 * keys that match the passed filtering constraint. It uses the result bitmap to resolve to decide output of the
 * predicate test method - for each key matching the computed result returns true, otherwise false.
 *
 * The filter is evaluated by no query plan of its own, so the schema capabilities it requests are counted with the
 * enclosing query: they are handed to its planning context, which counts them once when its plan is built - the
 * predicates are created while the query is planned, before that.
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
		final QueryPlanningContext targetQueryContext = createTargetQueryContext(
			queryContext, requestedScopes, filterBy, entityType
		);
		// create a deferred formula that will log the execution time to query telemetry
		this.filteringFormula = new DeferredFormula(
			new FormulaWrapper(
				createFormulaForTheFilter(
					targetQueryContext,
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
		// the context of the target collection never builds a plan, so what the filter requested there is handed to the
		// enclosing context, which counts it with the query
		if (targetQueryContext != queryContext) {
			for (final SchemaCapabilityUsage requestedCapability : targetQueryContext.drainRequestedCapabilities()) {
				queryContext.registerRequestedCapability(requestedCapability);
			}
		}
	}

	/**
	 * Returns the planning context the filter over the passed entity type is planned in: a context of the collection of
	 * that type, derived from the enclosing one the way a nested query is - several translators resolve their
	 * constraint against the entity type of the context, e.g. `hierarchyWithinSelf` against the tree of that type, and
	 * would resolve it against the queried entity type in the enclosing context. The enclosing context is used when the
	 * filter targets the queried entity type itself, when the type has no collection, or when there is no session to
	 * derive a context in.
	 *
	 * @param queryContext    the planning context of the enclosing query
	 * @param requestedScopes the scopes the filter is planned in
	 * @param filterBy        the filter
	 * @param entityType      the entity type the filter selects
	 * @return the planning context of the filter
	 */
	@Nonnull
	private static QueryPlanningContext createTargetQueryContext(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull Set<Scope> requestedScopes,
		@Nonnull FilterBy filterBy,
		@Nonnull String entityType
	) {
		final EvitaSession session = queryContext.getEvitaSession();
		if (session == null || entityType.equals(queryContext.getEntityType())) {
			return queryContext;
		}
		return queryContext.getEntityCollection(entityType)
			.map(
				collection -> collection.createQueryContext(
					queryContext,
					queryContext.getEvitaRequest().deriveCopyWith(entityType, filterBy, null, null, requestedScopes),
					session
				)
			)
			.orElse(queryContext);
	}

	/**
	 * Creates the predicate planning the filter against the passed global indexes rather than the ones the collection
	 * of the filtered entity type holds.
	 *
	 * @param queryContext            used for accessing global cache and recording query telemetry
	 * @param indexes                 the global indexes the filter is planned against
	 * @param filterBy                the filter constraints the entities must match
	 * @param entitySchema            the schema the filter is checked against, NULL for an entity type evitaDB does
	 *                                not manage
	 * @param stepDescriptionSupplier the message supplier for the query telemetry
	 */
	public FilteringFormulaPredicate(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull List<GlobalEntityIndex> indexes,
		@Nonnull FilterBy filterBy,
		@Nullable EntitySchemaContract entitySchema,
		@Nonnull Supplier<String> stepDescriptionSupplier
	) {
		this.filterBy = filterBy;
		// create a deferred formula that will log the execution time to query telemetry
		this.filteringFormula = new DeferredFormula(
			new FormulaWrapper(
				createFormulaForTheFilter(
					queryContext,
					GlobalEntityIndex.class,
					indexes,
					filterBy,
					null,
					entitySchema,
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
		return this.filteringFormula.compute().contains(entityPrimaryKey);
	}

}
