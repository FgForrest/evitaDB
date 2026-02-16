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

import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.StringUtils;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Abstract base class providing core functionality for all constraint implementations in evitaDB's query system.
 * This class serves as the foundation for both leaf constraints ({@link ConstraintLeaf}) and container constraints
 * ({@link ConstraintContainer}), implementing the essential behaviors required by the {@link Constraint} interface.
 *
 * **Responsibilities**
 *
 * `BaseConstraint` handles:
 * - **Constraint naming**: Derives constraint names from class names following the convention that class `FooBar`
 *   becomes constraint name `fooBar`. Supports custom names via constructor parameter.
 * - **Suffix handling**: For constraints implementing {@link ConstraintWithSuffix}, automatically appends the suffix
 *   to the base name (e.g., `facetSummary` + `OfReference` = `facetSummaryOfReference`).
 * - **Argument storage**: Stores constraint arguments as serializable values, automatically converting unsupported
 *   types to evitaDB-supported types via {@link io.evitadb.dataType.EvitaDataTypes}.
 * - **String representation**: Provides standard `toString()` implementation following EvitaQL syntax:
 *   `constraintName(arg1,arg2,...)`.
 * - **Equality and hashing**: Uses constraint name and arguments for equality checks (via Lombok's `@EqualsAndHashCode`).
 *
 * **Argument Type Conversion**
 *
 * All arguments passed to `BaseConstraint` constructors are automatically validated and converted to evitaDB-supported
 * types if necessary. This ensures that constraints can be serialized for cross-network communication (gRPC, REST)
 * and stored in the database. Unsupported types are converted using {@link io.evitadb.dataType.EvitaDataTypes#toSupportedType(Object)}.
 * The conversion is performed lazily — only when at least one argument requires conversion — to avoid unnecessary
 * array allocations in the common case where all arguments are already supported types.
 *
 * **Integration with Suffix and Default Systems**
 *
 * This class cooperates with two optional extension interfaces:
 * - {@link ConstraintWithSuffix}: Constraints implementing this interface can have multiple named variants (e.g.,
 *   `facetSummary()` vs `facetSummaryOfReference("brand")`). The suffix is appended to the base name in `getName()`,
 *   and implicit arguments/children can be hidden from string representation.
 * - {@link ConstraintWithDefaults}: Constraints implementing this interface can omit default values from their string
 *   representation while still storing them internally. The `getSerializedArguments()` method uses
 *   `getArgumentsExcludingDefaults()` to filter out default values.
 *
 * **Immutability**
 *
 * Instances of `BaseConstraint` and its subclasses are immutable. All fields are final, and the arguments array is
 * defensively copied during construction. Modifications to constraints (e.g., via `cloneWithArguments()`) always
 * produce new instances.
 *
 * **Usage Examples**
 *
 * Most developers interact with concrete constraint implementations rather than this base class directly:
 *
 * ```java
 * // Leaf constraint: attributeEquals("code", "PHONE-123")
 * // Internally: new AttributeEquals("code", "PHONE-123") extends ConstraintLeaf -> BaseConstraint
 *
 * // Container constraint: and(attributeEquals("code", "PHONE-123"), entityPrimaryKeyInSet(1, 2, 3))
 * // Internally: new And(...) extends ConstraintContainer -> BaseConstraint
 * ```
 *
 * Subclasses typically extend one of the intermediate abstract classes:
 * - {@link io.evitadb.api.query.filter.AbstractFilterConstraintLeaf}
 * - {@link io.evitadb.api.query.filter.AbstractFilterConstraintContainer}
 * - {@link io.evitadb.api.query.order.AbstractOrderConstraintLeaf}
 * - {@link io.evitadb.api.query.order.AbstractOrderConstraintContainer}
 * - {@link io.evitadb.api.query.require.AbstractRequireConstraintLeaf}
 * - {@link io.evitadb.api.query.require.AbstractRequireConstraintContainer}
 * - {@link io.evitadb.api.query.head.AbstractHeadConstraintLeaf}
 * - {@link io.evitadb.api.query.head.AbstractHeadConstraintContainer}
 *
 * **Thread Safety**
 *
 * Instances are immutable and thread-safe. The same constraint instance can be safely shared across multiple threads
 * and reused in multiple queries.
 *
 * @param <T> the type of constraint this base class implements (FilterConstraint, OrderConstraint, etc.)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
@EqualsAndHashCode(of = {"name", "arguments"})
public abstract class BaseConstraint<T extends Constraint<T>> implements Constraint<T> {
	@Serial private static final long serialVersionUID = 2216675116416057520L;
	/**
	 * The base name of this constraint, derived from the class name or explicitly set via constructor.
	 * For constraints implementing {@link ConstraintWithSuffix}, the final name returned by {@link #getName()}
	 * will be this base name plus the suffix.
	 */
	private final String name;
	/**
	 * Arguments passed to this constraint. All arguments are automatically converted to evitaDB-supported types
	 * via {@link io.evitadb.dataType.EvitaDataTypes} during construction. The array is immutable after creation.
	 */
	private final Serializable[] arguments;

	/**
	 * Converts a serializable value to its string representation for use in EvitaQL syntax.
	 * This method is used when generating the `toString()` output of constraints.
	 *
	 * Formatting rules:
	 * - `null` values are represented as `<NULL>`
	 * - Other values are formatted via {@link io.evitadb.dataType.EvitaDataTypes#formatValue(Serializable)},
	 *   which handles proper quoting for strings, formatting for numbers, dates, etc.
	 *
	 * @param value the value to convert; may be null
	 * @return string representation of the value suitable for EvitaQL syntax
	 */
	@Nonnull
	public static String convertToString(@Nullable Serializable value) {
		if (value == null) {
			return "<NULL>";
		}
		return EvitaDataTypes.formatValue(value);
	}

	/**
	 * Constructs a constraint with the default name (derived from the class name) and the specified arguments.
	 * The constraint name will be the uncapitalized simple class name (e.g., `AttributeEquals` becomes `attributeEquals`).
	 *
	 * @param arguments the constraint arguments; automatically converted to evitaDB-supported types if needed
	 */
	protected BaseConstraint(@Nonnull Serializable... arguments) {
		super();
		this.name = getDefaultName();
		this.arguments = convertArgumentsIfNeeded(arguments);
	}

	/**
	 * Derives the default constraint name from the implementing class's simple name by uncapitalizing it.
	 * For example, `AttributeEquals` becomes `attributeEquals`, `FilterBy` becomes `filterBy`.
	 *
	 * This convention ensures that constraint names in EvitaQL syntax match Java naming conventions
	 * (camelCase for method-like operations).
	 *
	 * @return the default constraint name derived from the class name
	 */
	@Nonnull
	protected String getDefaultName() {
		return Objects.requireNonNull(StringUtils.uncapitalize(this.getClass().getSimpleName()));
	}

	/**
	 * Constructs a constraint with an explicit name and the specified arguments.
	 * This constructor is used when the constraint name needs to override the default class-derived name,
	 * typically for constraints that support multiple named variants via {@link ConstraintWithSuffix}.
	 *
	 * @param name the explicit constraint name; if null, the default name (derived from class name) is used
	 * @param arguments the constraint arguments; automatically converted to evitaDB-supported types if needed
	 */
	protected BaseConstraint(@Nullable String name, @Nonnull Serializable... arguments) {
		this.name = name != null ? name : getDefaultName();
		this.arguments = convertArgumentsIfNeeded(arguments);
	}

	/**
	 * Checks whether any argument needs conversion to a supported type and if so, converts all arguments.
	 * Avoids creating a new array if all arguments are already of supported types.
	 *
	 * This method performs a two-pass optimization:
	 * 1. First pass: Check if any argument is unsupported (avoids allocation in common case)
	 * 2. Second pass: Only if conversion is needed, allocate new array and convert all arguments
	 *
	 * evitaDB supports a limited set of types for query arguments to ensure serializability across network
	 * protocols (gRPC, REST) and storage formats. Unsupported types are automatically converted to the nearest
	 * supported equivalent (e.g., `java.sql.Date` to `java.time.LocalDate`).
	 *
	 * @param arguments the raw arguments passed to the constraint constructor
	 * @return either the original array (if all types are supported) or a new array with converted values
	 */
	@Nonnull
	private static Serializable[] convertArgumentsIfNeeded(@Nonnull Serializable[] arguments) {
		// first pass: check if conversion is needed (avoids allocation in common case)
		boolean needsConversion = false;
		for (final Serializable argument : arguments) {
			if (argument != null && !EvitaDataTypes.isSupportedTypeOrItsArrayOrEnum(argument.getClass())) {
				needsConversion = true;
				break;
			}
		}
		// second pass: convert all arguments only if needed
		if (needsConversion) {
			final Serializable[] converted = new Serializable[arguments.length];
			for (int i = 0; i < arguments.length; i++) {
				converted[i] = EvitaDataTypes.toSupportedType(arguments[i]);
			}
			return converted;
		}
		return arguments;
	}

	/**
	 * Returns the complete name of this constraint, including any suffix if applicable.
	 *
	 * The name is constructed as follows:
	 * 1. Base name: Either the default (derived from class name) or an explicitly set name
	 * 2. Suffix (optional): If this constraint implements {@link ConstraintWithSuffix}, the suffix is capitalized
	 *    and appended to the base name (e.g., `facetSummary` + `ofReference` = `facetSummaryOfReference`)
	 *
	 * The name is always derived from the class name to simplify searching and mapping between EvitaQL syntax
	 * and Java constraint classes.
	 *
	 * @return the full constraint name including suffix if applicable
	 */
	@Nonnull
	@Override
	public String getName() {
		return this.name + (this instanceof ConstraintWithSuffix cws ?
			cws.getSuffixIfApplied().map(StringUtils::capitalize).orElse("") : "");
	}

	/**
	 * Returns the arguments of this constraint.
	 *
	 * All arguments are guaranteed to be evitaDB-supported types (converted during construction if necessary).
	 * The returned array is the internal storage array — callers should not modify it.
	 *
	 * @return the constraint arguments (never null, but may be empty array)
	 */
	@Nonnull
	@Override
	public Serializable[] getArguments() {
		return this.arguments;
	}

	/**
	 * Returns the EvitaQL string representation of this constraint.
	 *
	 * The format is: `constraintName(arg1,arg2,...,argN)` where:
	 * - `constraintName` is obtained from {@link #getName()} (includes suffix if applicable)
	 * - Arguments are serialized via {@link #getSerializedArguments()}, which:
	 *   - Excludes default values if this implements {@link ConstraintWithDefaults}
	 *   - Excludes implicit arguments if this implements {@link ConstraintWithSuffix}
	 * - Arguments and child constraints (for containers) are comma-separated
	 *
	 * This string representation is designed to be parseable by the EvitaQL parser, enabling round-trip
	 * conversion between constraint objects and query strings.
	 *
	 * @return EvitaQL string representation of this constraint
	 */
	@Nonnull
	@Override
	public String toString() {
		return getName() +
			ARG_OPENING +
			String.join(",", getSerializedArguments()) +
			ARG_CLOSING;
	}

	/**
	 * Serializes constraint arguments into individual strings for EvitaQL representation.
	 *
	 * This method applies two filtering strategies to produce a clean, readable output:
	 * 1. **Default value filtering**: If this constraint implements {@link ConstraintWithDefaults}, default values
	 *    are excluded via `getArgumentsExcludingDefaults()`.
	 * 2. **Implicit argument filtering**: If this constraint implements {@link ConstraintWithSuffix}, arguments that
	 *    are implicit for the current suffix are excluded via `isArgumentImplicitForSuffix()`.
	 *
	 * For example, `facetSummaryOfReference("brand")` has an implicit `FacetStatisticsDepth.COUNTS` argument that
	 * is not shown in the string representation.
	 *
	 * @return list of string representations of non-default, non-implicit arguments
	 */
	@Nonnull
	protected List<String> getSerializedArguments() {
		// use filtered arguments if this constraint supports defaults
		final Serializable[] arguments = (this instanceof ConstraintWithDefaults<?> constraintWithDefaults) ? constraintWithDefaults.getArgumentsExcludingDefaults() : getArguments();
		final List<String> serializedArguments = new ArrayList<>(arguments.length);
		for (int i = 0; i < arguments.length; i++) {
			final Serializable argument = arguments[i];
			// skip implicit arguments for suffixed constraints
			if (!(this instanceof ConstraintWithSuffix cws) || !cws.isArgumentImplicitForSuffix(i, argument)) {
				serializedArguments.add(BaseConstraint.convertToString(argument));
			}
		}
		return serializedArguments;
	}

	/**
	 * Checks if all arguments are non-null.
	 *
	 * This method is commonly used in {@link Constraint#isApplicable()} implementations to validate that
	 * the constraint has all required arguments present. Many constraint types require all arguments to be
	 * non-null to function correctly.
	 *
	 * @return true if all arguments are non-null, false if any argument is null
	 */
	protected boolean isArgumentsNonNull() {
		for (Serializable argument : getArguments()) {
			if (argument == null) {
				return false;
			}
		}
		return true;
	}

}
