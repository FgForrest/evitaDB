/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.index.RepresentativeReferenceKey;

import java.io.Serializable;

/**
 * This {@link Serializer} implementation reads/writes {@link RepresentativeReferenceKey} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class RepresentativeReferenceKeySerializer extends Serializer<RepresentativeReferenceKey> {

	@Override
	public void write(Kryo kryo, Output output, RepresentativeReferenceKey object) {
		output.writeString(object.referenceName());
		output.writeInt(object.primaryKey());
		final Serializable[] attrValues = object.representativeAttributeValues();
		output.writeVarInt(attrValues.length, true);
		for (Serializable attrValue : attrValues) {
			kryo.writeClassAndObject(output, attrValue);
		}
	}

	@Override
	public RepresentativeReferenceKey read(Kryo kryo, Input input, Class<? extends RepresentativeReferenceKey> type) {
		final String referenceName = input.readString();
		final int primaryKey = input.readInt();
		final int attrValuesCount = input.readVarInt(true);
		final Serializable[] attrValues = new Serializable[attrValuesCount];
		for (int i = 0; i < attrValuesCount; i++) {
			attrValues[i] = (Serializable) kryo.readClassAndObject(input);
		}
		return new RepresentativeReferenceKey(new ReferenceKey(referenceName, primaryKey), attrValues);
	}
}
