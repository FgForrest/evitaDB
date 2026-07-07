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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexLeafPagePart;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * This {@link Serializer} implementation reads/writes a {@link GlobalUniqueIndexLeafPagePart} — one leaf page of a
 * granular catalog-level global-unique-index bucket tree — from/to binary format. The `(streamId, pageSequence)` frame is
 * owned by {@link AbstractLeafPagePartSerializer}; this payload adds the leaf's `(value, payload)` columns. Each value is
 * written via Kryo; each payload is the packed `long` entity tuple (`locale:16 | entityType:16 | pk:32`) written verbatim.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class GlobalUniqueIndexLeafPagePartSerializer extends AbstractLeafPagePartSerializer<GlobalUniqueIndexLeafPagePart> {

	@Override
	protected int streamId(@Nonnull GlobalUniqueIndexLeafPagePart page) {
		return page.getStreamId();
	}

	@Override
	protected int pageSequence(@Nonnull GlobalUniqueIndexLeafPagePart page) {
		return page.getPageSequence();
	}

	@Override
	protected void writePayload(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull GlobalUniqueIndexLeafPagePart page) {
		final Serializable[] values = page.getValues();
		final long[] payloads = page.getPayloads();
		output.writeVarInt(values.length, true);
		for (int i = 0; i < values.length; i++) {
			kryo.writeClassAndObject(output, values[i]);
			output.writeLong(payloads[i]);
		}
	}

	@Nonnull
	@Override
	protected GlobalUniqueIndexLeafPagePart readPayload(
		@Nonnull Kryo kryo, @Nonnull Input input, int streamId, int pageSequence
	) {
		final int valueCount = input.readVarInt(true);
		final Serializable[] values = new Serializable[valueCount];
		final long[] payloads = new long[valueCount];
		for (int i = 0; i < valueCount; i++) {
			values[i] = (Serializable) kryo.readClassAndObject(input);
			payloads[i] = input.readLong();
		}

		return new GlobalUniqueIndexLeafPagePart(
			streamId, pageSequence, values, payloads,
			AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

}
