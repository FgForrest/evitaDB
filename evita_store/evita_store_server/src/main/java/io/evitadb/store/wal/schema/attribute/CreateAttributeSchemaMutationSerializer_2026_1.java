/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.store.wal.schema.MutationSerializationFunctions;

import java.io.Serializable;

/**
 * Backward-compatible reader for {@link CreateAttributeSchemaMutation} serialized in the `2026.1` format, before the
 * conflict resolution override field was added. It reconstructs the mutation defaulting the override to
 * {@link io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride#INHERITED}. Writing in this legacy
 * format is no longer supported.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Deprecated(since = "2026.1", forRemoval = true)
public class CreateAttributeSchemaMutationSerializer_2026_1 extends Serializer<CreateAttributeSchemaMutation> implements MutationSerializationFunctions {

	@Override
	public void write(Kryo kryo, Output output, CreateAttributeSchemaMutation mutation) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public CreateAttributeSchemaMutation read(Kryo kryo, Input input, Class<? extends CreateAttributeSchemaMutation> type) {
		final String name = input.readString();
		final String description = input.readString();
		final String deprecationNotice = input.readString();

		//noinspection unchecked
		final Class<? extends Serializable> theType = kryo.readClass(input).getType();

		final ScopedAttributeUniquenessType[] uniqueInScopes = CreateAttributeSchemaMutationSerializer.readScopedUniquenessTypesMap(kryo, input);
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
