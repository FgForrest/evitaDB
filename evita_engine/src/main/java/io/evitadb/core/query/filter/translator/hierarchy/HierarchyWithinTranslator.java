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

package io.evitadb.core.query.filter.translator.hierarchy;

import io.evitadb.api.exception.EntityIsNotHierarchicalException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
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
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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

	@Nonnull
	public static Formula createFormulaFromHierarchyIndex(
		@Nonnull HierarchyWithin hierarchyWithin,
		@Nonnull FilterByVisitor filterByVisitor
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

		// we use only the first applicable scope here - if LIVE scope is present it always takes precedence
		final Set<Scope> scopesToLookup = filterByVisitor.getProcessingScope().getScopes();
		return Arrays.stream(Scope.values())
			.filter(scopesToLookup::contains)
			.map(scope -> queryContext.getEntityIndex(targetEntitySchema.getName(), new EntityIndexKey(EntityIndexType.GLOBAL, scope), EntityIndex.class))
			.filter(Optional::isPresent)
			.map(Optional::get)
			.map(
				targetEntityIndex -> queryContext.computeOnlyOnce(
					Collections.singletonList(targetEntityIndex),
					hierarchyWithin,
					() -> {
						Assert.isTrue(
							targetEntitySchema.isWithHierarchy(),
							() -> new EntityIsNotHierarchicalException(
								ofNullable(referenceSchema).map(ReferenceSchemaContract::getName).orElse(null),
								targetEntitySchema.getName()
							)
						);

						Assert.isTrue(
							scopesToLookup.stream().allMatch(targetEntitySchema::isHierarchyIndexedInScope),
							() -> new HierarchyNotIndexedException(targetEntitySchema)
						);

						final FilterConstraint parentFilter = hierarchyWithin.getParentFilter();
						final Formula hierarchyParentFormula = createFormulaForTheFilter(
							queryContext,
							scopesToLookup,
							createFilter(queryContext, parentFilter),
							targetEntitySchema.getName(),
							() -> "Finding hierarchy parent node: " + parentFilter
						);
						// we need to initialize the formula with internal context,
						// because we'll need the result in planning phase
						hierarchyParentFormula.initialize(filterByVisitor.getInternalExecutionContext());

						queryContext.setRootHierarchyNodesFormula(hierarchyParentFormula);

						final int[] nodeIds = hierarchyParentFormula.compute().stream().toArray();
						return createFormulaFromHierarchyIndex(
							hierarchyWithin,
							targetEntityIndex,
							nodeIds,
							queryContext,
							scopesToLookup,
							referenceSchema,
							targetEntitySchema
						);
					}
				))
			.filter(it -> it != EmptyFormula.INSTANCE)
			.findFirst()
			.orElse(EmptyFormula.INSTANCE);
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
			!filterByVisitor.isReferenceQueriedByOtherConstraints()
		) {
			filterByVisitor.registerFormulaPostProcessor(
				HierarchyOptimizingPostProcessor.class, HierarchyOptimizingPostProcessor::new
			);
		}

		final Formula matchingHierarchyNodeIds = createFormulaFromHierarchyIndex(hierarchyWithin, filterByVisitor);
		if (hierarchyWithin.getReferenceName().isEmpty()) {
			return new HierarchyFormula(matchingHierarchyNodeIds);
		} else {
			return new HierarchyFormula(
				createFormulaForReferencingEntities(
					hierarchyWithin,
					filterByVisitor,
					() -> matchingHierarchyNodeIds
				)
			);
		}
	}

}
