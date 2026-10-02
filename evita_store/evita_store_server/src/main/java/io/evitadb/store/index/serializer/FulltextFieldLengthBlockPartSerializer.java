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
import io.evitadb.index.fulltext.FieldLengthTable.LengthBlock;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextFieldLengthBlockPart;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;

/**
 * This serializer reads/writes {@link FulltextFieldLengthBlockPart} - one block of a fulltext field length table.
 * After the `(streamId, pageSequence)` frame comes the entity count, then the block in whichever of two encodings is
 * smaller for that count:
 *
 * - **sparse** - the low 16 bits of the primary keys as ascending deltas, then the encoded lengths, about three bytes
 *   per entity;
 * - **slots** - all {@link #BLOCK_SIZE} slots, one byte each, `0` for an absent entity.
 *
 * The encoding is a function of the count alone, so it needs no marker of its own.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FulltextFieldLengthBlockPartSerializer
	extends AbstractLeafPagePartSerializer<FulltextFieldLengthBlockPart> {

	/**
	 * Number of slots of a block - one per low 16 bits of a primary key.
	 */
	static final int BLOCK_SIZE = 1 << 16;

	/**
	 * Entity count above which the slot encoding is the smaller one.
	 */
	static final int SLOTS_ENCODING_THRESHOLD = BLOCK_SIZE / 3;

	@Override
	protected int streamId(@Nonnull FulltextFieldLengthBlockPart page) {
		return page.getStreamId();
	}

	@Override
	protected int pageSequence(@Nonnull FulltextFieldLengthBlockPart page) {
		return page.getPageSequence();
	}

	@Override
	protected void writePayload(
		@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull FulltextFieldLengthBlockPart page
	) {
		final LengthBlock block = page.getBlock();
		final char[] lows = block.lows();
		final byte[] lengths = block.lengths();
		output.writeVarInt(lows.length, true);
		if (lows.length > SLOTS_ENCODING_THRESHOLD) {
			final byte[] slots = new byte[BLOCK_SIZE];
			for (int i = 0; i < lows.length; i++) {
				slots[lows[i]] = lengths[i];
			}
			output.writeBytes(slots);
		} else {
			int previous = 0;
			for (final char low : lows) {
				output.writeVarInt(low - previous, true);
				previous = low;
			}
			output.writeBytes(lengths);
		}
	}

	@Nonnull
	@Override
	protected FulltextFieldLengthBlockPart readPayload(
		@Nonnull Kryo kryo, @Nonnull Input input, int streamId, int pageSequence
	) {
		final int count = input.readVarInt(true);
		final char[] lows = new char[count];
		final byte[] lengths;
		if (count > SLOTS_ENCODING_THRESHOLD) {
			final byte[] slots = input.readBytes(BLOCK_SIZE);
			lengths = new byte[count];
			int position = 0;
			for (int low = 0; low < BLOCK_SIZE; low++) {
				if (slots[low] != 0) {
					Assert.isPremiseValid(
						position < count,
						() -> "A length block slot run holds more entities than its count " + count + "!"
					);
					lows[position] = (char) low;
					lengths[position] = slots[low];
					position++;
				}
			}
		} else {
			int previous = 0;
			for (int i = 0; i < count; i++) {
				previous += input.readVarInt(true);
				lows[i] = (char) previous;
			}
			lengths = input.readBytes(count);
		}
		// a slot run holding fewer entities than its count leaves zero lengths behind, which the block refuses
		return new FulltextFieldLengthBlockPart(
			streamId, pageSequence, lows, lengths, AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

}
