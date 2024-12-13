/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.store.traffic.serializer;


import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.traffic.data.SourceQueryContainer;

/**
 * This {@link Serializer} implementation reads/writes {@link SourceQueryContainer} type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class SourceQueryContainerSerializer extends Serializer<SourceQueryContainer> {

	@Override
	public void write(Kryo kryo, Output output, SourceQueryContainer object) {
		kryo.writeObject(output, object.sessionId());
		output.writeVarInt(object.recordSessionOffset(), true);
		kryo.writeObject(output, object.sourceQueryId());
		kryo.writeObject(output, object.created());
		output.writeString(object.sourceQuery());
		output.writeString(object.queryType());
	}

	@Override
	public SourceQueryContainer read(Kryo kryo, Input input, Class<? extends SourceQueryContainer> type) {
		return new SourceQueryContainer(
			kryo.readObject(input, java.util.UUID.class),
			input.readVarInt(true),
			kryo.readObject(input, java.util.UUID.class),
			kryo.readObject(input, java.time.OffsetDateTime.class),
			input.readString(),
			input.readString()
		);
	}

}
