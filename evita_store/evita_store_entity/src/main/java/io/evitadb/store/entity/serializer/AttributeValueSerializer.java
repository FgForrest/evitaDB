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

package io.evitadb.store.entity.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.structure.Attributes;
import io.evitadb.store.service.KeyCompressor;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;

/**
 * This {@link Serializer} implementation reads/writes {@link Attributes} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class AttributeValueSerializer extends Serializer<AttributeValue> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, AttributeValue attributeValue) {
		output.writeVarInt(attributeValue.version(), true);
		output.writeVarInt(this.keyCompressor.getId(attributeValue.key()), true);
		kryo.writeClassAndObject(output, attributeValue.value());
		output.writeBoolean(attributeValue.dropped());
	}

	@Override
	public AttributeValue read(Kryo kryo, Input input, Class<? extends AttributeValue> type) {
		final int version = input.readVarInt(true);
		final int keyId = input.readVarInt(true);
		final AttributeKey key = this.keyCompressor.getKeyForId(keyId);
		final Serializable value = (Serializable) kryo.readClassAndObject(input);
		final boolean dropped = input.readBoolean();
		return new AttributeValue(version, key, value, dropped);
	}

}
