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

import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.FilterConstraint;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Abstract base class for leaf filter constraints that operate on named entity attributes. This class specializes
 * {@link AbstractFilterConstraintLeaf} to provide uniform handling of the attribute name parameter, which is always
 * the first argument in attribute-based filter constraints.
 *
 * **Design Purpose**
 *
 * This class implements {@link io.evitadb.api.query.AttributeConstraint} and serves as the foundation for all
 * attribute-based filtering operations in evitaDB. Attributes are user-defined key-value properties attached to
 * entities (analogous to columns in relational databases), and this class hierarchy provides type-safe access to
 * attribute filtering capabilities.
 *
 * **Class Hierarchy**
 *
 * The attribute filter constraint hierarchy is further specialized by operation type:
 *
 * - **Comparison constraints** ({@link AbstractAttributeFilterComparisonConstraintLeaf}): Equality and relational
 *   operators
 *   - {@link AttributeEquals} — equality check (`attribute == value`)
 *   - {@link AttributeGreaterThan} — greater than (`attribute > value`)
 *   - {@link AttributeGreaterThanEquals} — greater than or equal (`attribute >= value`)
 *   - {@link AttributeLessThan} — less than (`attribute < value`)
 *   - {@link AttributeLessThanEquals} — less than or equal (`attribute <= value`)
 *
 * - **String search constraints** ({@link AbstractAttributeFilterStringSearchConstraintLeaf}): Pattern matching
 *   - {@link AttributeContains} — substring search (`attribute CONTAINS text`)
 *   - {@link AttributeStartsWith} — prefix match (`attribute STARTS WITH text`)
 *   - {@link AttributeEndsWith} — suffix match (`attribute ENDS WITH text`)
 *
 * - **Range and set constraints** (direct subclasses): Multi-value and interval operations
 *   - {@link AttributeInSet} — set membership (`attribute IN (value1, value2, ...)`)
 *   - {@link AttributeBetween} — closed interval (`value1 <= attribute <= value2`)
 *   - {@link AttributeInRange} — temporal/numeric range inclusion (`attribute IN RANGE`)
 *   - {@link AttributeIs} — special value checks (`attribute IS NULL`, `attribute IS NOT NULL`)
 *
 * **Attribute Name Access**
 *
 * The defining characteristic of this class is the {@link #getAttributeName()} method, which returns the attribute
 * name from the first argument position. This uniform access pattern enables:
 * - Constraint visitors to extract attribute names without type casting
 * - Schema validation to verify attribute existence and type compatibility
 * - Query optimizers to build attribute-specific execution plans
 * - Index selection logic to choose appropriate attribute indexes
 *
 * **Constructor Pattern**
 *
 * Concrete subclasses typically accept the attribute name as the first constructor parameter, followed by
 * operation-specific parameters:
 *
 * ```java
 * public AttributeEquals(String attributeName, Serializable value) {
 *     super(attributeName, value);
 * }
 *
 * public AttributeInSet(String attributeName, Serializable... values) {
 *     super(concat(attributeName, values)); // helper method from AbstractFilterConstraintLeaf
 * }
 * ```
 *
 * **Array Attribute Support**
 *
 * Most attribute filter constraints support array-valued attributes. When an entity attribute is an array, the
 * constraint typically returns `true` if **any** array element matches the filter condition. For example:
 *
 * ```java
 * // Entity has attribute "tags" with value ["sale", "new", "featured"]
 * equals("tags", "sale")        // matches (contains "sale")
 * equals("tags", "new")         // matches (contains "new")
 * equals("tags", "clearance")   // does not match
 * ```
 *
 * **Usage Examples**
 *
 * Typical usage in filter expressions:
 * ```java
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             equals("code", "PHONE-123"),           // exact match
 *             greaterThan("price", 100),              // comparison
 *             contains("name", "Samsung"),            // substring search
 *             inSet("status", "ACTIVE", "FEATURED"),  // set membership
 *             between("quantity", 10, 100)            // range check
 *         )
 *     )
 * );
 * ```
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 * @see AbstractAttributeFilterComparisonConstraintLeaf
 * @see AbstractAttributeFilterStringSearchConstraintLeaf
 * @see AbstractFilterConstraintLeaf
 * @see io.evitadb.api.query.AttributeConstraint
 */
public abstract class AbstractAttributeFilterConstraintLeaf extends AbstractFilterConstraintLeaf
	implements AttributeConstraint<FilterConstraint>, FilterConstraint {
	@Serial private static final long serialVersionUID = 3153809771456358624L;

	protected AbstractAttributeFilterConstraintLeaf(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	/**
	 * Returns attribute name that needs to be examined.
	 */
	@Override
	@Nonnull
	public String getAttributeName() {
		return (String) getArguments()[0];
	}

}
