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

import io.evitadb.api.exception.EntityIsNotHierarchicalException;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.infra.SkipFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.core.query.filter.FilterByVisitor.createFormulaForTheFilter;
import static java.util.Optional.of;
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
		final QueryContext queryContext = filterByVisitor.getQueryContext();
		final Optional<String> referenceName = hierarchyWithin.getReferenceName();

		final EntitySchemaContract entitySchema = filterByVisitor.getSchema();
		final ReferenceSchemaContract referenceSchema = referenceName
			.map(entitySchema::getReferenceOrThrowException)
			.orElse(null);
		final EntitySchemaContract targetEntitySchema = ofNullable(referenceSchema)
			.map(it -> filterByVisitor.getSchema(it.getReferencedEntityType()))
			.orElse(entitySchema);

		return queryContext.getGlobalEntityIndexIfExists(targetEntitySchema.getName())
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

						final FilterConstraint parentFilter = hierarchyWithin.getParentFilter();
						final Formula hierarchyParentFormula = createFormulaForTheFilter(
							queryContext,
							createFilter(queryContext, parentFilter),
							targetEntitySchema.getName(),
							() -> "Finding hierarchy parent node: " + parentFilter
						);
						queryContext.setRootHierarchyNodesFormula(hierarchyParentFormula);

						return FormulaFactory.or(
							StreamSupport.stream(hierarchyParentFormula.compute().spliterator(), false)
								.map(
									nodeId -> createFormulaFromHierarchyIndex(
										nodeId,
										createAndStoreHavingPredicate(
											nodeId,
											queryContext,
											of(new FilterBy(hierarchyWithin.getHavingChildrenFilter()))
												.filter(ConstraintContainer::isApplicable)
												.orElse(null),
											of(new FilterBy(hierarchyWithin.getExcludedChildrenFilter()))
												.filter(ConstraintContainer::isApplicable)
												.orElse(null),
											referenceSchema
										),
										hierarchyWithin.isDirectRelation(),
										hierarchyWithin.isExcludingRoot(),
										targetEntityIndex,
										queryContext
									)
								)
								.toArray(Formula[]::new)
						);
					}
				))
			.orElse(EmptyFormula.INSTANCE);
	}

	@Nonnull
	private static FilterBy createFilter(QueryContext queryContext, FilterConstraint parentFilter) {
		return ofNullable(queryContext.getLocale())
			.map(locale -> filterBy(parentFilter, entityLocaleEquals(locale)))
			.orElseGet(() -> filterBy(parentFilter));
	}

	private static Formula createFormulaFromHierarchyIndex(
		int parentId,
		@Nullable HierarchyFilteringPredicate excludedChildren,
		boolean directRelation,
		boolean excludingRoot,
		@Nonnull EntityIndex entityIndex,
		@Nonnull QueryContext queryContext
	) {
		if (directRelation) {
			// if the hierarchy entity is the same as queried entity
			if (Objects.equals(queryContext.getSchema().getName(), entityIndex.getEntitySchema().getName())) {
				if (excludedChildren == null) {
					return entityIndex.getHierarchyNodesForParentFormula(parentId);
				} else {
					return entityIndex.getHierarchyNodesForParentFormula(parentId, excludedChildren);
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
					entityIndex.getListHierarchyNodesFromParentFormula(parentId) :
					entityIndex.getListHierarchyNodesFromParentIncludingItselfFormula(parentId);
			} else {
				return excludingRoot ?
					entityIndex.getListHierarchyNodesFromParentFormula(parentId, excludedChildren) :
					entityIndex.getListHierarchyNodesFromParentIncludingItselfFormula(parentId, excludedChildren);
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
			filterByVisitor.isTargetIndexQueriedByOtherConstraints() &&
			!filterByVisitor.isReferenceQueriedByOtherConstraints()
		) {
			return SkipFormula.INSTANCE;
		} else {
			final Formula matchingHierarchyNodeIds = createFormulaFromHierarchyIndex(hierarchyWithin, filterByVisitor);
			if (hierarchyWithin.getReferenceName().isEmpty()) {
				return matchingHierarchyNodeIds;
			} else {
				return createFormulaForReferencingEntities(
					hierarchyWithin,
					filterByVisitor,
					() -> matchingHierarchyNodeIds
				);
			}
		}
	}

}
