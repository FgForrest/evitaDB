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
 * The `fromNode` requirement computes the hierarchy tree starting from the pivot node of the hierarchy, that is
 * identified by the node inner constraint. The fromNode calculates the result regardless of the potential use of
 * the {@link HierarchyWithin} constraint in the filtering part of the query. The scope of the calculated information
 * can be controlled by the {@link HierarchyStopAt} constraint. By default, the traversal goes all the way to the bottom
 * of the hierarchy tree unless you tell it to stop at anywhere. Calculated data is not affected by
 * the {@link HierarchyWithin} filter constraint - the query can filter entities using {@link HierarchyWithin} from
 * category Accessories, while still allowing you to correctly compute menu at different node defined in a `fromNode`
 * requirement. If you need to access statistical data, use statistics constraint.
 *
 * The constraint accepts following arguments:
 *
 * - mandatory String argument specifying the output name for the calculated data structure
 * - mandatory require constraint node that must match exactly one pivot hierarchical entity that represents the root
 *   node of the traversed hierarchy subtree.
 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope
 *   of the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may be
 *   present:
 *
 *      - {@link EntityFetch}
 *      - {@link HierarchyStopAt}
 *      - {@link HierarchyStatistics}
 *
 * The following query lists products in category Audio and its subcategories. Along with the products returned, it
 * also returns a computed sideMenu1 and sideMenu2 data structure that lists the flat category list for the categories
 * Portables and Laptops with a computed count of child categories for each menu item and an aggregated count of all
 * products that would fall into the given category.
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "audio")
 *         )
 *     ),
 *     require(
 *         hierarchyOfReference(
 *             "categories",
 *             fromNode(
 *                 "sideMenu1",
 *                 node(
 *                     filterBy(
 *                         attributeEquals("code", "portables")
 *                     )
 *                 ),
 *                 entityFetch(attributeContent("code")),
 *                 stopAt(distance(1)),
 *                 statistics(
 *                     CHILDREN_COUNT,
 *                     QUERIED_ENTITY_COUNT
 *                 )
 *             ),
 *             fromNode(
 *                 "sideMenu2",
 *                 node(
 *                     filterBy(
 *                         attributeEquals("code", "laptops")
 *                     )
 *                 ),
 *                 entityFetch(attributeContent("code")),
 *                 stopAt(distance(1)),
 *                 statistics(
 *                     CHILDREN_COUNT,
 *                     QUERIED_ENTITY_COUNT
 *                 )
 *             )
 *         )
 *     )
 * )
 * </pre>
 *
 * The calculated result for `fromNode` is not affected by the {@link HierarchyWithin} pivot hierarchy node.
 * If the {@link HierarchyWithin} contains inner constraints {@link HierarchyHaving}, {@link HierarchyAnyHaving}
 * or {@link HierarchyExcluding}, the `fromNode` respects them. The reason is simple: when you render a menu for
 * the query result, you want the calculated statistics to respect the rules that apply to the hierarchyWithin so that
 * the calculated number remains consistent for the end user.
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#from-node">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "fromNode",
	shortDescription = "The constraint triggers computing the hierarchy subtree starting at pivot node.",
	userDocsLink = "/documentation/query/requirements/hierarchy#from-node",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyFromNode extends AbstractRequireConstraintContainer implements HierarchyRequireConstraint {
	@Serial private static final long serialVersionUID = 283525753371686479L;
	private static final String CONSTRAINT_NAME = "fromNode";

	private HierarchyFromNode(@Nonnull String outputName, @Nonnull RequireConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
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

	public HierarchyFromNode(@Nonnull String outputName, @Nonnull HierarchyNode fromNode, @Nonnull HierarchyOutputRequireConstraint... requirements) {
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
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
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
