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
import io.evitadb.api.query.ConstraintWithDefaults;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AdditionalChild;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * Triggers computation of hierarchy data structures from the hierarchical entity type that is _referenced_ by
 * the queried entity. For example, if you are querying Product entities and want to compute a category tree
 * navigation based on the `categories` reference, use this constraint.
 *
 * This is the complement of {@link HierarchyOfSelf}: while `hierarchyOfSelf` operates on the queried entity's own
 * hierarchy, `hierarchyOfReference` operates on a hierarchy reached through a named reference. Both constraints can
 * appear together in a single query for unusual scenarios where the queried entity is itself hierarchical and also
 * references another hierarchy.
 *
 * The constraint may be repeated multiple times in a single query, once per reference type, when different
 * hierarchy traversal settings are needed for different references.
 *
 * **Required arguments:**
 *
 * - one or more reference names (classifier strings) identifying which reference(s) lead to the target hierarchical
 *   entity type; if multiple reference names share the same traversal requirements, they can be passed together
 *
 * **Optional arguments:**
 *
 * - {@link EmptyHierarchicalEntityBehaviour}: controls whether hierarchy nodes that contain no queried entities
 *   (neither directly nor transitively through their subtree) are included in the result:
 *     - {@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY} _(default)_: empty nodes are pruned from the tree
 *     - {@link EmptyHierarchicalEntityBehaviour#LEAVE_EMPTY}: empty nodes remain in the computed data structure
 * - an `OrderBy` constraint (as an additional child) controlling the sort order of `LevelInfo` elements within
 *   the computed hierarchy output
 *
 * **Required sub-constraints (at least one must be present):**
 *
 * - {@link HierarchyFromRoot}: starts traversal from the virtual top root of the hierarchy
 * - {@link HierarchyFromNode}: starts traversal from a dynamically identified pivot node
 * - {@link HierarchyChildren}: computes children of the node targeted by the current filter's `hierarchyWithin`
 * - {@link HierarchyParents}: traverses the ancestor axis from the current filtered node toward the root
 * - {@link HierarchySiblings}: lists siblings of the node targeted by the current filter's `hierarchyWithin`
 *
 * **Example — compute a category mega-menu for products filtered within a category:**
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
 *             fromRoot(
 *                 "megaMenu",
 *                 entityFetch(attributeContent("code")),
 *                 stopAt(level(2)),
 *                 statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "ofReference",
	shortDescription = "The constraint triggers computation of hierarchy data structures (tree, statistics, parent chain) for a referenced hierarchical entity type.",
	userDocsLink = "/documentation/query/requirements/hierarchy#hierarchy-of-reference"
)
public class HierarchyOfReference extends AbstractRequireConstraintContainer
	implements ConstraintWithDefaults<RequireConstraint>, RootHierarchyConstraint, SeparateEntityContentRequireContainer, ExtraResultRequireConstraint {

	@Serial private static final long serialVersionUID = 3121491811975308390L;

	private HierarchyOfReference(
		@Nonnull Serializable[] arguments,
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(arguments, children, additionalChildren);
		for (RequireConstraint child : children) {
			Assert.isTrue(
				child instanceof HierarchyRequireConstraint || child instanceof EntityFetch,
				"Constraint HierarchyOfReference accepts only HierarchyRequireConstraint, EntityFetch or OrderBy as inner constraints!"
			);
		}
		for (Constraint<?> child : additionalChildren) {
			Assert.isTrue(
				child instanceof OrderBy,
				"Constraint HierarchyOfReference accepts only HierarchyRequireConstraint, EntityFetch or OrderBy as inner constraints!"
			);
		}
	}

	public HierarchyOfReference(
		@Nonnull String referenceName,
		@Nonnull EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour,
		@Nonnull HierarchyRequireConstraint... requirement
	) {
		super(new Serializable[]{referenceName, emptyHierarchicalEntityBehaviour}, requirement);
	}

	public HierarchyOfReference(
		@Nonnull String[] referenceNames,
		@Nonnull EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour,
		@Nonnull HierarchyRequireConstraint... requirements) {
		super(
			ArrayUtils.mergeArrays(
				Arrays.stream(referenceNames)
					.map(Serializable.class::cast)
					.toArray(Serializable[]::new),
				new Serializable[] {emptyHierarchicalEntityBehaviour}
			),
			requirements
		);
	}

	@Creator
	public HierarchyOfReference(
		@Nonnull @Classifier String referenceName,
		@Nullable EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour,
		@Nullable @AdditionalChild(domain = ConstraintDomain.ENTITY) OrderBy orderBy,
		@Nonnull HierarchyRequireConstraint... requirements
	) {
		super(
			new Serializable[]{
				referenceName,
				ofNullable(emptyHierarchicalEntityBehaviour).orElse(EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY)
			},
			requirements,
			orderBy
		);
	}

	public HierarchyOfReference(
		@Nonnull String[] referenceNames,
		@Nonnull EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour,
		@Nonnull OrderBy orderBy,
		@Nonnull HierarchyRequireConstraint... requirements) {
		super(
			ArrayUtils.mergeArrays(
				Arrays.stream(referenceNames)
					.map(Serializable.class::cast)
					.toArray(Serializable[]::new),
				new Serializable[] {emptyHierarchicalEntityBehaviour}
			),
			requirements,
			orderBy
		);
	}

	/**
	 * Returns name of the reference this hierarchy query relates to. If there are multiple references,
	 * the {@link #getReferenceNames()} must be used.
	 */
	@Nonnull
	public String getReferenceName() {
		final String[] referenceNames = getReferenceNames();
		Assert.isTrue(referenceNames.length == 1, "There are multiple reference names, cannot get only one.");
		return referenceNames[0];
	}

	/**
	 * Returns all names of the references this hierarchy query relates to.
	 */
	@Nonnull
	public String[] getReferenceNames() {
		return Arrays.stream(getArguments())
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.toArray(String[]::new);
	}

	/**
	 * Returns the requested behaviour for hierarchy nodes that contain no single queried entity.
	 */
	@Nonnull
	public EmptyHierarchicalEntityBehaviour getEmptyHierarchicalEntityBehaviour() {
		return Arrays.stream(getArguments())
			.filter(EmptyHierarchicalEntityBehaviour.class::isInstance)
			.map(EmptyHierarchicalEntityBehaviour.class::cast)
			.findFirst()
			.orElseThrow(() -> new GenericEvitaInternalError("EmptyHierarchicalEntityBehaviour is a mandatory argument!"));
	}

	/**
	 * Returns requirement constraints for the loaded entities.
	 */
	@Nonnull
	public HierarchyRequireConstraint[] getRequirements() {
		return Arrays.stream(getChildren())
			.map(HierarchyRequireConstraint.class::cast)
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
		return getArguments().length > 0 && getChildrenCount() > 0;
	}

	@Nonnull
	@Override
	public Serializable[] getArgumentsExcludingDefaults() {
		return Arrays.stream(getArguments())
			.filter(it -> it != EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY)
			.toArray(Serializable[]::new);
	}

	@Override
	public boolean isArgumentImplicit(@Nonnull Serializable serializable) {
		return serializable == EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		return new HierarchyOfReference(getArguments(), children, additionalChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new HierarchyOfReference(
			Arrays.stream(newArguments)
				.map(String.class::cast)
				.toArray(String[]::new),
			getChildren(),
			getAdditionalChildren()
		);
	}

}
