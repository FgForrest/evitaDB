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

package io.evitadb.store.dataType.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.dataType.Multiple;
import io.evitadb.exception.EvitaInternalError;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;

/**
 * This {@link Serializer} implementation reads/writes {@link Multiple} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class MultipleSerializer extends Serializer<Multiple> {

	@Override
	public void write(Kryo kryo, Output output, Multiple multiple) {
		output.writeVarInt(multiple.getValues().length, true);
		for (Serializable value : multiple.getValues()) {
			kryo.writeClassAndObject(output, value);
		}
	}

	@Override
	public Multiple read(Kryo kryo, Input input, Class<? extends Multiple> type) {
		final int length = input.readVarInt(true);
		final Serializable[] values = new Serializable[length];
		for (int i = 0; i < length; i++) {
			values[i] = (Serializable) kryo.readClassAndObject(input);
		}
		if (values.length == 2) {
			return new Multiple(
				asComparableAndSerializable(values[0]),
				asComparableAndSerializable(values[1])
			);
		} else if (values.length == 3) {
			return new Multiple(
				asComparableAndSerializable(values[0]),
				asComparableAndSerializable(values[1]),
				asComparableAndSerializable(values[2])
			);
		} else if (values.length == 4) {
			return new Multiple(
				asComparableAndSerializable(values[0]),
				asComparableAndSerializable(values[1]),
				asComparableAndSerializable(values[2]),
				asComparableAndSerializable(values[3])
			);
		} else {
			throw new EvitaInternalError("Currently, only 2 to 4 arguments for multiple are supported!");
		}
	}

	public <T extends Comparable<? super T> & Serializable> T asComparableAndSerializable(Serializable value) {
		//noinspection unchecked
		return (T) value;
	}

}
