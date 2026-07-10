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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPagePart;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * This {@link Serializer} implementation reads/writes a {@link UniqueIndexLeafPagePart} — one leaf page of a granular
 * standalone (OWNER) unique-index bucket tree — from/to binary format. The `(streamId, pageSequence)` frame is owned by
 * {@link AbstractLeafPagePartSerializer}; this payload adds the leaf's `(value, recordId)` columns. Each value is written
 * via Kryo; each record id is a zig-zag varint (record ids are primary keys, small magnitudes regardless of sign).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class UniqueIndexLeafPagePartSerializer extends AbstractLeafPagePartSerializer<UniqueIndexLeafPagePart> {

	@Override
	protected int streamId(@Nonnull UniqueIndexLeafPagePart page) {
		return page.getStreamId();
	}

	@Override
	protected int pageSequence(@Nonnull UniqueIndexLeafPagePart page) {
		return page.getPageSequence();
	}

	@Override
	protected void writePayload(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull UniqueIndexLeafPagePart page) {
		final Serializable[] values = page.getValues();
		final int[] recordIds = page.getRecordIds();
		output.writeVarInt(values.length, true);
		for (int i = 0; i < values.length; i++) {
			kryo.writeClassAndObject(output, values[i]);
			output.writeVarInt(recordIds[i], false);
		}
	}

	@Nonnull
	@Override
	protected UniqueIndexLeafPagePart readPayload(
		@Nonnull Kryo kryo, @Nonnull Input input, int streamId, int pageSequence
	) {
		final int valueCount = input.readVarInt(true);
		final Serializable[] values = new Serializable[valueCount];
		final int[] recordIds = new int[valueCount];
		for (int i = 0; i < valueCount; i++) {
			values[i] = (Serializable) kryo.readClassAndObject(input);
			recordIds[i] = input.readVarInt(false);
		}

		return new UniqueIndexLeafPagePart(
			streamId, pageSequence, values, recordIds,
			AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

}
