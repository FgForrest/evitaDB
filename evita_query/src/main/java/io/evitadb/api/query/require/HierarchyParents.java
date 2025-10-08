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
 * The parents requirement computes the hierarchy tree starting at the same hierarchy node that is targeted by
 * the filtering part of the same query using the hierarchyWithin constraint towards the root of the hierarchy.
 * The scope of the calculated information can be controlled by the stopAt constraint. By default, the traversal goes
 * all the way to the top of the hierarchy tree unless you tell it to stop at anywhere. If you need to access
 * statistical data, use the statistics constraint.
 *
 * The constraint accepts following arguments:
 *
 * - mandatory String argument specifying the output name for the calculated data structure
 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope
 *   of the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may be
 *   present:
 *
 *      - {@link HierarchySiblings}
 *      - {@link EntityFetch}
 *      - {@link HierarchyStopAt}
 *      - {@link HierarchyStatistics}
 *
 * The following query lists products in the category Audio and its subcategories. Along with the products returned,
 * it also returns a computed parentAxis data structure that lists all the parent nodes of the currently focused
 * category True wireless with a computed count of child categories for each menu item and an aggregated count of all
 * products that would fall into the given category.
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "true-wireless")
 *         )
 *     ),
 *     require(
 *         hierarchyOfReference(
 *             "categories",
 *             parents(
 *                 "parentAxis",
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
 * The calculated result for parents is connected with the {@link HierarchyWithin} pivot hierarchy node.
 * If the {@link HierarchyWithin} contains inner constraints {@link HierarchyHaving}, {@link HierarchyAnyHaving}
 * or {@link HierarchyExcluding}, the parents will respect them as well during child nodes / queried entities statistics
 * calculation. The reason is simple: when you render a menu for the query result, you want the calculated statistics
 * to respect the rules that apply to the {@link HierarchyWithin} so that the calculated number remains consistent for
 * the end user.
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#parents">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "parents",
	shortDescription = "The constraint triggers computing the hierarchy parent axis starting at currently requested hierarchy node in filter by constraint.",
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

	public HierarchyParents(@Nonnull String outputName, @Nonnull EntityFetch entityFetch, @Nonnull HierarchyOutputRequireConstraint... requirements) {
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

	public HierarchyParents(@Nonnull String outputName,  @Nonnull HierarchySiblings siblings, @Nonnull HierarchyOutputRequireConstraint... requirements) {
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
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
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
