/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.ReferenceConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.Value;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `histogramHaving` constraint narrows a reference histogram to a specific `[from, to]` range (both ends
 * inclusive), optionally selecting a single group within a grouped reference. It is the first-class carrier for
 * slider-driven, per-histogram range selection on references such as `parameterValues`, where several independent
 * histograms (e.g. `height`, `weight`, `depth`) may share one physical reference.
 *
 * A single `histogramHaving` denotes one `(referenceName, histogramName, groupSelector, [from, to])` tuple. The
 * `histogramName` may be omitted when the reference hosts exactly one histogram. The `groupSelector` — a single
 * {@link EntityHaving} filter constraint over the referenced **group** entity — identifies the group slot for
 * grouped histograms; it is omitted for non-grouped slots.
 *
 * ## Usage inside `userFilter`
 *
 * Inside {@link UserFilter}, `histogramHaving` is both (a) applied to the filter formula like any other
 * `userFilter` child and (b) registered as a **range carrier** so the reference-histogram baseline cloner can peel
 * it out when computing the histogram's own `[min, max]` span. This solves the "sliders contracting under their own
 * handles" problem: moving one slider does not contract the `[min, max]` span of other sibling sliders.
 *
 * Two independent ranges on the same reference are expressed side by side:
 *
 * ```
 * userFilter(
 *     histogramHaving(
 *         "parameterValues", "basicUnitValue",
 *         50, 120,
 *         entityHaving(attributeEquals("code", "height"))
 *     ),
 *     histogramHaving(
 *         "parameterValues", "basicUnitValue",
 *         90, 140,
 *         entityHaving(attributeEquals("code", "weight"))
 *     )
 * )
 * ```
 *
 * ## Usage outside `userFilter`
 *
 * Outside {@link UserFilter}, `histogramHaving` behaves identically to the equivalent
 * `referenceHaving(...)` rewrite — it narrows the result set and does not participate in histogram baseline
 * relaxation.
 *
 * ## Validation
 *
 * - At least one of `from` / `to` must be non-null (both-null is a programmer error; no slider translates to that).
 * - When both bounds are non-null, `from.compareTo(to) <= 0` is required.
 * - `histogramName` may be empty string in user input; it is normalised to `null`.
 * - `groupSelector`, if present, must be a single child (arrays / lists are rejected).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ConstraintDefinition(
	name = "histogramHaving",
	shortDescription = "Narrows a reference histogram to a specific `[from, to]` range; inside `userFilter` it" +
		" also acts as a range carrier so the histogram's own baseline does not contract under the slider.",
	userDocsLink = "/documentation/query/filtering/references#histogram-having",
	supportedIn = ConstraintDomain.ENTITY
)
public class HistogramHaving extends AbstractFilterConstraintContainer
	implements ReferenceConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = -8251267731465192744L;

	/**
	 * Package-private constructor used internally by {@link #cloneWithArguments(Serializable[])} and
	 * {@link #getCopyWithNewChildren(FilterConstraint[], Constraint[])} to reconstruct the constraint verbatim
	 * without re-running validation.
	 *
	 * @param arguments the serialized arguments (classifier, histogram name, from, to)
	 * @param children  the child constraints (at most one group selector, or empty)
	 */
	private HistogramHaving(@Nonnull Serializable[] arguments, @Nonnull FilterConstraint... children) {
		super(arguments, children);
	}

	/**
	 * Creates a new {@link HistogramHaving} instance narrowing a reference histogram to the given range.
	 *
	 * @param referenceName   the reference name that hosts the target histogram (required)
	 * @param histogramName   the histogram name within the reference (nullable; empty string is normalised to null)
	 * @param from            the inclusive lower bound of the range (nullable if {@code to} is non-null)
	 * @param to              the inclusive upper bound of the range (nullable if {@code from} is non-null)
	 * @param groupSelector   optional single {@link EntityHaving} constraint selecting the group entity for grouped
	 *                        histograms; must be null for non-grouped slots
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when both bounds are null, when
	 *                                                        {@code from.compareTo(to) > 0}, or when
	 *                                                        {@code groupSelector} is not a single child
	 */
	@Creator
	public HistogramHaving(
		@Nonnull @Classifier String referenceName,
		@Nullable String histogramName,
		@Nullable @Value(requiresPlainType = true) Serializable from,
		@Nullable @Value(requiresPlainType = true) Serializable to,
		@Nullable @Child(
			domain = ConstraintDomain.ENTITY,
			allowed = { EntityHaving.class }
		) FilterConstraint groupSelector
	) {
		super(
			buildArguments(referenceName, histogramName, from, to),
			groupSelector == null ? FilterConstraint.EMPTY_ARRAY : new FilterConstraint[] { groupSelector }
		);
		validateBounds(from, to);
	}

	/**
	 * Returns the reference name this histogram range targets.
	 *
	 * @return the reference name, never null
	 */
	@Nonnull
	public String getReferenceName() {
		return (String) getArguments()[0];
	}

	/**
	 * Returns the histogram name within the reference, or null when the reference hosts a single histogram and the
	 * user omitted the name.
	 *
	 * @return the histogram name, or null when omitted
	 */
	@Nullable
	public String getHistogramName() {
		final Serializable value = getArguments()[1];
		return value == null ? null : (String) value;
	}

	/**
	 * Returns the inclusive lower bound of the range, or null when the bound is open-ended on the lower side.
	 *
	 * @return the lower bound, or null
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public <T extends Serializable> T getFrom() {
		return (T) getArguments()[2];
	}

	/**
	 * Returns the inclusive upper bound of the range, or null when the bound is open-ended on the upper side.
	 *
	 * @return the upper bound, or null
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public <T extends Serializable> T getTo() {
		return (T) getArguments()[3];
	}

	/**
	 * Returns the group selector — a single {@link EntityHaving} filter constraint identifying which group slot this
	 * `histogramHaving` targets, or null for the non-grouped slot.
	 *
	 * @return the group selector child, or null
	 */
	@Nullable
	public FilterConstraint getGroupSelector() {
		final FilterConstraint[] children = getChildren();
		return children.length == 0 ? null : children[0];
	}

	@Override
	public boolean isApplicable() {
		return getArguments().length == 4 && (getFrom() != null || getTo() != null);
	}

	@Override
	public boolean isNecessary() {
		return isApplicable();
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(
		@Nonnull FilterConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isPremiseValid(
			ArrayUtils.isEmpty(additionalChildren),
			"HistogramHaving doesn't accept additional children!"
		);
		Assert.isPremiseValid(
			children.length <= 1,
			"HistogramHaving accepts at most a single group-selector child!"
		);
		return new HistogramHaving(getArguments(), children);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new HistogramHaving(newArguments, getChildren());
	}

	/**
	 * Normalises and assembles the argument array: empty-string histogram name becomes null.
	 *
	 * @param referenceName the classifier
	 * @param histogramName the raw histogram name (may be null or empty)
	 * @param from          the lower bound
	 * @param to            the upper bound
	 * @return a four-element argument array with normalised histogram name
	 */
	@Nonnull
	private static Serializable[] buildArguments(
		@Nonnull String referenceName,
		@Nullable String histogramName,
		@Nullable Serializable from,
		@Nullable Serializable to
	) {
		final String normalisedHistogramName =
			histogramName == null || histogramName.isEmpty() ? null : histogramName;
		return new Serializable[] { referenceName, normalisedHistogramName, from, to };
	}

	/**
	 * Validates that the bounds satisfy the constraint's invariants: at least one bound is non-null, when both
	 * are non-null they share the same plain type, and they are ordered.
	 *
	 * Mismatched plain types are always a user mistake — a `from` of type `Long` paired with a `to` of type
	 * `BigDecimal` (or `Integer` paired with `String`) cannot be meaningfully ordered and always produces a runtime
	 * surprise downstream. Rejecting at construction with an actionable message naming both simple class names keeps
	 * the mistake visible at the EvitaQL build site instead of deep inside the query translator.
	 *
	 * @param from the lower bound (may be null)
	 * @param to   the upper bound (may be null)
	 */
	@SuppressWarnings("rawtypes")
	private static void validateBounds(@Nullable Serializable from, @Nullable Serializable to) {
		Assert.isTrue(
			from != null || to != null,
			"HistogramHaving requires at least one of `from` / `to` to be non-null!"
		);
		if (from != null && to != null) {
			// plain-type match is mandatory — mismatched types cannot be compared and always point at a user
			// mistake upstream; surface it here with a message naming both simple class names for traceability
			Assert.isTrue(
				from.getClass() == to.getClass(),
				() -> "HistogramHaving bounds `from` and `to` must share the same plain type (got " +
					from.getClass().getSimpleName() + " and " + to.getClass().getSimpleName() + ")!"
			);
			if (from instanceof Comparable fromCmp) {
				// identical types and both Comparable — enforce ordering
				//noinspection unchecked
				Assert.isTrue(
					fromCmp.compareTo(to) <= 0,
					"HistogramHaving requires `from` to be less than or equal to `to`!"
				);
			} else {
				// @Value(requiresPlainType = true) guarantees all plain types are Comparable; this branch
				// is unreachable under normal operation. Surface it as a programming error per the project's
				// defensive-design rule rather than silently accepting unordered bounds.
				throw new GenericEvitaInternalError(
					"HistogramHaving bound type `" + from.getClass().getSimpleName() +
						"` is not Comparable — plain types are always Comparable."
				);
			}
		}
	}
}
