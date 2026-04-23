/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Abstract base class for attribute filter constraints that perform string pattern matching operations. This class
 * specializes {@link AbstractAttributeFilterConstraintLeaf} to provide uniform access to the search text parameter
 * through the abstract {@link #getTextToSearch()} method.
 *
 * **Design Purpose**
 *
 * This class represents attribute filter constraints that search for text patterns within string-valued attributes
 * using substring matching, prefix matching, or suffix matching. It provides a common abstraction for all
 * pattern-based string search operations where the search is performed for a text fragment within attribute values.
 *
 * **Concrete Implementations**
 *
 * The string search constraint hierarchy includes:
 * - {@link AttributeContains} — substring search (`attribute CONTAINS text`)
 * - {@link AttributeStartsWith} — prefix match (`attribute STARTS WITH text`)
 * - {@link AttributeEndsWith} — suffix match (`attribute ENDS WITH text`)
 *
 * **Constructor Pattern**
 *
 * Concrete subclasses accept an attribute name and search text, storing both as arguments:
 *
 * ```java
 * public AttributeContains(String attributeName, String textToSearch) {
 *     super(attributeName, textToSearch);
 * }
 *
 * @Override
 * public String getTextToSearch() {
 *     return (String) getArguments()[1]; // text is at index 1 (attributeName is at index 0)
 * }
 * ```
 *
 * **Case Sensitivity and Encoding**
 *
 * All string search constraints are **case-sensitive** and perform comparisons using UTF-8 encoding (Java's native
 * string encoding). For case-insensitive searches, convert both attribute values and search text to the same case
 * before storage or use locale-aware collation features if available.
 *
 * **Array Attribute Support**
 *
 * When an attribute is array-valued, string search constraints return `true` if **any** array element matches the
 * pattern:
 *
 * ```java
 * // Entity has attribute "tags" with value ["smartphone", "android", "5G"]
 * contains("tags", "phone")    // true ("smartphone" contains "phone")
 * startsWith("tags", "smart")  // true ("smartphone" starts with "smart")
 * endsWith("tags", "5G")       // true ("5G" ends with "5G")
 * endsWith("tags", "ios")      // false (no element ends with "ios")
 * ```
 *
 * **Type Requirements**
 *
 * String search constraints require the attribute to be of type `String` (or `String[]` for array attributes).
 * Attempting to apply string search constraints to non-string attributes (numbers, dates, booleans) will return
 * `false` rather than throwing an exception, following the principle of graceful degradation.
 *
 * **Performance Considerations**
 *
 * String search constraints (especially `contains`) may have performance implications on large datasets as they
 * typically require full attribute value scanning rather than index lookups. For high-performance text search:
 * - Use prefix matching (`startsWith`) when possible — it can leverage sorted indexes
 * - Consider dedicated full-text search indexes for complex text queries
 * - Limit the result set with other constraints before applying string searches
 *
 * **Usage Examples**
 *
 * Typical usage in filter expressions:
 * ```java
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             contains("name", "Samsung"),          // find products with "Samsung" in name
 *             startsWith("code", "MOB-"),           // products with codes starting with "MOB-"
 *             endsWith("description", "warranty")   // products with descriptions ending in "warranty"
 *         )
 *     )
 * );
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 * @see AbstractAttributeFilterConstraintLeaf
 * @see AttributeContains
 * @see AttributeStartsWith
 * @see AttributeEndsWith
 */
public abstract class AbstractAttributeFilterStringSearchConstraintLeaf
	extends AbstractAttributeFilterConstraintLeaf {
	@Serial private static final long serialVersionUID = 219317868969717309L;

	protected AbstractAttributeFilterStringSearchConstraintLeaf(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	/**
	 * Returns the text to search for within the attribute value.
	 */
	@Nonnull
	public abstract String getTextToSearch();

}
