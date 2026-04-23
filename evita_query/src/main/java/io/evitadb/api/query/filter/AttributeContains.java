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
 * Filters entities by testing whether an attribute value contains a specified substring at any position.
 *
 * This constraint implements substring search semantics identical to Java's {@link String#contains(CharSequence)} method. It performs
 * case-sensitive matching using UTF-8 encoding (Java native string representation) and returns true if the search pattern appears anywhere
 * within the attribute value.
 *
 * ## Syntax
 *
 * ```
 * attributeContains(attributeName:string!, searchValue:string!)
 * ```
 *
 * ## Type Requirements
 *
 * The target attribute must be of type {@link String} and must be defined as either filterable or unique in the entity schema. Non-string
 * attributes or attributes lacking the required filterable/unique definition will cause query execution to fail.
 *
 * ## Matching Behavior
 *
 * - **Case-sensitive**: "abc" matches "abc" but not "ABC" or "Abc"
 * - **UTF-8 encoding**: Full Unicode character support including multi-byte characters
 * - **Position-independent**: Matches if the search pattern appears anywhere within the attribute value
 * - **Exact substring**: No wildcard expansion — the search pattern is matched literally
 *
 * ## Array Support
 *
 * When the attribute is an array type, the constraint returns true if ANY element in the array contains the search pattern. This uses
 * existential quantification semantics: at least one array element must match.
 *
 * For example, given an attribute `tags` with value `["smartphone", "electronics", "android"]`:
 *
 * ```
 * attributeContains("tags", "phone")  // matches (found in "smartphone")
 * attributeContains("tags", "smart")  // matches (found in "smartphone")
 * attributeContains("tags", "tron")   // matches (found in "electronics")
 * attributeContains("tags", "ios")    // does not match
 * ```
 *
 * ## Usage Patterns
 *
 * Common use cases include:
 * - **Product search**: Find products whose descriptions contain keywords
 * - **SKU filtering**: Match partial SKU codes or identifiers
 * - **Category matching**: Search for products in categories whose names contain specific terms
 * - **Tag-based filtering**: Find entities with tags containing search phrases
 *
 * Example query filtering products whose description contains "wireless":
 *
 * ```
 * query(
 *     collection("Product"),
 *     filterBy(
 *         attributeContains("description", "wireless")
 *     )
 * )
 * ```
 *
 * ## Related Constraints
 *
 * - {@link AttributeStartsWith}: Match prefix patterns (more efficient for prefix matching)
 * - {@link AttributeEndsWith}: Match suffix patterns
 * - {@link AttributeEquals}: Exact string matching (most efficient for equality checks)
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/string#attribute-contains)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "contains",
	shortDescription = "Compares value of the attribute with passed value and checks if the text value of that attribute contains part of passed text (case-sensitive).",
	userDocsLink = "/documentation/query/filtering/string#attribute-contains",
	supportedIn = {ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE},
	supportedValues = @ConstraintSupportedValues(supportedTypes = String.class, arraysSupported = true)
)
public class AttributeContains extends AbstractAttributeFilterStringSearchConstraintLeaf {
	@Serial private static final long serialVersionUID = 5307621598413172503L;

	private AttributeContains(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public AttributeContains(@Nonnull @Classifier String attributeName,
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
		return new AttributeContains(newArguments);
	}

}
