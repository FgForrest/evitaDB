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

package io.evitadb.api.query.order;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AdditionalChild;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The `segment` defines a single segment within the {@link Segments} container. Each segment specifies how a portion
 * of the filtered result should be sorted and optionally how many entities to extract from that sorted portion.
 *
 * A segment may contain:
 *
 * - an optional {@link EntityHaving} filter that selects which entities this segment applies to
 * - a mandatory {@link OrderBy} clause that defines the sorting order for this segment
 * - an optional {@link SegmentLimit} that limits how many entities are taken from this segment
 *
 * Entities matched and extracted by a segment are excluded from subsequent segments. If no limit is specified,
 * all matching entities are taken. If no filter is specified, all remaining entities are matched.
 *
 * Example:
 *
 * ```evitaql
 * orderBy(
 *    segments(
 *       segment(
 *          orderBy(
 *             attributeNatural("orderedQuantity", DESC)
 *          ),
 *          limit(3)
 *       ),
 *       segment(
 *          entityHaving(
 *             attributeEquals("new", true)
 *          ),
 *          orderBy(
 *             random()
 *          ),
 *          limit(2)
 *       ),
 *       segment(
 *          orderBy(
 *             ascending("code"),
 *             ascending("create")
 *          )
 *       )
 *    )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/segment)
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "segment",
	shortDescription = "Defines a single segment with its own sorting, optional filtering, and optional entity limit.",
	userDocsLink = "/documentation/query/ordering/segment",
	supportedIn = ConstraintDomain.SEGMENT
)
public class Segment extends AbstractOrderConstraintContainer implements GenericConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = -4576848889850648026L;

	private Segment(@Nonnull OrderConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(children, additionalChildren);
	}

	public Segment(
		@Nullable EntityHaving entityHaving,
		@Nonnull OrderBy orderBy,
		@Nullable SegmentLimit limit
	) {
		super(
			limit == null ? new OrderConstraint[] { orderBy } : new OrderConstraint[] { orderBy, limit },
			entityHaving == null ? new FilterConstraint[0] : new FilterConstraint[] { entityHaving }
		);
	}

	@Creator
	public static Segment _internalBuild(
		@Nullable @AdditionalChild(domain = ConstraintDomain.ENTITY) EntityHaving entityHaving,
		@Nonnull @Child(domain = ConstraintDomain.ENTITY) OrderConstraint[] orderBy,
		@Nullable @Child(domain = ConstraintDomain.SEGMENT) SegmentLimit limit
	) {
		return new Segment(entityHaving, new OrderBy(orderBy), limit);
	}

	public Segment(
		@Nonnull OrderBy orderBy,
		@Nullable SegmentLimit limit
	) {
		super(limit == null ? new OrderConstraint[] { orderBy } : new OrderConstraint[] { orderBy, limit });
	}

	public Segment(
		@Nullable EntityHaving entityHaving,
		@Nonnull OrderBy orderBy
	) {
		super(
			new OrderConstraint[] { orderBy },
			entityHaving == null ? new FilterConstraint[0] : new FilterConstraint[] { entityHaving }
		);
	}

	public Segment(@Nonnull OrderBy orderBy) {
		super(orderBy);
	}

	/**
	 * Retrieves the ordering clause of this segment.
	 *
	 * @return The first {@link OrderBy} instance found among the children of this segment.
	 */
	@Nonnull
	public OrderBy getOrderBy() {
		return Arrays.stream(getChildren())
			.filter(OrderBy.class::isInstance)
			.map(OrderBy.class::cast)
			.findFirst()
			.orElseThrow(() -> new EvitaInvalidUsageException("Segment must contain at least one orderBy clause!"));
	}

	/**
	 * Returns the limit of entities to be extracted from the sorted result for this segment.
	 *
	 * @return The first {@link SegmentLimit} value found among the children of this segment or empty if not present.
	 */
	@Nonnull
	public OptionalInt getLimit() {
		return Arrays.stream(getChildren())
			.filter(SegmentLimit.class::isInstance)
			.map(SegmentLimit.class::cast)
			.mapToInt(SegmentLimit::getLimit)
			.findFirst();
	}

	/**
	 * Retrieves the optional filtering constraint of this segment.
	 *
	 * @return The first {@link EntityHaving} instance found among the additional children of this segment.
	 */
	@Nonnull
	public Optional<EntityHaving> getEntityHaving() {
		return Arrays.stream(getAdditionalChildren())
			.filter(EntityHaving.class::isInstance)
			.map(EntityHaving.class::cast)
			.findFirst();
	}

	@Nonnull
	@Override
	public OrderConstraint getCopyWithNewChildren(
		@Nonnull OrderConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		return new Segment(children, additionalChildren);
	}

	@Override
	public boolean isNecessary() {
		return isApplicable();
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("Segment ordering constraint has no arguments!");
	}
}