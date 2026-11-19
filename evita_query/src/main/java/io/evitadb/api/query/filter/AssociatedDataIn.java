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
import io.evitadb.api.query.AssociatedDataConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;

/**
 * The `inAssociatedData` constraint restricts the enclosing {@link TextMatches} search to one or more searchable
 * associated data items of its level: of the queried collection directly under `textMatches`, and of the target
 * collection under `inEntity`. Several selectors search their union; repeated names contribute only once to
 * matching and scoring. The wildcard `inAssociatedDataAll()` selects every searchable associated data item of
 * this level. The named `inAssociatedData()` form without names is refused.
 * The search behaves as if only selected places were searchable, so it can match entities absent from plain search.
 *
 * Without selectors, `textMatches` searches every searchable place, including attributes, reference attributes,
 * associated data and declared composition. Outside `inEntity`, this selector selects no composed places.
 *
 * Searchable associated data names are application settings, so neither the constructors nor the factory ever omit the
 * selector: a null or empty array, and a null or blank name, are refused. Omitting the selector would widen the
 * search to every searchable place.
 *
 * **Syntax**
 *
 * ```evitaql
 * inAssociatedData('name', 'description')
 * ```
 *
 * **Arguments**
 *
 * - one or more searchable associated data names, each mandatory and non-blank
 *
 * **Example**
 *
 * ```evitaql
 * textMatches('black leather jacket', inAssociatedData('name', 'description'))
 * ```
 *
 * Associated data selectors are valid neither directly under `inReference` nor inside `referenceHaving`.
 *
 * @see TextMatches
 * @see TextMatchesSpecificationFilterConstraint
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ConstraintDefinition(
	name = "in",
	shortDescription = "The constraint restricts the enclosing `textMatches` fulltext search to one or more " +
		"searchable associated data.",
	userDocsLink = "/documentation/query/filtering/fulltext#in-associated-data",
	supportedIn = ConstraintDomain.TEXT,
	supportedValues = @ConstraintSupportedValues(
		supportedTypes = { String.class, ComplexDataObject.class }, arraysSupported = true
	)
)
public class AssociatedDataIn extends AbstractFilterConstraintLeaf
	implements AssociatedDataConstraint<FilterConstraint>, ConstraintWithSuffix,
	TextMatchesEntitySpecificationFilterConstraint,
	TextMatchesTargetSpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = 4215369447123180912L;
	private static final String CONSTRAINT_NAME = "inAssociatedData";

	/**
	 * Creates a copy with validated serialized arguments.
	 *
	 * @param arguments searchable associated data names, empty for the wildcard, otherwise non-blank strings
	 */
	private AssociatedDataIn(@Nonnull Serializable[] arguments) {
		super(CONSTRAINT_NAME, arguments != null && arguments.length == 0 ? arguments : validateNames(arguments));
	}

	/**
	 * Creates a selector for one searchable associated data item, exposed by the constraint descriptor.
	 *
	 * @param associatedDataName searchable associated data name, must not be null or blank
	 */
	@Creator
	public AssociatedDataIn(@Nonnull @Classifier String associatedDataName) {
		this(new String[]{associatedDataName});
	}

	/**
	 * Creates a selector for the union of the named searchable associated data.
	 *
	 * @param associatedDataNames searchable associated data names, at least one and none null or blank
	 */
	public AssociatedDataIn(@Nonnull String... associatedDataNames) {
		super(CONSTRAINT_NAME, validateNames(associatedDataNames));
	}

	/**
	 * Returns the searchable associated data names in the order supplied, including any repeated names.
	 *
	 * @return the searchable associated data names, empty for the wildcard `inAssociatedDataAll()`
	 */
	@Nonnull
	public String[] getAssociatedDataNames() {
		final Serializable[] arguments = getArguments();
		final String[] names = new String[arguments.length];
		for (int i = 0; i < arguments.length; i++) {
			names[i] = (String) arguments[i];
		}
		return names;
	}

	/**
	 * Creates the wildcard form selecting every searchable associated data item of the current level.
	 *
	 * @return the wildcard associated data selector
	 */
	@Nonnull
	@Creator(suffix = "all")
	public static AssociatedDataIn all() {
		return new AssociatedDataIn(new Serializable[0]);
	}

	/**
	 * Returns whether every searchable associated data item of this level is selected.
	 *
	 * @return true for `inAssociatedDataAll()`
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
		return new AssociatedDataIn(newArguments);
	}

	/**
	 * Validates names as the argument of the parent constructor call - the earliest point Java allows - so that
	 * neither a constructor nor cloning can create a selector without a usable name.
	 *
	 * @param arguments searchable associated data names supplied by a constructor or cloning
	 * @return the validated arguments
	 */
	@Nonnull
	private static Serializable[] validateNames(@Nonnull Serializable[] arguments) {
		final String message = "Constraint `inAssociatedData` requires at least one searchable associated data name; " +
			"every name must be a non-blank String!";
		Assert.isTrue(arguments != null && arguments.length > 0, message);
		for (final Serializable argument : arguments) {
			Assert.isTrue(argument instanceof String name && !name.isBlank(), message);
		}
		return arguments;
	}

}
