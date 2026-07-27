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

package io.evitadb.store.query.serializer.require;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.ReferenceHistogramStatistics;
import io.evitadb.api.query.require.ReferenceSummary;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * This {@link Serializer} implementation reads/writes {@link ReferenceSummary} from/to binary format.
 * The wire format follows the constraint's logical shape:
 *
 * 1. statistics depth ({@link FacetStatisticsDepth}, non-null)
 * 2. additional children: filterBy, filterGroupBy, orderBy, orderGroupBy (each nullable)
 * 3. fetch children: entityFetch, entityGroupFetch (each nullable)
 * 4. histogram statistics: count + N {@link ReferenceHistogramStatistics} children
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class ReferenceSummarySerializer extends Serializer<ReferenceSummary> {

	@Override
	public void write(Kryo kryo, Output output, ReferenceSummary object) {
		kryo.writeObject(output, object.getStatisticsDepth());

		kryo.writeObjectOrNull(output, object.getFilterBy().orElse(null), FilterBy.class);
		kryo.writeObjectOrNull(output, object.getFilterGroupBy().orElse(null), FilterGroupBy.class);
		kryo.writeObjectOrNull(output, object.getOrderBy().orElse(null), OrderBy.class);
		kryo.writeObjectOrNull(output, object.getOrderGroupBy().orElse(null), OrderGroupBy.class);

		kryo.writeObjectOrNull(output, object.getReferenceEntityRequirement().orElse(null), EntityFetch.class);
		kryo.writeObjectOrNull(output, object.getGroupEntityRequirement().orElse(null), EntityGroupFetch.class);

		final ReferenceHistogramStatistics[] histogramStatistics = object.getHistogramStatistics();
		output.writeInt(histogramStatistics.length);
		for (final ReferenceHistogramStatistics statistics : histogramStatistics) {
			kryo.writeObject(output, statistics);
		}
	}

	@Override
	public ReferenceSummary read(Kryo kryo, Input input, Class<? extends ReferenceSummary> type) {
		final FacetStatisticsDepth statisticsDepth = kryo.readObject(input, FacetStatisticsDepth.class);
		final FilterBy filterBy = kryo.readObjectOrNull(input, FilterBy.class);
		final FilterGroupBy filterGroupBy = kryo.readObjectOrNull(input, FilterGroupBy.class);
		final OrderBy orderBy = kryo.readObjectOrNull(input, OrderBy.class);
		final OrderGroupBy orderGroupBy = kryo.readObjectOrNull(input, OrderGroupBy.class);
		final EntityFetch entityFetch = kryo.readObjectOrNull(input, EntityFetch.class);
		final EntityGroupFetch entityGroupFetch = kryo.readObjectOrNull(input, EntityGroupFetch.class);

		final int histogramStatisticsCount = input.readInt();
		final List<RequireConstraint> children = new ArrayList<>(2 + histogramStatisticsCount);
		if (entityFetch != null) {
			children.add(entityFetch);
		}
		if (entityGroupFetch != null) {
			children.add(entityGroupFetch);
		}
		for (int i = 0; i < histogramStatisticsCount; i++) {
			children.add(kryo.readObject(input, ReferenceHistogramStatistics.class));
		}

		return new ReferenceSummary(
			statisticsDepth,
			filterBy, filterGroupBy,
			orderBy, orderGroupBy,
			children.toArray(new RequireConstraint[0])
		);
	}

}
