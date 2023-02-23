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
import io.evitadb.api.query.descriptor.annotation.ConstraintValueParamDef;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * This `withinHierarchy` query accepts [Serializable](https://docs.oracle.com/javase/8/docs/api/java/io/Serializable.html)
 * entity type in first argument, primary key of [Integer](https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html)
 * type of entity with [hierarchical placement](../model/entity_model.md#hierarchical-placement) in second argument. There
 * are also optional third and fourth arguments - see optional arguments {@link HierarchyDirectRelation}, {@link HierarchyExcludingRoot}
 * and {@link HierarchyExcluding}.
 *
 * Constraint can also have only one numeric argument representing primary key of [Integer](https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html)
 * the very same entity type in case this entity has [hierarchical placement](../model/entity_model.md#hierarchical-placement)
 * defined. This format of the query may be used for example for returning category sub-tree (where we want to return
 * category entities and also query them by their own hierarchy placement).
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
 * When query `withinHierarchy('category', 1)` is used in a query targeting product entities only products that
 * relates directly to categories: `TV`, `Crt`, `LCD`, `big`, `small` and `Plasma` will be returned. Products in `Fridges`
 * will be omitted because they are not in a sub-tree of `TV` hierarchy.
 *
 * Only single `withinHierarchy` query can be used in the query.
 *
 * Example:
 *
 * ```
 * withinHierarchy('category', 4)
 * ```
 *
 * If you want to query the entity that you're querying on you can also omit entity type specification. See example:
 *
 * ```
 * query(
 * entities('CATEGORY'),
 * filterBy(
 * withinHierarchy(5)
 * )
 * )
 * ```
 *
 * This query will return all categories that belong to the sub-tree of category with primary key equal to 5.
 *
 * If you want to list all entities from the root level you need to use different query - `withinRootHierarchy` that
 * has the same notation but doesn't specify the id of the root level entity:
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
 * This query returns all products that are attached to any category. Although, this query doesn't make much sense it starts
 * to be useful when combined with additional inner constraints described in following paragraphs.
 *
 * You can use additional sub constraints in `withinHierarchy` query: {@link HierarchyDirectRelation}, {@link HierarchyExcludingRoot}
 * and {@link HierarchyExcluding}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDef(
	name = "within",
	shortDescription = "The constraint if entity is placed inside the defined hierarchy tree (or has reference to any hierarchical entity in the tree).",
	supportedIn = ConstraintDomain.ENTITY
)
public class HierarchyWithin extends AbstractFilterConstraintContainer implements HierarchyFilterConstraint {
	@Serial private static final long serialVersionUID = 5346689836560255185L;

	private HierarchyWithin(Serializable[] argument, FilterConstraint[] fineGrainedConstraints) {
		super(argument, fineGrainedConstraints);
		checkInnerConstraintValidity(fineGrainedConstraints);
	}

	@ConstraintCreatorDef(suffix = "self", silentImplicitClassifier = true)
	public HierarchyWithin(@Nonnull @ConstraintValueParamDef Integer ofParent,
	                       @Nonnull @ConstraintChildrenParamDef(uniqueChildren = true) HierarchySpecificationFilterConstraint... with) {
		super(ofParent, with);
		checkInnerConstraintValidity(with);
	}

	@ConstraintCreatorDef
	public HierarchyWithin(@Nonnull @ConstraintClassifierParamDef String referenceName,
	                       @Nonnull @ConstraintValueParamDef Integer ofParent,
	                       @Nonnull @ConstraintChildrenParamDef(uniqueChildren = true) HierarchySpecificationFilterConstraint... with) {
		super(referenceName, ofParent, with);
		checkInnerConstraintValidity(with);
	}

	@Override
	@Nullable
	public String getReferenceName() {
		final Serializable firstArgument = getArguments()[0];
		return firstArgument instanceof Integer ? null : (String) firstArgument;
	}

	/**
	 * Returns true if withinHierarchy should return only entities directly related to the {@link #getParentId()} entity.
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

	/**
	 * Returns id of the entity in which hierarchy to search.
	 */
	public int getParentId() {
		final Serializable[] arguments = getArguments();
		return arguments[0] instanceof Integer ? (Integer) arguments[0] : (Integer) arguments[1];
	}

	/**
	 * Returns true if withinHierarchy should not return entities directly related to the {@link #getParentId()} entity.
	 */
	public boolean isExcludingRoot() {
		return Arrays.stream(getChildren())
			.anyMatch(HierarchyExcludingRoot.class::isInstance);
	}

	@Override
	public boolean isNecessary() {
		return super.isNecessary() || isApplicable();
	}

	@Override
	public boolean isApplicable() {
		final Serializable[] arguments = getArguments();
		return isArgumentsNonNull() && arguments.length >= 2 || (arguments.length >= 1 && arguments[0] instanceof Integer);
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull Constraint<?>[] children, @Nonnull Constraint<?>[] additionalChildren) {
		final FilterConstraint[] filterChildren = Arrays.stream(children)
				.map(c -> (FilterConstraint) c)
				.toArray(FilterConstraint[]::new);
		return new HierarchyWithin(getArguments(), filterChildren);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new HierarchyWithin(newArguments, getChildren());
	}

	private void checkInnerConstraintValidity(FilterConstraint[] fineGrainedConstraints) {
		for (FilterConstraint filterConstraint : fineGrainedConstraints) {
			Assert.isTrue(
				filterConstraint instanceof HierarchyDirectRelation ||
					filterConstraint instanceof HierarchyExcluding ||
					filterConstraint instanceof HierarchyExcludingRoot,
				"Constraint hierarchyWithin accepts only DirectRelation, ExcludingRoot and Excluding as inner constraints!"
			);
		}
	}
}
