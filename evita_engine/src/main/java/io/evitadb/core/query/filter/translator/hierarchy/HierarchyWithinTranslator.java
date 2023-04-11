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
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.infra.SkipFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.hierarchy.predicate.FilteringFormulaHierarchyEntityPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link HierarchyWithin} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class HierarchyWithinTranslator extends AbstractHierarchyTranslator<HierarchyWithin> {

	public static Formula createFormulaFromHierarchyIndex(
		int parentId,
		@Nullable HierarchyFilteringPredicate excludedChildren,
		boolean directRelation,
		boolean excludingRoot,
		@Nonnull EntityIndex globalIndex,
		@Nonnull QueryContext queryContext
	) {
		if (directRelation) {
			// if the hierarchy entity is the same as queried entity
			if (Objects.equals(queryContext.getSchema().getName(), globalIndex.getEntitySchema().getName())) {
				if (excludedChildren == null) {
					return globalIndex.getHierarchyNodesForParentFormula(parentId);
				} else {
					return globalIndex.getHierarchyNodesForParentFormula(parentId, excludedChildren);
				}
			} else {
				if (excludedChildren == null) {
					return new ConstantFormula(new BaseBitmap(parentId));
				} else {
					return excludedChildren.test(parentId) ? EmptyFormula.INSTANCE : new ConstantFormula(new BaseBitmap(parentId));
				}
			}
		} else {
			if (excludedChildren == null) {
				return excludingRoot ?
					globalIndex.getListHierarchyNodesFromParentFormula(parentId) :
					globalIndex.getListHierarchyNodesFromParentIncludingItselfFormula(parentId);
			} else {
				return excludingRoot ?
					globalIndex.getListHierarchyNodesFromParentFormula(parentId, excludedChildren) :
					globalIndex.getListHierarchyNodesFromParentIncludingItselfFormula(parentId, excludedChildren);
			}
		}
	}

	@Nonnull
	@Override
	public Formula translate(@Nonnull HierarchyWithin hierarchyWithin, @Nonnull FilterByVisitor filterByVisitor) {
		final QueryContext queryContext = filterByVisitor.getQueryContext();
		final String referenceName = hierarchyWithin.getReferenceName();
		final int parentId = hierarchyWithin.getParentId();
		final boolean directRelation = hierarchyWithin.isDirectRelation();
		final boolean excludingRoot = hierarchyWithin.isExcludingRoot();

		final EntitySchemaContract targetEntitySchema = referenceName == null ?
			filterByVisitor.getSchema() :
			filterByVisitor.getSchema(filterByVisitor.getSchema().getReferenceOrThrowException(referenceName).getReferencedEntityType());

		Assert.isTrue(
			targetEntitySchema.isWithHierarchy(),
			() -> new TargetEntityIsNotHierarchicalException(referenceName, targetEntitySchema.getName())
		);

		if (referenceName == null) {
			return queryContext.computeOnlyOnce(
				hierarchyWithin,
				() -> createFormulaFromHierarchyIndex(
					parentId,
					createAndStoreExclusionPredicate(
						queryContext,
						hierarchyWithin.getExcludedChildrenFilter(),
						filterByVisitor.getGlobalEntityIndex()
					),
					directRelation, excludingRoot, filterByVisitor.getGlobalEntityIndex(), queryContext
				)
			);
		} else {
			// when we target the hierarchy indexes and there are filtering constraints in conjunction scope that target
			// the index, we may omit the formula with ALL records in the index because the other constraints will
			// take care of more limited yet correct set of records
			// but! we can't do this when reference related constraints are found within the query because they'd use
			// the record sets from different indexes than it's our hierarchy index (i.e. not subset)
			if (filterByVisitor.isTargetIndexRepresentingConstraint(hierarchyWithin) &&
				filterByVisitor.isTargetIndexQueriedByOtherConstraints() &&
				!filterByVisitor.isReferenceQueriedByOtherConstraints()
			) {
				return SkipFormula.INSTANCE;
			} else {
				final TargetIndexes targetIndexes = filterByVisitor.findTargetIndexSet(hierarchyWithin);
				if (targetIndexes == null) {
					final EntityIndex foreignEntityIndex = filterByVisitor.getGlobalEntityIndex(referenceName);
					final FilterConstraint[] excludedChildrenFormula = hierarchyWithin.getExcludedChildrenFilter();
					final HierarchyFilteringPredicate exclusionPredicate = ArrayUtils.isEmpty(excludedChildrenFormula) ?
						null : new FilteringFormulaHierarchyEntityPredicate(queryContext, foreignEntityIndex, new FilterBy(excludedChildrenFormula));
					final Formula referencedIds = createFormulaFromHierarchyIndex(
						parentId, exclusionPredicate, directRelation, excludingRoot, foreignEntityIndex, queryContext
					);
					return getReferencedEntityFormulas(filterByVisitor, referenceName, referencedIds.compute().getArray());

				} else {
					return getReferencedEntityFormulas(targetIndexes.getIndexesOfType(EntityIndex.class));
				}
			}
		}
	}

}
