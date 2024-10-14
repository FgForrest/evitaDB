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

package io.evitadb.core.query.sort.segment;

import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.Segments;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link FilterBy} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class SegmentsTranslator implements OrderingConstraintTranslator<Segments>, SelfTraversingTranslator {

	@Nonnull
	@Override
	public Stream<Sorter> createSorter(@Nonnull Segments segments, @Nonnull OrderByVisitor orderByVisitor) {
		// map each segment to a separate sorter
		return Arrays.stream(segments.getSegments())
			.map(segment -> {
				// first, crete sorter by the ordering constraint contents
				final OrderBy orderBy = segment.getOrderBy();
				final Sorter delegate = orderByVisitor.collectIsolatedSorter(
					() -> Arrays.stream(orderBy.getChildren()).forEach(it -> it.accept(orderByVisitor))
				);
				// optionally, create a filtering formula for the segment by the filtering constraint contents
				final Optional<Formula> filteringFormula = segment.getEntityHaving()
					.map(it -> orderByVisitor.getQueryContext().computeOnlyOnce(
						Collections.emptyList(),
						it,
						() -> FilterByVisitor.createFormulaForTheFilter(
							orderByVisitor.getQueryContext(),
							new FilterBy(it.getChildren()),
							orderByVisitor.getSchema().getName(),
							() -> "Result segment filtering: " + it
						)
					));
				// optionally, retrieve the limit for the segment
				final OptionalInt limit = segment.getLimit();
				// segment sorter will delegate sorting to internal sorter, but will limit the number of sorted records
				// and also exclude records that were already sorted by previous segments
				return new SegmentSorter(
					delegate,
					filteringFormula.orElse(null),
					limit.orElse(Integer.MAX_VALUE)
				);
			});
	}

}
