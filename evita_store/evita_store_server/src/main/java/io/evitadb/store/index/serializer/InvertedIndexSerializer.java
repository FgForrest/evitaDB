/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;

import java.util.Comparator;

/**
 * Class handles Kryo (de)serialization of {@link InvertedIndex} instances.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@Deprecated
@SuppressWarnings("rawtypes")
public class InvertedIndexSerializer extends Serializer<InvertedIndex> {

	@Override
	public void write(Kryo kryo, Output output, InvertedIndex object) {
		final ValueToRecordBitmap[] points = object.getValueToRecordBitmap();
		output.writeInt(points.length);
		for (ValueToRecordBitmap range : points) {
			kryo.writeObject(output, range);
		}
	}

	@Override
	public InvertedIndex read(Kryo kryo, Input input, Class<? extends InvertedIndex> type) {
		final int pointCount = input.readInt();
		final ValueToRecordBitmap[] points = new ValueToRecordBitmap[pointCount];
		for (int i = 0; i < pointCount; i++) {
			points[i] = kryo.readObject(input, ValueToRecordBitmap.class);
		}
		//noinspection unchecked
		return new InvertedIndex(points, Comparator.naturalOrder());
	}

}
