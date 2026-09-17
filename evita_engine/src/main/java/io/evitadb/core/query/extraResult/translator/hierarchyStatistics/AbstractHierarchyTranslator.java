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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics;

import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HierarchyDistance;
import io.evitadb.api.query.require.HierarchyLevel;
import io.evitadb.api.query.require.HierarchyNode;
import io.evitadb.api.query.require.HierarchyStopAt;
import io.evitadb.api.query.require.HierarchyStopAtRequireConstraint;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.exception.HierarchyNotIndexedException;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor.ProcessingScope;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyEntityFetcher;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyProducerContext;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyStatisticsProducer;
import io.evitadb.core.query.extraResult.translator.reference.EntityFetchTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.hierarchy.HierarchyIndexContract;
import io.evitadb.index.hierarchy.predicate.FilteringFormulaHierarchyEntityPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

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
			.orElseGet(() -> new HierarchyStatisticsProducer(extraResultPlanner.getLocale()));
	}

	/**
	 * Resolves the single {@link Scope} from the processing scope and asserts the given hierarchical schema has
	 * its hierarchy indexed in that scope. Hierarchies cannot be produced from multiple scopes — they would
	 * represent two distinct trees — so any request involving more than one scope is rejected up front.
	 *
	 * @param processingScope    current processing scope carrying the requested scopes
	 * @param hierarchicalSchema the schema whose hierarchy is to be queried (the queried schema for self,
	 *                           the referenced schema for hierarchy-of-reference)
	 * @return the single {@link Scope} that is both requested and confirmed to have the hierarchy indexed
	 * @throws EvitaInvalidUsageException  when more than one scope is requested
	 * @throws HierarchyNotIndexedException when the hierarchy is not indexed in the requested scope
	 */
	@Nonnull
	protected static Scope resolveSingleHierarchicalScope(
		@Nonnull ProcessingScope processingScope,
		@Nonnull EntitySchemaContract hierarchicalSchema
	) {
		final Set<Scope> scopes = processingScope.getScopes();
		// hierarchy cannot be produced from multiple scopes — they represent two distinct trees
		if (scopes.size() > 1) {
			throw new EvitaInvalidUsageException(
				"Hierarchies of `" + hierarchicalSchema.getName() + "` from multiple scopes cannot be combined. " +
					"They represent two distinct trees."
			);
		}
		final Scope scope = scopes.iterator().next();
		Assert.isTrue(
			hierarchicalSchema.isHierarchyIndexedInScope(scope),
			() -> new HierarchyNotIndexedException(hierarchicalSchema, scope)
		);
		return scope;
	}

	/**
	 * Method creates a {@link HierarchyTraversalPredicate} controlling the scope of the generated {@link LevelInfo}
	 * hierarchy statistics according the contents of the {@link HierarchyStopAt} constraint.
	 *
	 * One rule governs both directions of the {@link HierarchyLevel} bound: **a level bound cannot cut a chain whose
	 * depth is unknown**. {@link HierarchyLevel} is an absolute depth counted from the top of the tree, and a node
	 * that is not reachable from any root has no such depth - the traversals report
	 * {@link HierarchyIndexContract#UNKNOWN_LEVEL} for it. Comparing that value against the bound in either
	 * direction would answer a question the index cannot answer: downwards it would admit everything, upwards it
	 * would silently drop reachable ancestors by an offset nobody can measure. So an unknown level is admitted
	 * outright, by the same expression in both directions, and a caller who needs a bound that still holds over a
	 * broken chain expresses it as a {@link HierarchyDistance} instead - `distance` counts from the pivot node and
	 * a break cannot shift it.
	 *
	 * @param direction       the direction the traversal runs in, which decides how a level bound is compared
	 * @param stopAt          the constraint to translate into a predicate
	 * @param queryContext    the planning context a nested `node` filter is compiled in
	 * @param entityIndex     the global index a nested `node` filter is evaluated against
	 * @param entitySchema    the schema of the hierarchical entity the constraint applies to
	 * @param referenceSchema the reference schema when the hierarchy is reached through a reference, `null` when
	 *                        the query traverses the queried entity's own hierarchy
	 * @return the predicate bounding the traversal, or `null` when the `stopAt` definition is none of the three
	 *         the planner knows how to translate
	 */
	@Nullable
	public static HierarchyTraversalPredicate stopAtConstraintToPredicate(
		@Nonnull TraversalDirection direction,
		@Nonnull HierarchyStopAt stopAt,
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull GlobalEntityIndex entityIndex,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema
	) {
		final HierarchyStopAtRequireConstraint filter = stopAt.getStopAtDefinition();
		if (filter instanceof HierarchyLevel levelConstraint) {
			final int requiredLevel = levelConstraint.getLevel();
			return (hierarchyNodeId, level, distance) -> level == HierarchyIndexContract.UNKNOWN_LEVEL ||
				(direction == TraversalDirection.TOP_DOWN ? level <= requiredLevel : level >= requiredLevel);
		} else if (filter instanceof HierarchyDistance distanceCnt) {
			final int requiredDistance = distanceCnt.getDistance();
			return (hierarchyNodeId, level, distance) -> distance > -1 && distance <= requiredDistance;
		} else if (filter instanceof HierarchyNode node) {
			return new FilteringFormulaHierarchyEntityPredicate(
				queryContext,
				entityIndex,
				node.getFilterBy(),
				entitySchema,
				referenceSchema
			);
		} else {
			return null;
		}
	}

	/**
	 * Method creates an implementation of {@link HierarchyEntityFetcher} that fabricates the proper instance of
	 * {@link EntityClassifier} according to the {@link EntityFetch} requirement. It fabricates either:
	 *
	 * - thin {@link EntityClassifier} that contains only entity type and primary key
	 * - {@link SealedEntity} with varying content according to requirements
	 *
	 * **A node that made it into the tree is never dropped for want of a body.** Which nodes a hierarchy
	 * statistics tree contains is settled before this fetcher runs, by the {@link HierarchyFilteringPredicate}
	 * the computer was given - that is where the query filter and its {@link EntityLocaleEquals} gate apply. This
	 * fetcher only decides what an admitted node *carries*, so when the requested body cannot be materialized
	 * (the node holds no data in the query locale, say) it falls back to the very thin {@link EntityReference}
	 * the no-`entityFetch` branch above returns, rather than to `null`. A statistics tree is a picture of a
	 * shape: cutting a node out of it, or handing the accumulator behind it a `null` to trip over when the
	 * {@link LevelInfo} is rendered, would misreport the very structure the caller asked for.
	 *
	 * Now that `AbstractHierarchyStatisticsComputer#createStatistics` applies the locale gate wherever it builds
	 * the filtering predicate itself, no known query reaches that fallback: a node admitted into the tree holds
	 * data in the query locale, so its body materializes. The fallback stays as the guard for any future path
	 * that admits a node whose body cannot be materialized, so that such a path degrades to a bodyless node
	 * instead of failing the whole query.
	 *
	 * @param entityFetch          the requirement describing the body to load, or `null` when the caller wants
	 *                             nothing but entity type and primary key
	 * @param context              the context of the enclosing hierarchy requirement
	 * @param extraResultPlanner   the planning visitor the requirement is being translated by
	 * @return the fetcher, which always yields a classifier and never `null`
	 */
	@Nonnull
	protected static HierarchyEntityFetcher createEntityFetcher(
		@Nullable EntityFetch entityFetch,
		@Nonnull HierarchyProducerContext context,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
	) {
		// first create the `entityFetcher` that either returns simple integer primary keys or full entities
		final String hierarchicalEntityType = context.entitySchema().getName();
		if (entityFetch == null) {
			return (executionContext, entityPk) -> new EntityReference(hierarchicalEntityType, entityPk);
		} else {
			ofNullable(context.fetchRequirementCollector())
				.ifPresent(it -> it.addRequirementsToPrefetch(entityFetch.getRequirements()));
			EntityFetchTranslator.verifyEntityFetchLocalizedAttributes(context.entitySchema(), entityFetch, extraResultPlanner);
			// the enriched entity fetch is a deep clone produced by ConstraintCloneVisitor — stable for the
			// lifetime of one QueryExecutionContext, so memoise it on first call instead of recomputing per visit
			return new HierarchyEntityFetcher() {
				@Nullable private EntityFetch enrichedFetch;

				@Nonnull
				@Override
				public EntityClassifier apply(
					@Nonnull QueryExecutionContext executionContext,
					@Nonnull Integer entityPk
				) {
					EntityFetch enriched = this.enrichedFetch;
					if (enriched == null) {
						enriched = executionContext.enrichEntityFetch(entityFetch);
						this.enrichedFetch = enriched;
					}
					// the body could not be materialized - report the node bodyless instead of not at all
					return executionContext.fetchEntity(hierarchicalEntityType, entityPk, enriched)
						.map(EntityClassifier.class::cast)
						.orElseGet(() -> new EntityReference(hierarchicalEntityType, entityPk));
				}
			};
		}
	}

	/**
	 * Represents the traversal direction.
	 */
	public enum TraversalDirection {
		BOTTOM_UP, TOP_DOWN
	}

}
