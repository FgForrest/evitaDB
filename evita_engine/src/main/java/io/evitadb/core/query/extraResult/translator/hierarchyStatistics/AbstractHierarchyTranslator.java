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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics;

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HierarchyDistance;
import io.evitadb.api.query.require.HierarchyLevel;
import io.evitadb.api.query.require.HierarchyNode;
import io.evitadb.api.query.require.HierarchyStopAt;
import io.evitadb.api.query.require.HierarchyStopAtRequireConstraint;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.exception.AttributeNotFoundException;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.deferred.FormulaWrapper;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyEntityFetcher;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyProducerContext;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyStatisticsProducer;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.DeferredSorter;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.translator.EntityAttributeExtractor;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.hierarchy.predicate.FilteringFormulaHierarchyEntityPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static io.evitadb.utils.Assert.isTrue;
import static io.evitadb.utils.Assert.notNull;
import static java.util.Optional.ofNullable;

/**
 * This ancestor contains shared methods for hierarchy constraint translators, it allows unified accessor to
 * {@link HierarchyStatisticsProducer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public abstract class AbstractHierarchyTranslator {

	/**
	 * Returns existing or creates new instance of the {@link HierarchyStatisticsProducer}.
	 */
	@Nonnull
	protected static HierarchyStatisticsProducer getHierarchyStatisticsProducer(
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
		) {
		return ofNullable(extraResultPlanner.findExistingProducer(HierarchyStatisticsProducer.class))
			.orElseGet(
				() -> new HierarchyStatisticsProducer(
					extraResultPlanner.getQueryContext(),
					extraResultPlanner.getLocale()
				)
			);
	}

	/**
	 * Method creates a {@link HierarchyTraversalPredicate} controlling the scope of the generated {@link LevelInfo}
	 * hierarchy statistics according the contents of the {@link HierarchyStopAt} constraint.
	 */
	@Nullable
	protected static HierarchyTraversalPredicate stopAtConstraintToPredicate(
		@Nonnull HierarchyProducerContext context,
		@Nonnull HierarchyStopAt stopAt
	) {
		final HierarchyStopAtRequireConstraint filter = stopAt.getGenericHierarchyOutputRequireConstraint();
		if (filter instanceof HierarchyLevel levelConstraint) {
			final int requiredLevel = levelConstraint.getLevel();
			return (hierarchyNodeId, level, distance) -> level <= requiredLevel;
		} else if (filter instanceof HierarchyDistance distanceCnt) {
			final int requiredDistance = distanceCnt.getDistance();
			return (hierarchyNodeId, level, distance) -> distance > -1 && distance <= requiredDistance;
		} else if (filter instanceof HierarchyNode node) {
			return new FilteringFormulaHierarchyEntityPredicate(
				context.queryContext(),
				context.entityIndex(),
				node.getFilterBy(),
				context.referenceSchema()
			);
		} else {
			return null;
		}
	}

	/**
	 * Method creates formula that is responsible for computing the queried entity count for
	 * {@link HierarchyStatisticsProducer}.
	 */
	@Nonnull
	protected Formula createFilterFormula(
		@Nonnull QueryContext queryContext,
		@Nonnull FilterBy filterBy,
		@Nonnull EntityIndex entityIndex
	) {
		try {
			final Supplier<String> stepDescriptionSupplier = () -> "Hierarchy statistics of `" + entityIndex.getEntitySchema().getName() + "`: " +
				Arrays.stream(filterBy.getChildren()).map(Object::toString).collect(Collectors.joining(", "));
			queryContext.pushStep(
				QueryPhase.PLANNING_FILTER_NESTED_QUERY,
				stepDescriptionSupplier
			);
			// crete a visitor
			final FilterByVisitor theFilterByVisitor = new FilterByVisitor(
				queryContext,
				Collections.emptyList(),
				TargetIndexes.EMPTY,
				false
			);
			// now analyze the filter by in a nested context with exchanged primary entity index
			final Formula theFormula = theFilterByVisitor.executeInContext(
				Collections.singletonList(entityIndex),
				null,
				null,
				null,
				null,
				(entitySchema, attributeName) -> {
					final EntitySchemaContract theSchema = ofNullable(entitySchema)
						.orElseGet(entityIndex::getEntitySchema);
					final AttributeSchemaContract attributeSchema = theSchema
						.getAttribute(attributeName)
						.orElse(null);
					notNull(
						attributeSchema,
						() -> new AttributeNotFoundException(attributeName, theSchema)
					);
					isTrue(
						attributeSchema.isFilterable() || attributeSchema.isUnique(),
						() -> new AttributeNotFilterableException(attributeName, theSchema)
					);
					return attributeSchema;
				},
				AttributesContract::getAttribute,
				() -> {
					filterBy.accept(theFilterByVisitor);
					// get the result and clear the visitor internal structures
					return theFilterByVisitor.getFormulaAndClear();
				}
			);
			// create a deferred formula that will log the execution time to query telemetry
			return new DeferredFormula(
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

	/**
	 * Method creates the {@link Sorter} implementation that should be used for sorting {@link LevelInfo} inside
	 * the {@link HierarchyStatistics} result object.
	 */
	@Nonnull
	protected Sorter createSorter(
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner,
		@Nonnull OrderBy orderBy,
		@Nonnull EntityIndex entityIndex
	) {
		final QueryContext queryContext = extraResultPlanner.getQueryContext();
		try {
			final Supplier<String> stepDescriptionSupplier = () -> "Hierarchy statistics of `" + entityIndex.getEntitySchema().getName() + "`: " +
				Arrays.stream(orderBy.getChildren()).map(Object::toString).collect(Collectors.joining(", "));
			queryContext.pushStep(
				QueryPhase.PLANNING_SORT,
				stepDescriptionSupplier
			);
			// crete a visitor
			final OrderByVisitor orderByVisitor = new OrderByVisitor(
				queryContext,
				extraResultPlanner.getPrefetchRequirementCollector(),
				extraResultPlanner.getFilteringFormula()
			);
			// now analyze the filter by in a nested context with exchanged primary entity index
			return orderByVisitor.executeInContext(
				entityIndex,
				(attributeName) -> {
					final AttributeSchemaContract attributeSchema = queryContext.getSchema()
						.getAttribute(attributeName)
						.orElse(null);
					notNull(
						attributeSchema,
						() -> new AttributeNotFoundException(attributeName, queryContext.getSchema())
					);
					isTrue(
						attributeSchema.isFilterable() || attributeSchema.isUnique(),
						() -> new AttributeNotFilterableException(attributeName, queryContext.getSchema())
					);
				},
				EntityAttributeExtractor.INSTANCE,
				() -> {
					for (OrderConstraint innerConstraint : orderBy.getChildren()) {
						innerConstraint.accept(orderByVisitor);
					}
					// create a deferred sorter that will log the execution time to query telemetry
					return new DeferredSorter(
						orderByVisitor.getLastUsedSorter(),
						sorter -> {
							try {
								queryContext.pushStep(QueryPhase.EXECUTION_SORT_AND_SLICE, stepDescriptionSupplier);
								return sorter.get();
							} finally {
								queryContext.popStep();
							}
						}
					);
				}
			);
		} finally {
			queryContext.popStep();
		}
	}

	/**
	 * Method creates an implementation of {@link HierarchyEntityFetcher} that fabricates the proper instance of
	 * {@link EntityClassifier} according to the {@link EntityFetch} requirement. It fabricates either:
	 *
	 * - thin {@link EntityClassifier} that contains only entity type and primary key
	 * - {@link SealedEntity} with varying content according to requirements
	 */
	@Nonnull
	protected static HierarchyEntityFetcher createEntityFetcher(
		@Nullable EntityFetch entityFetch,
		@Nonnull HierarchyProducerContext context
	) {
		// first create the `entityFetcher` that either returns simple integer primary keys or full entities
		final String hierarchicalEntityType = context.entitySchema().getName();
		if (entityFetch == null) {
			return entityPk -> new EntityReference(hierarchicalEntityType, entityPk);
		} else {
			ofNullable(context.prefetchRequirementCollector())
				.ifPresent(it -> it.addRequirementToPrefetch(entityFetch.getRequirements()));
			return entityPk -> context.queryContext().fetchEntity(hierarchicalEntityType, entityPk, entityFetch).orElse(null);
		}
	}

}
