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
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.store.wal.schema.MutationSerializationFunctions;

import javax.annotation.Nonnull;
import java.io.Serializable;

import static io.evitadb.store.wal.schema.attribute.CreateAttributeSchemaMutationSerializer.readScopedUniquenessTypesMap;
import static io.evitadb.store.wal.schema.attribute.CreateAttributeSchemaMutationSerializer.writeScopedUniquenessTypesMap;

/**
 * Serializer for {@link CreateGlobalAttributeSchemaMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CreateGlobalAttributeSchemaMutationSerializer extends Serializer<CreateGlobalAttributeSchemaMutation> implements MutationSerializationFunctions {

	/**
	 * Serializes an array of ScopedGlobalAttributeUniquenessType objects to the given Kryo output.
	 *
	 * @param kryo the Kryo instance to use for serialization
	 * @param output the Output instance to write to
	 * @param scopedUniquenessTypes the array of ScopedGlobalAttributeUniquenessType objects to serialize
	 */
	static void writeScopedGlobalUniquenessTypesMap(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull ScopedGlobalAttributeUniquenessType[] scopedUniquenessTypes) {
		output.writeVarInt(scopedUniquenessTypes.length, true);
		for (ScopedGlobalAttributeUniquenessType scopedUniquenessType : scopedUniquenessTypes) {
			kryo.writeObject(output, scopedUniquenessType.scope());
			kryo.writeObject(output, scopedUniquenessType.uniquenessType());
		}
	}

	/**
	 * Reads an array of ScopedGlobalAttributeUniquenessType objects from the given Kryo input.
	 *
	 * @param kryo  the Kryo instance to use for deserialization
	 * @param input the Input instance to read from
	 * @return the array of ScopedGlobalAttributeUniquenessType objects that were read from the input
	 */
	@Nonnull
	static ScopedGlobalAttributeUniquenessType[] readScopedGlobalUniquenessTypesMap(@Nonnull Kryo kryo, @Nonnull Input input) {
		final int size = input.readVarInt(true);
		final ScopedGlobalAttributeUniquenessType[] scopedGlobalUniquenessTypes = new ScopedGlobalAttributeUniquenessType[size];
		for (int i = 0; i < size; i++) {
			Scope scope = kryo.readObject(input, Scope.class);
			GlobalAttributeUniquenessType uniquenessType = kryo.readObject(input, GlobalAttributeUniquenessType.class);
			scopedGlobalUniquenessTypes[i] = new ScopedGlobalAttributeUniquenessType(scope, uniquenessType);
		}
		return scopedGlobalUniquenessTypes;
	}

	@Override
	public void write(Kryo kryo, Output output, CreateGlobalAttributeSchemaMutation mutation) {
		output.writeString(mutation.getName());
		output.writeString(mutation.getDescription());
		output.writeString(mutation.getDeprecationNotice());
		kryo.writeClass(output, mutation.getType());

		writeScopedUniquenessTypesMap(kryo, output, mutation.getUniqueInScopes());
		writeScopedGlobalUniquenessTypesMap(kryo, output, mutation.getUniqueGloballyInScopes());
		writeScopeArray(kryo, output, mutation.getFilterableInScopes());
		writeScopeArray(kryo, output, mutation.getSortableInScopes());

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

		//noinspection unchecked
		final Class<? extends Serializable> theType = kryo.readClass(input).getType();

		final ScopedAttributeUniquenessType[] uniqueInScopes = readScopedUniquenessTypesMap(kryo, input);
		final ScopedGlobalAttributeUniquenessType[] uniqueGloballyInScopes = readScopedGlobalUniquenessTypesMap(kryo, input);
		final Scope[] filterableInScopes = readScopeArray(kryo, input);
		final Scope[] sortableInScopes = readScopeArray(kryo, input);

		final boolean localized = input.readBoolean();
		final boolean nullable = input.readBoolean();
		final boolean representative = input.readBoolean();
		return new CreateGlobalAttributeSchemaMutation(
			name,
			description,
			deprecationNotice,
			uniqueInScopes, uniqueGloballyInScopes,
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
