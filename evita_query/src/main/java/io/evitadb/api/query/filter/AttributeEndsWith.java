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
 * Filters entities by testing whether an attribute value ends with a specified suffix string.
 *
 * This constraint implements suffix matching semantics identical to Java's {@link String#endsWith(String)} method. It performs case-sensitive
 * matching using UTF-8 encoding (Java native string representation) and returns true if the attribute value ends with the search pattern.
 *
 * ## Syntax
 *
 * ```
 * attributeEndsWith(attributeName:string!, searchValue:string!)
 * ```
 *
 * ## Type Requirements
 *
 * The target attribute must be of type {@link String} and must be defined as either filterable or unique in the entity schema. Non-string
 * attributes or attributes lacking the required filterable/unique definition will cause query execution to fail.
 *
 * ## Matching Behavior
 *
 * - **Case-sensitive**: "xyz" matches "abcxyz" but not "abcXYZ" or "abcXyz"
 * - **UTF-8 encoding**: Full Unicode character support including multi-byte characters
 * - **Suffix-only**: Matches only if the search pattern appears at the end of the attribute value
 * - **Exact suffix**: No wildcard expansion — the search pattern is matched literally
 *
 * ## Array Support
 *
 * When the attribute is an array type, the constraint returns true if ANY element in the array ends with the search pattern. This uses
 * existential quantification semantics: at least one array element must match.
 *
 * For example, given an attribute `filenames` with value `["report.pdf", "invoice.pdf", "data.csv"]`:
 *
 * ```
 * attributeEndsWith("filenames", ".pdf")     // matches (found "report.pdf" and "invoice.pdf")
 * attributeEndsWith("filenames", ".csv")     // matches (found "data.csv")
 * attributeEndsWith("filenames", ".xlsx")    // does not match
 * attributeEndsWith("filenames", "invoice")  // does not match (must include ".pdf" suffix)
 * ```
 *
 * ## Usage Patterns
 *
 * Common use cases include:
 * - **File extension filtering**: Match files by extension (e.g., ".pdf", ".jpg", ".xml")
 * - **Domain matching**: Find URLs ending with specific domains or TLDs
 * - **Suffix-based codes**: Filter by identifier suffixes or classification codes
 * - **Unit detection**: Match product codes or SKUs with specific unit suffixes (e.g., "-KG", "-LTR")
 *
 * Example query filtering documents whose filename ends with ".pdf":
 *
 * ```
 * query(
 *     collection("Document"),
 *     filterBy(
 *         attributeEndsWith("filename", ".pdf")
 *     )
 * )
 * ```
 *
 * ## Related Constraints
 *
 * - {@link AttributeContains}: Match substring at any position
 * - {@link AttributeStartsWith}: Match prefix patterns (more efficient with sorted indexes)
 * - {@link AttributeEquals}: Exact string matching (most efficient for equality checks)
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/string#attribute-ends-with)
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

	private AttributeEndsWith(@Nonnull Serializable... arguments) {
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
