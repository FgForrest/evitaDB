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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexLeafPagePart;

/**
 * This {@link Serializer} implementation reads/writes a {@link ReferenceTypeCardinalityIndexLeafPagePart} — one leaf page
 * of a granular reference-type-cardinality bucket tree — from/to binary format. The `(streamId, pageSequence)` pair fully
 * determines the storage-part primary key (via `pack`), so the key is recomputed on read rather than stored; only the
 * identifying pair and the leaf's `(key, count)` columns are written. Each key is a composed signed `long` written with
 * the non-optimize-positive `writeVarLong(.., false)` form (keys are negative for per-reference counters); each count is a
 * small positive `int` written via `writeVarInt` (the in-memory column is `long`, but counts fit `int` by construction —
 * matching the inline SINGLE root format).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ReferenceTypeCardinalityIndexLeafPagePartSerializer extends Serializer<ReferenceTypeCardinalityIndexLeafPagePart> {

	@Override
	public void write(Kryo kryo, Output output, ReferenceTypeCardinalityIndexLeafPagePart page) {
		output.writeVarInt(page.getStreamId(), true);
		output.writeVarInt(page.getPageSequence(), true);

		final long[] keys = page.getKeys();
		final long[] payloads = page.getPayloads();
		output.writeVarInt(keys.length, true);
		for (int i = 0; i < keys.length; i++) {
			output.writeVarLong(keys[i], false);
			output.writeVarInt((int) payloads[i], false);
		}
	}

	@Override
	public ReferenceTypeCardinalityIndexLeafPagePart read(Kryo kryo, Input input, Class<? extends ReferenceTypeCardinalityIndexLeafPagePart> type) {
		final int streamId = input.readVarInt(true);
		final int pageSequence = input.readVarInt(true);

		final int keyCount = input.readVarInt(true);
		final long[] keys = new long[keyCount];
		final long[] payloads = new long[keyCount];
		for (int i = 0; i < keyCount; i++) {
			keys[i] = input.readVarLong(false);
			payloads[i] = input.readVarInt(false);
		}

		// the key is derived from the identifying pair, never stored
		return new ReferenceTypeCardinalityIndexLeafPagePart(
			streamId, pageSequence, keys, payloads,
			ReferenceTypeCardinalityIndexLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

}
