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
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.query.visitor.ConstraintCloneVisitor;
import io.evitadb.api.query.visitor.QueryPurifierVisitor;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
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
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

import static java.util.Optional.ofNullable;

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
	protected static HierarchyFilteringPredicate createAndStoreExclusionPredicate(
		@Nonnull QueryContext queryContext,
		@Nullable FilterBy exclusionFilter,
		@Nullable ReferenceSchemaContract referenceSchema
	) {
		if (exclusionFilter == null || !exclusionFilter.isApplicable()) {
			return null;
		} else {
			final FilteringFormulaHierarchyEntityPredicate exclusionPredicate = new FilteringFormulaHierarchyEntityPredicate(
				queryContext,
				exclusionFilter,
				referenceSchema
			);
			queryContext.setHierarchyExclusionPredicate(exclusionPredicate);
			return exclusionPredicate;
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
		Assert.notNull(
			filterByVisitor.getSchema().getReferenceOrThrowException(hierarchyWithinConstraint.getReferenceName()),
			"Reference name validation (will never be printed)."
		);
		final TargetIndexes targetIndexes = filterByVisitor.findTargetIndexSet(hierarchyWithinConstraint);
		if (targetIndexes == null) {
			final Formula hierarchyNodesFormula = hierarchyNodesFormulaSupplier.get();
			final QueryContext queryContext = filterByVisitor.getQueryContext();
			return FormulaFactory.or(
				StreamSupport.stream(hierarchyNodesFormula.compute().spliterator(), false)
					.map(hierarchyNodeId -> (EntityIndex) queryContext.getIndex(
						new EntityIndexKey(
							EntityIndexType.REFERENCED_HIERARCHY_NODE,
							new ReferenceKey(hierarchyWithinConstraint.getReferenceName(), hierarchyNodeId)
						)
					))
					.filter(Objects::nonNull)
					.map(EntityIndex::getAllPrimaryKeysFormula)
					.toArray(Formula[]::new)
			);
		} else {
			// the exclusion was already evaluated when the target indexes were initialized
			return FormulaFactory.or(
				targetIndexes.getIndexesOfType(EntityIndex.class)
					.stream()
					.filter(Objects::nonNull)
					.map(EntityIndex::getAllPrimaryKeysFormula)
					.toArray(Formula[]::new)
			);
		}
	}

	/**
	 * We need to strip all {@link EntityHaving} constraints from the filter because those were already applied
	 * when the `hierarchyIndexes` were selected and as such are "implicit" here.
	 */
	@Nullable
	private static FilterConstraint getExcludedFormulaDiscardingEntityHaving(@Nonnull FilterConstraint excludedChildrenFormula) {
		return ofNullable(
			ConstraintCloneVisitor.clone(
				excludedChildrenFormula,
				(visitor, constraint) -> constraint instanceof EntityHaving ? null : constraint
			)
		)
			.map(it -> (FilterConstraint) QueryPurifierVisitor.purify(it))
			.orElse(null);
	}

	/**
	 * Method returns {@link Formula} that returns all entity ids present in `hierarchyIndexes`.
	 */
	@Nonnull
	protected static Formula getReferencedEntityFormulas(@Nonnull List<EntityIndex> hierarchyIndexes) {
		// return OR product of all indexed primary keys in those indexes
		return FormulaFactory.or(
			hierarchyIndexes.stream()
				// get all entity ids referencing the pivot id
				.map(EntityIndex::getAllPrimaryKeysFormula)
				// filter out empty formulas (with no results) to optimize computation
				.filter(it -> !(it instanceof EmptyFormula))
				// return as array
				.toArray(Formula[]::new)
		);
	}

}
