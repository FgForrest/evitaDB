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

package io.evitadb.store.wal.schema.attribute;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.store.wal.schema.MutationSerializationFunctions;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Serializer for {@link CreateAttributeSchemaMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CreateAttributeSchemaMutationSerializer extends Serializer<CreateAttributeSchemaMutation> implements MutationSerializationFunctions {

	/**
	 * Serializes an array of ScopedAttributeUniquenessType objects to the given Kryo output.
	 *
	 * @param kryo the Kryo instance to use for serialization
	 * @param output the Output instance to write to
	 * @param scopedUniquenessTypes the array of ScopedAttributeUniquenessType objects to serialize
	 */
	static void writeScopedUniquenessTypesMap(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull ScopedAttributeUniquenessType[] scopedUniquenessTypes) {
		output.writeVarInt(scopedUniquenessTypes.length, true);
		for (ScopedAttributeUniquenessType scopedUniquenessType : scopedUniquenessTypes) {
			kryo.writeObject(output, scopedUniquenessType.scope());
			kryo.writeObject(output, scopedUniquenessType.uniquenessType());
		}
	}


	/**
	 * Reads an array of ScopedAttributeUniquenessType objects from the given Kryo input.
	 *
	 * @param kryo  the Kryo instance to use for deserialization
	 * @param input the Input instance to read from
	 * @return the array of ScopedAttributeUniquenessType objects that were read from the input
	 */
	@Nonnull
	static ScopedAttributeUniquenessType[] readScopedUniquenessTypesMap(@Nonnull Kryo kryo, @Nonnull Input input) {
		final int size = input.readVarInt(true);
		final ScopedAttributeUniquenessType[] scopedUniquenessTypes = new ScopedAttributeUniquenessType[size];
		for (int i = 0; i < size; i++) {
			Scope scope = kryo.readObject(input, Scope.class);
			AttributeUniquenessType uniquenessType = kryo.readObject(input, AttributeUniquenessType.class);
			scopedUniquenessTypes[i] = new ScopedAttributeUniquenessType(scope, uniquenessType);
		}
		return scopedUniquenessTypes;
	}

	@Override
	public void write(Kryo kryo, Output output, CreateAttributeSchemaMutation mutation) {
		output.writeString(mutation.getName());
		output.writeString(mutation.getDescription());
		output.writeString(mutation.getDeprecationNotice());
		kryo.writeClass(output, mutation.getType());

		writeScopedUniquenessTypesMap(kryo, output, mutation.getUniqueInScopes());
		writeScopeArray(kryo, output, mutation.getFilterableInScopes());
		writeScopeArray(kryo, output, mutation.getSortableInScopes());

		output.writeBoolean(mutation.isLocalized());
		output.writeBoolean(mutation.isNullable());
		output.writeBoolean(mutation.isRepresentative());
		kryo.writeObjectOrNull(output, mutation.getDefaultValue(), mutation.getType());
		output.writeVarInt(mutation.getIndexedDecimalPlaces(), true);
	}

	@Override
	public CreateAttributeSchemaMutation read(Kryo kryo, Input input, Class<? extends CreateAttributeSchemaMutation> type) {
		final String name = input.readString();
		final String description = input.readString();
		final String deprecationNotice = input.readString();

		//noinspection unchecked
		final Class<? extends Serializable> theType = kryo.readClass(input).getType();

		final ScopedAttributeUniquenessType[] uniqueInScopes = readScopedUniquenessTypesMap(kryo, input);
		final Scope[] filterableInScopes = readScopeArray(kryo, input);
		final Scope[] sortableInScopes = readScopeArray(kryo, input);

		final boolean localized = input.readBoolean();
		final boolean nullable = input.readBoolean();
		final boolean representative = input.readBoolean();
		return new CreateAttributeSchemaMutation(
			name,
			description,
			deprecationNotice,
			uniqueInScopes,
			filterableInScopes,
			sortableInScopes,
			localized,
			nullable,
			representative,
			theType,
			kryo.readObjectOrNull(input, theType),
			input.readVarInt(true)
		);
	}

}
