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

package io.evitadb.core.query.filter.translator.hierarchy;

import io.evitadb.api.exception.TargetEntityIsNotHierarchicalException;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.HierarchyWithinRoot;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.infra.SkipFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Optional;

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link HierarchyWithinRoot} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class HierarchyWithinRootTranslator extends AbstractHierarchyTranslator<HierarchyWithinRoot> {

	@Nonnull
	public static Formula createFormulaFromHierarchyIndex(
		@Nonnull HierarchyWithinRoot hierarchyWithinRoot,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final QueryContext queryContext = filterByVisitor.getQueryContext();

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
			() -> new TargetEntityIsNotHierarchicalException(
				ofNullable(referenceSchema).map(ReferenceSchemaContract::getName).orElse(null),
				targetEntitySchema.getName()
			)
		);

		return queryContext.getGlobalEntityIndexIfExists(targetEntitySchema.getName())
			.map(
				targetEntityIndex ->
					queryContext.computeOnlyOnce(
						Collections.singletonList(targetEntityIndex),
						hierarchyWithinRoot,
						() -> createFormulaFromHierarchyIndex(
							createAndStoreHavingPredicate(
								queryContext,
								of(new FilterBy(hierarchyWithinRoot.getHavingChildrenFilter()))
									.filter(ConstraintContainer::isApplicable)
									.orElse(null),
								of(new FilterBy(hierarchyWithinRoot.getExcludedChildrenFilter()))
									.filter(ConstraintContainer::isApplicable)
									.orElse(null),
								referenceSchema
							),
							hierarchyWithinRoot.isDirectRelation(),
							targetEntityIndex
						)
					)
			)
			.orElse(EmptyFormula.INSTANCE);
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
			filterByVisitor.isTargetIndexQueriedByOtherConstraints() &&
			!filterByVisitor.isReferenceQueriedByOtherConstraints()) {
			return SkipFormula.INSTANCE;
		} else {
			final Formula matchingHierarchyNodIds = createFormulaFromHierarchyIndex(hierarchyWithinRoot, filterByVisitor);
			if (hierarchyWithinRoot.getReferenceName().isEmpty()) {
				return matchingHierarchyNodIds;
			} else {
				return createFormulaForReferencingEntities(
					hierarchyWithinRoot,
					filterByVisitor,
					() -> matchingHierarchyNodIds
				);
			}
		}
	}

}
