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

package io.evitadb.store.wal.schema.attribute;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaTypeMutation;

import java.io.Serializable;

/**
 * Serializer for {@link ModifyAttributeSchemaTypeMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ModifyAttributeSchemaTypeMutationSerializer extends Serializer<ModifyAttributeSchemaTypeMutation> {

	@Override
	public void write(Kryo kryo, Output output, ModifyAttributeSchemaTypeMutation mutation) {
		output.writeString(mutation.getName());
		kryo.writeObject(output, mutation.getType());
		output.writeVarInt(mutation.getIndexedDecimalPlaces(), true);
	}

	@Override
	public ModifyAttributeSchemaTypeMutation read(Kryo kryo, Input input, Class<? extends ModifyAttributeSchemaTypeMutation> type) {
		final String name = input.readString();
		//noinspection unchecked
		final Class<? extends Serializable> theType = kryo.readClass(input).getType();
		final int indexedDecimalPlaces = input.readVarInt(true);
		return new ModifyAttributeSchemaTypeMutation(
			name,
			theType,
			indexedDecimalPlaces
		);
	}

}
