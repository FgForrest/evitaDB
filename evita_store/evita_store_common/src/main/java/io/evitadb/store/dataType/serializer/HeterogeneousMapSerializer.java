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

import java.util.Map;
import java.util.Map.Entry;
import java.util.function.IntFunction;

/**
 * This serializer implementation allows to store any implementation of the {@link Map} interface. This serializer
 * implementation expects that Map will contain no NULL values or keys and also all keys or values may be heterogeneous.
 *
 * By heterogeneous I mean that although keys and values implement common superclass they can be each of different type.
 * Opposite to this serializer is {@link MapSerializer} that allows to store only homogenous keys and values.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class HeterogeneousMapSerializer<K, V> extends Serializer<Map<K, V>> {
	public final IntFunction<Map<K, V>> setFactory;

	@Override
	public void write(Kryo kryo, Output output, Map<K, V> object) {
		output.writeVarInt(object.size(), true);
		for (Entry<K, V> entry : object.entrySet()) {
			kryo.writeClassAndObject(output, entry.getKey());
			kryo.writeClassAndObject(output, entry.getValue());
		}
	}

	@Override
	public Map<K, V> read(Kryo kryo, Input input, Class<? extends Map<K, V>> type) {
		final int itemCount = input.readVarInt(true);
		final Map<K, V> targetSet = this.setFactory.apply(itemCount);
		for (int i = 0; i < itemCount; i++) {
			//noinspection unchecked
			targetSet.put(
				(K)kryo.readClassAndObject(input),
				(V)kryo.readClassAndObject(input)
			);
		}
		return targetSet;
	}

}
