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

package io.evitadb.store.traffic;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.core.traffic.TrafficRecorder;
import io.evitadb.store.traffic.data.MutationContainer;
import io.evitadb.store.traffic.data.QueryContainer;
import io.evitadb.store.traffic.data.RecordEnrichmentContainer;
import io.evitadb.store.traffic.data.RecordFetchContainer;
import io.evitadb.store.traffic.data.SessionCloseContainer;
import io.evitadb.store.traffic.data.SessionStartContainer;
import io.evitadb.store.traffic.data.SourceQueryContainer;
import io.evitadb.store.traffic.data.SourceQueryStatisticsContainer;
import io.evitadb.store.traffic.serializer.MutationContainerSerializer;
import io.evitadb.store.traffic.serializer.QueryContainerSerializer;
import io.evitadb.store.traffic.serializer.RecordEnrichmentContainerSerializer;
import io.evitadb.store.traffic.serializer.RecordFetchContainerSerializer;
import io.evitadb.store.traffic.serializer.SessionCloseContainerSerializer;
import io.evitadb.store.traffic.serializer.SessionStartContainerSerializer;
import io.evitadb.store.traffic.serializer.SourceQueryContainerSerializer;
import io.evitadb.store.traffic.serializer.SourceQueryStatisticsContainerSerializer;
import io.evitadb.utils.Assert;

import java.util.function.Consumer;

/**
 * This {@link Consumer} implementation takes default Kryo instance and registers additional serializers that are
 * required to (de)serialize {@link TrafficRecorder} containers.
 */
public class TrafficRecordingSerializationKryoConfigurer implements Consumer<Kryo> {
	private static final int TRAFFIC_RECORDING_BASE = 5000;
	public static final TrafficRecordingSerializationKryoConfigurer INSTANCE = new TrafficRecordingSerializationKryoConfigurer();

	@Override
	public void accept(Kryo kryo) {
		int index = TRAFFIC_RECORDING_BASE;
		kryo.register(SessionStartContainer.class, new SessionStartContainerSerializer(), index++);
		kryo.register(SessionCloseContainer.class, new SessionCloseContainerSerializer(), index++);
		kryo.register(QueryContainer.class, new QueryContainerSerializer(), index++);
		kryo.register(MutationContainer.class, new MutationContainerSerializer(), index++);
		kryo.register(RecordFetchContainer.class, new RecordFetchContainerSerializer(), index++);
		kryo.register(RecordEnrichmentContainer.class, new RecordEnrichmentContainerSerializer(), index++);
		kryo.register(SourceQueryContainer.class, new SourceQueryContainerSerializer(), index++);
		kryo.register(SourceQueryStatisticsContainer.class, new SourceQueryStatisticsContainerSerializer(), index++);

		Assert.isPremiseValid(index < 5100, "Index count overflow.");
	}

}
