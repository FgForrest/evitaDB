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
 * **Writing the {@link QueryTelemetryContent} level is a knowingly breaking change: traffic recordings written
 * before the constraint gained its argument can no longer be read.** Until then this serializer emitted zero
 * bytes, and the recording format carries no version, magic or length stamp - so a reader that consumes an enum
 * consumes bytes belonging to the *next* element of an older recording. There is no compatible middle ground to
 * take instead: query constraints are registered directly rather than through `SerialVersionBasedSerializer`, so
 * there is no `serialVersionUID` prefix to dispatch a backward-compatible reader on, and Kryo binds one
 * registration id per class, so the two forms cannot be told apart at all.
 *
 * The break was chosen over the alternative of persisting nothing, which kept old recordings readable at the cost
 * of replaying every recorded `queryTelemetry(PLAN)` as a plain `queryTelemetry()` - a debugging constraint
 * silently degrading, forever, on a path where the recording exists precisely to reproduce what happened. A clean
 * failure to read a stale recording is preferable to a quiet change of meaning in every future one.
 *
 * A compatibility mechanism for query constraints comparable to the one the data structures already have is
 * planned; when it lands, this serializer is one of its first customers.
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
