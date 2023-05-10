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
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;
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
@ConstraintDefinition(
	name = "within",
	shortDescription = "The constraint if entity is placed inside the defined hierarchy tree (or has reference to any hierarchical entity in the tree).",
	supportedIn = ConstraintDomain.ENTITY
)
public class HierarchyWithin extends AbstractFilterConstraintContainer implements HierarchyFilterConstraint, SeparateEntityScopeContainer {
	@Serial private static final long serialVersionUID = 5346689836560255185L;

	private HierarchyWithin(@Nonnull Serializable[] argument, @Nonnull FilterConstraint[] fineGrainedConstraints, @Nonnull Constraint<?>... additionalChildren) {
		super(argument, fineGrainedConstraints);
		Assert.isPremiseValid(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint hierarchyWithin accepts only filtering inner constraints!"
		);
	}

	@Creator(suffix = "self", silentImplicitClassifier = true)
	public HierarchyWithin(
		@Nonnull @Child(domain = ConstraintDomain.ENTITY) FilterConstraint ofParent,
		@Nonnull @Child(uniqueChildren = true) HierarchySpecificationFilterConstraint... with
	) {
		this(
			NO_ARGS,
			ArrayUtils.mergeArrays(
				new FilterConstraint[] {ofParent},
				with
			)
		);
	}

	@Creator
	public HierarchyWithin(
		@Nonnull @Classifier String referenceName,
		@Nonnull @Child(domain = ConstraintDomain.ENTITY) FilterConstraint ofParent,
		@Nonnull @Child(uniqueChildren = true) HierarchySpecificationFilterConstraint... with
	) {
		this(
			new Serializable[]{referenceName},
			ArrayUtils.mergeArrays(
				new FilterConstraint[] {ofParent},
				with
			)
		);
	}

	@Override
	@Nullable
	public String getReferenceName() {
		return getArguments().length == 0 ? null : (String) getArguments()[0];
	}

	/**
	 * Returns true if withinHierarchy should return only entities directly related to the {@link #getParentFilter()} entity.
	 */
	@Override
	public boolean isDirectRelation() {
		return Arrays.stream(getChildren())
			.anyMatch(HierarchyDirectRelation.class::isInstance);
	}

	/**
	 * Returns filtering constraints that return entities whose trees should be included in hierarchy query.
	 */
	@Nonnull
	public FilterConstraint getParentFilter() {
		return Arrays.stream(getChildren())
			.filter(it -> !(it instanceof HierarchySpecificationFilterConstraint))
			.findFirst()
			.orElseThrow(() -> new EvitaInvalidUsageException("No filtering was specified for the HierarchyWithin constraint!"));
	}

	/**
	 * Returns filtering constraints that return entities whose trees should be excluded from hierarchy query.
	 */
	@Override
	@Nonnull
	public FilterConstraint[] getExcludedChildrenFilter() {
		return Arrays.stream(getChildren())
			.filter(HierarchyExcluding.class::isInstance)
			.map(it -> ((HierarchyExcluding) it).getFiltering())
			.findFirst()
			.orElseGet(() -> new FilterConstraint[0]);
	}

	/**
	 * Returns true if withinHierarchy should not return entities directly related to the {@link #getParentFilter()}} entity.
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
		return getChildren().length > 0;
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		return new HierarchyWithin(getArguments(), children, additionalChildren);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new HierarchyWithin(newArguments, getChildren(), getExcludedChildrenFilter());
	}

}
