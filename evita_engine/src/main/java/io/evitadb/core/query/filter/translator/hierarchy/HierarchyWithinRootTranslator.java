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
import io.evitadb.api.query.filter.HierarchyWithinRoot;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
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
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link HierarchyWithinRoot} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class HierarchyWithinRootTranslator extends AbstractHierarchyTranslator<HierarchyWithinRoot> {

	/**
	 * Creates the formula of the hierarchy nodes the constraint selects in all processing scopes: the union of what
	 * {@link #createFormulaFromHierarchyIndex(HierarchyWithinRoot, FilterByVisitor, Scope)} selects in each of them.
	 *
	 * @param hierarchyWithinRoot the translated constraint
	 * @param filterByVisitor     the visitor translating the constraint
	 * @return the formula of the selected hierarchy nodes
	 */
	@Nonnull
	public static Formula createFormulaFromHierarchyIndex(
		@Nonnull HierarchyWithinRoot hierarchyWithinRoot,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final Set<Scope> scopes = filterByVisitor.getProcessingScope().getScopes();
		if (scopes.size() == 1) {
			return createFormulaFromHierarchyIndex(hierarchyWithinRoot, filterByVisitor, scopes.iterator().next());
		}
		final Formula nodesOfAllScopes = FormulaFactory.or(
			Arrays.stream(Scope.values())
				.filter(scopes::contains)
				.map(scope -> createFormulaFromHierarchyIndex(hierarchyWithinRoot, filterByVisitor, scope))
				.filter(it -> it != EmptyFormula.INSTANCE)
				.toArray(Formula[]::new)
		);
		// the index selection computes the nodes in the planning phase
		nodesOfAllScopes.initialize(filterByVisitor.getInternalExecutionContext());
		return nodesOfAllScopes;
	}

	/**
	 * Creates the formula of the hierarchy nodes the constraint selects in the tree of one scope, exactly as a query
	 * over that scope alone would, with the node visibility recorded for that scope and the result memoized per scope.
	 *
	 * @param hierarchyWithinRoot the translated constraint
	 * @param filterByVisitor     the visitor translating the constraint
	 * @param scope               the scope to select the nodes in
	 * @return the formula of the nodes selected in the scope, {@link EmptyFormula} when the scope has no index - the
	 *         node filters are then checked over an empty index
	 */
	@Nonnull
	public static Formula createFormulaFromHierarchyIndex(
		@Nonnull HierarchyWithinRoot hierarchyWithinRoot,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull Scope scope
	) {
		final QueryPlanningContext queryContext = filterByVisitor.getQueryContext();
		final Optional<String> referenceName = hierarchyWithinRoot.getReferenceName();

		final EntitySchemaContract entitySchema = filterByVisitor.getSchema();
		final ReferenceSchemaContract referenceSchema = referenceName
			.map(entitySchema::getReferenceOrThrowException)
			.orElse(null);
		final EntitySchemaContract targetEntitySchema = ofNullable(referenceSchema)
			.map(it -> filterByVisitor.getSchema(it.getReferencedEntityType()))
			.orElse(entitySchema);
		Assert.isTrue(
			targetEntitySchema.isWithHierarchy(),
			() -> new EntityIsNotHierarchicalException(
				ofNullable(referenceSchema).map(ReferenceSchemaContract::getName).orElse(null),
				targetEntitySchema.getName()
			)
		);

		final Set<Scope> scopeToLookup = EnumSet.of(scope);
		final Function<EntityIndex, Formula> nodesFormulaFactory = targetEntityIndex -> createFormulaFromHierarchyIndex(
			createAndStoreHavingPredicate(
				hierarchyWithinRoot,
				null,
				queryContext,
				scopeToLookup,
				hierarchyWithinRoot.getHavingChildrenFilter(),
				hierarchyWithinRoot.getHavingAnyChildFilter(),
				hierarchyWithinRoot.getExcludedChildrenFilter(),
				referenceSchema
			),
			hierarchyWithinRoot.isDirectRelation(),
			targetEntityIndex
		);
		return queryContext.getEntityIndex(
				targetEntitySchema.getName(), new EntityIndexKey(EntityIndexType.GLOBAL, scope), EntityIndex.class
			)
			.map(
				targetEntityIndex ->
					queryContext.computeOnlyOnce(
						Collections.singletonList(targetEntityIndex),
						hierarchyWithinRoot,
						() -> nodesFormulaFactory.apply(targetEntityIndex),
						scopesCacheKey(scopeToLookup)
					)
			)
			.orElseGet(
				() -> {
					// a scope holding no entity of the hierarchy selects no node, but the node filters of the
					// constraint are checked against the schema over an empty index all the same - the query must
					// not fail or pass depending on the data; the scope contributes nothing, whatever the translation
					// produced
					nodesFormulaFactory.apply(GlobalEntityIndex.createEmptyIndex(targetEntitySchema.getName(), scope));
					return EmptyFormula.INSTANCE;
				}
			);
	}

	@Nonnull
	private static Formula createFormulaFromHierarchyIndex(
		@Nullable HierarchyFilteringPredicate excludedChildren,
		boolean directRelation,
		@Nonnull EntityIndex globalIndex
	) {
		if (directRelation) {
			if (excludedChildren == null) {
				return globalIndex.getRootHierarchyNodesFormula();
			} else {
				return globalIndex.getRootHierarchyNodesFormula(excludedChildren);
			}
		} else {
			if (excludedChildren == null) {
				return globalIndex.getListHierarchyNodesFromRootFormula();
			} else {
				return globalIndex.getListHierarchyNodesFromRootFormula(excludedChildren);
			}
		}
	}

	@Nonnull
	@Override
	public Formula translate(@Nonnull HierarchyWithinRoot hierarchyWithinRoot, @Nonnull FilterByVisitor filterByVisitor) {
		// when we target the hierarchy indexes and there are filtering constraints in conjunction scope that target
		// the index, we may omit the formula with ALL records in the index because the other constraints will
		// take care of more limited yet correct set of records
		// but! we can't do this when reference related constraints are found within the query because they'd use
		// the record sets from different indexes than it's our hierarchy index (i.e. not subset)
		if (filterByVisitor.isTargetIndexRepresentingConstraint(hierarchyWithinRoot) &&
			filterByVisitor.isReferenceNotQueriedByOtherConstraints()
		) {
			filterByVisitor.registerFormulaPostProcessor(
				HierarchyOptimizingPostProcessor.class, HierarchyOptimizingPostProcessor::new
			);
		}

		final Formula matchingHierarchyNodeIds = createFormulaFromHierarchyIndex(hierarchyWithinRoot, filterByVisitor);
		final String referenceName = hierarchyWithinRoot.getReferenceName().orElse(null);
		if (referenceName == null) {
			return new HierarchyFormula(null, matchingHierarchyNodeIds);
		} else {
			return new HierarchyFormula(
				referenceName,
				createFormulaForReferencingEntities(
					hierarchyWithinRoot,
					filterByVisitor,
					() -> matchingHierarchyNodeIds
				)
			);
		}
	}

}
