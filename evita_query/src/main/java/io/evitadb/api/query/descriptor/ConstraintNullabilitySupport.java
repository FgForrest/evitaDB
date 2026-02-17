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

package io.evitadb.api.query.descriptor;

/**
 * Defines the nullability requirements of a constraint, specifying whether it can operate on nullable data, non-null
 * data, or both. This metadata enables validation of constraint usage against schema definitions and helps external
 * API builders generate correct type schemas.
 *
 * **Purpose and Usage**
 *
 * In evitaDB, attributes and other data can be marked as nullable or non-null in the schema. This enum categorizes
 * constraints based on their nullability requirements:
 * - Some constraints only make sense for nullable data (e.g., checking if a value is `null`)
 * - Some constraints require non-null data (e.g., numeric comparisons)
 * - Many constraints work with either nullable or non-null data (e.g., equality checks)
 *
 * At application startup, {@link io.evitadb.api.query.descriptor.ConstraintProcessor} reads
 * `{@link io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues#nullability()}` to populate this
 * metadata in the `{@link io.evitadb.api.query.descriptor.ConstraintDescriptor.SupportedValues}` structure.
 *
 * **Validation Context**
 *
 * External API builders (GraphQL, REST) use this information to:
 * - Generate appropriate type definitions (nullable vs. non-null fields)
 * - Validate that constraints are only applied to compatible schema elements
 * - Provide better error messages when constraints are misapplied
 *
 * **Example Usage**
 *
 * ```
 * @ConstraintDefinition(
 *     name = "is",
 *     supportedValues = @ConstraintSupportedValues(
 *         nullability = ConstraintNullabilitySupport.ONLY_NULLABLE
 *     )
 * )
 * public class AttributeIs extends AbstractAttributeFilterConstraintLeaf {
 *     // This constraint checks for NULL/NOT_NULL, so it only applies to nullable attributes
 * }
 * ```
 *
 * **Related Classes**
 *
 * - `{@link io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues}` - Annotation declaring nullability
 * requirements
 * - `{@link io.evitadb.api.query.descriptor.ConstraintDescriptor.SupportedValues}` - Runtime descriptor containing
 * nullability support
 * - `{@link io.evitadb.api.query.descriptor.ConstraintProcessor}` - Processes nullability annotations at startup
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public enum ConstraintNullabilitySupport {

	/**
	 * Constraint can operate on both nullable and non-null data.
	 *
	 * **Typical Use Cases:**
	 * - Equality checks: `attributeEquals` works whether the attribute is nullable or not
	 * - Range queries: `attributeBetween` works for both nullable and non-null numeric attributes
	 * - String operations: `attributeContains` works for nullable and non-null string attributes
	 *
	 * **Schema Compatibility:**
	 * These constraints are universally applicable and don't impose nullability restrictions on the schema elements
	 * they target.
	 */
	NULLABLE_AND_NONNULL,
	/**
	 * Constraint can only operate on nullable data and must be rejected if applied to non-null schema elements.
	 *
	 * **Typical Use Cases:**
	 * - Null checks: `attributeIs(NULL)` or `attributeIs(NOT_NULL)` only make sense for nullable attributes
	 *
	 * **Schema Compatibility:**
	 * External API builders should prevent these constraints from being used with non-null attributes, as the
	 * operation would always fail or be meaningless.
	 *
	 * **Validation:**
	 * If applied to a non-null attribute, the constraint should be rejected at query validation time or API schema
	 * generation time.
	 */
	ONLY_NULLABLE,
	/**
	 * Constraint can only operate on non-null data and should be used with caution on nullable schema elements.
	 *
	 * **Typical Use Cases:**
	 * - Operations that require a guaranteed value (though in practice, most such constraints in evitaDB work with
	 * nullable data by skipping null values during evaluation)
	 *
	 * **Schema Compatibility:**
	 * While the constraint requires non-null data conceptually, it may still be applied to nullable attributes at
	 * runtime. The constraint implementation should handle null values gracefully (typically by treating them as
	 * non-matching).
	 *
	 * **Note:**
	 * This category is less common in evitaDB's current constraint set, as most constraints handle nullable data
	 * by implicitly skipping null values.
	 */
	ONLY_NONNULL
}
