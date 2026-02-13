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

import io.evitadb.api.query.ConstraintWithSuffix;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a constructor or static factory method as the creator for instantiating a constraint from external API calls
 * or programmatic query building. Every constraint class annotated with `{@link ConstraintDefinition}` must have at
 * least one creator.
 *
 * **Multiple Creators and Suffixes**
 *
 * A single constraint class can have multiple creators, each representing a different variant of the constraint with
 * different parameter combinations. Each creator must have a unique `{@link #suffix()}`. The creator without a suffix
 * (empty string) is considered the default variant.
 *
 * When a suffix is specified:
 * - The full constraint name becomes `{baseName}{CapitalizedSuffix}`
 * - The constraint class must implement `{@link ConstraintWithSuffix}` to enable suffix-based parsing
 * - The combination of base name and suffix must be unique across all constraints of the same type and property type
 *
 * Example with multiple creators:
 * ```
 * @ConstraintDefinition(name = "referenceContent", ...)
 * public class ReferenceContent extends ... implements ConstraintWithSuffix {
 *
 * @Creator // Default variant: referenceContent(String referenceName)
 * public ReferenceContent(@Classifier String referenceName) { ... }
 *
 * @Creator(suffix = "withAttributes")  // Variant: referenceContentWithAttributes(String referenceName, ...)
 * public ReferenceContent(@Classifier String referenceName, @Child AttributeContent attributeContent) { ... }
 *
 * @Creator(suffix = "all")  // Variant: referenceContentAll()
 * public ReferenceContent(@Child(uniqueChildren = true) ReferenceContent... references) { ... }
 * }
 * ```
 *
 * **Parameter Requirements**
 *
 * All parameters of a creator method/constructor must be annotated with one of:
 * - `{@link Classifier}` - identifies the target (attribute name, reference name, etc.)
 * - `{@link Value}` - primitive or serializable value parameter
 * - `{@link Child}` - nested constraint(s) of the same type
 * - `{@link AdditionalChild}` - nested constraint(s) of a different type
 *
 * **Static Factory Methods**
 *
 * Static factory methods can be used instead of constructors. They must:
 * - Be annotated with `@Creator`
 * - Have `static` modifier
 * - Return an instance of the constraint class
 * - Follow the same parameter annotation requirements
 *
 * **Implicit Classifiers**
 *
 * Some constraints require a classifier but cannot accept it as a parameter (e.g., the classifier is fixed or
 * determined by context). Use `{@link #silentImplicitClassifier()}` or `{@link #implicitClassifier()}` to handle
 * these cases.
 *
 * Only one of the following can be specified:
 * - A `{@link Classifier}` parameter
 * - `{@link #silentImplicitClassifier()} = true`
 * - `{@link #implicitClassifier()}` with a non-empty value
 *
 * **Processing Pipeline**
 *
 * At startup, `{@link io.evitadb.api.query.descriptor.ConstraintProcessor}` scans all creators:
 * 1. Validates that all parameters have required annotations
 * 2. Builds a `{@link io.evitadb.api.query.descriptor.ConstraintCreator}` descriptor for each creator
 * 3. Combines the creator with its constraint definition to form a
 * `{@link io.evitadb.api.query.descriptor.ConstraintDescriptor}`
 * 4. Indexes descriptors for fast lookup during query parsing and API schema generation
 *
 * **Related Classes**
 *
 * - `{@link ConstraintWithSuffix}` - Interface for constraints with multiple creator variants
 * - `{@link io.evitadb.api.query.descriptor.ConstraintCreator}` - Runtime descriptor for a creator
 * - `{@link io.evitadb.api.query.descriptor.ConstraintProcessor}` - Processes `@Creator` annotations at startup
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @see ConstraintDefinition
 * @see Classifier
 * @see Value
 * @see Child
 * @see AdditionalChild
 * @see ConstraintWithSuffix
 */
@Target({ElementType.CONSTRUCTOR, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Creator {

	/**
	 * Unique identifier for this creator variant when a constraint class has multiple creators. The suffix must be
	 * unique across all creators of the same constraint class.
	 *
	 * **Naming Convention:**
	 * - Must be in camelCase format
	 * - When combined with the constraint's base name, the first letter is capitalized
	 * - Empty string (default) indicates the default creator variant
	 *
	 * **Full Name Formation:**
	 * - Base name: `"referenceContent"`, suffix: `""` → full name: `"referenceContent"`
	 * - Base name: `"referenceContent"`, suffix: `"withAttributes"` → full name: `"referenceContentWithAttributes"`
	 * - Base name: `"facetSummary"`, suffix: `"ofReference"` → full name: `"facetSummaryOfReference"`
	 *
	 * **Uniqueness Constraint:**
	 *
	 * The combination of constraint base name (`{@link ConstraintDefinition#name()}`) and suffix must be unique
	 * across all constraints with the same `{@link io.evitadb.api.query.descriptor.ConstraintType}` and
	 * `{@link io.evitadb.api.query.descriptor.ConstraintPropertyType}`.
	 *
	 * **Interface Requirement:**
	 *
	 * When a suffix is specified (non-empty), the constraint class must implement `{@link ConstraintWithSuffix}` to
	 * enable suffix-based parsing and serialization.
	 *
	 * Default: `""` (default creator variant, no suffix)
	 *
	 * @see ConstraintWithSuffix
	 */
	String suffix() default "";

	/**
	 * Indicates that this creator has an implicit classifier that should not appear in the constraint's serialized
	 * form (EvitaQL, GraphQL, REST). Used when a classifier is required internally but is determined by context
	 * rather than user input.
	 *
	 * **Use Case:**
	 *
	 * Silent implicit classifiers are used when the constraint's behavior requires a classifier, but the classifier
	 * is inferred from context rather than being explicitly provided. For example, a constraint that always operates
	 * on a specific attribute determined by the query structure.
	 *
	 * **Mutual Exclusivity:**
	 *
	 * Only one of the following can be specified for a creator:
	 * - A parameter annotated with `{@link Classifier}`
	 * - `silentImplicitClassifier = true`
	 * - A non-empty `{@link #implicitClassifier()}` value
	 *
	 * Specifying multiple classifier mechanisms will result in a validation error at startup.
	 *
	 * Default: `false` (no silent implicit classifier)
	 */
	boolean silentImplicitClassifier() default false;

	/**
	 * Fixed classifier value when the constraint always operates on a specific, predetermined target that cannot be
	 * changed by the user and must appear in the constraint's serialized form.
	 *
	 * **Use Case:**
	 *
	 * Fixed implicit classifiers are used when a constraint always targets a specific named element (attribute,
	 * reference, etc.) that is hard-coded into the constraint's semantics. The classifier appears in serialized
	 * queries but is not parameterized.
	 *
	 * Example: A constraint that always filters by a `"locale"` attribute might use
	 * `implicitClassifier = "locale"`.
	 *
	 * **Mutual Exclusivity:**
	 *
	 * Only one of the following can be specified for a creator:
	 * - A parameter annotated with `{@link Classifier}`
	 * - `{@link #silentImplicitClassifier()} = true`
	 * - A non-empty `implicitClassifier` value
	 *
	 * Specifying multiple classifier mechanisms will result in a validation error at startup.
	 *
	 * Default: `""` (no fixed implicit classifier)
	 */
	String implicitClassifier() default "";
}
