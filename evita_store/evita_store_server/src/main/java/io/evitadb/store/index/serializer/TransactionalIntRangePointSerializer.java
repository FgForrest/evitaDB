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

/**
 * Class handles Kryo (de)serialization of {@link RangePoint} instances.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public class TransactionalIntRangePointSerializer extends Serializer<TransactionalRangePoint> {

	public void write(Kryo kryo, Output output, TransactionalRangePoint intRangePoint) {
		output.writeLong(intRangePoint.getThreshold());
		final TransactionalBitmap starts = intRangePoint.getStarts();
		final TransactionalBitmap ends = intRangePoint.getEnds();
		kryo.writeObject(output, starts);
		kryo.writeObject(output, ends);
	}

	public TransactionalRangePoint read(Kryo kryo, Input input, Class<? extends TransactionalRangePoint> type) {
		final long threshold = input.readLong();
		return new TransactionalRangePoint(
			threshold,
			kryo.readObject(input, TransactionalBitmap.class),
			kryo.readObject(input, TransactionalBitmap.class)
		);
	}

}
