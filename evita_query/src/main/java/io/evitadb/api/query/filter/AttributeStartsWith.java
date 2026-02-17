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
 * Filters entities by testing whether an attribute value begins with a specified prefix string.
 *
 * This constraint implements prefix matching semantics identical to Java's {@link String#startsWith(String)} method. It performs case-sensitive
 * matching using UTF-8 encoding (Java native string representation) and returns true if the attribute value begins with the search pattern.
 *
 * ## Syntax
 *
 * ```
 * attributeStartsWith(attributeName:string!, searchValue:string!)
 * ```
 *
 * ## Type Requirements
 *
 * The target attribute must be of type {@link String} and must be defined as either filterable or unique in the entity schema. Non-string
 * attributes or attributes lacking the required filterable/unique definition will cause query execution to fail.
 *
 * ## Matching Behavior
 *
 * - **Case-sensitive**: "abc" matches "abcdef" but not "ABCDEF" or "Abcdef"
 * - **UTF-8 encoding**: Full Unicode character support including multi-byte characters
 * - **Prefix-only**: Matches only if the search pattern appears at the beginning of the attribute value (index 0)
 * - **Exact prefix**: No wildcard expansion — the search pattern is matched literally
 *
 * ## Array Support
 *
 * When the attribute is an array type, the constraint returns true if ANY element in the array starts with the search pattern. This uses
 * existential quantification semantics: at least one array element must match.
 *
 * For example, given an attribute `identifiers` with value `["SKU-1234", "EAN-5678", "ISBN-9012"]`:
 *
 * ```
 * attributeStartsWith("identifiers", "SKU")   // matches (found "SKU-1234")
 * attributeStartsWith("identifiers", "EAN-5") // matches (found "EAN-5678")
 * attributeStartsWith("identifiers", "1234")  // does not match (no element starts with "1234")
 * ```
 *
 * ## Usage Patterns
 *
 * Common use cases include:
 * - **Prefix-based codes**: Filter by SKU prefixes, product code families, or identifier schemes
 * - **Hierarchical categories**: Match category paths that start with specific segments
 * - **URL filtering**: Find entities with URLs beginning with specific domains or paths
 * - **Auto-complete**: Support type-ahead search functionality where users type the beginning of a value
 *
 * Example query filtering products whose SKU starts with "ELEC":
 *
 * ```
 * query(
 *     collection("Product"),
 *     filterBy(
 *         attributeStartsWith("sku", "ELEC")
 *     )
 * )
 * ```
 *
 * ## Related Constraints
 *
 * - {@link AttributeContains}: Match substring at any position (less efficient for prefix matching)
 * - {@link AttributeEndsWith}: Match suffix patterns
 * - {@link AttributeEquals}: Exact string matching (most efficient for equality checks)
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/string#attribute-starts-with)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "startsWith",
	shortDescription = "Compares value of the attribute with passed value and checks if the text value of that attribute starts with passed text (case-sensitive).",
	userDocsLink = "/documentation/query/filtering/string#attribute-starts-with",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE },
	supportedValues = @ConstraintSupportedValues(
		supportedTypes = String.class,
		arraysSupported = true
	)
)
public class AttributeStartsWith extends AbstractAttributeFilterStringSearchConstraintLeaf implements FilterConstraint {
	@Serial private static final long serialVersionUID = 5516189083269213655L;

	private AttributeStartsWith(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public AttributeStartsWith(@Nonnull @Classifier String attributeName,
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
		return new AttributeStartsWith(newArguments);
	}
}
