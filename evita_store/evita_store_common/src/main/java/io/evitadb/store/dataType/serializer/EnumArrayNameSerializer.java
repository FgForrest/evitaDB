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

package io.evitadb.store.dataType.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.Array;

/**
 * This Kryo {@link Serializer} allows to serialize any Java enum array by storing its {@link Enum#name()} value.
 * Kryo by default serializes enums as {@link Enum#ordinal()} but this is not resistant to enum reordering so this
 * serializer stores and loads enum names instead.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class EnumArrayNameSerializer<T extends Enum<T>> extends Serializer<T[]> {

	@Override
	public void write(Kryo kryo, Output output, T[] object) {
		kryo.writeClass(output, object.getClass().getComponentType());
		output.writeVarInt(object.length, true);
		for (T item : object) {
			output.writeString(item.name());
		}
	}

	@Override
	public T[] read(Kryo kryo, Input input, Class<? extends T[]> type) {
		//noinspection unchecked
		final Class<T> enumType = kryo.readClass(input).getType();
		final int length = input.readVarInt(true);
		@SuppressWarnings("unchecked")
		final T[] result = (T[]) Array.newInstance(enumType, length);
		for(int i = 0; i < length; i++) {
			result[i] = Enum.valueOf(enumType, input.readString());
		}
		return result;
	}

}
