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
import java.lang.reflect.Array;

/**
 * This {@link Serializer} implementation reads/writes any basic type wrapped into array from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class GenericArraySerializer<T extends Serializable> extends Serializer<T[]> {
	private final Class<T> targetType;

	@Override
	public void write(Kryo kryo, Output output, T[] object) {
		output.writeVarInt(object.length, true);
		for (T serializable : object) {
			kryo.writeObjectOrNull(output, serializable, this.targetType);
		}
	}

	@Override
	public T[] read(Kryo kryo, Input input, Class<? extends T[]> type) {
		final int arrayLength = input.readVarInt(true);
		final T[] serializables = (T[]) Array.newInstance(this.targetType, arrayLength);
		for(int i=0; i < arrayLength; i++) {
			serializables[i] = kryo.readObjectOrNull(input, this.targetType);
		}
		return serializables;
	}

}
