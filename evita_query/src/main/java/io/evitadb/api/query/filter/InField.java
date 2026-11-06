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
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `inField` constraint restricts the enclosing {@link TextMatches} fulltext search to a single searchable field.
 * It can only be used as a child of `textMatches`; several `inField` children widen the search to all the named
 * fields. A `textMatches` without any `inField` child searches every searchable field of the queried collection.
 *
 * **Syntax**
 *
 * ```evitaql
 * inField(
 *     argument:string!
 * )
 * ```
 *
 * **Arguments**
 *
 * - `argument:string!`: the name of the searchable field, mandatory and non-blank
 *
 * **Example**
 *
 * ```evitaql
 * textMatches(
 *     "black leather jacket",
 *     inField("name"),
 *     inField("description")
 * )
 * ```
 *
 * The constraint has no `attribute` prefix, because a searchable field is not necessarily an entity attribute — the
 * fulltext index also covers reference attributes and associated data, which a `textMatches` without `inField`
 * searches. `inField` itself names an entity or global attribute of the queried collection.
 *
 * Naming the same field twice is accepted and has the same effect as naming it once.
 *
 * @see TextMatches
 * @see TextMatchesSpecificationFilterConstraint
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ConstraintDefinition(
	name = "inField",
	shortDescription = "The constraint restricts the enclosing `textMatches` fulltext search to a single " +
		"searchable field.",
	userDocsLink = "/documentation/query/filtering/fulltext#in-field",
	// the ENTITY domain would also offer `inField` inside every logical container of the external APIs, so it needs
	// a domain only `textMatches` switches into before the constraint is registered
	//TODO JNO change it at the end of #258
	supportedIn = ConstraintDomain.ENTITY
)
public class InField extends AbstractFilterConstraintLeaf
	implements GenericConstraint<FilterConstraint>, TextMatchesSpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = 4215369447123180911L;
	private static final String CONSTRAINT_NAME = "inField";

	private InField(@Nonnull Serializable... arguments) {
		super(CONSTRAINT_NAME, arguments);
	}

	/**
	 * Creates the constraint restricting the fulltext search to the field of the given name.
	 *
	 * @param fieldName name of the searchable field, must not be blank
	 */
	@Creator
	public InField(@Nonnull String fieldName) {
		// the name must be passed explicitly, the single-string constructor of the parent would take the field name
		// for the constraint name
		super(CONSTRAINT_NAME, fieldName);
		Assert.isTrue(
			fieldName != null && !fieldName.isBlank(),
			"Field name of the `inField` constraint must not be blank!"
		);
	}

	/**
	 * Returns the name of the searchable field the fulltext search is restricted to.
	 */
	@Nonnull
	public String getFieldName() {
		return (String) getArguments()[0];
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 1;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length == 1 && newArguments[0] instanceof String,
			"Constraint `inField` requires exactly one argument of type String!"
		);
		return new InField(newArguments);
	}

}
