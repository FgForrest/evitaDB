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
 * The requirement triggers the calculation of the Hierarchy data structure for the hierarchies of the referenced entity
 * type.
 *
 * The hierarchy of reference can still be combined with {@link HierarchyOfSelf} if the queried entity is a hierarchical
 * entity that is also connected to another hierarchical entity. Such situations are rather sporadic in reality.
 *
 * The `hierarchyOfReference` can be repeated multiple times in a single query if you need different calculation
 * settings for different reference types.
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
 *        structures (default behavior)
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
 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "ofReference",
	shortDescription = "The constraint triggers computation of hierarchy statistics (how many matching children the hierarchy nodes have) of referenced hierarchical entities into response.",
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
	 * Returns name of the reference this hierarchy query relates to. If there are multiple references, the {@link #getReferenceNames()}
	 * must be used.
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
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
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
