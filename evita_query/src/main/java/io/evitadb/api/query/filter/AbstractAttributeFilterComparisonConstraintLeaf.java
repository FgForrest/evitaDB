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
 * Abstract base class for attribute filter constraints that perform value comparison operations. This class
 * specializes {@link AbstractAttributeFilterConstraintLeaf} to provide uniform access to the comparison value
 * parameter through the abstract {@link #getAttributeValue()} method.
 *
 * **Design Purpose**
 *
 * This class represents attribute filter constraints that compare an entity's attribute value against a single
 * comparison value using relational or equality operators. It provides a common abstraction for all binary comparison
 * operations (equality, greater than, less than, etc.) where the comparison is performed between an attribute and
 * a scalar value.
 *
 * **Concrete Implementations**
 *
 * The comparison constraint hierarchy includes:
 * - {@link AttributeEquals} — equality check (`attribute == value`)
 * - {@link AttributeGreaterThan} — greater than comparison (`attribute > value`)
 * - {@link AttributeGreaterThanEquals} — greater than or equal comparison (`attribute >= value`)
 * - {@link AttributeLessThan} — less than comparison (`attribute < value`)
 * - {@link AttributeLessThanEquals} — less than or equal comparison (`attribute <= value`)
 *
 * **Constructor Pattern**
 *
 * Concrete subclasses accept an attribute name and a comparison value, storing both as arguments:
 *
 * ```java
 * public AttributeEquals(String attributeName, Serializable value) {
 *     super(attributeName, value);
 * }
 *
 * @Override
 * public <T extends Serializable> T getAttributeValue() {
 *     return (T) getArguments()[1]; // value is at index 1 (attributeName is at index 0)
 * }
 * ```
 *
 * **Type Compatibility**
 *
 * evitaDB performs automatic type conversion between attribute values and comparison values when types differ but are
 * compatible (e.g., comparing an `Integer` attribute against a `Long` value). If types are incompatible, the
 * constraint returns `false` rather than throwing an exception, following the principle of graceful degradation.
 *
 * **Array Attribute Support**
 *
 * When an attribute is array-valued, comparison constraints return `true` if **any** array element matches the
 * comparison condition:
 *
 * ```java
 * // Entity has attribute "prices" with value [100, 200, 300]
 * equals("prices", 100)        // true (contains 100)
 * greaterThan("prices", 250)   // true (300 > 250)
 * lessThan("prices", 50)       // false (no element < 50)
 * ```
 *
 * **Null Handling**
 *
 * Comparison constraints typically do not match null attribute values. Use {@link AttributeIs} with
 * {@link io.evitadb.api.query.filter.AttributeSpecialValue#NULL} to filter for null attributes explicitly.
 *
 * **Usage Examples**
 *
 * Typical usage in filter expressions:
 * ```java
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             equals("status", "ACTIVE"),
 *             greaterThanEquals("price", 50.00),
 *             lessThan("stock", 10)
 *         )
 *     )
 * );
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 * @see AbstractAttributeFilterConstraintLeaf
 * @see AttributeEquals
 * @see AttributeGreaterThan
 * @see AttributeLessThan
 */
public abstract class AbstractAttributeFilterComparisonConstraintLeaf
	extends AbstractAttributeFilterConstraintLeaf {
	@Serial private static final long serialVersionUID = 2078148475923740022L;

	protected AbstractAttributeFilterComparisonConstraintLeaf(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	/**
	 * Returns value which needs to be compared with the attribute value.
	 */
	@Nonnull
	public abstract <T extends Serializable> T getAttributeValue();

}
