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
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.hierarchy.predicate.FilteringFormulaHierarchyEntityPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
	public static HierarchyFilteringPredicate createAndStoreExclusionPredicate(
		@Nonnull QueryContext queryContext,
		@Nullable FilterConstraint[] exclusionFilter,
		@Nonnull EntityIndex targetHierarchyIndex
	) {
		if (ArrayUtils.isEmpty(exclusionFilter)) {
			return null;
		} else {
			final FilteringFormulaHierarchyEntityPredicate exclusionPredicate = new FilteringFormulaHierarchyEntityPredicate(
				queryContext, targetHierarchyIndex, new FilterBy(exclusionFilter)
			);
			queryContext.setHierarchyExclusionPredicate(exclusionPredicate);
			return exclusionPredicate;
		}
	}

	/**
	 * Method returns {@link Formula} that returns all entity ids that are referencing ids in `pivotIds`.
	 */
	@Nonnull
	protected Formula getReferencedEntityFormulas(@Nonnull FilterByVisitor filterByVisitor, @Nonnull String referenceName, @Nonnull int[] pivotIds) {
		// hierarchy indexes are tied to the entity that is being requested
		final String containingEntityType = filterByVisitor.getSchema().getName();
		// return OR product of all indexed primary keys in those indexes
		return FormulaFactory.or(
			Arrays.stream(pivotIds)
				// for each pivot id create EntityIndexKey to REFERENCED_HIERARCHY_NODE entity index
				.mapToObj(referencedId -> new EntityIndexKey(
					EntityIndexType.REFERENCED_HIERARCHY_NODE,
					new ReferenceKey(referenceName, referencedId)
				))
				// get the index
				.map(it -> filterByVisitor.getIndex(containingEntityType, it))
				// filter out indexes that are not present (no entity references the pivot id)
				.filter(Objects::nonNull)
				// get all entity ids referencing the pivot id
				.map(EntityIndex::getAllPrimaryKeysFormula)
				// filter out empty formulas (with no results) to optimize computation
				.filter(it -> !(it instanceof EmptyFormula))
				// return as array
				.toArray(Formula[]::new)
		);
	}

	/**
	 * Method returns {@link Formula} that returns all entity ids that are referencing ids in `pivotIds`.
	 */
	@Nonnull
	protected Formula getReferencedEntityFormulas(@Nonnull List<EntityIndex> entityIndexes) {
		// return OR product of all indexed primary keys in those indexes
		return FormulaFactory.or(
			entityIndexes.stream()
				// get all entity ids referencing the pivot id
				.map(EntityIndex::getAllPrimaryKeysFormula)
				// filter out empty formulas (with no results) to optimize computation
				.filter(it -> !(it instanceof EmptyFormula))
				// return as array
				.toArray(Formula[]::new)
		);
	}

}
