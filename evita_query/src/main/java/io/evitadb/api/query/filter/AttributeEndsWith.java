/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This `endsWith` is query that searches value of the attribute with name passed in first argument for presence of the
 * {@link String} value passed in the second argument.
 *
 * Function returns true if attribute value contains secondary argument (using reverse lookup from last position).
 * InSet other words attribute value ends with string passed in second argument. Function is case sensitive and comparison
 * is executed using `UTF-8` encoding (Java native).
 *
 * Example:
 *
 * <pre>
 * endsWith("code", "ida")
 * </pre>
 *
 * Function supports attribute arrays and when attribute is of array type `endsWith` returns true if any of attribute
 * values ends with the value in the query. If we have the attribute `code` with value `["cat","mouse","dog"]` all these
 * constraints will match:
 *
 * <pre>
 * contains("code","at")
 * contains("code","og")
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/string#attribute-ends-with">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "endsWith",
	shortDescription = "Compares value of the attribute with passed value and checks if the text value of that attribute ends with passed text (case-sensitive).",
	userDocsLink = "/documentation/query/filtering/string#attribute-ends-with",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE },
	supportedValues = @ConstraintSupportedValues(supportedTypes = String.class, arraysSupported = true)
)
public class AttributeEndsWith extends AbstractAttributeFilterStringSearchConstraintLeaf {
	@Serial private static final long serialVersionUID = -8551542903236177197L;

	private AttributeEndsWith(Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public AttributeEndsWith(@Nonnull @Classifier String attributeName,
	                         @Nonnull String textToSearch) {
		super(attributeName, textToSearch);
	}

	@Override
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
		return new AttributeEndsWith(newArguments);
	}
}
