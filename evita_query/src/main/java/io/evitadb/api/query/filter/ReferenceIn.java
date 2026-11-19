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
import io.evitadb.api.query.ReferenceConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * Selects searchable attributes of a named reference and, through `inEntity`, its composed target places.
 * The search behaves as if only selected places were searchable, so it can match entities absent from plain search.
 *
 * Without children, selects all searchable attributes of this reference. `inAttribute` children select reference
 * attributes; `inEntity` children select target places only and require the reference to declare
 * `searchableThrough(COMPOSITION)` in every processed scope. Repeated references merge their selected places.
 *
 * Empty and single-child forms remain necessary during query normalization: removing this container would change
 * the selected level.
 *
 * @see TextMatches
 * @see EntityIn
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ConstraintDefinition(
	name = "in",
	shortDescription = "Selects searchable reference attributes and composed target places.",
	userDocsLink = "/documentation/query/filtering/fulltext#in-reference",
	supportedIn = ConstraintDomain.TEXT
)
public class ReferenceIn extends AbstractFilterConstraintContainer
	implements ReferenceConstraint<FilterConstraint>, TextMatchesEntitySpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = 4215369447123180913L;
	private static final String CONSTRAINT_NAME = "inReference";

	/**
	 * Creates a selector retaining its level even when no children are supplied.
	 *
	 * @param referenceName reference whose places should be searched, mandatory and non-blank
	 * @param in optional selectors restricting the places of this level
	 */
	@Creator
	public ReferenceIn(
		@Nonnull @Classifier String referenceName,
		@Nullable @Child(domain = ConstraintDomain.TEXT, uniqueChildren = true)
		TextMatchesReferenceSpecificationFilterConstraint... in
	) {
		this(new Serializable[]{referenceName}, in == null ? new FilterConstraint[0] : in);
	}

	/**
	 * Copies the selector while validating its serialized arguments and level-specific children.
	 *
	 * @param arguments the mandatory reference name
	 * @param in selectors of this level
	 */
	private ReferenceIn(@Nonnull Serializable[] arguments, @Nonnull FilterConstraint[] in) {
		super(CONSTRAINT_NAME, arguments, in);
		Assert.isTrue(
			arguments.length == 1 && arguments[0] instanceof String name && !name.isBlank(),
			"Constraint `inReference` requires one non-blank reference name!"
		);
		for (final FilterConstraint child : getChildren()) {
			Assert.isTrue(child instanceof TextMatchesReferenceSpecificationFilterConstraint,
				() -> "Constraint `inReference` does not accept `" + child.getName() + "` as its child!");
		}
	}

	/**
	 * Returns the reference whose attributes or target places are selected.
	 *
	 * @return the reference name
	 */
	@Nonnull
	public String getReferenceName() {
		return (String) getArguments()[0];
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
		return new ReferenceIn(newArguments, getChildren());
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(
		@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isTrue(ArrayUtils.isEmpty(additionalChildren),
			"Constraint `inReference` accepts only place selectors!");
		return new ReferenceIn(getArguments(), children);
	}
}
