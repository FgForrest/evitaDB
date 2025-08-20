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
import io.evitadb.api.query.descriptor.annotation.AdditionalChild;
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
 * The requirement triggers the calculation of the Hierarchy data structure for the hierarchy of which it is a part.
 *
 * The hierarchy of self can still be combined with {@link HierarchyOfReference} if the queried entity is a hierarchical
 * entity that is also connected to another hierarchical entity. Such situations are rather sporadic in reality.
 *
 * The constraint accepts following arguments:
 *
 * - specification of one or more reference names that identify the reference to the target hierarchical entity for
 *   which the menu calculation should be performed; usually only one reference name makes sense, but to adapt
 *   the constraint to the behavior of other similar constraints, evitaQL accepts multiple reference names for the case
 *   that the same requirements apply to different references of the queried entity.
 * - optional argument of type EmptyHierarchicalEntityBehaviour enum allowing you to specify whether or not to return
 *   empty hierarchical entities (e.g., those that do not have any queried entities that satisfy the current query
 *   filter constraint assigned to them - either directly or transitively):
 *
 *      - {@link EmptyHierarchicalEntityBehaviour#LEAVE_EMPTY}: empty hierarchical nodes will remain in computed data
 *        structures
 *      - {@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY}: empty hierarchical nodes are omitted from computed data
 *        structures
 *
 * - optional ordering constraint that allows you to specify an order of Hierarchy LevelInfo elements in the result
 *   hierarchy data structure
 * - mandatory one or more constraints allowing you to instruct evitaDB to calculate menu components; one or all of
 *   the constraints may be present:
 *
 *      - {@link HierarchyFromRoot}
 *      - {@link HierarchyFromNode}
 *      - {@link HierarchySiblings}
 *      - {@link HierarchyChildren}
 *      - {@link HierarchyParents}
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-self">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "ofSelf",
	shortDescription = "The constraint triggers computation of hierarchy statistics (how many matching children the hierarchy nodes have) of same hierarchical collection into response.",
	userDocsLink = "/documentation/query/requirements/hierarchy#hierarchy-of-self"
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
		@Nonnull HierarchyRequireConstraint... requirements
	) {
		super(new Serializable[0], requirements, orderBy);
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
