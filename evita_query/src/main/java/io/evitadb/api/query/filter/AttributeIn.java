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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.ConstraintWithSuffix;
import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;

/**
 * The `inAttribute` constraint restricts the enclosing {@link TextMatches} fulltext search to one or more
 * searchable attributes of its level: of the queried collection directly under `textMatches`, of the reference
 * under `inReference` and inside `referenceHaving`, and of the target collection under `inEntity`. Several
 * selectors search the union of their named attributes, with the same matches and scores as a single selector
 * naming them all. Repeated names are ignored when searching and scoring. Without a selector, `textMatches`
 * searches every searchable place, including reference attributes, associated data and text reached through
 * composition. Outside `inEntity`, an attribute selector selects no composed places.
 * The wildcard `inAttributeAll()` selects every searchable attribute of the current level; `inAttribute()`
 * without names is refused.
 * The search behaves as if only selected places were searchable, so it can match entities absent from plain search.
 *
 * Searchable attribute names are application settings, so neither the constructors nor the factory ever omit the
 * selector: a null or empty array, and a null or blank name, are refused. Omitting the selector would widen the
 * search to every searchable place.
 *
 * **Syntax**
 *
 * ```evitaql
 * inAttribute('name', 'description')
 * ```
 *
 * **Arguments**
 *
 * - one or more searchable attribute names, each mandatory and non-blank
 *
 * **Example**
 *
 * ```evitaql
 * textMatches('black leather jacket', inAttribute('name', 'description'))
 * ```
 *
 * @see TextMatches
 * @see TextMatchesSpecificationFilterConstraint
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ConstraintDefinition(
	name = "in",
	shortDescription = "The constraint restricts the enclosing `textMatches` fulltext search to one or more " +
		"searchable attributes.",
	userDocsLink = "/documentation/query/filtering/fulltext#in-attribute",
	supportedIn = ConstraintDomain.TEXT,
	supportedValues = @ConstraintSupportedValues(supportedTypes = String.class, arraysSupported = true)
)
public class AttributeIn extends AbstractFilterConstraintLeaf
	implements AttributeConstraint<FilterConstraint>, ConstraintWithSuffix,
	TextMatchesEntitySpecificationFilterConstraint, TextMatchesReferenceSpecificationFilterConstraint,
	TextMatchesTargetSpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = 4215369447123180911L;
	private static final String CONSTRAINT_NAME = "inAttribute";

	/**
	 * Creates a copy with validated serialized arguments.
	 *
	 * @param arguments searchable attribute names, empty for the wildcard, otherwise non-blank strings
	 */
	private AttributeIn(@Nonnull Serializable[] arguments) {
		super(CONSTRAINT_NAME, arguments != null && arguments.length == 0 ? arguments : validateNames(arguments));
	}

	/**
	 * Creates a selector for one searchable attribute, exposed by the constraint descriptor.
	 *
	 * @param attributeName searchable attribute name, must not be null or blank
	 */
	@Creator
	public AttributeIn(@Nonnull @Classifier String attributeName) {
		this(new String[]{attributeName});
	}

	/**
	 * Creates a selector for the union of the named searchable attributes.
	 *
	 * @param attributeNames searchable attribute names, at least one and none null or blank
	 */
	public AttributeIn(@Nonnull String... attributeNames) {
		super(CONSTRAINT_NAME, validateNames(attributeNames));
	}

	/**
	 * Returns the searchable attribute names in the order supplied, including any repeated names.
	 *
	 * @return the searchable attribute names, empty for the wildcard `inAttributeAll()`
	 */
	@Nonnull
	@Override
	public String[] getAttributeNames() {
		final Serializable[] arguments = getArguments();
		final String[] names = new String[arguments.length];
		for (int i = 0; i < arguments.length; i++) {
			names[i] = (String) arguments[i];
		}
		return names;
	}

	/**
	 * Creates the wildcard form selecting every searchable attribute of the current level.
	 *
	 * @return the wildcard attribute selector
	 */
	@Nonnull
	@Creator(suffix = "all")
	public static AttributeIn all() {
		return new AttributeIn(new Serializable[0]);
	}

	/**
	 * Returns whether every searchable attribute of this level is selected.
	 *
	 * @return true for `inAttributeAll()`
	 */
	public boolean isAllRequested() {
		return getArguments().length == 0;
	}

	@Nonnull
	@Override
	public Optional<String> getSuffixIfApplied() {
		return isAllRequested() ? Optional.of("all") : Optional.empty();
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		if (newArguments != null && newArguments.length == 0 && !isAllRequested()) {
			validateNames(newArguments);
		}
		return new AttributeIn(newArguments);
	}

	/**
	 * Validates names as the argument of the parent constructor call - the earliest point Java allows - so that
	 * neither a constructor nor cloning can create a selector without a usable name.
	 *
	 * @param arguments searchable attribute names supplied by a constructor or cloning
	 * @return the validated arguments
	 */
	@Nonnull
	private static Serializable[] validateNames(@Nonnull Serializable[] arguments) {
		final String message = "Constraint `inAttribute` requires at least one searchable attribute name; " +
			"every name must be a non-blank String!";
		Assert.isTrue(arguments != null && arguments.length > 0, message);
		for (final Serializable argument : arguments) {
			Assert.isTrue(argument instanceof String name && !name.isBlank(), message);
		}
		return arguments;
	}

}
