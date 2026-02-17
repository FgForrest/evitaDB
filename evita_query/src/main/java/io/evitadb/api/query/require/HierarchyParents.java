/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
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
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.HierarchyAnyHaving;
import io.evitadb.api.query.filter.HierarchyExcluding;
import io.evitadb.api.query.filter.HierarchyHaving;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;

import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * Computes the ancestor axis of the hierarchy node targeted by the `hierarchyWithin` filter constraint, traversing
 * upward from that node toward the tree root. The result is an ordered list of ancestor nodes — from the direct
 * parent all the way up — which is the canonical building block for rendering breadcrumb navigation.
 *
 * By default, the traversal ascends all the way to the top-level nodes (depth-one nodes whose parent is the
 * virtual root). Use a {@link HierarchyStopAt} inner constraint to limit how far up the ancestor axis is
 * followed — for example, to stop at a configurable depth for deep hierarchies.
 *
 * **Sibling enrichment:**
 *
 * An optional nested {@link HierarchySiblings} inner constraint enriches each node on the ancestor axis with its
 * sibling nodes. This lets you render a fully expanded breadcrumb where each breadcrumb step also shows the other
 * options at that level (e.g., a two-level expandable breadcrumb). When `siblings` is nested inside `parents`,
 * it uses the parent node's output name and does not require its own separate name argument.
 *
 * **Interaction with `hierarchyWithin` filter:**
 *
 * The pivot node of `hierarchyWithin` is the starting point of the upward traversal. The `having`, `anyHaving`,
 * and `excluding` inner constraints of `hierarchyWithin` are respected when computing child-count and entity-count
 * statistics at each ancestor node, keeping those numbers consistent with the query result.
 *
 * **Required arguments:**
 *
 * - mandatory `outputName` (`String`): key under which the computed ancestor axis is registered in the extra results
 *
 * **Optional inner constraints:**
 *
 * - {@link HierarchySiblings}: requests sibling nodes for each ancestor returned on the parent axis
 * - {@link EntityFetch}: specifies which data to fetch for each hierarchy entity on the ancestor axis
 * - {@link HierarchyStopAt}: limits how far up the ancestor axis the traversal proceeds
 * - {@link HierarchyStatistics}: requests computation of `CHILDREN_COUNT` and/or `QUERIED_ENTITY_COUNT` per node
 *
 * **Example — breadcrumb for products within the "True Wireless" category:**
 *
 * ```evitaql
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin("categories", attributeEquals("code", "true-wireless"))
 *     ),
 *     require(
 *         hierarchyOfReference(
 *             "categories",
 *             parents(
 *                 "parentAxis",
 *                 entityFetch(attributeContent("code")),
 *                 statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#parents)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "parents",
	shortDescription = "The constraint computes the ancestor axis (parent chain toward root) starting at the node targeted by the hierarchyWithin filter constraint.",
	userDocsLink = "/documentation/query/requirements/hierarchy#parents",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyParents extends AbstractRequireConstraintContainer implements HierarchyRequireConstraint {
	@Serial private static final long serialVersionUID = -6336717342562034135L;
	private static final String CONSTRAINT_NAME = "parents";

	private HierarchyParents(@Nonnull String outputName, @Nonnull RequireConstraint[] children) {
		super(CONSTRAINT_NAME, new Serializable[]{outputName}, children);
		for (RequireConstraint requireConstraint : children) {
			Assert.isTrue(
				requireConstraint instanceof HierarchyOutputRequireConstraint ||
					requireConstraint instanceof HierarchySiblings ||
					requireConstraint instanceof EntityFetch,
				"Constraint HierarchyParents accepts only HierarchyStopAt, HierarchyStatistics, HierarchySiblings and EntityFetch as inner constraints!"
			);
		}
	}

	public HierarchyParents(
		@Nonnull String outputName,
		@Nonnull EntityFetch entityFetch,
		@Nonnull HierarchyOutputRequireConstraint... requirements
	) {
		super(
			CONSTRAINT_NAME,
			new Serializable[]{outputName},
			ArrayUtils.mergeArrays(
				new RequireConstraint[]{entityFetch},
				requirements
			)
		);
	}

	@Creator
	public HierarchyParents(@Nonnull String outputName,
	                        @Nullable EntityFetch entityFetch,
	                        @Nullable HierarchySiblings siblings,
	                        @Nonnull @Child(uniqueChildren = true) HierarchyOutputRequireConstraint... requirements) {
		super(
			CONSTRAINT_NAME,
			new Serializable[]{outputName},
			ArrayUtils.mergeArrays(
				new RequireConstraint[]{entityFetch, siblings},
				requirements
			)
		);
	}

	public HierarchyParents(@Nonnull String outputName, @Nonnull HierarchyOutputRequireConstraint... requirements) {
		super(
			CONSTRAINT_NAME,
			new Serializable[]{outputName},
			requirements
		);
	}

	public HierarchyParents(
		@Nonnull String outputName,
		@Nonnull HierarchySiblings siblings,
		@Nonnull HierarchyOutputRequireConstraint... requirements
	) {
		super(
			CONSTRAINT_NAME,
			new Serializable[]{outputName},
			ArrayUtils.mergeArrays(
				new RequireConstraint[] {siblings},
				requirements
			)
		);
	}

	/**
	 * Returns the key the computed extra result should be registered to.
	 */
	@Nonnull
	@Override
	public String getOutputName() {
		return (String) getArguments()[0];
	}

	/**
	 * Returns the condition that limits the top-down hierarchy traversal.
	 */
	@Nonnull
	public Optional<HierarchyStopAt> getStopAt() {
		for (RequireConstraint constraint : getChildren()) {
			if (constraint instanceof HierarchyStopAt hierarchyStopAt) {
				return of(hierarchyStopAt);
			}
		}
		return empty();
	}

	/**
	 * Returns the constraint that defines whether siblings of all (or specific) returned parent nodes should
	 * be returned and what content requirements are connected with them.
	 */
	@Nonnull
	public Optional<HierarchySiblings> getSiblings() {
		for (RequireConstraint constraint : getChildren()) {
			if (constraint instanceof HierarchySiblings siblings) {
				return of(siblings);
			}
		}
		return empty();
	}

	/**
	 * Returns content requirements for hierarchy entities.
	 */
	@Nonnull
	public Optional<EntityFetch> getEntityFetch() {
		for (RequireConstraint constraint : getChildren()) {
			if (constraint instanceof EntityFetch entityFetch) {
				return of(entityFetch);
			}
		}
		return empty();
	}

	/**
	 * Returns {@link HierarchyStatistics} settings.
	 */
	@Nonnull
	public Optional<HierarchyStatistics> getStatistics() {
		for (RequireConstraint constraint : getChildren()) {
			if (constraint instanceof HierarchyStatistics statistics) {
				return of(statistics);
			}
		}
		return empty();
	}

	@AliasForParameter("requirements")
	@Nonnull
	public HierarchyOutputRequireConstraint[] getOutputRequirements() {
		return Arrays.stream(getChildren())
			.filter(it -> HierarchyOutputRequireConstraint.class.isAssignableFrom(it.getClass()))
			.map(HierarchyOutputRequireConstraint.class::cast)
			.toArray(HierarchyOutputRequireConstraint[]::new);
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 1;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isTrue(ArrayUtils.isEmpty(additionalChildren), "Inner constraints of different type than `require` are not expected.");
		return new HierarchyParents(getOutputName(), children);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length == 1 && newArguments[0] instanceof String,
			"HierarchyParents container accepts only single String argument!"
		);
		return new HierarchyParents((String) newArguments[0], getChildren());
	}

}
