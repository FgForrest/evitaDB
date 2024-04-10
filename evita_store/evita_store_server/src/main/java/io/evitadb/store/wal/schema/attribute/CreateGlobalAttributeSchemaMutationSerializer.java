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
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;

import java.io.Serializable;

/**
 * Serializer for {@link CreateGlobalAttributeSchemaMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CreateGlobalAttributeSchemaMutationSerializer extends Serializer<CreateGlobalAttributeSchemaMutation> {

	@Override
	public void write(Kryo kryo, Output output, CreateGlobalAttributeSchemaMutation mutation) {
		output.writeString(mutation.getName());
		output.writeString(mutation.getDescription());
		output.writeString(mutation.getDeprecationNotice());
		kryo.writeObject(output, mutation.getUnique());
		kryo.writeObject(output, mutation.getUniqueGlobally());
		kryo.writeClass(output, mutation.getType());
		output.writeBoolean(mutation.isFilterable());
		output.writeBoolean(mutation.isSortable());
		output.writeBoolean(mutation.isLocalized());
		output.writeBoolean(mutation.isNullable());
		output.writeBoolean(mutation.isRepresentative());
		kryo.writeObjectOrNull(output, mutation.getDefaultValue(), mutation.getType());
		output.writeVarInt(mutation.getIndexedDecimalPlaces(), true);
	}

	@Override
	public CreateGlobalAttributeSchemaMutation read(Kryo kryo, Input input, Class<? extends CreateGlobalAttributeSchemaMutation> type) {
		final String name = input.readString();
		final String description = input.readString();
		final String deprecationNotice = input.readString();
		final AttributeUniquenessType unique = kryo.readObject(input, AttributeUniquenessType.class);
		final GlobalAttributeUniquenessType uniqueGlobally = kryo.readObject(input, GlobalAttributeUniquenessType.class);
		//noinspection unchecked
		final Class<? extends Serializable> theType = kryo.readClass(input).getType();
		final boolean filterable = input.readBoolean();
		final boolean sortable = input.readBoolean();
		final boolean localized = input.readBoolean();
		final boolean nullable = input.readBoolean();
		final boolean representative = input.readBoolean();
		return new CreateGlobalAttributeSchemaMutation(
			name,
			description,
			deprecationNotice,
			unique, uniqueGlobally,
			filterable,
			sortable,
			localized,
			nullable,
			representative,
			theType,
			kryo.readObjectOrNull(input, theType),
			input.readVarInt(true)
		);
	}
}
