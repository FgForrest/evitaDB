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
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.util.EnumSet;

/**
 * This implementation was created as copy of {@link com.esotericsoftware.kryo.serializers.DefaultSerializers.EnumSetSerializer}
 * but correcting the mistake in serializer read method where null has been passed instead of proper type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class EnumSetSerializer extends Serializer<EnumSet<?>> {

	@Override
	public void write(Kryo kryo, Output output, EnumSet<?> object) {
		Serializer<Object> serializer;
		if (object.isEmpty()) {
			EnumSet<?> tmp = EnumSet.complementOf(object);
			if (tmp.isEmpty()) throw new KryoException("An EnumSet must have a defined Enum to be serialized.");
			serializer = kryo.writeClass(output, tmp.iterator().next().getClass()).getSerializer();
		} else {
			serializer = kryo.writeClass(output, object.iterator().next().getClass()).getSerializer();
		}
		output.writeVarInt(object.size(), true);
		for (Object element : object)
			serializer.write(kryo, output, element);
	}

	@Override
	public EnumSet<?> read(Kryo kryo, Input input, Class<? extends EnumSet<?>> type) {
		Registration registration = kryo.readClass(input);
		EnumSet object = EnumSet.noneOf(registration.getType());
		Serializer serializer = registration.getSerializer();
		int length = input.readVarInt(true);
		for (int i = 0; i < length; i++)
			object.add(serializer.read(kryo, input, registration.getType()));
		return object;
	}

}
