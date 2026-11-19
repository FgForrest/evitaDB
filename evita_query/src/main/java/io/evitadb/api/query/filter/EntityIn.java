/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.EntityConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * Selects searchable places of the target collection of the enclosing `inReference`.
 * The search behaves as if only selected places were searchable, so it can match entities absent from plain search.
 *
 * Without children, selects exactly the places plain composition searches: target attributes, associated data
 * and target reference attributes. Target composition declarations are never followed. Explicit children may
 * select target attributes and associated data. A target scope in which nothing is selected is skipped, as plain
 * composition skips it. The enclosing reference must declare `searchableThrough(COMPOSITION)` in every processed
 * scope. Target weights retain their ordinary composition multiplier.
 *
 * Empty and single-child forms remain necessary during query normalization: removing this container would change
 * the selected level.
 *
 * @see ReferenceIn
 * @see TextMatches
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ConstraintDefinition(
	name = "in",
	shortDescription = "Selects searchable target places of the enclosing reference.",
	userDocsLink = "/documentation/query/filtering/fulltext#in-entity",
	supportedIn = ConstraintDomain.TEXT
)
public class EntityIn extends AbstractFilterConstraintContainer
	implements EntityConstraint<FilterConstraint>, TextMatchesReferenceSpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = 4215369447123180914L;
	private static final String CONSTRAINT_NAME = "inEntity";

	/**
	 * Creates a selector retaining its level even when no children are supplied.
	 *
	 * @param in optional selectors restricting the places of this level
	 */
	@Creator
	public EntityIn(
		@Nullable @Child(domain = ConstraintDomain.TEXT, uniqueChildren = true)
		TextMatchesTargetSpecificationFilterConstraint... in
	) {
		this(NO_ARGS, in == null ? new FilterConstraint[0] : in);
	}

	/**
	 * Copies the selector while validating its serialized arguments and level-specific children.
	 *
	 * @param arguments empty because this level has no arguments
	 * @param in selectors of this level
	 */
	private EntityIn(@Nonnull Serializable[] arguments, @Nonnull FilterConstraint[] in) {
		super(CONSTRAINT_NAME, arguments, in);
		Assert.isTrue(
			arguments.length == 0,
			"Constraint `inEntity` accepts no arguments!"
		);
		for (final FilterConstraint child : getChildren()) {
			Assert.isTrue(child instanceof TextMatchesTargetSpecificationFilterConstraint,
				() -> "Constraint `inEntity` does not accept `" + child.getName() + "` as its child!");
		}
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Override
	public boolean isNecessary() {
		return true;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new EntityIn(newArguments, getChildren());
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(
		@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isTrue(ArrayUtils.isEmpty(additionalChildren),
			"Constraint `inEntity` accepts only place selectors!");
		return new EntityIn(getArguments(), children);
	}
}
