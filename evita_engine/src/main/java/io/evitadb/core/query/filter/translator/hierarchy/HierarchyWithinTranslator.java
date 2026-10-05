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

package io.evitadb.core.query.filter.translator.hierarchy;

import io.evitadb.api.exception.EntityIsNotHierarchicalException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.core.exception.HierarchyNotIndexedException;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.hierarchy.HierarchyFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.core.query.filter.FilterByVisitor.createFormulaForTheFilter;
import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link HierarchyWithin} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class HierarchyWithinTranslator extends AbstractHierarchyTranslator<HierarchyWithin> {

	/**
	 * Creates the formula of the hierarchy nodes the constraint selects in all processing scopes: the union of what
	 * {@link #createFormulaFromHierarchyIndex(HierarchyWithin, FilterByVisitor, Scope)} selects in each of them, so
	 * that a query over several scopes matches what the queries over each scope alone match together.
	 *
	 * @param hierarchyWithin the translated constraint
	 * @param filterByVisitor the visitor translating the constraint
	 * @return the formula of the selected hierarchy nodes
	 */
	@Nonnull
	public static Formula createFormulaFromHierarchyIndex(
		@Nonnull HierarchyWithin hierarchyWithin,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final Set<Scope> scopes = filterByVisitor.getProcessingScope().getScopes();
		if (scopes.size() == 1) {
			return createFormulaFromHierarchyIndex(hierarchyWithin, filterByVisitor, scopes.iterator().next());
		}
		final Formula nodesOfAllScopes = FormulaFactory.or(
			Arrays.stream(Scope.values())
				.filter(scopes::contains)
				.map(scope -> createFormulaFromHierarchyIndex(hierarchyWithin, filterByVisitor, scope))
				.filter(it -> it != EmptyFormula.INSTANCE)
				.toArray(Formula[]::new)
		);
		// the index selection computes the nodes in the planning phase
		nodesOfAllScopes.initialize(filterByVisitor.getInternalExecutionContext());
		return nodesOfAllScopes;
	}

	/**
	 * Creates the formula of the hierarchy nodes the constraint selects in one scope, exactly as a query over that
	 * scope alone would: the parent filter is resolved among the entities of the scope - a unique attribute is looked
	 * up in that scope's unique index - and the nodes are taken from the scope's own tree. The roots and the node
	 * visibility are recorded for that scope, and the result is memoized per scope, so an occurrence in
	 * `inScope(scope, ...)` and the share of an occurrence over several scopes are one and the same computation.
	 *
	 * @param hierarchyWithin the translated constraint
	 * @param filterByVisitor the visitor translating the constraint
	 * @param scope           the scope to select the nodes in
	 * @return the formula of the nodes selected in the scope, {@link EmptyFormula} when the scope has no index - the
	 *         parent and node filters are then checked over an empty index
	 * @throws EntityIsNotHierarchicalException when the target entity is not hierarchical
	 * @throws HierarchyNotIndexedException when the hierarchy is not indexed in the scope, whether or not the scope
	 *                                      holds an index of the target entity
	 */
	@Nonnull
	public static Formula createFormulaFromHierarchyIndex(
		@Nonnull HierarchyWithin hierarchyWithin,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull Scope scope
	) {
		final QueryPlanningContext queryContext = filterByVisitor.getQueryContext();
		final Optional<String> referenceName = hierarchyWithin.getReferenceName();

		final EntitySchemaContract entitySchema = filterByVisitor.getSchema();
		final ReferenceSchemaContract referenceSchema = referenceName
			.map(entitySchema::getReferenceOrThrowException)
			.orElse(null);
		final EntitySchemaContract targetEntitySchema = ofNullable(referenceSchema)
			.map(it -> filterByVisitor.getSchema(it.getReferencedEntityType()))
			.orElse(entitySchema);

		final Set<Scope> scopeToLookup = EnumSet.of(scope);
		final Function<EntityIndex, Formula> nodesFormulaFactory = targetEntityIndex -> {
			verifyHierarchyIndexedAndRecordUsage(
				queryContext, targetEntitySchema, referenceSchema, scope, scopeToLookup
			);

			final FilterConstraint parentFilter = hierarchyWithin.getParentFilter();
			final Formula hierarchyParentFormula = createFormulaForTheFilter(
				queryContext,
				scopeToLookup,
				createFilter(queryContext, parentFilter),
				targetEntitySchema.getName(),
				() -> "Finding hierarchy parent node: " + parentFilter
			);
			// we need to initialize the formula with internal context,
			// because we'll need the result in planning phase
			hierarchyParentFormula.initialize(filterByVisitor.getInternalExecutionContext());

			queryContext.setRootHierarchyNodesFormula(
				hierarchyWithin, scopeToLookup, hierarchyParentFormula
			);

			final int[] nodeIds = hierarchyParentFormula.compute().stream().toArray();
			return createFormulaFromHierarchyIndex(
				hierarchyWithin,
				targetEntityIndex,
				nodeIds,
				queryContext,
				scopeToLookup,
				referenceSchema,
				targetEntitySchema
			);
		};
		return queryContext.getEntityIndex(
				targetEntitySchema.getName(), new EntityIndexKey(EntityIndexType.GLOBAL, scope), EntityIndex.class
			)
			.map(
				targetEntityIndex -> queryContext.computeOnlyOnce(
					Collections.singletonList(targetEntityIndex),
					hierarchyWithin,
					() -> nodesFormulaFactory.apply(targetEntityIndex),
					scopesCacheKey(scopeToLookup)
				)
			)
			.orElseGet(
				() -> {
					// a scope holding no entity of the hierarchy selects no node, but the query depends on its tree
					// all the same - it must be indexed, and the parent and node filters are checked against the
					// schema over an empty index, whether or not an entity happens to live there; the scope
					// contributes nothing, whatever the translation produced
					nodesFormulaFactory.apply(GlobalEntityIndex.createEmptyIndex(targetEntitySchema.getName(), scope));
					return EmptyFormula.INSTANCE;
				}
			);
	}

	/**
	 * Verifies that the target entity is hierarchical and that its hierarchy is indexed in the scope, and records
	 * that the query depended on the hierarchy indexing of that scope.
	 *
	 * @param queryContext       the context of the query the usage is recorded in
	 * @param targetEntitySchema the schema of the entity whose tree is searched
	 * @param referenceSchema    the reference leading to the tree, NULL for the queried entity's own tree
	 * @param scope              the scope whose tree is searched
	 * @param scopeToLookup      the set holding the scope only
	 * @throws EntityIsNotHierarchicalException when the target entity is not hierarchical
	 * @throws HierarchyNotIndexedException     when the hierarchy is not indexed in the scope
	 */
	private static void verifyHierarchyIndexedAndRecordUsage(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull EntitySchemaContract targetEntitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull Set<Scope> scopeToLookup
	) {
		Assert.isTrue(
			targetEntitySchema.isWithHierarchy(),
			() -> new EntityIsNotHierarchicalException(
				ofNullable(referenceSchema).map(ReferenceSchemaContract::getName).orElse(null),
				targetEntitySchema.getName()
			)
		);

		Assert.isTrue(
			targetEntitySchema.isHierarchyIndexedInScope(scope),
			() -> new HierarchyNotIndexedException(targetEntitySchema, scope)
		);

		// past the assertion on purpose - the count has to mean "a query depended on this flag being on".
		// A `hierarchyWithin` naming another collection's entity records nothing here, the same way a filter
		// evaluated against another collection does
		queryContext.recordRequestedEntityCapability(targetEntitySchema, Capability.HIERARCHICAL, scopeToLookup);
	}

	/**
	 * Creates a {@link Formula} for a given hierarchy index based on the provided parameters.
	 * The formula represents computational logic to handle hierarchical relationships,
	 * filtering, and scope constraints for entities within a hierarchy.
	 *
	 * @param hierarchyWithin the hierarchy-related constraints to process, containing filtering rules and relational settings.
	 * @param targetEntityIndex the target {@link EntityIndex} where the computations will run.
	 * @param nodeIds an array of node identifiers representing hierarchical nodes to be processed.
	 * @param queryContext the context of the query, providing access to query-level configurations and dependencies.
	 * @param scopesToLookup a set of {@link Scope} objects representing specific query scopes to consider.
	 * @param referenceSchema the schema of the reference entity defining structural rules and constraints.
	 * @param targetEntitySchema the schema of the target entity defining the structure and constraints for the target entity.
	 * @return the constructed {@link Formula} representing the computation logic for the hierarchy index.
	 */
	@Nonnull
	public static Formula createFormulaFromHierarchyIndex(
		@Nonnull HierarchyWithin hierarchyWithin,
		@Nonnull EntityIndex targetEntityIndex,
		@Nonnull int[] nodeIds,
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull Set<Scope> scopesToLookup,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull EntitySchemaContract targetEntitySchema
	) {
		return createFormulaFromHierarchyIndex(
			nodeIds,
			createAndStoreHavingPredicate(
				hierarchyWithin,
				nodeIds,
				queryContext,
				scopesToLookup,
				hierarchyWithin.getHavingChildrenFilter(),
				hierarchyWithin.getHavingAnyChildFilter(),
				hierarchyWithin.getExcludedChildrenFilter(),
				referenceSchema
			),
			hierarchyWithin.isDirectRelation(),
			hierarchyWithin.isExcludingRoot(),
			targetEntitySchema,
			targetEntityIndex,
			queryContext
		);
	}

	/**
	 * Creates a {@link FilterBy} instance based on the provided query context and parent filter constraint.
	 * This method generates a filter by incorporating a locale-specific filter when the query context
	 * has a defined locale. If no locale is specified in the query context, it uses the parent filter directly.
	 *
	 * @param queryContext the context of the query, used to access configurations, dependencies, and the locale (if available).
	 * @param parentFilter the parent {@link FilterConstraint} that serves as the base for building the filter structure.
	 * @return a {@link FilterBy} instance containing the constructed filter constraints.
	 */
	@Nonnull
	private static FilterBy createFilter(@Nonnull QueryPlanningContext queryContext, @Nonnull FilterConstraint parentFilter) {
		return ofNullable(queryContext.getLocale())
			.map(locale -> filterBy(parentFilter, entityLocaleEquals(locale)))
			.orElseGet(() -> filterBy(parentFilter));
	}

	/**
	 * Creates a {@link Formula} from a hierarchy index based on the provided inputs.
	 **/
	@Nonnull
	private static Formula createFormulaFromHierarchyIndex(
		@Nonnull int[] parentIds,
		@Nullable HierarchyFilteringPredicate excludedChildren,
		boolean directRelation,
		boolean excludingRoot,
		@Nonnull EntitySchemaContract targetEntitySchema,
		@Nonnull EntityIndex entityIndex,
		@Nonnull QueryPlanningContext queryContext
	) {
		if (directRelation) {
			// if the hierarchy entity is the same as queried entity
			if (Objects.equals(queryContext.getSchema().getName(), targetEntitySchema.getName())) {
				if (excludedChildren == null) {
					return FormulaFactory.or(
						Arrays.stream(parentIds).mapToObj(
							entityIndex::getHierarchyNodesForParentFormula
						).toArray(Formula[]::new)
					);
				} else {
					return FormulaFactory.or(
						Arrays.stream(parentIds).mapToObj(
							parentId -> entityIndex.getHierarchyNodesForParentFormula(parentId, excludedChildren)
						).toArray(Formula[]::new)
					);
				}
			} else {
				if (excludedChildren == null) {
					return new ConstantFormula(new BaseBitmap(parentIds));
				} else {
					final int[] filteredParents = Arrays.stream(parentIds)
						.filter(excludedChildren::test)
						.toArray();
					return filteredParents.length == 0 ?
						EmptyFormula.INSTANCE : new ConstantFormula(new BaseBitmap(filteredParents));
				}
			}
		} else {
			if (excludedChildren == null) {
				return excludingRoot ?
					FormulaFactory.or(
						Arrays.stream(parentIds).mapToObj(
							entityIndex::getListHierarchyNodesFromParentFormula
						).toArray(Formula[]::new)
					) :
					FormulaFactory.or(
						Arrays.stream(parentIds).mapToObj(
							entityIndex::getListHierarchyNodesFromParentIncludingItselfFormula
						).toArray(Formula[]::new)
					);
			} else {
				return excludingRoot ?
					FormulaFactory.or(
						Arrays.stream(parentIds).mapToObj(
							parentId -> entityIndex.getListHierarchyNodesFromParentFormula(parentId, excludedChildren)
						).toArray(Formula[]::new)
					) :
					FormulaFactory.or(
						Arrays.stream(parentIds).mapToObj(
							parentId -> entityIndex.getListHierarchyNodesFromParentIncludingItselfFormula(parentId, excludedChildren)
						).toArray(Formula[]::new)
					);
			}
		}
	}

	@Nonnull
	@Override
	public Formula translate(@Nonnull HierarchyWithin hierarchyWithin, @Nonnull FilterByVisitor filterByVisitor) {
		// when we target the hierarchy indexes and there are filtering constraints in conjunction scope that target
		// the index, we may omit the formula with ALL records in the index because the other constraints will
		// take care of more limited yet correct set of records
		// but! we can't do this when reference related constraints are found within the query because they'd use
		// the record sets from different indexes than it's our hierarchy index (i.e. not subset)
		if (filterByVisitor.isTargetIndexRepresentingConstraint(hierarchyWithin) &&
			filterByVisitor.isReferenceNotQueriedByOtherConstraints()
		) {
			filterByVisitor.registerFormulaPostProcessor(
				HierarchyOptimizingPostProcessor.class, HierarchyOptimizingPostProcessor::new
			);
		}

		final Formula matchingHierarchyNodeIds = createFormulaFromHierarchyIndex(hierarchyWithin, filterByVisitor);
		final String referenceName = hierarchyWithin.getReferenceName().orElse(null);
		if (referenceName == null) {
			return new HierarchyFormula(null, matchingHierarchyNodeIds);
		} else {
			return new HierarchyFormula(
				referenceName,
				createFormulaForReferencingEntities(
					hierarchyWithin,
					filterByVisitor,
					() -> matchingHierarchyNodeIds
				)
			);
		}
	}

}
