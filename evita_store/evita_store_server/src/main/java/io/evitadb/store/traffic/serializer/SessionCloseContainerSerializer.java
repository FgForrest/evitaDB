/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.api.requestResponse.trafficRecording.SessionCloseContainer;

/**
 * This {@link Serializer} implementation reads/writes {@link SessionCloseContainer} type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class SessionCloseContainerSerializer extends Serializer<SessionCloseContainer> {

	@Override
	public void write(Kryo kryo, Output output, SessionCloseContainer object) {
		kryo.writeObject(output, object.sessionId());
		output.writeVarInt(object.recordSessionOffset(), true);
		output.writeLong(object.catalogVersion());
		kryo.writeObject(output, object.created());
		output.writeVarInt(object.durationInMilliseconds(), true);
		output.writeVarInt(object.ioFetchCount(), true);
		output.writeVarInt(object.ioFetchedSizeBytes(), true);
		output.writeVarInt(object.trafficRecordCount(), true);
		output.writeVarInt(object.trafficRecordsMissedOut(), true);
		output.writeVarInt(object.queryCount(), true);
		output.writeVarInt(object.entityFetchCount(), true);
		output.writeVarInt(object.mutationCount(), true);
	}

	@Override
	public SessionCloseContainer read(Kryo kryo, Input input, Class<? extends SessionCloseContainer> type) {
		return new SessionCloseContainer(
			SessionSequenceOrderContext.getSessionSequenceOrder(),
			kryo.readObject(input, java.util.UUID.class),
			input.readVarInt(true),
			input.readLong(),
			kryo.readObject(input, java.time.OffsetDateTime.class),
			input.readVarInt(true),
			input.readVarInt(true),
			input.readVarInt(true),
			input.readVarInt(true),
			input.readVarInt(true),
			input.readVarInt(true),
			input.readVarInt(true),
			input.readVarInt(true)
		);
	}

}
