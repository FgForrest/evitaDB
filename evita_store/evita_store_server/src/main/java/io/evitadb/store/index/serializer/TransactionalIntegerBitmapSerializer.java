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
import com.esotericsoftware.kryo.io.KryoDataInput;
import com.esotericsoftware.kryo.io.KryoDataOutput;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.store.offsetIndex.exception.KryoSerializationException;
import org.roaringbitmap.RoaringBitmap;

import java.io.IOException;

/**
 * Class handles Kryo (de)serialization of {@link Bitmap} instances.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class TransactionalIntegerBitmapSerializer extends Serializer<TransactionalBitmap> {

	@Override
	public void write(Kryo kryo, Output output, TransactionalBitmap bitmap) {
		final RoaringBitmap roaringBitmap = RoaringBitmapBackedBitmap.getRoaringBitmap(bitmap);
		try {
			roaringBitmap.serialize(new KryoDataOutput(output));
		} catch (IOException e) {
			throw new KryoSerializationException("Cannot store bitmap!", e);
		}
	}

	@Override
	public TransactionalBitmap read(Kryo kryo, Input input, Class<? extends TransactionalBitmap> type) {
		final RoaringBitmap bitmap = new RoaringBitmap();
		try {
			bitmap.deserialize(new KryoDataInput(input));
		} catch (IOException e) {
			throw new KryoSerializationException("Cannot store bitmap!", e);
		}
		return new TransactionalBitmap(new BaseBitmap(bitmap));
	}

}
