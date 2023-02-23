/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.filter;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintClassifierParamDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintCreatorDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.ConstraintValueParamDef;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This `startsWith` is query that searches value of the attribute with name passed in first argument for presence of the
 * {@link String} value passed in the second argument.
 *
 * Function returns true if attribute value contains secondary argument (from first position). InSet other words attribute
 * value starts with string passed in second argument. Function is case sensitive and comparison is executed using `UTF-8`
 * encoding (Java native).
 *
 * Example:
 *
 * ```
 * startsWith('code', 'vid')
 * ```
 *
 * Function supports attribute arrays and when attribute is of array type `startsWith` returns true if *any of attribute* values
 * starts with the value in the query. If we have the attribute `code` with value `['cat','mouse','dog']` all these
 * constraints will match:
 *
 * ```
 * contains('code','mou')
 * contains('code','do')
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDef(
	name = "startsWith",
	shortDescription = "Compares value of the attribute with passed value and checks if the text value of that attributes starts with passed text. (case-sensitive)",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE },
	supportedValues = @ConstraintSupportedValues(
		supportedTypes = String.class,
		arraysSupported = true
	)
)
public class AttributeStartsWith extends AbstractAttributeFilterConstraintLeaf implements IndexUsingConstraint {
	@Serial private static final long serialVersionUID = 5516189083269213655L;

	private AttributeStartsWith(Serializable... arguments) {
		super(arguments);
	}

	@ConstraintCreatorDef
	public AttributeStartsWith(@Nonnull @ConstraintClassifierParamDef String attributeName,
	                           @Nonnull @ConstraintValueParamDef String textToSearch) {
		super(attributeName, textToSearch);
	}

	/**
	 * Returns part of attribute value that needs to be looked up for.
	 */
	@Nonnull
	public String getTextToSearch() {
		return (String) getArguments()[1];
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 2;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new AttributeStartsWith(newArguments);
	}
}
