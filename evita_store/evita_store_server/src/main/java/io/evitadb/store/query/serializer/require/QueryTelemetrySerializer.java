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
import io.evitadb.api.query.require.QueryTelemetry;
import io.evitadb.api.query.require.QueryTelemetryContent;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link QueryTelemetry} from/to binary format.
 *
 * The constraint used to carry no state at all, and this serializer used to write nothing. It now carries
 * a {@link QueryTelemetryContent} level, which has to survive the round trip - dropping it would silently
 * downgrade a `queryTelemetry(PLAN)` reaching the engine through the Java driver or replayed from a traffic
 * recording back to a plain `queryTelemetry()`, with no error anywhere to explain the missing plan.
 *
 * The level is always written, {@link QueryTelemetryContent#TIMINGS} included, because it is always present on
 * the constraint - it is implicit only in the EvitaQL string form, never in the object.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class QueryTelemetrySerializer extends Serializer<QueryTelemetry> {

	@Override
	public void write(Kryo kryo, Output output, QueryTelemetry object) {
		kryo.writeObject(output, object.getContent());
	}

	@Override
	public QueryTelemetry read(Kryo kryo, Input input, Class<? extends QueryTelemetry> type) {
		return new QueryTelemetry(kryo.readObject(input, QueryTelemetryContent.class));
	}

}
