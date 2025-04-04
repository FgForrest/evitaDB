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

package io.evitadb.store.query.serializer.filter;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.filter.AttributeInSet;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.lang.reflect.Array;

/**
 * This {@link Serializer} implementation reads/writes {@link AttributeInSet} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class AttributeInSetSerializer<T extends Serializable> extends Serializer<AttributeInSet> {

	@Override
	public void write(Kryo kryo, Output output, AttributeInSet object) {
		output.writeString(object.getAttributeName());
		final Object[] set = object.getAttributeValues();
		kryo.writeClass(output, set.getClass().getComponentType());
		output.writeVarInt(set.length, true);
		for (Object comparableValue : set) {
			kryo.writeClassAndObject(output, comparableValue);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public AttributeInSet read(Kryo kryo, Input input, Class<? extends AttributeInSet> type) {
		final String attributeName = input.readString();
		final Registration arrayType = kryo.readClass(input);
		final int setSize = input.readVarInt(true);
		final T[] set = (T[]) Array.newInstance(arrayType.getType(), setSize);
		for (int i = 0; i < setSize; i++) {
			set[i] = (T) kryo.readClassAndObject(input);
		}
		return new AttributeInSet(attributeName, set);
	}



}
