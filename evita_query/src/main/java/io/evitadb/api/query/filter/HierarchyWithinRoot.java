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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintChildrenParamDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintClassifierParamDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintCreatorDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintDef;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * This `withinRootHierarchy` query accepts [Serializable](https://docs.oracle.com/javase/8/docs/api/java/io/Serializable.html)
 * entity type in first argument. There are also optional second and third arguments - see optional arguments {@link HierarchyDirectRelation},
 * and {@link HierarchyExcluding}.
 *
 * Function returns true if entity has at least one [reference](../model/entity_model.md#references) that relates to specified entity
 * type and entity either directly or relates to any other entity of the same type with [hierarchical placement](../model/entity_model.md#hierarchical-placement)
 * subordinate to the directly related entity placement (in other words is present in its sub-tree).
 *
 * Let's have following hierarchical tree of categories (primary keys are in brackets):
 *
 * - TV (1)
 * - Crt (2)
 * - LCD (3)
 * - big (4)
 * - small (5)
 * - Plasma (6)
 * - Fridges (7)
 *
 * When query `withinRootHierarchy('category')` is used in a query targeting product entities all products that
 * relates to any of categories will be returned.
 *
 * Only single `withinRootHierarchy` query can be used in the query.
 *
 * Example:
 *
 * ```
 * withinRootHierarchy('category')
 * ```
 *
 * If you want to query the entity that you're querying on you can also omit entity type specification. See example:
 *
 * ```
 * query(
 * entities('CATEGORY'),
 * filterBy(
 * withinRootHierarchy()
 * )
 * )
 * ```
 *
 * This query will return all categories within `CATEGORY` entity.
 *
 * You may use this query to list entities that refers to the hierarchical entities:
 *
 * ```
 * query(
 * entities('PRODUCT'),
 * filterBy(
 * withinRootHierarchy('CATEGORY')
 * )
 * )
 * ```
 *
 * This query returns all products that are attached to any category.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDef(
	name = "withinRoot",
	shortDescription = "The constraint if entity is placed inside the defined hierarchy tree starting at the root of the tree (or has reference to any hierarchical entity in the tree).",
	supportedIn = ConstraintDomain.ENTITY
)
public class HierarchyWithinRoot extends AbstractFilterConstraintContainer implements HierarchyFilterConstraint {
	@Serial private static final long serialVersionUID = -4396541048481960654L;

	private HierarchyWithinRoot(Serializable[] argument, FilterConstraint[] fineGrainedConstraints) {
		super(argument, fineGrainedConstraints);
		checkInnerConstraintValidity(fineGrainedConstraints);
	}

	@ConstraintCreatorDef(suffix = "self", silentImplicitClassifier = true)
	public HierarchyWithinRoot(@Nonnull @ConstraintChildrenParamDef(uniqueChildren = true) HierarchySpecificationFilterConstraint... with) {
		super(with);
		checkInnerConstraintValidity(with);
	}

	@ConstraintCreatorDef
	public HierarchyWithinRoot(@Nonnull @ConstraintClassifierParamDef String entityType,
	                           @Nonnull @ConstraintChildrenParamDef(uniqueChildren = true) HierarchySpecificationFilterConstraint... with) {
		super(entityType, with);
		checkInnerConstraintValidity(with);
	}

	@Override
	@Nullable
	public String getReferenceName() {
		final Serializable[] arguments = getArguments();
		final Serializable firstArgument = arguments.length > 0 ? arguments[0] : null;
		return firstArgument instanceof Integer ? null : (String) firstArgument;
	}

	/**
	 * Returns true if withinHierarchy should return only entities directly related to the root entity.
	 */
	@Override
	public boolean isDirectRelation() {
		return Arrays.stream(getChildren())
			.anyMatch(HierarchyDirectRelation.class::isInstance);
	}

	/**
	 * Returns ids of child entities which hierarchies should be excluded from search.
	 */
	@Override
	@Nonnull
	public int[] getExcludedChildrenIds() {
		return Arrays.stream(getChildren())
			.filter(HierarchyExcluding.class::isInstance)
			.map(it -> ((HierarchyExcluding) it).getPrimaryKeys())
			.findFirst()
			.orElseGet(() -> new int[0]);
	}

	@Override
	public boolean isNecessary() {
		return true;
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		return new HierarchyWithinRoot(getArguments(), children);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new HierarchyWithinRoot(newArguments, getChildren());
	}

	private void checkInnerConstraintValidity(@Nonnull FilterConstraint[] fineGrainedConstraints) {
		for (FilterConstraint filterConstraint : fineGrainedConstraints) {
			Assert.isTrue(
				filterConstraint instanceof HierarchyExcluding ||
					(filterConstraint instanceof HierarchyDirectRelation && getReferenceName() == null),
				"Constraint hierarchyWithinRoot accepts only Excluding, or DirectRelation when it targets same entity type as inner query!"
			);
		}
	}
}
