/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.store.dataType.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.function.IntFunction;

/**
 * This serializer implementation allows to store any implementation of the {@link List} interface. This serializer
 * implementation expects that List will contain no NULL values and also all values will be homogenous.
 *
 * By homogenous I mean, that all objects must be instances of the very same class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class ListSerializer<K extends Serializable> extends Serializer<List<K>> {
	public final IntFunction<List<K>> listFactory;

	@Override
	public void write(Kryo kryo, Output output, List<K> object) {
		output.writeVarInt(object.size(), true);
		boolean classWritten = false;
		for (Serializable item : object) {
			if (!classWritten) {
				kryo.writeClass(output, item.getClass());
				classWritten = true;
			}
			kryo.writeObject(output, item);
		}
	}

	@Override
	public List<K> read(Kryo kryo, Input input, Class<? extends List<K>> type) {
		final int size = input.readVarInt(true);
		Class<K> itemClass = null;
		final List<K> collection = this.listFactory.apply(size);
		for (int i = 0; i < size; i++) {
			if (itemClass == null) {
				//noinspection unchecked
				itemClass = kryo.readClass(input).getType();
			}
			collection.add(kryo.readObject(input, itemClass));
		}
		return collection;
	}

}
