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
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.trafficRecording.Label;
import io.evitadb.api.requestResponse.trafficRecording.QueryContainer;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * This {@link Serializer} implementation reads/writes {@link QueryContainer} type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class QueryContainerSerializer extends Serializer<QueryContainer> {

	@Override
	public void write(Kryo kryo, Output output, QueryContainer object) {
		kryo.writeObject(output, object.sessionId());
		output.writeVarInt(object.recordSessionOffset(), true);
		output.writeString(object.queryDescription());
		kryo.writeObject(output, object.query());
		output.writeVarInt(object.labels().length, true);
		for (int i = 0; i < object.labels().length; i++) {
			output.writeString(object.labels()[i].name());
			kryo.writeClassAndObject(output, object.labels()[i].value());
		}
		kryo.writeObject(output, object.created());
		output.writeVarInt(object.durationInMilliseconds(), true);
		output.writeVarInt(object.totalRecordCount(), true);
		output.writeVarInt(object.ioFetchCount(), true);
		output.writeVarInt(object.ioFetchedSizeBytes(), true);
		output.writeVarInt(object.primaryKeys().length, true);
		output.writeInts(object.primaryKeys(), 0, object.primaryKeys().length);
		output.writeString(object.finishedWithError());
	}

	@Override
	public QueryContainer read(Kryo kryo, Input input, Class<? extends QueryContainer> type) {
		final UUID sessionId = kryo.readObject(input, UUID.class);
		final int recordSessionOffset = input.readVarInt(true);
		final String queryDescription = input.readString();
		final Query query = kryo.readObject(input, Query.class);
		final int labelCount = input.readVarInt(true);
		final Label[] labels = labelCount == 0 ? Label.EMPTY_LABELS : new Label[labelCount];
		for (int i = 0; i < labelCount; i++) {
			labels[i] = new Label(
				input.readString(),
				(Serializable) kryo.readClassAndObject(input)
			);
		}

		final CurrentSessionRecordContext.SessionRecordContext sessionRecordContext = CurrentSessionRecordContext.get();
		return new QueryContainer(
			sessionRecordContext == null ? null : sessionRecordContext.sessionSequenceOrder(),
			sessionId,
			recordSessionOffset,
			sessionRecordContext == null ? null : sessionRecordContext.sessionRecordsCount(),
			queryDescription,
			query,
			labels,
			kryo.readObject(input, OffsetDateTime.class),
			input.readVarInt(true),
			input.readVarInt(true),
			input.readVarInt(true),
			input.readVarInt(true),
			input.readInts(input.readVarInt(true)),
			input.readString()
		);
	}

}
