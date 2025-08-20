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
import java.util.Set;
import java.util.function.IntFunction;

/**
 * This serializer implementation allows to store any implementation of the {@link Set} interface. This serializer
 * implementation expects that Set will contain no NULL keys and also that all keys will be homogenous.
 *
 * By homogenous I mean that all objects must be instances of the very same class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class SetSerializer<T extends Serializable> extends Serializer<Set<T>> {
	public final IntFunction<Set<T>> setFactory;

	@Override
	public void write(Kryo kryo, Output output, Set<T> object) {
		output.writeVarInt(object.size(), true);
		boolean classWritten = false;
		for (T itemInSet : object) {
			if (!classWritten) {
				kryo.writeClass(output, itemInSet.getClass());
				classWritten = true;
			}
			kryo.writeObject(output, itemInSet);
		}
	}

	@Override
	public Set<T> read(Kryo kryo, Input input, Class<? extends Set<T>> type) {
		final int itemCount = input.readVarInt(true);
		final Set<T> targetSet = this.setFactory.apply(itemCount);
		Class<T> itemClass = null;
		for (int i = 0; i < itemCount; i++) {
			if (itemClass == null) {
				//noinspection unchecked
				itemClass = kryo.readClass(input).getType();
			}
			targetSet.add(kryo.readObject(input, itemClass));
		}
		return targetSet;
	}

}
