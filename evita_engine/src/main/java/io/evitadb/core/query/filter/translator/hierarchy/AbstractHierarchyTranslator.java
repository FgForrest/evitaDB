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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.hierarchy.predicate.FilteringFormulaHierarchyEntityPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

/**
 * Abstract super class for hierarchy query translators containing the shared logic.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract class AbstractHierarchyTranslator<T extends FilterConstraint> implements FilteringConstraintTranslator<T>, SelfTraversingTranslator {

	/**
	 * Creates a hierarchy exclusion predicate if the exclusion filter is defined and stores it to {@link QueryContext}
	 * for later use.
	 */
	@Nullable
	protected static HierarchyFilteringPredicate createAndStoreHavingPredicate(
		@Nullable Integer parentId,
		@Nonnull QueryContext queryContext,
		@Nullable FilterBy havingFilter,
		@Nullable FilterBy exclusionFilter,
		@Nullable ReferenceSchemaContract referenceSchema
	) {
		final boolean havingNotPresent = havingFilter == null || !havingFilter.isApplicable();
		final boolean exclusionNotPresent = exclusionFilter == null || !exclusionFilter.isApplicable();
		Assert.isTrue(
			havingNotPresent || exclusionNotPresent,
			() -> "Having and exclusion filters are mutually exclusive! " +
				"The query contains both having: " + havingFilter + " and exclusion: " + exclusionFilter + " constraints."
		);
		if (exclusionNotPresent && havingNotPresent) {
			return null;
		} else {
			final HierarchyFilteringPredicate predicate;
			if (havingNotPresent) {
				predicate = new FilteringFormulaHierarchyEntityPredicate(
					parentId, false,
					queryContext,
					exclusionFilter,
					referenceSchema
				).negate();
			} else {
				predicate = new FilteringFormulaHierarchyEntityPredicate(
					parentId, true,
					queryContext,
					havingFilter,
					referenceSchema
				);
			}

			queryContext.setHierarchyHavingPredicate(predicate);
			return predicate;
		}
	}

	/**
	 * Method creates a formula producing primary keys that are part of the requested `hierarchyWithinConstraint`.
	 * It favorites the already resolved target index set connected with the constraint, but if such is missing it
	 * computes the set using provided `hierarchyNodesFormulaSupplier`.
	 */
	@Nonnull
	protected static Formula createFormulaForReferencingEntities(
		@Nonnull HierarchyFilterConstraint hierarchyWithinConstraint,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull Supplier<Formula> hierarchyNodesFormulaSupplier
		) {
		final String referenceName = hierarchyWithinConstraint.getReferenceName().orElseThrow();
		Assert.notNull(
			filterByVisitor.getSchema().getReferenceOrThrowException(referenceName),
			"Reference name validation (will never be printed)."
		);
		final TargetIndexes<?> targetIndexes = filterByVisitor.findTargetIndexSet(hierarchyWithinConstraint);
		if (targetIndexes == null) {
			final Formula hierarchyNodesFormula = hierarchyNodesFormulaSupplier.get();
			final QueryContext queryContext = filterByVisitor.getQueryContext();
			return FormulaFactory.or(
				StreamSupport.stream(hierarchyNodesFormula.compute().spliterator(), false)
					.map(
						hierarchyNodeId -> queryContext.getIndex(
							new EntityIndexKey(
								EntityIndexType.REFERENCED_HIERARCHY_NODE,
								new ReferenceKey(referenceName, hierarchyNodeId)
							)
						).orElse(null)
					)
					.map(EntityIndex.class::cast)
					.filter(Objects::nonNull)
					.map(EntityIndex::getAllPrimaryKeysFormula)
					.toArray(Formula[]::new)
			);
		} else {
			// the exclusion was already evaluated when the target indexes were initialized
			return FormulaFactory.or(
				targetIndexes.getIndexes()
					.stream()
					.filter(Objects::nonNull)
					.map(EntityIndex.class::cast)
					.map(EntityIndex::getAllPrimaryKeysFormula)
					.toArray(Formula[]::new)
			);
		}
	}

}
