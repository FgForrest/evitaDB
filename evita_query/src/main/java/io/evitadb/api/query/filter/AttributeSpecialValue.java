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

import io.evitadb.dataType.SupportedEnum;

/**
 * Enumeration of special attribute states used by {@link AttributeIs} to test for attribute presence or absence
 * without comparing actual values. These constants represent nullability conditions that cannot be expressed through
 * standard {@link Comparable} operations, enabling queries to distinguish between entities with and without specific
 * attributes.
 *
 * This enum is marked as {@link SupportedEnum}, making it available for use in generated API
 * schemas (GraphQL, REST, gRPC) and serializable in query transmission protocols.
 *
 * **Usage context:**
 *
 * These values are exclusively used as the second argument to {@link AttributeIs} constraints:
 *
 * ```
 * attributeIs("attributeName", NULL)       // Matches entities without the attribute
 * attributeIs("attributeName", NOT_NULL)   // Matches entities with the attribute set
 * ```
 *
 * **Nullability semantics:**
 *
 * - An attribute is considered NULL if it was never set on the entity (implicitly null) or explicitly set to null
 * - An attribute is considered NOT_NULL if it has any assigned value, including empty strings, zero or false
 * - For array-typed attributes, the test applies to the individual elements
 *
 * @see AttributeIs
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@SupportedEnum
public enum AttributeSpecialValue {

	/**
	 * Matches entities where the named attribute is absent (never set) or explicitly set to null. Both implicit and
	 * explicit null states are treated identically. For array-typed attributes, this matches when the array itself is
	 * missing, not when the array is empty.
	 */
	NULL,

	/**
	 * Matches entities where the named attribute has any non-null value assigned. This includes all non-null values
	 * such as empty strings (""), zero (0), false, empty arrays ([]), and any other explicitly-set values. For
	 * array-typed attributes, this matches when the array exists, even if it contains no elements.
	 */
	NOT_NULL

}
