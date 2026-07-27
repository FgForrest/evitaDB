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
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.ReferenceHistogramStatistics;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link ReferenceHistogramStatistics} from/to binary format.
 * The wire format mirrors the constraint's logical shape:
 *
 * 1. requested bucket count (int)
 * 2. histogram behavior ({@link HistogramBehavior}; nullable — encoded via `writeObjectOrNull`)
 * 3. optional {@link EntityFetch} child (nullable — encoded via `writeObjectOrNull`)
 * 4. histogram index names (variable-length string array)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class ReferenceHistogramStatisticsSerializer extends Serializer<ReferenceHistogramStatistics> {

	@Override
	public void write(Kryo kryo, Output output, ReferenceHistogramStatistics object) {
		output.writeInt(object.getRequestedBucketCount());
		kryo.writeObjectOrNull(output, object.getBehavior(), HistogramBehavior.class);

		final EntityFetch entityFetch = object.getEntityFetch().orElse(null);
		kryo.writeObjectOrNull(output, entityFetch, EntityFetch.class);

		final String[] indexNames = object.getIndexNames();
		output.writeInt(indexNames.length);
		for (final String indexName : indexNames) {
			output.writeString(indexName);
		}
	}

	@Override
	public ReferenceHistogramStatistics read(Kryo kryo, Input input, Class<? extends ReferenceHistogramStatistics> type) {
		final int requestedBucketCount = input.readInt();
		final HistogramBehavior behavior = kryo.readObjectOrNull(input, HistogramBehavior.class);
		final EntityFetch entityFetch = kryo.readObjectOrNull(input, EntityFetch.class);
		final int indexNameCount = input.readInt();
		final String[] indexNames = new String[indexNameCount];
		for (int i = 0; i < indexNameCount; i++) {
			indexNames[i] = input.readString();
		}
		return new ReferenceHistogramStatistics(requestedBucketCount, behavior, entityFetch, indexNames);
	}

}
