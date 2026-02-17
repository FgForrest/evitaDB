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

package io.evitadb.api.query.descriptor.annotation;

import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a creator parameter as a value parameter that accepts primitive or serializable data rather than nested
 * constraints. Value parameters represent the actual data being filtered, compared, or configured.
 *
 * **Purpose**
 *
 * Value parameters are used for:
 * - Filter values: `attributeEquals("code", "PHONE")` - `"PHONE"` is a value parameter
 * - Numeric thresholds: `greaterThan("price", 100)` - `100` is a value parameter
 * - Date ranges: `between("validFrom", from, to)` - `from` and `to` are value parameters
 * - Configuration: `page(1, 20)` - `1` and `20` are value parameters
 *
 * Multiple parameters in a single creator can be annotated with `@Value`.
 *
 * **Parameter Type Requirements**
 *
 * A parameter annotated with `@Value` must have one of:
 * - A primitive type or its wrapper (`int`, `Integer`, `boolean`, `Boolean`, etc.)
 * - A type supported by `{@link io.evitadb.dataType.EvitaDataTypes}` (String, BigDecimal, LocalDateTime, etc.)
 * - A generic `Serializable` type when the concrete type is determined from schema metadata at runtime
 * - An array of any of the above (e.g., `String[]`, `Integer[]`)
 *
 * **Nullability**
 *
 * Value parameters can be nullable (annotated with `@Nullable`) to support optional values. For example, a constraint
 * might accept `null` to indicate "no filtering" or "use default value".
 *
 * **Plain Type Requirement**
 *
 * The `{@link #requiresPlainType()}` flag controls type conversion for range and complex types:
 * - `requiresPlainType = false` (default): accepts both plain values and range types
 * - Example: `between("price", 100, 200)` accepts integers OR `IntegerNumberRange`
 * - `requiresPlainType = true`: only accepts the plain/primitive form, rejecting range types
 * - Example: `greaterThan("price", 100)` must receive an integer, NOT `IntegerNumberRange`
 *
 * This distinction matters for attributes that store range values (e.g., `IntegerNumberRange`, `DateTimeRange`).
 * Some constraints operate on the range itself, while others operate on the bounds.
 *
 * **Example Usage**
 *
 * ```
 * @ConstraintDefinition(name = "equals", ...)
 * public class AttributeEquals extends ... {
 *
 * @Creator
 * public AttributeEquals(@Classifier String attributeName, @Value Serializable attributeValue) {
 * super(attributeName, attributeValue);
 * }
 * }
 * ```
 *
 * ```
 * @ConstraintDefinition(name = "between", ...)
 * public class AttributeBetween extends ... {
 *
 * @Creator
 * public AttributeBetween(
 * @Classifier String attributeName,
 * @Value(requiresPlainType = true) Serializable from,
 * @Value(requiresPlainType = true) Serializable to
 * ) {
 * super(attributeName, from, to);
 * }
 * }
 * ```
 *
 * **Processing**
 *
 * During startup, `{@link io.evitadb.api.query.descriptor.ConstraintProcessor}` creates a
 * `{@link io.evitadb.api.query.descriptor.ConstraintCreator.ValueParameterDescriptor}` for each `@Value` parameter,
 * capturing:
 * - Parameter name (used to find getter methods via reflection)
 * - Parameter type (for validation and schema generation)
 * - Whether the parameter is required (non-nullable)
 * - Whether it requires plain types (`requiresPlainType` flag)
 *
 * External API builders use this metadata to:
 * - Generate appropriate input types (GraphQL scalars, REST JSON schemas)
 * - Validate value types against attribute schemas
 * - Convert between API-specific formats and evitaDB internal types
 *
 * **Related Annotations**
 *
 * - `{@link Classifier}` - for target identifier parameters (attribute name, reference name, etc.)
 * - `{@link Child}` - for nested constraint parameters of the same type
 * - `{@link AdditionalChild}` - for nested constraint parameters of a different type
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @see Creator
 * @see ConstraintDefinition
 * @see Classifier
 * @see Child
 * @see AdditionalChild
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Value {

	/**
	 * Controls whether this parameter accepts only plain/primitive values or also accepts range/complex types.
	 *
	 * **When `false` (default):**
	 * - Parameter accepts both plain values and range types
	 * - Example: `between("validFrom", from, to)` can accept both `LocalDateTime` values AND `DateTimeRange` objects
	 * - External APIs will accept either form and convert as needed
	 *
	 * **When `true`:**
	 * - Parameter accepts only plain/primitive values, rejecting range types
	 * - Example: `greaterThan("price", 100)` accepts `Integer` but NOT `IntegerNumberRange`
	 * - Forces explicit boundary values instead of range objects
	 *
	 * **Use Cases:**
	 * - `false`: constraints like `between()` that logically accept range values
	 * - `true`: comparison constraints (`greaterThan`, `lessThan`) that operate on scalar bounds
	 *
	 * Default: `false` (accepts both plain and range types)
	 */
	boolean requiresPlainType() default false;
}
