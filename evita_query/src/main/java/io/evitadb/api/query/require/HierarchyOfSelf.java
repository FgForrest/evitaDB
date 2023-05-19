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

package io.evitadb.api.query.require;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AdditionalChild;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;

/**
 * This `hierarchyStatistics` require query triggers computing the statistics for referenced hierarchical entities
 * and adds an object to the result index. It has at least one {@link Serializable}
 * argument that specifies type of hierarchical entity that this entity relates to. Additional arguments allow passing
 * requirements for fetching the referenced entity contents so that there are no other requests to the evitaDB necessary
 * and all data are fetched in single query.
 *
 * When this require query is used an additional object is stored to result index:
 *
 * - **HierarchyStatistics**
 * this object is organized in the tree structure that reflects the hierarchy of the entities of desired type that are
 * referenced by entities returned by primary query, for each tree entity there is a number that represents the count of
 * currently queried entities that relates to that referenced hierarchical entity and match the query filter - either
 * directly or to some subordinate entity of this hierarchical entity
 *
 * Example:
 *
 * <pre>
 * hierarchyStatisticsOfReference('category')
 * hierarchyStatisticsOfReference('category', entityBody(), attributes())
 * </pre>
 *
 * This require query is usually used when hierarchical menu rendering is needed. For example when we need to render
 * menu for entire e-commerce site, but we want to take excluded subtrees into an account and also reflect the filtering
 * conditions that may filter out dozens of products (and thus leading to empty categories) we can invoke following query:
 *
 * <pre>
 * query(
 *     entities('PRODUCT'),
 *     filterBy(
 *         and(
 *             eq('visible', true),
 *             inRange('valid', 2020-07-30T20:37:50+00:00),
 *             priceInCurrency('USD'),
 *             priceValidIn(2020-07-30T20:37:50+00:00),
 *             priceInPriceLists('vip', 'standard'),
 *             withinRootHierarchy('CATEGORY', excluding(3, 7))
 *         )
 *     ),
 *     require(
 *         page(1, 20),
 *         hierarchyStatisticsOfReference('CATEGORY', entityBody(), attributes())
 *     )
 * )
 * </pre>
 *
 * This query would return first page with 20 products (omitting hundreds of others on additional pages) but also returns a
 * HierarchyStatistics in additional data. This object may contain following structure:
 *
 * <pre>
 * Electronics -> 1789
 *     TV -> 126
 *         LED -> 90
 *         CRT -> 36
 *     Washing machines -> 190
 *         Slim -> 40
 *         Standard -> 40
 *         With drier -> 23
 *         Top filling -> 42
 *         Smart -> 45
 *     Cell phones -> 350
 *     Audio / Video -> 230
 *     Printers -> 80
 * </pre>
 *
 * The tree will contain category entities loaded with `attributes` instead the names you see in the example. The number
 * after the arrow represents the count of the products that are referencing this category (either directly or some of its
 * children). You can see there are only categories that are valid for the passed query - excluded category subtree will
 * not be part of the category listing (query filters out all products with excluded category tree) and there is also no
 * category that happens to be empty (e.g. contains no products or only products that don't match the filter query).
 *
 * TOBEDONE JNO: review docs
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "ofSelf",
	shortDescription = "The constraint triggers computation of hierarchy statistics (how many matching children the hierarchy nodes have) of same hierarchical collection into response."
)
public class HierarchyOfSelf extends AbstractRequireConstraintContainer
	implements RootHierarchyConstraint, SeparateEntityContentRequireContainer, ExtraResultRequireConstraint {

	@Serial private static final long serialVersionUID = -4394552939743167661L;

	private HierarchyOfSelf(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(children, additionalChildren);
		for (RequireConstraint child : children) {
			Assert.isTrue(
				child instanceof HierarchyRequireConstraint || child instanceof EntityFetch,
				"Constraint HierarchyOfSelf accepts only HierarchyRequireConstraint, EntityFetch or OrderBy as inner constraints!"
			);
		}
		for (Constraint<?> child : additionalChildren) {
			Assert.isTrue(
				child instanceof OrderBy,
				"Constraint HierarchyOfSelf accepts only HierarchyRequireConstraint, EntityFetch or OrderBy as inner constraints!"
			);
		}
	}

	public HierarchyOfSelf(
		HierarchyRequireConstraint... requirements
	) {
		super(new Serializable[0], requirements);
	}

	@Creator(silentImplicitClassifier = true)
	public HierarchyOfSelf(
		@Nullable @AdditionalChild(domain = ConstraintDomain.ENTITY) OrderBy orderBy,
		// todo lho we cannot use uniqueChildren here because we need duplicate constraints, but we dont have any generic joining containers here. What to do?
		@Nonnull @Child HierarchyRequireConstraint... requirements
	) {
		super(new Serializable[0], requirements, orderBy);
	}

	/**
	 * Returns requirement constraints for the loaded entities.
	 */
	@Nullable
	public HierarchyRequireConstraint[] getRequirements() {
		return Arrays.stream(getChildren())
			.map(it -> (HierarchyRequireConstraint)it)
			.toArray(HierarchyRequireConstraint[]::new);
	}

	/**
	 * Returns filtering constraints that return entities whose trees should be excluded from hierarchy query.
	 */
	@Nonnull
	public Optional<OrderBy> getOrderBy() {
		return Arrays.stream(getAdditionalChildren())
			.filter(OrderBy.class::isInstance)
			.map(OrderBy.class::cast)
			.findFirst();
	}

	@Override
	public boolean isApplicable() {
		return getChildrenCount() > 0;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		return new HierarchyOfSelf(children, additionalChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("This type of constraint doesn't support arguments!");
	}

}
