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
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;

/**
 * This {@link Serializer} implementation reads/writes a {@link FilterIndexLeafPagePart} — one leaf page of a granular
 * FilterIndex bucket tree — from/to binary format. The `(streamId, pageSequence)` pair fully determines the
 * storage-part primary key (via `join`), so the key is recomputed on read rather than stored; only the identifying
 * pair and the leaf's buckets are written.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FilterIndexLeafPagePartSerializer extends Serializer<FilterIndexLeafPagePart> {

	@Override
	public void write(Kryo kryo, Output output, FilterIndexLeafPagePart page) {
		output.writeVarInt(page.getStreamId(), true);
		output.writeVarInt(page.getPageSequence(), true);

		final ValueToRecordBitmap[] buckets = page.getBuckets();
		output.writeVarInt(buckets.length, true);
		for (final ValueToRecordBitmap bucket : buckets) {
			kryo.writeObject(output, bucket);
		}
	}

	@Override
	public FilterIndexLeafPagePart read(Kryo kryo, Input input, Class<? extends FilterIndexLeafPagePart> type) {
		final int streamId = input.readVarInt(true);
		final int pageSequence = input.readVarInt(true);

		final int bucketCount = input.readVarInt(true);
		final ValueToRecordBitmap[] buckets = new ValueToRecordBitmap[bucketCount];
		for (int i = 0; i < bucketCount; i++) {
			buckets[i] = kryo.readObject(input, ValueToRecordBitmap.class);
		}

		// the key is derived from the identifying pair, never stored
		return new FilterIndexLeafPagePart(
			streamId, pageSequence, buckets, FilterIndexLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

}
