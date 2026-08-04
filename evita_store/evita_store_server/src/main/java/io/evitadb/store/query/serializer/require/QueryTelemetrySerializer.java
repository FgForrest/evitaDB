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
 * **It deliberately writes nothing, even though the constraint now carries a {@link QueryTelemetryContent}
 * level.** Persisting the level looks like the obvious thing to do and was tried; it cannot be done compatibly:
 *
 * - This configurer has exactly two production users, both traffic recording - `OffHeapTrafficRecorder` and
 *   `InputStreamTrafficRecordReader`. The remote drivers send EvitaQL as a string and never reach this code, so
 *   replay of a recorded query is the only path the level could travel on.
 * - Before the level existed this serializer emitted **zero bytes**, and the recording format carries no version,
 *   magic or length stamp. A reader that consumes an enum would therefore consume bytes belonging to the *next*
 *   element of any recording written by an earlier build - corrupting the constraint tree, or raising a
 *   `KryoException` far from its cause, rather than failing cleanly.
 * - The usual escape hatch does not apply either: query constraints are registered directly rather than through
 *   `SerialVersionBasedSerializer`, so there is no `serialVersionUID` prefix to dispatch a backward-compatible
 *   reader on, and Kryo binds one registration id per class, so the old and new forms cannot be told apart.
 *
 * The cost of writing nothing is that replaying a recorded `queryTelemetry(PLAN)` re-executes it as
 * `queryTelemetry()`, losing the formula plan on that one path. That is a debugging constraint degrading to its
 * default during replay, which is a far smaller price than every pre-existing recording becoming unreadable.
 *
 * Revisit only if the recording format gains a version stamp; then the level can be written behind it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class QueryTelemetrySerializer extends Serializer<QueryTelemetry> {

	@Override
	public void write(Kryo kryo, Output output, QueryTelemetry object) {
		// intentionally empty - see the class JavaDoc: the recording format cannot tell an old zero-byte
		// payload from a new one, so writing the level here would break every recording made before it existed
	}

	@Override
	public QueryTelemetry read(Kryo kryo, Input input, Class<? extends QueryTelemetry> type) {
		return new QueryTelemetry();
	}

}
