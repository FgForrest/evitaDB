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
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.infra.SkipFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link HierarchyWithin} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class HierarchyWithinTranslator extends AbstractHierarchyTranslator<HierarchyWithin> {

	public static int[] createFormulaFromHierarchyIndexForDifferentEntity(int parentId, int[] excludedChildrenIds, boolean directRelation, boolean excludingRoot, EntityIndex globalIndex) {
		if (directRelation) {
			if (ArrayUtils.isEmpty(excludedChildrenIds)) {
				return new int[]{parentId};
			} else {
				throw new EvitaInvalidUsageException("Excluded query has no sense when direct relation query is used as well!");
			}
		} else {
			return excludingRoot ?
				globalIndex.listHierarchyNodesFromParent(parentId, excludedChildrenIds).getArray() :
				globalIndex.listHierarchyNodesFromParentIncludingItself(parentId, excludedChildrenIds).getArray();
		}
	}

	@Nonnull
	@Override
	public Formula translate(@Nonnull HierarchyWithin hierarchyWithin, @Nonnull FilterByVisitor filterByVisitor) {
		final String referenceName = hierarchyWithin.getReferenceName();
		final int parentId = hierarchyWithin.getParentId();
		final int[] excludedChildrenIds = hierarchyWithin.getExcludedChildrenIds();
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
			final EntityIndex globalIndex = filterByVisitor.getGlobalEntityIndex();
			return createFormulaFromHierarchyIndexForSameEntity(
				parentId, excludedChildrenIds, directRelation, excludingRoot, globalIndex
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
					final int[] referencedIds = createFormulaFromHierarchyIndexForDifferentEntity(
						parentId, excludedChildrenIds, directRelation, excludingRoot, foreignEntityIndex
					);
					return getReferencedEntityFormulas(filterByVisitor, referenceName, referencedIds);

				} else {
					return getReferencedEntityFormulas(targetIndexes.getIndexesOfType(EntityIndex.class));
				}
			}
		}
	}

	private Formula createFormulaFromHierarchyIndexForSameEntity(int parentId, int[] excludedChildrenIds, boolean directRelation, boolean excludingRoot, EntityIndex globalIndex) {
		if (directRelation) {
			if (ArrayUtils.isEmpty(excludedChildrenIds)) {
				return globalIndex.getHierarchyNodesForParentFormula(parentId);
			} else {
				return new NotFormula(
					new ConstantFormula(
						new BaseBitmap(excludedChildrenIds)
					),
					globalIndex.getHierarchyNodesForParentFormula(parentId)
				);
			}
		} else {
			return excludingRoot ?
				globalIndex.getListHierarchyNodesFromParentFormula(parentId, excludedChildrenIds) :
				globalIndex.getListHierarchyNodesFromParentIncludingItselfFormula(parentId, excludedChildrenIds);
		}
	}

}
