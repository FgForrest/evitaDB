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

package io.evitadb.core.query.filter.translator.reference;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.deferred.FormulaWrapper;
import io.evitadb.core.query.algebra.reference.ReferenceOwnerTranslatingFormula;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.sort.attribute.translator.EntityNestedQueryComparator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link io.evitadb.api.query.filter.EntityHaving} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityHavingTranslator implements FilteringConstraintTranslator<EntityHaving>, SelfTraversingTranslator {

	@Nonnull
	private static Formula getNestedQueryFormula(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull String referencedEntityType,
		@Nonnull EntityCollection referencedEntityCollection,
		@Nonnull FilterBy filterBy,
		@Nullable EntityNestedQueryComparator entityNestedQueryComparator
	) {
		final QueryPlanningContext nestedQueryContext = referencedEntityCollection.createQueryContext(
			filterByVisitor.getQueryContext(),
			filterByVisitor.getEvitaRequest().deriveCopyWith(
				referencedEntityType,
				filterBy,
				ofNullable(entityNestedQueryComparator)
					.map(EntityNestedQueryComparator::getOrderBy)
					.map(it -> new OrderBy(it.getChildren()))
					.orElse(null),
				ofNullable(entityNestedQueryComparator)
					.map(EntityNestedQueryComparator::getLocale)
					.orElse(null)
			),
			filterByVisitor.getEvitaSession()
		);

		final QueryPlan queryPlan = QueryPlanner.planNestedQuery(nestedQueryContext);
		if (entityNestedQueryComparator != null) {
			entityNestedQueryComparator.setSorter(nestedQueryContext.createExecutionContext(), queryPlan.getSorter());
		}

		return queryPlan.getFilter();
	}

	@Nonnull
	@Override
	public Formula translate(@Nonnull EntityHaving entityHaving, @Nonnull FilterByVisitor filterByVisitor) {
		final EntitySchemaContract entitySchema = filterByVisitor.getProcessingScope().getEntitySchema();
		final ReferenceSchemaContract referenceSchema = filterByVisitor.getReferenceSchema()
			.orElseThrow(() -> new EvitaInvalidUsageException(
					"Filtering constraint `" + entityHaving + "` needs to be placed within `ReferenceHaving` " +
						"parent constraint that allows to resolve the entity `" +
						entitySchema.getName() + "` referenced entity type."
				)
			);
		Assert.isTrue(
			referenceSchema.isReferencedEntityTypeManaged(),
			() -> "Filtering constraint `" + entityHaving + "` targets entity " +
				"`" + referenceSchema.getReferencedEntityType() + "` that is not managed by evitaDB."
		);
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		final EntityCollection referencedEntityCollection = filterByVisitor.getEntityCollectionOrThrowException(
			referencedEntityType, "resolve entity having constraint"
		);
		final FilterConstraint filterConstraint = entityHaving.getChild();
		if (filterConstraint != null) {
			final ProcessingScope processingScope = filterByVisitor.getProcessingScope();
			final Function<FilterConstraint, FilterConstraint> enricher = processingScope.getNestedQueryFormulaEnricher();
			final FilterBy combinedFilterBy = new FilterBy(enricher.apply(filterConstraint));
			final Supplier<String> nestedQueryDescription = () -> "Reference `" + referenceSchema.getName() + "`, " +
				"entity `" + referencedEntityType + "`: " +
				Arrays.stream(combinedFilterBy.getChildren()).map(Object::toString).collect(Collectors.joining(", "));

			final Optional<GlobalEntityIndex> globalIndexIfExists = referencedEntityCollection.getGlobalIndexIfExists();
			if (globalIndexIfExists.isPresent()) {
				final GlobalEntityIndex globalEntityIndex = globalIndexIfExists.get();
				final Formula nestedQueryFormula = filterByVisitor.computeOnlyOnce(
					Collections.singletonList(globalEntityIndex),
					combinedFilterBy,
					() -> {
						try {
							filterByVisitor.pushStep(
								QueryPhase.PLANNING_FILTER_NESTED_QUERY,
								nestedQueryDescription
							);
							return getNestedQueryFormula(
								filterByVisitor,
								referencedEntityType,
								referencedEntityCollection,
								combinedFilterBy,
								processingScope.getEntityNestedQueryComparator()
							);
						} finally {
							filterByVisitor.popStep();
						}
					},
					1L
				);

				if (ReferencedTypeEntityIndex.class.isAssignableFrom(processingScope.getIndexType())) {
					return nestedQueryFormula;
				} else {
					return filterByVisitor.computeOnlyOnce(
						Collections.singletonList(globalEntityIndex),
						combinedFilterBy,
						() -> {
							final ReferenceOwnerTranslatingFormula outputFormula = new ReferenceOwnerTranslatingFormula(
								globalEntityIndex,
								nestedQueryFormula,
								it -> {
									// leave the return here, so that we can easily debug it
									return ofNullable(filterByVisitor.getReferencedEntityIndex(entitySchema, referenceSchema, it))
										.map(EntityIndex::getAllPrimaryKeys)
										.orElse(EmptyBitmap.INSTANCE);
								}
							);
							return new DeferredFormula(
								new FormulaWrapper(
									outputFormula,
									(executionContext, formula) -> {
										try {
											executionContext.pushStep(QueryPhase.EXECUTION_FILTER_NESTED_QUERY, nestedQueryDescription);
											return formula.compute();
										} finally {
											executionContext.popStep();
										}
									}
								)
							);
						},
						2L,
						// we need to add exact pointers to the entity schema and reference schema, which play role
						// in the lambda evaluation
						NumberUtils.join(
							System.identityHashCode(entitySchema),
							System.identityHashCode(referenceSchema)
						)
					);
				}
			}
		}
		return EmptyFormula.INSTANCE;
	}

}
