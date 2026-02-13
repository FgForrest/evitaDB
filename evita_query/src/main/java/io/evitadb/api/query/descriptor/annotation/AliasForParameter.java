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

import javax.annotation.Nonnull;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a getter method as an alias for retrieving a constraint parameter's value when the method name doesn't
 * follow the standard naming convention. This annotation is used for reflection-based parameter extraction when
 * building constraint descriptors and when external APIs need to read parameter values.
 *
 * **Purpose**
 *
 * By default, the constraint descriptor system looks for getter methods that match parameter names using standard
 * JavaBean conventions:
 * - Parameter `attributeName` → getter `getAttributeName()` or `attributeName()`
 * - Parameter `pageNumber` → getter `getPageNumber()` or `pageNumber()`
 *
 * When a parameter's value is exposed through a differently named method, use `@AliasForParameter` to map the
 * parameter name to the actual getter method.
 *
 * **Use Cases**
 *
 * Common scenarios requiring aliased getters:
 * - **Legacy API compatibility**: parameter name changed but public getter must remain stable
 * - **Computed values**: parameter is stored internally but exposed through a transformed getter
 * - **Naming conflicts**: multiple parameters map to the same underlying field accessed via different getters
 * - **Convenience methods**: parameter is exposed through a more readable method name
 *
 * **Example Usage**
 *
 * ```
 * @ConstraintDefinition(name = "page", ...)
 * public class Page extends AbstractRequireConstraintContainer {
 *
 * @Creator
 * public Page(@Nullable Integer number, @Nullable Integer size) {
 * super(number, size);
 * }
 *
 * // Standard getter for 'size' parameter
 * public int getPageSize() {
 * return (Integer) getArguments()[1];
 * }
 *
 * // Aliased getter for 'number' parameter
 * @AliasForParameter("number")
 * public int getPageNumber() {
 * return (Integer) getArguments()[0];
 * }
 * }
 * ```
 *
 * In this example, the creator parameter `number` is accessed via the getter `getPageNumber()` instead of the
 * default expected name `getNumber()`.
 *
 * **Return Type Compatibility**
 *
 * The aliased getter method should have a return type compatible with the parameter it represents. While exact type
 * matching is ideal, the descriptor system can handle:
 * - Primitive types and their wrappers (e.g., parameter `Integer`, getter returns `int`)
 * - Supertype return values (e.g., parameter `String`, getter returns `Serializable`)
 * - Array/varargs compatibility
 *
 * **Processing**
 *
 * During startup, `{@link io.evitadb.api.query.descriptor.ConstraintProcessor}` scans constraint classes for getter
 * methods. When building parameter descriptors, it:
 * 1. First looks for a getter matching the parameter name via standard conventions
 * 2. If not found, searches for methods annotated with `@AliasForParameter` matching the parameter name
 * 3. Associates the found method with the parameter descriptor for runtime reflection access
 *
 * External API builders (GraphQL, REST) use getter methods to:
 * - Extract parameter values from constraint instances for serialization
 * - Generate schema documentation showing parameter types and defaults
 * - Validate constraint instances during query parsing
 *
 * **Related Annotations**
 *
 * - `{@link Creator}` - defines the constructor/factory method with parameters
 * - `{@link Classifier}`, `{@link Value}`, `{@link Child}`, `{@link AdditionalChild}` - parameter type annotations
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 * @see Creator
 * @see ConstraintDefinition
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AliasForParameter {

	/**
	 * The name of the creator parameter that this getter method retrieves. This must exactly match the parameter
	 * name as declared in the `{@link Creator}`-annotated constructor or static factory method.
	 *
	 * **Example:**
	 *
	 * ```
	 * @Creator
	 * public Page(@Nullable Integer number, @Nullable Integer size) { ... }
	 *
	 * @AliasForParameter("number")
	 * public int getPageNumber() { ... }
	 * ```
	 *
	 * In this example, the value `"number"` links the getter `getPageNumber()` to the creator parameter `number`.
	 *
	 * @return the exact parameter name from the creator method signature
	 */
	@Nonnull
	String value();
}
