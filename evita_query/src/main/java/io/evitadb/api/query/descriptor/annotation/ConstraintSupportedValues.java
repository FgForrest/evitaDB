/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.query.descriptor.annotation;

import io.evitadb.api.query.descriptor.ConstraintNullabilitySupport;
import io.evitadb.dataType.EvitaDataTypes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines the data types that a constraint can operate on when filtering, comparing, or validating values. This
 * annotation is used as an element of `{@link ConstraintDefinition}` to specify type compatibility for constraints
 * that work with data values.
 *
 * **Purpose**
 *
 * This annotation provides metadata about:
 * - Which primitive/object types the constraint accepts (String, Integer, BigDecimal, etc.)
 * - Whether array values are supported
 * - Whether compound types (sortable attribute compounds) are supported
 * - Nullability requirements (nullable-only, nonnull-only, or both)
 *
 * This metadata is used for:
 * - **Validation**: ensuring constraints are used with compatible attribute types
 * - **Schema generation**: creating appropriate GraphQL/REST input types
 * - **Query resolution**: selecting the right constraint based on attribute schema
 * - **Documentation**: showing which types work with each constraint
 *
 * **Type Specification Patterns**
 *
 * Choose one of two patterns for specifying supported types:
 *
 * 1. **All types supported** (most filtering constraints):
 * ```
 * @ConstraintSupportedValues(allTypesSupported = true)
 * ```
 * Resolves to all types in `{@link EvitaDataTypes#getSupportedDataTypes()}`.
 *
 * 2. **Specific types only** (specialized constraints):
 * ```
 * @ConstraintSupportedValues(supportedTypes = {String.class, Integer.class, Long.class})
 * ```
 * Only the listed types are accepted.
 *
 * If `{@link #allTypesSupported()}` is `true`, `{@link #supportedTypes()}` is ignored.
 *
 * **Array Support**
 *
 * Set `{@link #arraysSupported()} = true` when the constraint can operate on array-valued attributes:
 * ```
 * @ConstraintSupportedValues(allTypesSupported = true, arraysSupported = true)
 * ```
 *
 * This enables constraints like `contains("tags", "electronics")` where `tags` is a `String[]` attribute.
 *
 * **Compound Type Support**
 *
 * Set `{@link #compoundsSupported()} = true` when the constraint can operate on sortable attribute compounds:
 * ```
 * @ConstraintSupportedValues(compoundsSupported = true)
 * ```
 *
 * Sortable attribute compounds are composite values formed from multiple attributes for complex sorting scenarios.
 *
 * **Nullability Requirements**
 *
 * Use `{@link #nullability()}` to restrict whether the constraint works with nullable or nonnull attributes:
 * - `NULLABLE_AND_NONNULL` (default): works with both nullable and nonnull attributes
 * - `ONLY_NULLABLE`: only works with nullable attributes (e.g., `isNull()` constraint)
 * - `ONLY_NONNULL`: only works with nonnull attributes
 *
 * **Example Configurations**
 *
 * ```
 * // Comparison constraint supporting all types
 * @ConstraintDefinition(
 * name = "equals",
 * supportedValues = @ConstraintSupportedValues(allTypesSupported = true)
 * )
 *
 * // String-specific constraint
 * @ConstraintDefinition(
 * name = "startsWith",
 * supportedValues = @ConstraintSupportedValues(supportedTypes = {String.class})
 * )
 *
 * // Array constraint supporting all types
 * @ConstraintDefinition(
 * name = "contains",
 * supportedValues = @ConstraintSupportedValues(
 * allTypesSupported = true,
 * arraysSupported = true
 * )
 * )
 *
 * // Null-checking constraint
 * @ConstraintDefinition(
 * name = "isNull",
 * supportedValues = @ConstraintSupportedValues(
 * allTypesSupported = true,
 * nullability = ConstraintNullabilitySupport.ONLY_NULLABLE
 * )
 * )
 *
 * // Container constraint (no value operations)
 * @ConstraintDefinition(
 * name = "and",
 * supportedValues = @ConstraintSupportedValues()  // default: no type support
 * )
 * ```
 *
 * **Processing**
 *
 * During startup, `{@link io.evitadb.api.query.descriptor.ConstraintProcessor}` converts this annotation into a
 * `{@link io.evitadb.api.query.descriptor.ConstraintDescriptor.SupportedValues}` record that:
 * - Expands `allTypesSupported = true` into the full set of evitaDB types
 * - Stores the array/compound support flags
 * - Captures the nullability requirement
 *
 * External API builders use this metadata to:
 * - Generate attribute-specific constraint fields (e.g., `codeEquals`, `priceGreaterThan`)
 * - Select compatible constraints based on attribute type (String attributes → string constraints)
 * - Validate constraint usage against entity schema
 *
 * **Related Classes**
 *
 * - `{@link ConstraintNullabilitySupport}` - enum defining nullability support options
 * - `{@link EvitaDataTypes}` - registry of all supported evitaDB data types
 * - `{@link io.evitadb.api.query.descriptor.ConstraintDescriptor.SupportedValues}` - runtime representation
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @see ConstraintDefinition
 * @see ConstraintNullabilitySupport
 * @see EvitaDataTypes
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConstraintSupportedValues {

	/**
	 * Array of specific data types that this constraint can operate on. Use wrapper classes, not primitives
	 * (e.g., `Integer.class`, not `int.class`).
	 *
	 * **When to Use:**
	 * - Constraints that only work with specific types (e.g., `startsWith` only for `String`)
	 * - Numeric-only constraints (e.g., arithmetic operations on `Integer`, `Long`, `BigDecimal`)
	 *
	 * **Precedence:**
	 *
	 * If `{@link #allTypesSupported()} = true`, this array is ignored. Use one or the other, not both.
	 *
	 * **Examples:**
	 * - String constraints: `supportedTypes = {String.class}`
	 * - Numeric constraints: `supportedTypes = {Integer.class, Long.class, BigDecimal.class}`
	 * - Date constraints: `supportedTypes = {LocalDate.class, LocalDateTime.class, OffsetDateTime.class}`
	 *
	 * Default: `{}` (no specific types, constraint doesn't operate on values)
	 */
	Class<?>[] supportedTypes() default {};

	/**
	 * Indicates that all data types supported by evitaDB are accepted by this constraint. During processing, this
	 * is resolved to `{@link EvitaDataTypes#getSupportedDataTypes()}`.
	 *
	 * **When to Use:**
	 * - Generic comparison constraints (`equals`, `greaterThan`, `lessThan`, etc.)
	 * - Null-checking constraints (`isNull`, `isNotNull`)
	 * - Generic filtering constraints that work uniformly across all types
	 *
	 * **Precedence:**
	 *
	 * When `true`, `{@link #supportedTypes()}` is ignored.
	 *
	 * **Supported Types Include:**
	 * - Primitives: `String`, `Integer`, `Long`, `Boolean`, `Byte`, `Short`, `Character`, `BigDecimal`
	 * - Date/Time: `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`
	 * - Special: `Locale`, `Currency`, `UUID`, `Predecessor`, `DateTimeRange`, `NumberRange` variants
	 * - Binary: `byte[]`
	 *
	 * Default: `false` (only types in `supportedTypes` are accepted)
	 */
	boolean allTypesSupported() default false;

	/**
	 * Indicates that this constraint can operate on array-valued attributes in addition to single-valued attributes.
	 *
	 * **When to Use:**
	 * - Constraints that search within arrays (`contains`, `inSet`)
	 * - Constraints that operate on multi-valued attributes
	 *
	 * **Example:**
	 *
	 * An attribute `tags` of type `String[]` can be queried with `contains("tags", "electronics")` if the constraint
	 * has `arraysSupported = true`.
	 *
	 * **Interaction with `supportedTypes`:**
	 *
	 * If `supportedTypes = {String.class}` and `arraysSupported = true`, the constraint accepts both:
	 * - Single `String` values
	 * - `String[]` array values
	 *
	 * Default: `false` (only single-valued attributes supported)
	 */
	boolean arraysSupported() default false;

	/**
	 * Indicates that this constraint can operate on sortable attribute compound values. Compounds are composite
	 * values formed from multiple attributes used in complex sorting scenarios.
	 *
	 * **When to Use:**
	 * - Constraints that order by compound keys
	 * - Constraints that filter on compound attribute combinations
	 *
	 * **What are Compounds:**
	 *
	 * Sortable attribute compounds combine multiple attribute values into a single composite value for sorting.
	 * For example, sorting products by `(brand, name)` uses a compound of two string attributes.
	 *
	 * Default: `false` (compounds not supported)
	 *
	 * @see io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract
	 */
	boolean compoundsSupported() default false;

	/**
	 * Specifies whether the constraint requires nullable attributes, nonnull attributes, or supports both.
	 *
	 * **Options:**
	 * - `NULLABLE_AND_NONNULL` (default): works with both nullable and nonnull attributes
	 * - `ONLY_NULLABLE`: only works with nullable attributes (e.g., `isNull()` constraint)
	 * - `ONLY_NONNULL`: only works with nonnull attributes
	 *
	 * **Use Cases:**
	 * - `ONLY_NULLABLE`: null-checking constraints (`isNull`, `isNotNull`)
	 * - `ONLY_NONNULL`: constraints that assume value presence (rare, most constraints handle both)
	 * - `NULLABLE_AND_NONNULL`: most constraints (comparisons, filters, etc.)
	 *
	 * **Validation:**
	 *
	 * When generating API schemas, the descriptor system checks attribute nullability against this setting to ensure
	 * only compatible constraints are offered for each attribute.
	 *
	 * Default: `ConstraintNullabilitySupport.NULLABLE_AND_NONNULL` (works with both)
	 *
	 * @see ConstraintNullabilitySupport
	 */
	ConstraintNullabilitySupport nullability() default ConstraintNullabilitySupport.NULLABLE_AND_NONNULL;
}
