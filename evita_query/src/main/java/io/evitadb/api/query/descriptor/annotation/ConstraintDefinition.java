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
import io.evitadb.api.query.descriptor.ConstraintDomain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines metadata for a query constraint class in evitaDB's constraint descriptor system. This annotation marks
 * constraint classes (filtering, ordering, require) and provides essential metadata that enables automatic processing
 * by external API builders (GraphQL, REST), documentation generation, and constraint validation.
 *
 * **Architecture Pattern**
 *
 * The constraint descriptor system uses runtime reflection to build a registry of all available constraints along
 * with their metadata. This registry is then used to:
 * - Generate GraphQL and REST API schemas dynamically based on entity schemas
 * - Validate constraint trees for correctness (domain compatibility, value types, etc.)
 * - Generate user documentation with links to detailed guides
 * - Enable constraint introspection and programmatic query building
 *
 * **Usage Requirements**
 *
 * A class annotated with `@ConstraintDefinition` must:
 * - Implement one of the constraint interfaces: `FilterConstraint`, `OrderConstraint`, `RequireConstraint`,
 * or `HeadConstraint`
 * - Have at least one constructor or static factory method annotated with `{@link Creator}`
 * - Annotate all creator parameters with `{@link Classifier}`, `{@link Value}`, `{@link Child}`, or
 * `{@link AdditionalChild}`
 * - Be registered in `{@link io.evitadb.api.query.descriptor.ConstraintRegistry#REGISTERED_CONSTRAINTS}`
 *
 * **Processing Pipeline**
 *
 * At application startup:
 * 1. `{@link ConstraintDescriptorProvider}` invokes `{@link io.evitadb.api.query.descriptor.ConstraintProcessor}`
 * 2. The processor scans all registered constraint classes for this annotation
 * 3. A `{@link io.evitadb.api.query.descriptor.ConstraintDescriptor}` is created for each constraint variant
 * (each `@Creator` with a unique suffix produces a separate descriptor)
 * 4. Descriptors are indexed by type, property type, and full name for fast lookup
 * 5. External API builders (GraphQL/REST) use these descriptors to generate schemas dynamically
 *
 * **Example Usage**
 *
 * ```
 * @ConstraintDefinition(
 * name = "equals",
 * shortDescription = "Compares attribute value for equality.",
 * userDocsLink = "/documentation/query/filtering/comparable#equals",
 * supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE }
 * )
 * public class AttributeEquals extends AbstractAttributeFilterConstraintLeaf
 * implements IndexUsingConstraint {
 *
 * @Creator
 * public AttributeEquals(@Classifier String attributeName, @Value Serializable attributeValue) {
 * super(attributeName, attributeValue);
 * }
 * }
 * ```
 *
 * **Constraint Naming Convention**
 *
 * The `{@link #name()}` element specifies the base constraint name in camelCase. When combined with a creator's
 * `{@link Creator#suffix()}`, the full constraint name is formed by capitalizing the first letter of the suffix:
 * - `name = "referenceContent"` + `suffix = ""` → `referenceContent`
 * - `name = "referenceContent"` + `suffix = "withAttributes"` → `referenceContentWithAttributes`
 *
 * **Related Classes**
 *
 * Annotation processing:
 * - `{@link ConstraintDescriptorProvider}` - Entry point for accessing constraint metadata
 * - `{@link io.evitadb.api.query.descriptor.ConstraintProcessor}` - Processes annotations at startup
 * - `{@link io.evitadb.api.query.descriptor.ConstraintDescriptor}` - Runtime descriptor for a constraint variant
 *
 * External API integration:
 * - `{@link io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ConstraintSchemaBuilder}` - Base class
 * for GraphQL/REST schema builders
 * - `{@link io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.GraphQLConstraintSchemaBuilder}` -
 * GraphQL schema builder
 * - `{@link io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint.OpenApiConstraintSchemaBuilder}` -
 * REST/OpenAPI schema builder
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @see Creator
 * @see Classifier
 * @see Value
 * @see Child
 * @see AdditionalChild
 * @see ConstraintDescriptorProvider
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConstraintDefinition {

	/**
	 * Base name of the constraint in camelCase format. This name identifies the constraint operation or condition
	 * (e.g., `"equals"`, `"fetch"`, `"referenceContent"`).
	 *
	 * When a `{@link Creator}` specifies a `{@link Creator#suffix()}`, the full constraint name is formed by appending
	 * the capitalized suffix to this base name. For example:
	 * - `name = "referenceContent"` + `suffix = ""` → full name: `"referenceContent"`
	 * - `name = "referenceContent"` + `suffix = "withAttributes"` → full name: `"referenceContentWithAttributes"`
	 *
	 * The full name must be unique within the constraint's type and property type categorization.
	 */
	String name();

	/**
	 * Brief description of the constraint's purpose and behavior. This description is used in generated API
	 * documentation (GraphQL schema descriptions, REST API docs) and should be concise (1-2 sentences).
	 *
	 * For full documentation with examples and detailed behavior, use `{@link #userDocsLink()}`.
	 */
	String shortDescription();

	/**
	 * Relative URL path to the user documentation page where this constraint is described in detail.
	 *
	 * The path is relative to the evitaDB documentation root and should follow the pattern:
	 * `/documentation/query/{section}/{page}#{anchor}`
	 *
	 * Examples:
	 * - `"/documentation/query/filtering/comparable#equals"` for `AttributeEquals` constraint
	 * - `"/documentation/query/filtering/price#price-in-price-lists"` for `PriceInPriceLists` constraint
	 * - `"/documentation/query/requirements/fetching#reference-content"` for `ReferenceContent` constraint
	 *
	 * This link is included in generated API documentation to help users find detailed usage examples.
	 */
	String userDocsLink();

	/**
	 * Array of domains in which this constraint can be used. Domains define the data context where the constraint
	 * operates (entity attributes, reference attributes, hierarchy, etc.).
	 *
	 * Common domain patterns:
	 * - `{ ConstraintDomain.ENTITY }` - operates on entity data (attributes, prices, etc.)
	 * - `{ ConstraintDomain.REFERENCE }` - operates on reference attributes
	 * - `{ ConstraintDomain.HIERARCHY }` - operates within hierarchy navigation
	 * - `{ ConstraintDomain.GENERIC }` - domain-agnostic container or utility constraint
	 * - `{ ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE }` - works in both entity and reference contexts
	 *
	 * The descriptor system validates that child constraints use compatible domains. For example, a constraint
	 * operating in `REFERENCE` domain cannot have children requiring `ENTITY` domain unless the parent explicitly
	 * switches domains via `{@link Child#domain()}` or `{@link AdditionalChild#domain()}`.
	 *
	 * Default: `{ ConstraintDomain.GENERIC }`
	 *
	 * @see ConstraintDomain
	 */
	ConstraintDomain[] supportedIn() default {ConstraintDomain.GENERIC};

	/**
	 * Defines the data types that this constraint can operate on when filtering or comparing values. This metadata
	 * is used for validation and to generate appropriate schema types in external APIs.
	 *
	 * For constraints that don't operate on data values (containers, logical operators, etc.), use the default empty
	 * configuration. For constraints that filter or compare data (equals, greaterThan, etc.), specify the supported
	 * types via `{@link ConstraintSupportedValues#supportedTypes()}` or
	 * `{@link ConstraintSupportedValues#allTypesSupported()}`.
	 *
	 * Example configurations:
	 * - All types: `@ConstraintSupportedValues(allTypesSupported = true)`
	 * - Specific types: `@ConstraintSupportedValues(supportedTypes = {String.class, Integer.class})`
	 * - With arrays: `@ConstraintSupportedValues(allTypesSupported = true, arraysSupported = true)`
	 * - Nullable only: `@ConstraintSupportedValues(allTypesSupported = true,
	 * nullability = ConstraintNullabilitySupport.ONLY_NULLABLE)`
	 *
	 * Default: empty configuration (constraint doesn't operate on data values)
	 *
	 * @see ConstraintSupportedValues
	 */
	ConstraintSupportedValues supportedValues() default @ConstraintSupportedValues();
}
