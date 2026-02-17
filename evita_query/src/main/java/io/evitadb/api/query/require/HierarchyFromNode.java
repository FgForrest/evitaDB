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
 * Computes a hierarchy subtree starting from a specific pivot node, identified dynamically via a nested
 * {@link HierarchyNode} filter constraint. Unlike {@link HierarchyFromRoot} (which always starts at the virtual top
 * root) and {@link HierarchyChildren} (which starts at the node targeted by `hierarchyWithin`), `fromNode` lets
 * you anchor the computation to any arbitrary node in the hierarchy, completely independently of the query filter.
 *
 * This is particularly powerful for rendering multiple independent side-menus or sub-menus in a single query
 * round-trip — for example, showing the children of both "Portables" and "Laptops" alongside products filtered
 * within "Audio", all in one request.
 *
 * The traversal descends from the resolved pivot node all the way to leaf nodes by default. Use a
 * {@link HierarchyStopAt} inner constraint to cap the depth.
 *
 * **Interaction with `hierarchyWithin` filter:**
 *
 * The pivot node of `hierarchyWithin` does not affect which node `fromNode` starts from — the starting point is
 * determined entirely by the {@link HierarchyNode} inner constraint. However, the `having`, `anyHaving`, and
 * `excluding` inner constraints of `hierarchyWithin` _are_ respected during statistics computation to keep
 * entity counts consistent with the user-facing query result.
 *
 * **Required arguments:**
 *
 * - mandatory `outputName` (`String`): key under which the computed subtree is registered in the extra results map
 * - mandatory {@link HierarchyNode}: a filter identifying the single pivot node that serves as the root of traversal
 *
 * **Optional inner constraints:**
 *
 * - {@link EntityFetch}: specifies which data to fetch for each hierarchy entity in the result
 * - {@link HierarchyStopAt}: limits the traversal depth relative to the pivot node
 * - {@link HierarchyStatistics}: requests computation of `CHILDREN_COUNT` and/or `QUERIED_ENTITY_COUNT` per node
 *
 * **Example — two independent side-menus from different pivot nodes, queried alongside Audio products:**
 *
 * ```evitaql
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin("categories", attributeEquals("code", "audio"))
 *     ),
 *     require(
 *         hierarchyOfReference(
 *             "categories",
 *             fromNode(
 *                 "sideMenu1",
 *                 node(filterBy(attributeEquals("code", "portables"))),
 *                 entityFetch(attributeContent("code")),
 *                 stopAt(distance(1)),
 *                 statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
 *             ),
 *             fromNode(
 *                 "sideMenu2",
 *                 node(filterBy(attributeEquals("code", "laptops"))),
 *                 entityFetch(attributeContent("code")),
 *                 stopAt(distance(1)),
 *                 statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#from-node)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "fromNode",
	shortDescription = "The constraint computes a hierarchy subtree starting at a dynamically specified pivot node identified by a nested filter.",
	userDocsLink = "/documentation/query/requirements/hierarchy#from-node",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyFromNode extends AbstractRequireConstraintContainer implements HierarchyRequireConstraint {
	@Serial private static final long serialVersionUID = 283525753371686479L;
	private static final String CONSTRAINT_NAME = "fromNode";

	private HierarchyFromNode(
		@Nonnull String outputName,
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(CONSTRAINT_NAME, new Serializable[]{outputName}, children, additionalChildren);
		for (RequireConstraint requireConstraint : children) {
			Assert.isTrue(
				requireConstraint instanceof HierarchyNode ||
					requireConstraint instanceof HierarchyOutputRequireConstraint ||
					requireConstraint instanceof EntityFetch,
				"Constraint HierarchyFromNode accepts only HierarchyStopAt, HierarchyStatistics and EntityFetch as inner constraints!"
			);
		}
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint HierarchyFromNode accepts only HierarchyStopAt, HierarchyStatistics and EntityFetch as inner constraints!"
		);
	}

	@Creator
	public HierarchyFromNode(@Nonnull String outputName,
	                         @Nonnull HierarchyNode node,
	                         @Nullable EntityFetch entityFetch,
	                         @Nonnull @Child(uniqueChildren = true) HierarchyOutputRequireConstraint... requirements) {
		super(
			CONSTRAINT_NAME,
			new Serializable[]{outputName},
			ArrayUtils.mergeArrays(
				new RequireConstraint[] {node, entityFetch},
				requirements
			)
		);
	}

	public HierarchyFromNode(@Nonnull String outputName, @Nonnull HierarchyNode fromNode) {
		super(CONSTRAINT_NAME, new Serializable[]{outputName}, fromNode);
	}

	public HierarchyFromNode(
		@Nonnull String outputName,
		@Nonnull HierarchyNode fromNode,
		@Nonnull HierarchyOutputRequireConstraint... requirements
	) {
		super(
			CONSTRAINT_NAME,
			new Serializable[]{outputName},
			ArrayUtils.mergeArrays(
				new RequireConstraint[] {fromNode},
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
	 * Contains filtering condition that allows to find a pivot node that should be used as a root for enclosing
	 * hierarchy constraint container.
	 */
	@AliasForParameter("node")
	@Nonnull
	public HierarchyNode getFromNode() {
		for (RequireConstraint constraint : getChildren()) {
			if (constraint instanceof HierarchyNode hierarchyNode) {
				return hierarchyNode;
			}
		}
		throw new IllegalStateException("The HierarchyNode inner constraint unexpectedly not found!");
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
		return isArgumentsNonNull() && getArguments().length == 1 && getChildren().length >= 1;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		return new HierarchyFromNode(getOutputName(), children, additionalChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length == 1 && newArguments[0] instanceof String,
			"HierarchyFromNode container accepts only single String argument!"
		);
		return new HierarchyFromNode((String) newArguments[0], getChildren(), getAdditionalChildren());
	}

}
