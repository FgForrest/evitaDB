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

package io.evitadb.store.wal.schema.reference;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.store.wal.schema.MutationSerializationFunctions;

import java.util.Arrays;

/**
 * Serializer for {@link CreateReferenceSchemaMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Deprecated(since = "2025.5", forRemoval = true)
public class CreateReferenceSchemaMutationSerializer_2025_5 extends Serializer<CreateReferenceSchemaMutation> implements MutationSerializationFunctions {

	@Override
	public void write(Kryo kryo, Output output, CreateReferenceSchemaMutation mutation) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public CreateReferenceSchemaMutation read(Kryo kryo, Input input, Class<? extends CreateReferenceSchemaMutation> type) {
		final String name = input.readString();
		final String description = input.readString();
		final String deprecationNotice = input.readString();
		final Cardinality cardinality = kryo.readObject(input, Cardinality.class);
		final String referencedEntityType = input.readString();
		final boolean referencedEntityTypeManaged = input.readBoolean();
		final String referencedGroupType = input.readString();
		final boolean referencedGroupTypeManaged = input.readBoolean();

		final Scope[] indexedInScopes = readScopeArray(kryo, input);
		final Scope[] facetedInScopes = readScopeArray(kryo, input);

		return new CreateReferenceSchemaMutation(
			name,
			description,
			deprecationNotice,
			cardinality,
			referencedEntityType,
			referencedEntityTypeManaged,
			referencedGroupType,
			referencedGroupTypeManaged,
			Arrays.stream(indexedInScopes)
				.map(
					scope -> new ScopedReferenceIndexType(scope, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)
				)
				.toArray(ScopedReferenceIndexType[]::new),
			facetedInScopes
		);
	}

}
