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

package io.evitadb.api.query.require;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintWithDefaults;
import io.evitadb.api.query.ReferenceConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;

/**
 * The `histogramStatistics` require constraint triggers computation of histogram statistics for one or more named
 * histogram indexes within a reference summary. It is used as a child of {@link ReferenceSummary} or
 * {@link ReferenceSummaryOfReference} to request histogram data alongside facet statistics.
 *
 * **Arguments**
 *
 * 1. `requestedBucketCount` (int, required) - the number of histogram buckets (columns) to produce. Typical values
 *    are 10-50, chosen to match the pixel width of the histogram widget in the UI.
 * 2. `behavior` ({@link HistogramBehavior}, optional, default `STANDARD`) - controls how bucket boundaries are
 *    positioned and whether empty buckets are suppressed:
 *    - `STANDARD`: exactly the requested number of equal-width buckets, even if some are empty.
 *    - `OPTIMIZED`: up to the requested count, but empty buckets are dropped for a denser result.
 *    - `EQUALIZED`: exactly the requested count with frequency-equalised boundaries.
 *    - `EQUALIZED_OPTIMIZED`: frequency-equalised boundaries with empty-bucket suppression combined.
 * 3. `indexNames` (String..., required, at least one) - names of the histogram indexes defined on the reference
 *    schema for which histograms should be computed. Each named index produces a separate histogram in the response.
 *
 * **Children**
 *
 * An optional {@link EntityFetch} child can be provided to define the richness of referenced entities returned
 * alongside the histogram statistics.
 *
 * The constraint is applicable only when at least one index name is provided; an instance with no index names
 * is not applicable and is ignored during query evaluation.
 *
 * **ConstraintWithDefaults behaviour**
 *
 * `STANDARD` is an implicit (default) argument and is omitted from the EvitaQL string representation.
 *
 * **Example**
 *
 * ```evitaql
 * referenceSummary(
 *     COUNTS,
 *     histogramStatistics(20, "priceIndex"),
 *     histogramStatistics(10, OPTIMIZED, "ratingIndex", "weightIndex")
 * )
 * ```
 *
 * @see ReferenceSummary
 * @see ReferenceSummaryOfReference
 * @see HistogramBehavior
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@ConstraintDefinition(
	name = "histogramStatistics",
	shortDescription = "The constraint triggers computation of histogram statistics for specified histogram indexes within a reference summary.",
	userDocsLink = "/documentation/query/requirements/facet#histogram-statistics",
	supportedIn = ConstraintDomain.REFERENCE
)
public class ReferenceHistogramStatistics extends AbstractRequireConstraintContainer
	implements ConstraintWithDefaults<RequireConstraint>,
	ReferenceConstraint<RequireConstraint>,
	SeparateEntityContentRequireContainer {
	@Serial private static final long serialVersionUID = 2748319054117829341L;
	private static final String CONSTRAINT_NAME = "histogramStatistics";

	private ReferenceHistogramStatistics(@Nonnull Serializable[] arguments, @Nonnull RequireConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(CONSTRAINT_NAME, arguments, children, additionalChildren);
		Assert.isTrue(
			isArgumentsNonNull() && getArguments().length > 2,
			"Histogram statistics requires at least bucket count, behavior, and one index name."
		);
		for (RequireConstraint child : children) {
			Assert.isTrue(
				child instanceof EntityFetch,
				"Histogram statistics accepts only `EntityFetch` constraint."
			);
		}
		Assert.isTrue(
			Arrays.stream(children).filter(EntityFetch.class::isInstance).count() <= 1,
			"Histogram statistics accepts only one `EntityFetch` constraint."
		);
	}

	@Creator
	public ReferenceHistogramStatistics(
		int requestedBucketCount,
		@Nullable HistogramBehavior behavior,
		@Nullable @Child EntityFetch entityFetch,
		@Nonnull String... indexNames
	) {
		super(
			CONSTRAINT_NAME,
			ArrayUtils.mergeArrays(
				new Serializable[]{
					requestedBucketCount,
					behavior == null ? HistogramBehavior.STANDARD : behavior
				},
				indexNames
			),
			entityFetch == null ? new RequireConstraint[0] : new RequireConstraint[]{entityFetch}
		);
	}

	public ReferenceHistogramStatistics(
		int requestedBucketCount,
		@Nullable HistogramBehavior behavior,
		@Nonnull String... indexNames
	) {
		super(
			CONSTRAINT_NAME,
			ArrayUtils.mergeArrays(
				new Serializable[]{
					requestedBucketCount,
					behavior == null ? HistogramBehavior.STANDARD : behavior
				},
				indexNames
			)
		);
	}

	public ReferenceHistogramStatistics(int requestedBucketCount, @Nonnull String... indexNames) {
		super(
			CONSTRAINT_NAME,
			ArrayUtils.mergeArrays(
				new Serializable[]{
					requestedBucketCount,
					HistogramBehavior.STANDARD
				},
				indexNames
			)
		);
	}

	/**
	 * Returns the number of optimal histogram buckets (columns) count that can be safely visualized to the user.
	 * Usually there is fixed size area dedicated to the histogram visualisation and there is no sense to return
	 * histogram with so many buckets (columns) that wouldn't be possible to render.
	 */
	public int getRequestedBucketCount() {
		return (Integer) getArguments()[0];
	}

	/**
	 * Returns the requested behavior of the histogram calculation.
	 *
	 * @return {@link HistogramBehavior#STANDARD} if not specified otherwise.
	 * @see HistogramBehavior
	 */
	@Nonnull
	public HistogramBehavior getBehavior() {
		return (HistogramBehavior) getArguments()[1];
	}

	/**
	 * Returns names of histogram indexes for which histogram statistics should be computed.
	 */
	@Nonnull
	public String[] getIndexNames() {
		return Arrays.stream(getArguments())
			.skip(2)
			.map(String.class::cast)
			.toArray(String[]::new);
	}

	/**
	 * Returns content requirements for referenced entities in the histogram statistics.
	 */
	@Nonnull
	public Optional<EntityFetch> getEntityFetch() {
		return Arrays.stream(getChildren())
			.filter(EntityFetch.class::isInstance)
			.map(EntityFetch.class::cast)
			.findFirst();
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length > 2;
	}

	@Nonnull
	@Override
	public Serializable[] getArgumentsExcludingDefaults() {
		return Arrays.stream(getArguments())
			.filter(it -> it != HistogramBehavior.STANDARD)
			.toArray(Serializable[]::new);
	}

	@Override
	public boolean isArgumentImplicit(@Nonnull Serializable serializable) {
		return serializable == HistogramBehavior.STANDARD;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		return new ReferenceHistogramStatistics(getArguments(), children, additionalChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new ReferenceHistogramStatistics(
			newArguments,
			getChildren(),
			getAdditionalChildren()
		);
	}
}
