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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintDomain;

import javax.annotation.Nonnull;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a creator parameter as a child constraint parameter that accepts one or more nested constraints of the same
 * constraint type as the parent. This annotation is used for building hierarchical constraint trees where a container
 * constraint can have multiple child constraints.
 *
 * **Purpose**
 *
 * Child parameters enable composition of constraints into trees:
 * - Logical operators: `and(equals("a", 1), greaterThan("b", 2))` - both children are filtering constraints
 * - Containers: `filterBy(equals("code", "X"), priceInPriceLists("basic"))` - children are filter constraints
 * - Fetch requirements: `entityFetch(attributeContent(), priceContent())` - children are require constraints
 *
 * The key characteristic of `@Child` (vs `{@link AdditionalChild}`) is that children must be of the same constraint
 * type as the parent (filtering children under filtering parent, ordering children under ordering parent, etc.).
 *
 * **Parameter Type Requirements**
 *
 * A parameter annotated with `@Child` must be:
 * - A varargs parameter: `FilterConstraint... children`
 * - An array: `FilterConstraint[] children`
 * - A single constraint: `FilterConstraint child` (for containers that accept exactly one child)
 *
 * The constraint type must match the parent's type:
 * - `FilterConstraint` children under `FilterConstraint` parent
 * - `OrderConstraint` children under `OrderConstraint` parent
 * - `RequireConstraint` children under `RequireConstraint` parent
 *
 * **Domain Inheritance and Override**
 *
 * By default, child constraints inherit the domain from their parent constraint. However, the `{@link #domain()}`
 * element can override this to switch contexts:
 *
 * Common patterns:
 * - `domain = ConstraintDomain.DEFAULT` (default): children inherit parent's domain
 * - `domain = ConstraintDomain.ENTITY`: children operate on entity data (attributes, prices)
 * - `domain = ConstraintDomain.REFERENCE`: children operate on reference attributes
 * - `domain = ConstraintDomain.HIERARCHY`: children operate within hierarchy navigation
 *
 * Example: A reference filtering constraint might switch its children to ENTITY domain to filter on referenced entity
 * attributes rather than reference attributes.
 *
 * **Constraining Allowed Children**
 *
 * Use `{@link #allowed()}` or `{@link #forbidden()}` to restrict which constraint classes can be used as children:
 * - `allowed`: whitelist - only specified constraint classes are permitted
 * - `forbidden`: blacklist - specified constraint classes are prohibited
 * - Empty arrays (default): all constraints of the matching type and domain are allowed
 *
 * Only one of `allowed` or `forbidden` should be specified (non-empty). Specifying both is an error.
 *
 * **Uniqueness Constraint**
 *
 * The `{@link #uniqueChildren()}` flag controls whether the same constraint can appear multiple times:
 * - `false` (default): duplicates allowed - `and(equals("a", 1), equals("a", 2))` is valid
 * - `true`: duplicates forbidden - each constraint class can appear at most once
 *
 * This is typically used for requirement containers where combining duplicate requirements would be meaningless
 * (e.g., `entityFetch(attributeContent(), attributeContent())` is redundant).
 *
 * **Example Usage**
 *
 * ```
 * @ConstraintDefinition(name = "and", ...)
 * public class And extends AbstractFilterConstraintContainer {
 *
 * @Creator
 * public And(@Child(domain = ConstraintDomain.DEFAULT) FilterConstraint... children) {
 * super(children);
 * }
 * }
 * ```
 *
 * ```
 * @ConstraintDefinition(name = "entityFetch", ...)
 * public class EntityFetch extends AbstractRequireConstraintContainer {
 *
 * @Creator
 * public EntityFetch(
 * @Child(
 * domain = ConstraintDomain.ENTITY,
 * uniqueChildren = true,
 * forbidden = { EntityGroupFetch.class }
 * )
 * RequireConstraint... requirements
 * ) {
 * super(requirements);
 * }
 * }
 * ```
 *
 * **Processing**
 *
 * During startup, `{@link io.evitadb.api.query.descriptor.ConstraintProcessor}` creates a
 * `{@link io.evitadb.api.query.descriptor.ConstraintCreator.ChildParameterDescriptor}` capturing:
 * - Parameter name
 * - Target domain for children
 * - Allowed/forbidden constraint classes
 * - Uniqueness requirement
 *
 * External API builders use this metadata to:
 * - Generate nested object types with appropriate child constraint fields
 * - Validate child constraint compatibility (type, domain, allowed/forbidden)
 * - Build constraint tree schemas recursively
 *
 * **Related Annotations**
 *
 * - `{@link AdditionalChild}` - for children of a different constraint type
 * - `{@link Classifier}` - for target identifier parameters
 * - `{@link Value}` - for primitive/serializable value parameters
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @see Creator
 * @see ConstraintDefinition
 * @see AdditionalChild
 * @see Classifier
 * @see Value
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Child {

	/**
	 * Specifies the domain in which child constraints operate. Domains define the data context (entity attributes,
	 * reference attributes, hierarchy, etc.) where constraints can access data.
	 *
	 * **Domain Inheritance:**
	 *
	 * By default (`ConstraintDomain.DEFAULT`), children inherit the domain from their parent constraint. The parent's
	 * domain is typically determined by its `{@link io.evitadb.api.query.descriptor.ConstraintPropertyType}`.
	 *
	 * **Domain Override:**
	 *
	 * You can override the inherited domain to switch contexts. For example, a reference filtering constraint might
	 * set `domain = ConstraintDomain.ENTITY` for its children to filter on referenced entity attributes rather than
	 * reference attributes.
	 *
	 * **Domain Compatibility Rules:**
	 *
	 * Not all parent-child domain combinations are valid. Key restrictions:
	 * - If the child domain requires reference context (`REFERENCE`, `INLINE_REFERENCE`), the parent must also
	 * operate in a reference context to provide the reference name
	 * - Exception: `HIERARCHY` domain can be used on hierarchical entity collections without requiring a reference
	 * context
	 * - `ENTITY` domain can be used as a child of `REFERENCE` domain (for filtering referenced entities)
	 *
	 * **Common Domain Values:**
	 * - `DEFAULT` - inherit parent's domain (most common)
	 * - `ENTITY` - operate on entity data (attributes, prices, hierarchy)
	 * - `REFERENCE` - operate on reference attributes in the main query
	 * - `INLINE_REFERENCE` - operate on reference attributes within a property filter/order
	 * - `HIERARCHY` - operate within hierarchy navigation
	 * - `FACET` - operate on facet references
	 * - `SEGMENT` - container segmentation (e.g., spacing gaps in pagination)
	 *
	 * Default: `ConstraintDomain.DEFAULT` (inherit from parent)
	 *
	 * @see ConstraintDomain
	 */
	ConstraintDomain domain() default ConstraintDomain.DEFAULT;

	/**
	 * Controls whether each child constraint class can appear at most once in the children array.
	 *
	 * **When `false` (default):**
	 * - Duplicate constraint classes are allowed
	 * - Example: `and(equals("a", 1), equals("b", 2), equals("c", 3))` - multiple `AttributeEquals` children
	 * - Use for logical operators and most container constraints
	 *
	 * **When `true`:**
	 * - Each constraint class can appear at most once
	 * - Example: `entityFetch(attributeContent(), priceContent())` - each content type appears once
	 * - Duplicate constraint classes will be rejected during validation
	 * - Use for requirement containers where combining duplicates is meaningless
	 *
	 * Default: `false` (duplicates allowed)
	 */
	boolean uniqueChildren() default false;

	/**
	 * Whitelist of allowed child constraint classes. Only constraints in this set can be used as children. An empty
	 * array (default) means all constraints of the matching type and domain are allowed.
	 *
	 * **Mutual Exclusivity:**
	 *
	 * Do not specify both `allowed` and `forbidden` (non-empty). Use one or the other, not both.
	 *
	 * **Example:**
	 * ```
	 * @Child(
	 * allowed = { AttributeContent.class, PriceContent.class, AssociatedDataContent.class }
	 * )
	 * RequireConstraint... requirements
	 * ```
	 *
	 * In this example, only the three specified requirement types can be used as children. All other require
	 * constraints are implicitly forbidden.
	 *
	 * Default: `{}` (all constraints of matching type and domain allowed)
	 */
	@Nonnull
	Class<? extends Constraint<?>>[] allowed() default {};

	/**
	 * Blacklist of forbidden child constraint classes. All constraints except those in this set are allowed. An empty
	 * array (default) means no constraints are explicitly forbidden (all allowed by default).
	 *
	 * **Mutual Exclusivity:**
	 *
	 * Do not specify both `allowed` and `forbidden` (non-empty). Use one or the other, not both.
	 *
	 * **Example:**
	 * ```
	 * @Child(
	 * forbidden = { EntityGroupFetch.class }
	 * )
	 * RequireConstraint... requirements
	 * ```
	 *
	 * In this example, all require constraints are allowed except `EntityGroupFetch`. This pattern is useful when
	 * you want to allow most constraints but exclude a few specific ones.
	 *
	 * Default: `{}` (no explicit forbidden constraints)
	 */
	@Nonnull
	Class<? extends Constraint<?>>[] forbidden() default {};
}
