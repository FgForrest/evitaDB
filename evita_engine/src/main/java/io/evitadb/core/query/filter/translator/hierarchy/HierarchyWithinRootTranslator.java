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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.HierarchyWithinRoot;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.infra.SkipFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.hierarchy.predicate.FilteringFormulaHierarchyEntityPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link HierarchyWithinRoot} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class HierarchyWithinRootTranslator extends AbstractHierarchyTranslator<HierarchyWithinRoot> {

	@Nonnull
	public static Formula createFormulaFromHierarchyIndex(
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
		final QueryContext queryContext = filterByVisitor.getQueryContext();
		final String referenceName = hierarchyWithinRoot.getReferenceName();
		final boolean directRelation = hierarchyWithinRoot.isDirectRelation();

		final ReferenceSchemaContract referenceSchema = ofNullable(referenceName)
			.map(it -> filterByVisitor.getSchema().getReferenceOrThrowException(it))
			.orElse(null);
		final EntitySchemaContract targetEntitySchema = ofNullable(referenceSchema)
			.map(it -> filterByVisitor.getSchema(it.getReferencedEntityType()))
			.orElse(filterByVisitor.getSchema());

		Assert.isTrue(
			targetEntitySchema.isWithHierarchy(),
			() -> new TargetEntityIsNotHierarchicalException(referenceName, targetEntitySchema.getName())
		);

		if (referenceName == null) {
			return queryContext.computeOnlyOnce(
				hierarchyWithinRoot,
				() -> createFormulaFromHierarchyIndex(
					createAndStoreExclusionPredicate(
						queryContext,
						hierarchyWithinRoot.getExcludedChildrenFilter(),
						filterByVisitor.getGlobalEntityIndex(),
						referenceSchema
					),
					directRelation,
					filterByVisitor.getGlobalEntityIndex()
				)
			);
		} else {
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
				final TargetIndexes targetIndexes = filterByVisitor.findTargetIndexSet(hierarchyWithinRoot);
				if (targetIndexes == null) {
					final EntityIndex foreignEntityIndex = filterByVisitor.getGlobalEntityIndex(referenceName);
					final FilterConstraint[] excludedChildrenFormula = hierarchyWithinRoot.getExcludedChildrenFilter();
					final HierarchyFilteringPredicate exclusionPredicate = ArrayUtils.isEmpty(excludedChildrenFormula) ?
						null :
						new FilteringFormulaHierarchyEntityPredicate(
							queryContext, foreignEntityIndex, new FilterBy(excludedChildrenFormula), referenceSchema
						);
					final Formula referencedIdsFormula = createFormulaFromHierarchyIndex(exclusionPredicate, directRelation, foreignEntityIndex);
					return getReferencedEntityFormulas(filterByVisitor, referenceName, referencedIdsFormula.compute().getArray());
				} else {
					return getReferencedEntityFormulas(targetIndexes.getIndexesOfType(EntityIndex.class));
				}
			}
		}
	}

}
