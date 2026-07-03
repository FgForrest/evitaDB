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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexLeafPagePart;

/**
 * This {@link Serializer} implementation reads/writes a {@link ChainIndexLeafPagePart} — one leaf page of a granular
 * {@link io.evitadb.index.attribute.ChainIndex} value tree — from/to binary format. The `(streamId, pageSequence)` pair
 * fully determines the storage-part primary key (via `pack`), so the key is recomputed on read rather than stored; only
 * the identifying pair and the non-derived page payload are written.
 *
 * The payload is the leaf's ordered {@link ChainIndexLeafPagePart#getRecordIds() recordIds} (raw fixed-width ints — they
 * are in tree order, NOT sorted, so a delta encoding would not help), the {@link ChainIndexLeafPagePart#getHeadWords()
 * headWords} bitset marking which of those records are chain heads, and the aligned
 * {@link ChainIndexLeafPagePart#getHeadPredecessorPks() headPredecessorPks}. The head-bitset word count is not stored —
 * it is derived as `ceil(recordCount / 64)` on read, exactly as it is sliced on write.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ChainIndexLeafPagePartSerializer extends Serializer<ChainIndexLeafPagePart> {

	@Override
	public void write(Kryo kryo, Output output, ChainIndexLeafPagePart page) {
		output.writeVarInt(page.getStreamId(), true);
		output.writeVarInt(page.getPageSequence(), true);

		// the leaf's records in tree order - written raw (chain order is not sorted, so a delta encoding cannot help)
		final int[] recordIds = page.getRecordIds();
		final int recordCount = recordIds.length;
		output.writeVarInt(recordCount, true);
		output.writeInts(recordIds, 0, recordCount);

		// the head bitset: only the meaningful `ceil(recordCount / 64)` words - the count is derivable on read, so it is
		// not stored. The producer guarantees the head-words array is at least this wide (its wider in-memory tail is all
		// zero by the tree invariant).
		final int wordCount = (recordCount + 63) >>> 6;
		final long[] headWords = page.getHeadWords();
		for (int i = 0; i < wordCount; i++) {
			output.writeLong(headWords[i]);
		}

		// the head predecessor primary keys, one per set head bit, in ascending bit-position order
		final int[] headPredecessorPks = page.getHeadPredecessorPks();
		output.writeVarInt(headPredecessorPks.length, true);
		output.writeInts(headPredecessorPks, 0, headPredecessorPks.length);
	}

	@Override
	public ChainIndexLeafPagePart read(Kryo kryo, Input input, Class<? extends ChainIndexLeafPagePart> type) {
		final int streamId = input.readVarInt(true);
		final int pageSequence = input.readVarInt(true);

		final int recordCount = input.readVarInt(true);
		final int[] recordIds = input.readInts(recordCount);

		// recover the head bitset - exactly `ceil(recordCount / 64)` words, matching the slice written above
		final int wordCount = (recordCount + 63) >>> 6;
		final long[] headWords = new long[wordCount];
		for (int i = 0; i < wordCount; i++) {
			headWords[i] = input.readLong();
		}

		final int headPredecessorCount = input.readVarInt(true);
		final int[] headPredecessorPks = input.readInts(headPredecessorCount);

		// the key is derived from the identifying pair, never stored
		return new ChainIndexLeafPagePart(
			streamId, pageSequence, recordIds, headWords, headPredecessorPks,
			AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

}
