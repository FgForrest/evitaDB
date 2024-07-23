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

package io.evitadb.store.query.serializer.require;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.FacetSummary;
import io.evitadb.api.query.require.FacetSummaryOfReference;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link FacetSummary} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class FacetSummaryOfReferenceSerializer extends Serializer<FacetSummaryOfReference> {

	@Override
	public void write(Kryo kryo, Output output, FacetSummaryOfReference object) {
		kryo.writeObject(output, object.getReferenceName());
		kryo.writeObject(output, object.getStatisticsDepth());

		final FilterBy filterBy = object.getFilterBy().orElse(null);
		kryo.writeObjectOrNull(output, filterBy, FilterBy.class);

		final FilterGroupBy filterGroupBy = object.getFilterGroupBy().orElse(null);
		kryo.writeObjectOrNull(output, filterGroupBy, FilterGroupBy.class);

		final OrderBy orderBy = object.getOrderBy().orElse(null);
		kryo.writeObjectOrNull(output, orderBy, OrderBy.class);

		final OrderGroupBy orderGroupBy = object.getOrderGroupBy().orElse(null);
		kryo.writeObjectOrNull(output, orderGroupBy, OrderGroupBy.class);

		final EntityFetch facetEntityRequirement = object.getFacetEntityRequirement().orElse(null);
		kryo.writeObjectOrNull(output, facetEntityRequirement, EntityFetch.class);

		final EntityGroupFetch groupEntityRequirement = object.getGroupEntityRequirement().orElse(null);
		kryo.writeObjectOrNull(output, groupEntityRequirement, EntityGroupFetch.class);
	}

	@Override
	public FacetSummaryOfReference read(Kryo kryo, Input input, Class<? extends FacetSummaryOfReference> type) {
		final String referenceName = kryo.readObject(input, String.class);
		final FacetStatisticsDepth statisticsDepth = kryo.readObject(input, FacetStatisticsDepth.class);
		final FilterBy filterBy = kryo.readObjectOrNull(input, FilterBy.class);
		final FilterGroupBy filterGroupBy = kryo.readObjectOrNull(input, FilterGroupBy.class);
		final OrderBy orderBy = kryo.readObjectOrNull(input, OrderBy.class);
		final OrderGroupBy orderGroupBy = kryo.readObjectOrNull(input, OrderGroupBy.class);
		final EntityFetch facetEntityRequirement = kryo.readObjectOrNull(input, EntityFetch.class);
		final EntityGroupFetch groupEntityRequirement = kryo.readObjectOrNull(input, EntityGroupFetch.class);
		return new FacetSummaryOfReference(
			referenceName, statisticsDepth,
			filterBy, filterGroupBy,
			orderBy, orderGroupBy,
			facetEntityRequirement, groupEntityRequirement
		);
	}

}
