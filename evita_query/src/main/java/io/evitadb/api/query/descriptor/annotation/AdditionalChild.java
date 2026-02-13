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

import io.evitadb.api.query.descriptor.ConstraintDomain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a creator parameter as an additional child constraint parameter that accepts nested constraints of a
 * different constraint type than the parent. This annotation enables cross-type constraint composition, such as
 * embedding ordering or requirement constraints within a filtering constraint.
 *
 * **Purpose**
 *
 * Additional child parameters enable constraints to accept children of different types:
 * - `ReferenceContent` (require constraint) can accept `FilterBy` and `OrderBy` children to filter/order references
 * - `HierarchyOfReference` (require constraint) can accept `FilterBy` children to filter hierarchy nodes
 * - `FacetSummary` (require constraint) can accept `FilterBy` children to filter facets
 *
 * The key distinction from `{@link Child}` is that `@AdditionalChild` accepts constraints of a different
 * `{@link io.evitadb.api.query.descriptor.ConstraintType}` than the parent (e.g., filtering children under a
 * require parent).
 *
 * **Parameter Type Requirements**
 *
 * A parameter annotated with `@AdditionalChild` must:
 * - Be a constraint container type: `FilterBy`, `OrderBy`, etc.
 * - Have `{@link io.evitadb.api.query.descriptor.ConstraintPropertyType#GENERIC}` property type
 * - Accept only one direct child parameter in its own constructor
 *
 * These requirements enable simplifications in constraint tree building and resolution by ensuring additional
 * children are always wrapped in a container that can be easily identified and processed.
 *
 * **Uniqueness Constraint**
 *
 * Currently, only one `@AdditionalChild` parameter per constraint type can be marked in a single creator. However,
 * a creator can have multiple `@AdditionalChild` parameters if each accepts a different
 * `{@link io.evitadb.api.query.descriptor.ConstraintType}` (e.g., one `FilterBy`, one `OrderBy`).
 *
 * **Domain Inheritance and Override**
 *
 * Similar to `{@link Child}`, additional children inherit the domain from their parent by default
 * (`{@link ConstraintDomain#DEFAULT}`), but this can be overridden via the `{@link #domain()}` element.
 *
 * Domain compatibility rules apply:
 * - If the child domain requires reference context (`REFERENCE`, `INLINE_REFERENCE`), the parent must provide it
 * - `HIERARCHY` domain can be used on hierarchical collections without requiring a reference context
 * - `ENTITY` domain can be used to switch from reference context to entity context
 *
 * **Example Usage**
 *
 * ```
 * @ConstraintDefinition(name = "referenceContent", ...)
 * public class ReferenceContent extends AbstractRequireConstraintContainer {
 *
 * @Creator
 * public ReferenceContent(
 * @Classifier String referenceName,
 * @AdditionalChild(domain = ConstraintDomain.REFERENCE) FilterBy filterBy,
 * @AdditionalChild(domain = ConstraintDomain.REFERENCE) OrderBy orderBy,
 * @Child EntityFetch entityRequirement,
 * @Child EntityGroupFetch entityGroupRequirement
 * ) {
 * super(referenceName, filterBy, orderBy, entityRequirement, entityGroupRequirement);
 * }
 * }
 * ```
 *
 * In this example:
 * - `ReferenceContent` is a require constraint (parent type: `RequireConstraint`)
 * - `filterBy` and `orderBy` are additional children of different types (`FilterConstraint`, `OrderConstraint`)
 * - `entityRequirement` and `entityGroupRequirement` are regular children (same type: `RequireConstraint`)
 * - The `FilterBy` and `OrderBy` operate in `REFERENCE` domain to filter/order reference attributes
 *
 * **Processing**
 *
 * During startup, `{@link io.evitadb.api.query.descriptor.ConstraintProcessor}` creates an
 * `{@link io.evitadb.api.query.descriptor.ConstraintCreator.AdditionalChildParameterDescriptor}` capturing:
 * - Parameter name
 * - Target domain for the additional child
 * - Container type (FilterBy, OrderBy, etc.)
 *
 * External API builders use this metadata to:
 * - Generate fields for cross-type children in GraphQL/REST schemas
 * - Validate that additional children are properly wrapped in their container types
 * - Build constraint tree schemas that support multi-type composition
 *
 * **Related Annotations**
 *
 * - `{@link Child}` - for children of the same constraint type
 * - `{@link Classifier}` - for target identifier parameters
 * - `{@link Value}` - for primitive/serializable value parameters
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 * @see Creator
 * @see ConstraintDefinition
 * @see Child
 * @see Classifier
 * @see Value
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AdditionalChild {

	/**
	 * Specifies the domain in which the additional child constraint operates. Domains define the data context
	 * (entity attributes, reference attributes, hierarchy, etc.) where constraints can access data.
	 *
	 * **Domain Inheritance:**
	 *
	 * By default (`ConstraintDomain.DEFAULT`), the additional child inherits the domain from its parent constraint.
	 * The parent's domain is typically determined by its
	 * `{@link io.evitadb.api.query.descriptor.ConstraintPropertyType}`.
	 *
	 * **Domain Override:**
	 *
	 * You can override the inherited domain to switch contexts. Common patterns:
	 * - `domain = ConstraintDomain.REFERENCE`: additional child filters/orders reference attributes
	 * - `domain = ConstraintDomain.ENTITY`: additional child filters/orders referenced entity attributes
	 * - `domain = ConstraintDomain.HIERARCHY`: additional child operates within hierarchy navigation
	 *
	 * **Domain Compatibility Rules:**
	 *
	 * Not all parent-child domain combinations are valid. Key restrictions:
	 * - If the child domain requires reference context (`REFERENCE`, `INLINE_REFERENCE`), the parent must also
	 * operate in a reference context to provide the reference name
	 * - Exception: `HIERARCHY` domain can be used on hierarchical entity collections without requiring a reference
	 * context
	 * - `ENTITY` domain can be used as a child of `REFERENCE` domain to switch from reference to entity context
	 *
	 * **Example:**
	 *
	 * ```
	 * @AdditionalChild(domain = ConstraintDomain.REFERENCE)
	 * FilterBy filterBy
	 * ```
	 *
	 * This additional child will filter on reference attributes within the parent reference constraint's context.
	 *
	 * Default: `ConstraintDomain.DEFAULT` (inherit from parent)
	 *
	 * @see ConstraintDomain
	 */
	ConstraintDomain domain() default ConstraintDomain.DEFAULT;
}
