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
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;

/**
 * Class handles Kryo (de)serialization of {@link ValueToRecordBitmap} instances.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@SuppressWarnings("rawtypes")
public class ValueToRecordBitmapSerializer extends Serializer<ValueToRecordBitmap> {

	public void write(Kryo kryo, Output output, ValueToRecordBitmap valueToRecordBitmap) {
		kryo.writeClassAndObject(output, valueToRecordBitmap.getValue());
		final Bitmap bitmap = valueToRecordBitmap.getRecordIds();
		kryo.writeObject(output, bitmap);
	}

	public ValueToRecordBitmap read(Kryo kryo, Input input, Class<? extends ValueToRecordBitmap> type) {
		final Comparable comparable = (Comparable) kryo.readClassAndObject(input);
		//noinspection unchecked
		return new ValueToRecordBitmap(
			comparable,
			kryo.readObject(input, TransactionalBitmap.class)
		);
	}

}
