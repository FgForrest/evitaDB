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
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.query.visitor.ConstraintCloneVisitor;
import io.evitadb.api.query.visitor.QueryPurifierVisitor;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.hierarchy.predicate.FilteringFormulaHierarchyEntityPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static io.evitadb.api.query.QueryConstraints.and;
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

	/**
	 * Method returns {@link Formula} that returns all entity ids that are present in `hierarchyIndexes` and that
	 * doesn't match the `excludedChildrenFormula`.
	 */
	@Nonnull
	protected static Formula getReferencedAndFilteredEntityFormulas(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull FilterConstraint[] excludedChildrenFormula,
		@Nonnull List<EntityIndex> hierarchyIndexes
	) {
		final FilterConstraint filter = getExcludedFormulaDiscardingEntityHaving(and(excludedChildrenFormula));
		// if there is any filtering present
		if (filter != null && filter.isApplicable()) {
			final String referenceName = referenceSchema.getName();
			// transform each hierarchy index into one formula where the entity primary matching the exclusion filter are missing
			final List<Formula> referencedEntityFormulas = new ArrayList<>(hierarchyIndexes.size());
			for (EntityIndex hierarchyIndex : hierarchyIndexes) {
				final ProcessingScope processingScope = filterByVisitor.getProcessingScope();
				referencedEntityFormulas.add(
					filterByVisitor.executeInContextAndIsolatedFormulaStack(
						Collections.singletonList(hierarchyIndex),
						ReferenceContent.ALL_REFERENCES,
						entitySchema,
						referenceSchema,
						processingScope.getNestedQueryFormulaEnricher(),
						processingScope.getEntityNestedQueryComparator(),
						processingScope.withReferenceSchemaAccessor(referenceName),
						(entityContract, attributeName, locale) -> entityContract.getReferences(referenceName)
							.stream()
							.map(it -> it.getAttributeValue(attributeName, locale)),
						() -> {
							filter.accept(filterByVisitor);
							// wrap the result to the NOT formula
							final Formula[] collectedFormulas = filterByVisitor.getCollectedFormulasOnCurrentLevel();
							return switch (collectedFormulas.length) {
								case 0 -> hierarchyIndex.getAllPrimaryKeysFormula();
								case 1 ->
									new NotFormula(collectedFormulas[0], hierarchyIndex.getAllPrimaryKeysFormula());
								default ->
									new NotFormula(new OrFormula(collectedFormulas), hierarchyIndex.getAllPrimaryKeysFormula());
							};
						},
						EntityPrimaryKeyInSet.class
					)
				);
			}
			// join the results using OR
			return FormulaFactory.or(
				referencedEntityFormulas
					.stream()
					.filter(Objects::nonNull)
					.toArray(Formula[]::new)
			);
		} else {
			// else return all entity primary keys found in hierarchy indexes
			return getReferencedEntityFormulas(hierarchyIndexes);
		}
	}
}
