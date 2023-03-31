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

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HierarchyDistance;
import io.evitadb.api.query.require.HierarchyLevel;
import io.evitadb.api.query.require.HierarchyNode;
import io.evitadb.api.query.require.HierarchyStopAt;
import io.evitadb.api.query.require.HierarchyStopAtRequireConstraint;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.predicate.FilteredHierarchyEntityPredicate;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyEntityFetcher;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyPositionalPredicate;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyProducerContext;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyStatisticsProducer;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.sort.attribute.translator.EntityNestedQueryComparator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
					extraResultPlanner.getLocale(),
					extraResultPlanner.getFilteringFormula(),
					extraResultPlanner.getFilteringFormulaWithoutUserFilter()
				)
			);
	}

	/**
	 * TODO JNO - document me
	 */
	@Nullable
	protected static HierarchyPositionalPredicate stopAtConstraintToPredicate(String constraintName, ExtraResultPlanningVisitor extraResultPlanningVisitor, HierarchyStatisticsProducer producer, HierarchyStopAt stopAt) {
		final HierarchyProducerContext context = producer.getContext(constraintName);
		final HierarchyStopAtRequireConstraint filter = stopAt.getGenericHierarchyOutputRequireConstraint();
		if (filter instanceof HierarchyLevel levelConstraint) {
			final int requiredLevel = levelConstraint.getLevel();
			return (hierarchyNodeId, level, distance) -> level <= requiredLevel;
		} else if (filter instanceof HierarchyDistance distanceCnt) {
			final int requiredDistance = distanceCnt.getDistance();
			return (hierarchyNodeId, level, distance) -> distance > -1 && distance <= requiredDistance;
		} else if (filter instanceof HierarchyNode node) {
			return new FilteredHierarchyEntityPredicate(context, node.getFilterBy());
		} else {
			return null;
		}
	}

	/**
	 * TODO JNO - document me
	 */
	@Nonnull
	private static Formula getNestedQueryFormula(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull String referencedEntityType,
		@Nonnull EntityCollection referencedEntityCollection,
		@Nonnull FilterBy filterBy,
		@Nullable EntityNestedQueryComparator entityNestedQueryComparator
	) {
		final QueryContext nestedQueryContext = referencedEntityCollection.createQueryContext(
			filterByVisitor.getQueryContext(),
			filterByVisitor.getEvitaRequest().deriveCopyWith(
				referencedEntityType,
				filterBy,
				ofNullable(entityNestedQueryComparator)
					.map(EntityNestedQueryComparator::getOrderBy)
					.map(it -> new OrderBy(it.getChildren()))
					.orElse(null)
			),
			filterByVisitor.getEvitaSession()
		);
		final QueryPlan queryPlan = QueryPlanner.planNestedQuery(nestedQueryContext);
		if (entityNestedQueryComparator != null) {
			entityNestedQueryComparator.initSorter(nestedQueryContext, queryPlan.getSorter());
		}

		return queryPlan.getFilter();
	}

	/**
	 * TODO JNO - DOCUMENT ME
	 */
	@Nonnull
	protected HierarchyEntityFetcher createEntityFetcher(
		@Nonnull RequireConstraint constraint,
		@Nullable EntityFetch entityFetch,
		@Nonnull HierarchyStatisticsProducer producer
	) {
		// first create the `entityFetcher` that either returns simple integer primary keys or full entities
		final HierarchyProducerContext context = producer.getContext(constraint.getName());
		final String hierarchicalEntityType = context.entitySchema().getName();
		if (entityFetch == null) {
			return entityPk -> new EntityReference(hierarchicalEntityType, entityPk);
		} else {
			return entityPk -> context.queryContext().fetchEntity(hierarchicalEntityType, entityPk, entityFetch).orElse(null);
		}
	}

}
