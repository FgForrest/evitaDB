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
import io.evitadb.api.query.filter.HierarchyWithinRoot;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.infra.SkipFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link HierarchyWithinRoot} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class HierarchyWithinRootTranslator extends AbstractHierarchyTranslator<HierarchyWithinRoot> {

	public static int[] createFormulaFromHierarchyIndexForDifferentEntity(int[] excludedChildrenIds, boolean directRelation, EntityIndex globalIndex) {
		if (directRelation) {
			if (ArrayUtils.isEmpty(excludedChildrenIds)) {
				return globalIndex.getRootHierarchyNodes().getArray();
			} else {
				return new NotFormula(
					new BaseBitmap(excludedChildrenIds),
					globalIndex.getRootHierarchyNodes()
				)
					.compute()
					.getArray();
			}
		} else {
			return globalIndex.listHierarchyNodesFromRoot(excludedChildrenIds).getArray();
		}
	}

	@Nonnull
	@Override
	public Formula translate(@Nonnull HierarchyWithinRoot hierarchyWithinRoot, @Nonnull FilterByVisitor filterByVisitor) {
		final String referenceName = hierarchyWithinRoot.getReferenceName();
		final int[] excludedChildrenIds = hierarchyWithinRoot.getExcludedChildrenIds();
		final boolean directRelation = hierarchyWithinRoot.isDirectRelation();

		final EntitySchemaContract targetEntitySchema = referenceName == null ?
			filterByVisitor.getSchema() :
			filterByVisitor.getSchema(filterByVisitor.getSchema().getReferenceOrThrowException(referenceName).getReferencedEntityType());

		Assert.isTrue(
			targetEntitySchema.isWithHierarchy(),
			() -> new TargetEntityIsNotHierarchicalException(referenceName, targetEntitySchema.getName())
		);

		if (referenceName == null) {
			final EntityIndex globalIndex = filterByVisitor.getGlobalEntityIndex();
			return createFormulaFromHierarchyIndex(excludedChildrenIds, directRelation, globalIndex);
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
					final int[] referencedIds = createFormulaFromHierarchyIndexForDifferentEntity(excludedChildrenIds, directRelation, foreignEntityIndex);
					return getReferencedEntityFormulas(filterByVisitor, referenceName, referencedIds);
				} else {
					return getReferencedEntityFormulas(targetIndexes.getIndexesOfType(EntityIndex.class));
				}
			}
		}
	}

	private Formula createFormulaFromHierarchyIndex(int[] excludedChildrenIds, boolean directRelation, EntityIndex globalIndex) {
		if (directRelation) {
			if (ArrayUtils.isEmpty(excludedChildrenIds)) {
				return globalIndex.getRootHierarchyNodesFormula();
			} else {
				return new NotFormula(
					new ConstantFormula(
						new BaseBitmap(excludedChildrenIds)
					),
					globalIndex.getRootHierarchyNodesFormula()
				);
			}
		} else {
			return globalIndex.getListHierarchyNodesFromRootFormula(excludedChildrenIds);
		}
	}

}
