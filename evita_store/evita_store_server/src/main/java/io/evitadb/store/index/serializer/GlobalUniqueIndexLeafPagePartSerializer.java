/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexLeafPagePart;

import java.io.Serializable;

/**
 * This {@link Serializer} implementation reads/writes a {@link GlobalUniqueIndexLeafPagePart} — one leaf page of a
 * granular catalog-level global-unique-index bucket tree — from/to binary format. The `(streamId, pageSequence)` pair
 * fully determines the storage-part primary key (via `join`), so the key is recomputed on read rather than stored; only
 * the identifying pair and the leaf's `(value, payload)` columns are written. Each value is written via Kryo; each
 * payload is the packed `long` entity tuple (`locale:16 | entityType:16 | pk:32`) written verbatim.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class GlobalUniqueIndexLeafPagePartSerializer extends Serializer<GlobalUniqueIndexLeafPagePart> {

	@Override
	public void write(Kryo kryo, Output output, GlobalUniqueIndexLeafPagePart page) {
		output.writeVarInt(page.getStreamId(), true);
		output.writeVarInt(page.getPageSequence(), true);

		final Serializable[] values = page.getValues();
		final long[] payloads = page.getPayloads();
		output.writeVarInt(values.length, true);
		for (int i = 0; i < values.length; i++) {
			kryo.writeClassAndObject(output, values[i]);
			output.writeLong(payloads[i]);
		}
	}

	@Override
	public GlobalUniqueIndexLeafPagePart read(Kryo kryo, Input input, Class<? extends GlobalUniqueIndexLeafPagePart> type) {
		final int streamId = input.readVarInt(true);
		final int pageSequence = input.readVarInt(true);

		final int valueCount = input.readVarInt(true);
		final Serializable[] values = new Serializable[valueCount];
		final long[] payloads = new long[valueCount];
		for (int i = 0; i < valueCount; i++) {
			values[i] = (Serializable) kryo.readClassAndObject(input);
			payloads[i] = input.readLong();
		}

		// the key is derived from the identifying pair, never stored
		return new GlobalUniqueIndexLeafPagePart(
			streamId, pageSequence, values, payloads,
			GlobalUniqueIndexLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

}
