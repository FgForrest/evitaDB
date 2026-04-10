/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Marker interface for constraints that have default (implicit) values for certain arguments. When a constraint
 * uses default values, those values are stored internally for correctness but can be omitted from the string
 * representation to produce cleaner, more readable EvitaQL syntax.
 *
 * ## Purpose and Design Rationale
 *
 * Many constraints have optional parameters with sensible defaults. For example:
 * - `attributeHistogram(20, STANDARD, "width")` — `STANDARD` is the default behavior mode
 * - `facetSummary(COUNTS)` — `COUNTS` is the default statistics depth
 * - `priceHistogram(20, STANDARD)` — `STANDARD` is the default behavior mode
 *
 * Including these default values in every query makes the syntax verbose and less readable. By implementing this
 * interface, constraints can:
 * 1. Store default values internally for correct execution
 * 2. Omit them from the string representation for readability
 * 3. Allow users to explicitly specify non-default values when needed
 *
 * This balances explicitness (all data stored) with readability (defaults hidden).
 *
 * ## String Representation Behavior
 *
 * {@link BaseConstraint#toString()} checks if a constraint implements this interface and calls
 * {@link #getArgumentsExcludingDefaults()} instead of {@link Constraint#getArguments()} when building the string
 * representation. This produces output like:
 * - With defaults: `attributeHistogram(20, STANDARD, "width")` (verbose)
 * - Without defaults: `attributeHistogram(20, "width")` (clean, assumes STANDARD)
 *
 * The full argument array (including defaults) is always available via {@link Constraint#getArguments()}, ensuring
 * correct execution even when defaults are hidden from the string form.
 *
 * ## Relationship to ConstraintWithSuffix
 *
 * Both `ConstraintWithDefaults` and {@link ConstraintWithSuffix} hide information from the string representation,
 * but for different reasons:
 * - `ConstraintWithDefaults`: Hides arguments that match conventional default values
 * - `ConstraintWithSuffix`: Hides arguments that are encoded in the constraint name suffix
 *
 * A constraint can implement both interfaces if it has both suffixed variants and default argument values.
 *
 * ## Example: AttributeHistogram
 *
 * ```java
 * @ConstraintDefinition(name = "histogram", ...)
 * public class AttributeHistogram extends ... implements ConstraintWithDefaults<RequireConstraint> {
 *
 *     @Creator
 *     public AttributeHistogram(int bucketCount, HistogramBehavior behavior, String... attributeNames) {
 *         super(ArrayUtils.mergeArrays(
 *             new Serializable[]{bucketCount, behavior == null ? STANDARD : behavior},
 *             attributeNames
 *         ));
 *     }
 *
 *     public AttributeHistogram(int bucketCount, String... attributeNames) {
 *         this(bucketCount, STANDARD, attributeNames); // Use default STANDARD behavior
 *     }
 *
 *     @Override
 *     public Serializable[] getArgumentsExcludingDefaults() {
 *         HistogramBehavior behavior = (HistogramBehavior) getArguments()[1];
 *         if (behavior == STANDARD) {
 *             // Omit the STANDARD behavior from output
 *             return ArrayUtils.mergeArrays(
 *                 new Serializable[]{getArguments()[0]}, // bucket count
 *                 Arrays.copyOfRange(getArguments(), 2, getArguments().length) // attribute names
 *             );
 *         }
 *         return getArguments(); // Non-default behavior, include all arguments
 *     }
 *
 *     @Override
 *     public boolean isArgumentImplicit(Serializable argument) {
 *         return argument instanceof HistogramBehavior && argument == STANDARD;
 *     }
 * }
 * ```
 *
 * ## Applicability
 *
 * This interface is appropriate when:
 * - The constraint has optional parameters with well-established default values
 * - The default values are used frequently in typical queries
 * - Including the default values in every query would make syntax overly verbose
 * - The default values are documented and understood by users
 *
 * It is NOT appropriate when:
 * - The parameter has no universally sensible default
 * - Omitting the parameter would make queries ambiguous or hard to understand
 * - The parameter is required and should always be explicit
 *
 * ## Implementation Responsibilities
 *
 * Implementations must:
 * 1. Store all arguments (including defaults) in the internal argument array for correct execution
 * 2. Implement {@link #getArgumentsExcludingDefaults()} to return a filtered array omitting default values
 * 3. Implement {@link #isArgumentImplicit(Serializable)} to identify whether a specific value is a default
 * 4. Document which arguments have defaults and what those defaults are
 * 5. Ensure that omitting defaults from the string representation does not create ambiguity
 *
 * @param <T> the concrete constraint type, enabling type-safe operations
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 * @see Constraint#getArguments()
 * @see BaseConstraint#toString()
 * @see ConstraintWithSuffix
 */
public interface ConstraintWithDefaults<T extends Constraint<T>> extends Constraint<T> {

	/**
	 * Returns the array of constraint arguments excluding those that match default (implicit) values. This method
	 * is called by {@link BaseConstraint#toString()} to produce a cleaner string representation by omitting
	 * redundant default values.
	 *
	 * The returned array should contain:
	 * - All required arguments (never omit these)
	 * - Optional arguments that have non-default values
	 * - Arguments should appear in the same relative order as {@link Constraint#getArguments()}
	 *
	 * The full argument array (including defaults) remains available via {@link Constraint#getArguments()} and is
	 * used during query execution. This method only affects string representation, not runtime behavior.
	 *
	 * Implementation note: The returned array may be a subset of {@link Constraint#getArguments()}, but it must
	 * maintain the relative order of remaining arguments to preserve semantic meaning.
	 *
	 * @return array of arguments excluding default values, never null but may be shorter than
	 *         {@link Constraint#getArguments()}
	 */
	@Nonnull
	Serializable[] getArgumentsExcludingDefaults();

	/**
	 * Determines whether a specific argument value is an implicit default that can be omitted from the string
	 * representation. This method is used by {@link #getArgumentsExcludingDefaults()} to decide which arguments
	 * to filter out.
	 *
	 * An argument is considered implicit when:
	 * - It matches a documented default value for its parameter position
	 * - Omitting it from the query would not change the constraint's behavior
	 * - Users would typically expect this value when no explicit value is provided
	 *
	 * Examples of implicit arguments:
	 * - `HistogramBehavior.STANDARD` in histogram constraints (most common behavior)
	 * - `FacetStatisticsDepth.COUNTS` in facet summary constraints (most common depth)
	 * - Default locale or currency values when a system-wide default is well-defined
	 *
	 * The method should return false for required arguments or when omission would create ambiguity.
	 *
	 * @param serializable the argument value to check
	 * @return true if this argument can be omitted from string representation as a default value, false otherwise
	 */
	boolean isArgumentImplicit(@Nonnull Serializable serializable);
}
