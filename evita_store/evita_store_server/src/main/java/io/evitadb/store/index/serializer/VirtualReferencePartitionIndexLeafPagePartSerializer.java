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
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexLeafPagePart;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;

/**
 * Reads and writes a {@link VirtualReferencePartitionIndexLeafPagePart} - one leaf page of the entry tree of a virtual
 * reference partition index. The `(streamId, pageSequence)` frame is owned by {@link AbstractLeafPagePartSerializer};
 * the payload is the leaf's `(key, payload)` columns in the entry encoding of {@link #writeEntries}, which the inline
 * root shares.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class VirtualReferencePartitionIndexLeafPagePartSerializer
	extends AbstractLeafPagePartSerializer<VirtualReferencePartitionIndexLeafPagePart> {

	/**
	 * Writes positionally-aligned entry columns. The keys are `pack(slotId, partitionPrimaryKey)` with a non-negative
	 * slot and a positive partition primary key, ascending, so the first is written as a positive varint and every
	 * other as its positive distance from its predecessor - consecutive partitions of one slot cost a byte or two. A
	 * payload has no common shape across slots (two arbitrary `int` primary keys of the owner slot, a small id of the
	 * tuple slot), so each is written as its two 32-bit halves, each a zig-zag varint: small values of either sign stay
	 * short in both halves.
	 *
	 * @param output   the output to write to
	 * @param keys     the ascending keys
	 * @param payloads the payloads, aligned with `keys`
	 */
	static void writeEntries(@Nonnull Output output, @Nonnull long[] keys, @Nonnull long[] payloads) {
		output.writeVarInt(keys.length, true);
		long previousKey = 0L;
		for (int i = 0; i < keys.length; i++) {
			output.writeVarLong(keys[i] - previousKey, true);
			previousKey = keys[i];
			output.writeVarInt(NumberUtils.unpackHigh(payloads[i]), false);
			output.writeVarInt(NumberUtils.unpackLow(payloads[i]), false);
		}
	}

	/**
	 * Reads entry columns written by {@link #writeEntries}.
	 *
	 * @param input the input to read from
	 * @return the keys at index 0 and the aligned payloads at index 1
	 */
	@Nonnull
	static long[][] readEntries(@Nonnull Input input) {
		final int count = input.readVarInt(true);
		final long[] keys = new long[count];
		final long[] payloads = new long[count];
		long previousKey = 0L;
		for (int i = 0; i < count; i++) {
			previousKey += input.readVarLong(true);
			keys[i] = previousKey;
			final int high = input.readVarInt(false);
			final int low = input.readVarInt(false);
			payloads[i] = NumberUtils.pack(high, low);
		}
		return new long[][]{keys, payloads};
	}

	@Override
	protected int streamId(@Nonnull VirtualReferencePartitionIndexLeafPagePart page) {
		return page.getStreamId();
	}

	@Override
	protected int pageSequence(@Nonnull VirtualReferencePartitionIndexLeafPagePart page) {
		return page.getPageSequence();
	}

	@Override
	protected void writePayload(
		@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull VirtualReferencePartitionIndexLeafPagePart page
	) {
		writeEntries(output, page.getKeys(), page.getPayloads());
	}

	@Nonnull
	@Override
	protected VirtualReferencePartitionIndexLeafPagePart readPayload(
		@Nonnull Kryo kryo, @Nonnull Input input, int streamId, int pageSequence
	) {
		final long[][] entries = readEntries(input);
		return new VirtualReferencePartitionIndexLeafPagePart(
			streamId, pageSequence, entries[0], entries[1],
			AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

}
