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

package io.evitadb.api.query;

import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;

/**
 * Specialized marker interface for {@link ConstraintContainer} implementations that support multiple creator variants
 * distinguished by suffixes. This extends {@link ConstraintWithSuffix} with additional methods for hiding child
 * constraints that are implicit when a suffix is applied.
 *
 * ## Purpose
 *
 * Container constraints with suffixes often encode structural information (presence or type of child constraints)
 * in the suffix itself. For example:
 * - `referenceContent("brand")` — basic reference content without child constraints
 * - `referenceContentWithAttributes("brand", attributeContent(...))` — includes attribute content, signaled by suffix
 *
 * When the suffix indicates the presence of a specific child type, that child may be considered "implicit" in the
 * string representation to avoid redundancy. This interface provides methods to identify such implicit children.
 *
 * ## Difference from ConstraintWithSuffix
 *
 * While {@link ConstraintWithSuffix} handles implicit *arguments*, this interface handles implicit *child constraints*:
 * - `ConstraintWithSuffix.isArgumentImplicitForSuffix()`: Hides scalar arguments encoded in the suffix
 * - `ConstraintContainerWithSuffix.isChildImplicitForSuffix()`: Hides regular child constraints encoded in the suffix
 * - `ConstraintContainerWithSuffix.isAdditionalChildImplicitForSuffix()`: Hides additional child constraints encoded
 *   in the suffix
 *
 * ## Child vs Additional Child
 *
 * {@link ConstraintContainer} distinguishes two kinds of children:
 * - **Children**: Regular child constraints of the same type as the container's primary child type
 * - **Additional children**: Child constraints of different types, used for cross-type composition
 *
 * This interface provides separate methods for hiding each kind because suffixes may encode the presence of one,
 * both, or neither.
 *
 * ## Example: ReferenceContent with Multiple Variants
 *
 * ```java
 * @ConstraintDefinition(name = "referenceContent", ...)
 * public class ReferenceContent extends ... implements ConstraintContainerWithSuffix {
 *
 *     @Creator // Default: referenceContent(String referenceName)
 *     public ReferenceContent(String referenceName) { ... }
 *
 *     @Creator(suffix = "withAttributes")
 *     // Variant: referenceContentWithAttributes(String referenceName, AttributeContent attributeContent)
 *     public ReferenceContent(String referenceName, AttributeContent attributeContent) { ... }
 *
 *     @Creator(suffix = "all")
 *     // Variant: referenceContentAll(ReferenceContent... children)
 *     public ReferenceContent(ReferenceContent... children) { ... }
 *
 *     @Override
 *     public Optional<String> getSuffixIfApplied() {
 *         // Return appropriate suffix based on arguments and children
 *     }
 *
 *     @Override
 *     public boolean isAdditionalChildImplicitForSuffix(Constraint<?> child) {
 *         // Hide AttributeContent when suffix is "withAttributes" because it's redundant
 *         return "withAttributes".equals(getSuffixIfApplied().orElse(null))
 *             && child instanceof AttributeContent;
 *     }
 * }
 * ```
 *
 * In the example above, `referenceContentWithAttributes("brand", attributeContent(...))` could be rendered without
 * the `attributeContent(...)` child in the string representation because the suffix already indicates its presence.
 *
 * ## String Representation Behavior
 *
 * {@link ConstraintContainer#toString()} consults these methods to determine which children to include:
 * 1. For each child, call {@link #isChildImplicitForSuffix(Constraint)} — if true, skip it
 * 2. For each additional child, call {@link #isAdditionalChildImplicitForSuffix(Constraint)} — if true, skip it
 * 3. Include all non-implicit children in the output
 *
 * This produces cleaner EvitaQL syntax by eliminating redundancy when the suffix conveys structural information.
 *
 * ## Usage with Explicit Child Arrays
 *
 * The {@link ConstraintContainer#getExplicitChildren()} and {@link ConstraintContainer#getExplicitAdditionalChildren()}
 * methods use these implicit checks to return arrays excluding implicit children. This is useful for:
 * - Serialization: Only serialize explicit children
 * - Visitor traversal: Some visitors may want to skip implicit children
 * - Testing: Verify expected explicit structure without implementation details
 *
 * ## Implementation Responsibilities
 *
 * Implementations must:
 * 1. Implement {@link ConstraintWithSuffix#getSuffixIfApplied()} to identify which suffix applies
 * 2. Optionally override {@link #isChildImplicitForSuffix(Constraint)} to hide regular children
 * 3. Optionally override {@link #isAdditionalChildImplicitForSuffix(Constraint)} to hide additional children
 * 4. Ensure that implicit children are still accessible via {@link ConstraintContainer#getChildren()} and
 *    {@link ConstraintContainer#getAdditionalChildren()} — they are only hidden from string representation
 *
 * The default implementations return false, meaning all children are explicit unless overridden.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 * @see ConstraintWithSuffix
 * @see ConstraintContainer
 * @see Creator#suffix()
 * @see ConstraintContainer#getExplicitChildren()
 * @see ConstraintContainer#getExplicitAdditionalChildren()
 */
public interface ConstraintContainerWithSuffix extends ConstraintWithSuffix {

	/**
	 * Determines whether a specific regular child constraint should be excluded from the string representation
	 * because it is implicit when the suffix is applied. This method is called by {@link ConstraintContainer#toString()}
	 * for each child to decide whether it should appear in the EvitaQL output.
	 *
	 * A child is considered implicit when the suffix already conveys its presence or type, making it redundant to
	 * include in the string representation. However, implicit children remain accessible via
	 * {@link ConstraintContainer#getChildren()} — they are only hidden from the string form.
	 *
	 * The default implementation returns false, meaning all children are explicit. Override this method when the
	 * suffix encodes the presence of specific regular children.
	 *
	 * @param child the child constraint to check
	 * @return true if this child should be omitted from {@link ConstraintContainer#toString()}, false otherwise
	 */
	default boolean isChildImplicitForSuffix(@Nonnull Constraint<?> child) {
		return false;
	}

	/**
	 * Determines whether a specific additional child constraint should be excluded from the string representation
	 * because it is implicit when the suffix is applied. This method is called by {@link ConstraintContainer#toString()}
	 * for each additional child to decide whether it should appear in the EvitaQL output.
	 *
	 * Additional children are child constraints of a different type than the container's primary child type (see
	 * {@link ConstraintContainer#getAdditionalChildren()}). A suffix may encode the presence of specific additional
	 * child types, making them implicit in the string representation.
	 *
	 * For example, a container with suffix `withFilter` might always include a `FilterBy` additional child, making
	 * it redundant to show in the string form when the suffix is present.
	 *
	 * The default implementation returns false, meaning all additional children are explicit. Override this method
	 * when the suffix encodes the presence of specific additional children.
	 *
	 * @param child the additional child constraint to check
	 * @return true if this additional child should be omitted from {@link ConstraintContainer#toString()}, false otherwise
	 */
	default boolean isAdditionalChildImplicitForSuffix(@Nonnull Constraint<?> child) {
		return false;
	}

}
