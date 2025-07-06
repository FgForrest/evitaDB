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
import io.evitadb.api.query.filter.HierarchyWithinRoot;
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
 * The siblings requirement computes the hierarchy tree starting at the same hierarchy node that is targeted by
 * the filtering part of the same query using the hierarchyWithin. It lists all sibling nodes to the node that is
 * requested by hierarchyWithin constraint (that's why the siblings has no sense with {@link HierarchyWithinRoot}
 * constraint - "virtual" top level node cannot have any siblings). Siblings will produce a flat list of siblings unless
 * the {@link HierarchyStopAt} constraint is used as an inner constraint. The {@link HierarchyStopAt} constraint
 * triggers a top-down hierarchy traversal from each of the sibling nodes until the {@link HierarchyStopAt} is
 * satisfied. If you need to access statistical data, use the statistics constraint.
 *
 * The constraint accepts following arguments:
 *
 * - mandatory String argument specifying the output name for the calculated data structure
 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope
 *   of the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may
 *   be present:
 *
 *      - {@link EntityFetch}
 *      - {@link HierarchyStopAt}
 *      - {@link HierarchyStatistics}
 *
 * The following query lists products in category Audio and its subcategories. Along with the products returned, it also
 * returns a computed audioSiblings data structure that lists the flat category list the currently focused category
 * Audio with a computed count of child categories for each menu item and an aggregated count of all products that would
 * fall into the given category.
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
 *             siblings(
 *                 "audioSiblings",
 *                 entityFetch(attributeContent("code")),
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
 * The calculated result for siblings is connected with the {@link HierarchyWithin} pivot hierarchy node. If
 * the {@link HierarchyWithin} contains inner constraints {@link HierarchyHaving}, {@link HierarchyAnyHaving}
 * or {@link HierarchyExcluding}, the children will respect them as well. The reason is simple: when you render a menu
 * for the query result, you want the calculated statistics to respect the rules that apply to the hierarchyWithin so
 * that the calculated number remains consistent for the end user.
 *
 * <strong>Different siblings syntax when used within parents parent constraint</strong>
 *
 * The siblings constraint can be used separately as a child of {@link HierarchyOfSelf} or {@link HierarchyOfReference},
 * or it can be used as a child constraint of {@link HierarchyParents}. In such a case, the siblings constraint lacks
 * the first string argument that defines the name for the output data structure. The reason is that this name is
 * already defined on the enclosing parents constraint, and the siblings constraint simply extends the data available
 * in its data structure.
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#siblings">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "siblings",
	shortDescription = "The constraint triggers computing the sibling axis for currently requested hierarchy node in filter by constraint or processed node by hierarchy parents axis.",
	userDocsLink = "/documentation/query/requirements/hierarchy#siblings",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchySiblings extends AbstractRequireConstraintContainer implements HierarchyRequireConstraint {
	@Serial private static final long serialVersionUID = 6203461550836216251L;
	private static final String CONSTRAINT_NAME = "siblings";

	private HierarchySiblings(@Nullable String outputName, @Nonnull RequireConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(CONSTRAINT_NAME, new Serializable[]{outputName}, children, additionalChildren);
		for (RequireConstraint requireConstraint : children) {
			Assert.isTrue(
				requireConstraint instanceof HierarchyOutputRequireConstraint ||
					requireConstraint instanceof EntityFetch,
				"Constraint HierarchySiblings accepts only HierarchyStopAt, HierarchyStatistics and EntityFetch as inner constraints!"
			);
		}
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint HierarchySiblings accepts only HierarchyStopAt, HierarchyStatistics and EntityFetch as inner constraints!"
		);
	}

	@Creator
	public HierarchySiblings(@Nullable String outputName,
	                         @Nullable EntityFetch entityFetch,
	                         @Nonnull @Child(uniqueChildren = true) HierarchyOutputRequireConstraint... requirements) {
		super(
			CONSTRAINT_NAME,
			outputName == null ? NO_ARGS : new Serializable[]{outputName},
			ArrayUtils.mergeArrays(
				new RequireConstraint[]{entityFetch},
				requirements
			)
		);
	}

	public HierarchySiblings(@Nullable String outputName, @Nonnull HierarchyOutputRequireConstraint... requirements) {
		super(
			CONSTRAINT_NAME,
			outputName == null ? NO_ARGS : new Serializable[]{outputName},
			requirements
		);
	}

	/**
	 * Returns the key the computed extra result should be registered to.
	 */
	@Nullable
	@Override
	public String getOutputName() {
		final Serializable[] arguments = getArguments();
		return arguments.length > 0 ? (String) arguments[0] : null;
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
		return true;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		return new HierarchySiblings(getOutputName(), children, additionalChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length == 0 ||
			(newArguments.length == 1 && newArguments[0] instanceof String),
			"HierarchySiblings container accepts zero or only single String argument!"
		);
		return new HierarchySiblings((String) newArguments[0], getChildren(), getAdditionalChildren());
	}

}
