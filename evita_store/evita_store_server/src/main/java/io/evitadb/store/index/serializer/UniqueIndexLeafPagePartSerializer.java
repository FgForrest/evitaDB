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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPagePart;

import java.io.Serializable;

/**
 * This {@link Serializer} implementation reads/writes a {@link UniqueIndexLeafPagePart} — one leaf page of a granular
 * standalone (OWNER) unique-index bucket tree — from/to binary format. The `(streamId, pageSequence)` pair fully
 * determines the storage-part primary key (via `join`), so the key is recomputed on read rather than stored; only the
 * identifying pair and the leaf's `(value, recordId)` columns are written. Each value is written via Kryo; each record
 * id is a zig-zag varint (record ids are primary keys, small magnitudes regardless of sign).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class UniqueIndexLeafPagePartSerializer extends Serializer<UniqueIndexLeafPagePart> {

	@Override
	public void write(Kryo kryo, Output output, UniqueIndexLeafPagePart page) {
		output.writeVarInt(page.getStreamId(), true);
		output.writeVarInt(page.getPageSequence(), true);

		final Serializable[] values = page.getValues();
		final int[] recordIds = page.getRecordIds();
		output.writeVarInt(values.length, true);
		for (int i = 0; i < values.length; i++) {
			kryo.writeClassAndObject(output, values[i]);
			output.writeVarInt(recordIds[i], false);
		}
	}

	@Override
	public UniqueIndexLeafPagePart read(Kryo kryo, Input input, Class<? extends UniqueIndexLeafPagePart> type) {
		final int streamId = input.readVarInt(true);
		final int pageSequence = input.readVarInt(true);

		final int valueCount = input.readVarInt(true);
		final Serializable[] values = new Serializable[valueCount];
		final int[] recordIds = new int[valueCount];
		for (int i = 0; i < valueCount; i++) {
			values[i] = (Serializable) kryo.readClassAndObject(input);
			recordIds[i] = input.readVarInt(false);
		}

		// the key is derived from the identifying pair, never stored
		return new UniqueIndexLeafPagePart(
			streamId, pageSequence, values, recordIds,
			UniqueIndexLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

}
