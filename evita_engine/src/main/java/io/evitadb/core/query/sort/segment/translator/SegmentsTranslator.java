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

package io.evitadb.core.query.sort.segment.translator;

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.Segment;
import io.evitadb.api.query.order.Segments;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.segment.sorter.SegmentSorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link Segments} to a chain of
 * {@link SegmentSorter} instances, one per {@link Segment}.
 *
 * A segment consists only of the entities its own ordering can sort: an entity lacking the data the ordering sorts
 * by - an attribute, a reference, a price - is excluded from the segment and left to the following segments, or to
 * the entities appended after all of them. That is why the sorters of a segment's ordering are collected by
 * {@link OrderByVisitor#collectSegmentSorters(Runnable)}, without the default {@link NoSorter}, which would otherwise
 * place every such entity at the end of the segment. An explicit ascending primary key order still sorts every entity
 * of its scope - every entity carries a primary key - because within a segment its translator registers a sorter of
 * its own at the declared position (see
 * {@link io.evitadb.core.query.sort.primaryKey.translator.EntityPrimaryKeyNaturalTranslator}).
 *
 * Nested segments - a {@link Segments} container anywhere in the ordering of a segment - are rejected with
 * {@link EvitaInvalidUsageException} when the query is planned.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class SegmentsTranslator implements OrderingConstraintTranslator<Segments>, SelfTraversingTranslator {

	@Nonnull
	@Override
	public Stream<Sorter> createSorter(@Nonnull Segments segments, @Nonnull OrderByVisitor orderByVisitor) {
		// a segmentation of one segment has no defined meaning - the check runs here rather than in the constraint
		// constructors, so that a stored query of this shape can still be deserialized (e.g. in a traffic recording)
		if (orderByVisitor.isWithinSegment()) {
			throw new EvitaInvalidUsageException(
				"Nested `segments` are not supported - the ordering of a `segment` must not contain another " +
					"`segments` container: " + segments
			);
		}
		// map each segment to a separate sorter
		return Arrays.stream(segments.getSegments())
			.map(segment -> {
				// first, create sorter by the ordering constraint contents - the entities they cannot sort fall out of
				// the segment, unless the ordering asks for the ascending primary key order, which sorts them all
				final OrderBy orderBy = segment.getOrderBy();
				final OrderConstraint[] orderConstraints = orderBy.getChildren();
				final List<Sorter> embeddedSorters = orderByVisitor.collectSegmentSorters(
					() -> {
						for (OrderConstraint orderConstraint : orderConstraints) {
							orderConstraint.accept(orderByVisitor);
						}
					}
				);
				final QueryPlanningContext queryContext = orderByVisitor.getQueryContext();
				// optionally, create a filtering formula for the segment by the filtering constraint contents
				final Optional<Formula> filteringFormula = segment.getEntityHaving()
					.map(it -> queryContext.computeOnlyOnce(
						Collections.emptyList(),
						it,
						() -> FilterByVisitor.createFormulaForTheFilter(
							queryContext,
							orderByVisitor.getProcessingScope().getScopes(),
							new FilterBy(it.getChildren()),
							queryContext.getFilterBy(),
							orderByVisitor.getSchema().getName(),
							() -> "Result segment filtering: " + it
						)
					));
				// optionally, retrieve the limit for the segment
				final OptionalInt limit = segment.getLimit();
				// segment sorter will delegate sorting to internal sorter, but will limit the number of sorted records
				// and also exclude records that were already sorted by previous segments
				return new SegmentSorter(
					embeddedSorters,
					filteringFormula.orElse(null),
					limit.orElse(Integer.MAX_VALUE)
				);
			});
	}

}
