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
 * Computes all sibling nodes of the hierarchy node targeted by the `hierarchyWithin` filter constraint — that is,
 * all other children of the parent of the currently focused node that share the same parent. This is useful for
 * rendering a "you are here, and here are the alternatives at the same level" navigation panel alongside the
 * current page's primary breadcrumb or sub-navigation.
 *
 * By default, `siblings` produces a flat list of the sibling nodes with no further descent. Adding a
 * {@link HierarchyStopAt} inner constraint triggers a top-down traversal from each sibling node down to the
 * specified stopping condition, producing a tree rather than a flat list.
 *
 * **Constraint has no meaning with `hierarchyWithinRoot`:** the virtual top root has no parent, and therefore no
 * siblings are possible; using `siblings` in that context yields no results.
 *
 * **Two usage modes — standalone vs. nested inside `parents`:**
 *
 * When used as a direct child of {@link HierarchyOfSelf} or {@link HierarchyOfReference}, the `outputName`
 * argument is mandatory and specifies the key under which the sibling list is registered in the extra results.
 *
 * When used as a nested child of {@link HierarchyParents}, the `outputName` argument is omitted (it is `null`).
 * In this mode, the `siblings` constraint extends each node on the ancestor axis with its sibling nodes, and
 * the output is integrated into the parent axis result rather than registered as a separate extra result entry.
 *
 * **Interaction with `hierarchyWithin` filter:**
 *
 * The `having`, `anyHaving`, and `excluding` inner constraints of `hierarchyWithin` are respected during statistics
 * computation, keeping entity counts consistent with the user-facing query result.
 *
 * **Optional inner constraints:**
 *
 * - {@link EntityFetch}: specifies which data to fetch for each sibling hierarchy entity
 * - {@link HierarchyStopAt}: limits the top-down traversal depth from each sibling node
 * - {@link HierarchyStatistics}: requests computation of `CHILDREN_COUNT` and/or `QUERIED_ENTITY_COUNT` per node
 *
 * **Example — flat sibling list of the focused "Audio" category with statistics:**
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
 *             siblings(
 *                 "audioSiblings",
 *                 entityFetch(attributeContent("code")),
 *                 statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#siblings)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "siblings",
	shortDescription = "The constraint computes sibling nodes (nodes sharing the same parent) of the node targeted by the hierarchyWithin filter constraint.",
	userDocsLink = "/documentation/query/requirements/hierarchy#siblings",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchySiblings extends AbstractRequireConstraintContainer implements HierarchyRequireConstraint {
	@Serial private static final long serialVersionUID = 6203461550836216251L;
	private static final String CONSTRAINT_NAME = "siblings";

	private HierarchySiblings(
		@Nullable String outputName,
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
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
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
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
		return new HierarchySiblings(newArguments.length == 0 ? null : (String) newArguments[0], getChildren(), getAdditionalChildren());
	}

}
