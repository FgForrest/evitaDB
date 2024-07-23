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
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.RangePoint;
import io.evitadb.index.range.TransactionalRangePoint;

/**
 * Class handles Kryo (de)serialization of {@link RangeIndex} instances.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public class IntRangeIndexSerializer extends Serializer<RangeIndex> {

	@Override
	public void write(Kryo kryo, Output output, RangeIndex object) {
		final RangePoint<?>[] ranges = object.getRanges();
		output.writeInt(ranges.length);
		for (RangePoint<?> range : ranges) {
			kryo.writeObject(output, range);
		}
	}

	@Override
	public RangeIndex read(Kryo kryo, Input input, Class<? extends RangeIndex> type) {
		final int rangeCount = input.readInt();
		final TransactionalRangePoint[] ranges = new TransactionalRangePoint[rangeCount];
		for (int i = 0; i < rangeCount; i++) {
			ranges[i] = kryo.readObject(input, TransactionalRangePoint.class);
		}
		return new RangeIndex(ranges);
	}

}
