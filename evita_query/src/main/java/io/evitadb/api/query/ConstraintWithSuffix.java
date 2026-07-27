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

package io.evitadb.api.query;

import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Optional;

/**
 * Marker interface for constraints that support multiple creator variants, each identified by a distinct suffix.
 * Constraints implementing this interface can have multiple {@link Creator} annotations with different
 * {@link Creator#suffix()} values, allowing a single constraint class to represent several related operations
 * with different parameter combinations.
 *
 * ## Purpose and Design Rationale
 *
 * Some constraints logically represent a family of related operations that differ only in parameter types or
 * optional features. Rather than creating separate classes for each variant, a single constraint class can
 * implement multiple creators distinguished by suffixes. This approach:
 *
 * - Reduces code duplication when variants share most implementation logic
 * - Maintains a clear semantic relationship between related constraint forms
 * - Simplifies constraint descriptor registration and API schema generation
 * - Aligns EvitaQL syntax with Java API method naming conventions
 *
 * ## Naming Convention
 *
 * When a suffix is applied, the constraint's full name becomes: `baseName + capitalizedSuffix`
 *
 * Examples:
 * - Base name: `facetSummary`, suffix: `ofReference` → Full name: `facetSummaryOfReference`
 * - Base name: `referenceContent`, suffix: `all` → Full name: `referenceContentAll`
 * - Base name: `inRange`, suffix: `now` → Full name: `inRangeNow`
 *
 * The suffix is capitalized when appended to the base name, following camelCase conventions. The resulting full
 * name is used in:
 * - EvitaQL query parsing
 * - String representation via {@link Constraint#toString()}
 * - API schema definitions (GraphQL, REST, gRPC)
 *
 * ## Relationship to {@link Creator} Annotation
 *
 * Every constraint with multiple creators MUST:
 * 1. Implement `ConstraintWithSuffix` interface
 * 2. Have multiple {@link Creator} annotations, each with a unique `suffix` value
 * 3. Have exactly one creator with an empty suffix (the default variant)
 *
 * The {@link io.evitadb.api.query.descriptor.ConstraintProcessor} validates these requirements at startup and
 * builds a {@link io.evitadb.api.query.descriptor.ConstraintDescriptor} for each creator variant.
 *
 * ## Implicit Arguments
 *
 * Suffixes often encode information that would otherwise be an explicit argument. For example:
 * - `inRangeNow()` uses the suffix `now` to indicate that the current date/time should be used implicitly
 * - `facetSummaryOfReference("brand")` uses the suffix `ofReference` to indicate a specific reference is targeted
 *
 * The {@link #isArgumentImplicitForSuffix(int, Serializable)} method allows implementations to hide such
 * implicit arguments from the string representation, making the EvitaQL syntax cleaner:
 * - Without hiding: `inRangeNow("validity", <current-time>)` — confusing because "now" is redundant
 * - With hiding: `inRangeNow("validity")` — clean because the suffix makes the current-time usage obvious
 *
 * ## Example: AttributeInRange with "now" Suffix
 *
 * ```java
 * @ConstraintDefinition(name = "inRange", ...)
 * public class AttributeInRange extends ... implements ConstraintWithSuffix {
 *
 *     @Creator // Default variant: inRange(String attributeName, Number value)
 *     public AttributeInRange(String attributeName, Number value) { ... }
 *
 *     @Creator(suffix = "now") // Variant: inRangeNow(String attributeName)
 *     public AttributeInRange(String attributeName, OffsetDateTime implicitNow) { ... }
 *
 *     @Override
 *     public Optional<String> getSuffixIfApplied() {
 *         return getArguments().length == 2 && getArguments()[1] instanceof OffsetDateTime
 *             ? Optional.of("now")
 *             : Optional.empty();
 *     }
 *
 *     @Override
 *     public boolean isArgumentImplicitForSuffix(int position, Serializable argument) {
 *         return position == 1 && argument instanceof OffsetDateTime; // Hide the implicit date argument
 *     }
 * }
 * ```
 *
 * ## Example: FacetSummary vs FacetSummaryOfReference
 *
 * ```java
 * // Generic facet summary for all references
 * facetSummary(COUNTS, entityFetch(...))
 *
 * // Facet summary for a specific reference, overriding generic settings
 * facetSummaryOfReference("brand", COUNTS, entityFetch(...))
 * ```
 *
 * Both use the same base name `summary` but different suffixes distinguish them. The `ofReference` variant
 * includes a reference name argument that the base variant lacks.
 *
 * ## Implementation Responsibilities
 *
 * Implementations must:
 * 1. Return the appropriate suffix from {@link #getSuffixIfApplied()} based on constructor arguments
 * 2. Return `Optional.empty()` when no suffix applies (the default variant)
 * 3. Optionally override {@link #isArgumentImplicitForSuffix(int, Serializable)} to hide implicit arguments
 *
 * The {@link BaseConstraint#getName()} method automatically appends the suffix to the base name when present,
 * so implementations need not override `getName()`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 * @see Creator
 * @see Creator#suffix()
 * @see ConstraintContainerWithSuffix
 * @see io.evitadb.api.query.descriptor.ConstraintDescriptor
 */
public interface ConstraintWithSuffix {

	/**
	 * Returns the suffix that should be appended to the constraint's base name for this specific instance.
	 * The suffix identifies which {@link Creator} variant was used to construct this constraint.
	 *
	 * Implementations typically determine the suffix by inspecting constructor arguments:
	 * - If arguments match the pattern for a suffixed creator, return `Optional.of(suffix)`
	 * - If arguments match the default creator (no suffix), return `Optional.empty()`
	 *
	 * The returned suffix (if present) is automatically capitalized and appended to the base name by
	 * {@link BaseConstraint#getName()}, forming the full constraint name used in parsing and string representation.
	 *
	 * @return the suffix for this instance, or empty if the default (unsuffixed) variant applies
	 */
	@Nonnull
	Optional<String> getSuffixIfApplied();

	/**
	 * Determines whether a specific argument should be excluded from the string representation because it is
	 * implicit when the suffix is applied. This method is called by {@link BaseConstraint#toString()} for each
	 * argument to decide whether it should appear in the EvitaQL output.
	 *
	 * Suffixes often encode information that would otherwise be an explicit argument. For example:
	 * - `inRangeNow("validity")` uses the suffix to indicate "use current date/time", so the date argument is hidden
	 * - `facetSummaryOfReference("brand", ...)` uses the suffix to indicate a reference-specific summary, but the
	 *   reference name is still explicit
	 *
	 * The default implementation returns false, meaning all arguments are explicit. Override this method when
	 * the suffix makes certain arguments redundant in the string representation.
	 *
	 * @param argumentPosition zero-based position of the argument in {@link Constraint#getArguments()}
	 * @param argument the argument value to check
	 * @return true if this argument should be omitted from {@link BaseConstraint#toString()}, false otherwise
	 */
	default boolean isArgumentImplicitForSuffix(int argumentPosition, @Nonnull Serializable argument) {
		return false;
	}

}
