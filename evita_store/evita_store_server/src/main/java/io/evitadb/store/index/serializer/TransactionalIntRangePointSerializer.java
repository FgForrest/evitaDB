/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.range.RangePoint;
import io.evitadb.index.range.TransactionalRangePoint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Class handles Kryo (de)serialization of {@link RangePoint} instances.
 *
 * The layout is the threshold, occurrence level 1 of the starts and of the ends, and then the repeated occurrence
 * levels — a varint count of repeated start levels followed by their bitmaps, and the same for the ends. Only the
 * non-empty levels are written; the empty spare levels a point keeps in memory are not part of the persisted form. A
 * point where nothing repeats, the common case, spends two bytes on the two zero counts. Points written before the
 * levels existed carry the previous serial version UID and are read by {@link
 * TransactionalIntRangePointSerializer_2026_2}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public class TransactionalIntRangePointSerializer extends Serializer<TransactionalRangePoint> {

	/**
	 * Writes the count of the non-empty occurrence levels followed by each one's bitmap. The levels after the first
	 * empty one are spare and empty too, so they are left out.
	 *
	 * @param kryo   the Kryo instance
	 * @param output the output to write to
	 * @param levels the repeated occurrence levels, possibly empty
	 */
	private static void writeLevels(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull TransactionalBitmap[] levels) {
		int count = 0;
		while (count < levels.length && !levels[count].isEmpty()) {
			count++;
		}
		output.writeVarInt(count, true);
		for (int i = 0; i < count; i++) {
			kryo.writeObject(output, levels[i]);
		}
	}

	/**
	 * Reads a count of occurrence levels followed by each level's bitmap.
	 *
	 * @param kryo  the Kryo instance
	 * @param input the input to read from
	 * @return the repeated occurrence levels, or `null` when the point has none
	 */
	@Nullable
	private static TransactionalBitmap[] readLevels(@Nonnull Kryo kryo, @Nonnull Input input) {
		final int levelCount = input.readVarInt(true);
		if (levelCount == 0) {
			return null;
		}
		final TransactionalBitmap[] levels = new TransactionalBitmap[levelCount];
		for (int i = 0; i < levelCount; i++) {
			levels[i] = kryo.readObject(input, TransactionalBitmap.class);
		}
		return levels;
	}

	@Override
	public void write(Kryo kryo, Output output, TransactionalRangePoint intRangePoint) {
		output.writeLong(intRangePoint.getThreshold());
		final TransactionalBitmap starts = intRangePoint.getStarts();
		final TransactionalBitmap ends = intRangePoint.getEnds();
		kryo.writeObject(output, starts);
		kryo.writeObject(output, ends);
		writeLevels(kryo, output, intRangePoint.getRepeatedStarts());
		writeLevels(kryo, output, intRangePoint.getRepeatedEnds());
	}

	@Override
	public TransactionalRangePoint read(Kryo kryo, Input input, Class<? extends TransactionalRangePoint> type) {
		final long threshold = input.readLong();
		final TransactionalBitmap starts = kryo.readObject(input, TransactionalBitmap.class);
		final TransactionalBitmap ends = kryo.readObject(input, TransactionalBitmap.class);
		final TransactionalBitmap[] repeatedStarts = readLevels(kryo, input);
		final TransactionalBitmap[] repeatedEnds = readLevels(kryo, input);
		return new TransactionalRangePoint(threshold, starts, ends, repeatedStarts, repeatedEnds);
	}

}
