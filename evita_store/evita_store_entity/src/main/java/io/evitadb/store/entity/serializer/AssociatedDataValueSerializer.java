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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.entity.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.store.service.KeyCompressor;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;

/**
 * This {@link Serializer} implementation reads/writes {@link AssociatedDataValue} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class AssociatedDataValueSerializer extends Serializer<AssociatedDataValue> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, AssociatedDataValue associatedDataValue) {
		output.writeVarInt(associatedDataValue.version(), true);
		output.writeVarInt(keyCompressor.getId(associatedDataValue.key()), true);
		kryo.writeClassAndObject(output, associatedDataValue.value());
		output.writeBoolean(associatedDataValue.dropped());
	}

	@Override
	public AssociatedDataValue read(Kryo kryo, Input input, Class<? extends AssociatedDataValue> type) {
		final int version = input.readVarInt(true);
		final AssociatedDataKey key = keyCompressor.getKeyForId(input.readVarInt(true));
		final Serializable value = (Serializable) kryo.readClassAndObject(input);
		final boolean dropped = input.readBoolean();
		return new AssociatedDataValue(version, key, value, dropped);
	}

}
