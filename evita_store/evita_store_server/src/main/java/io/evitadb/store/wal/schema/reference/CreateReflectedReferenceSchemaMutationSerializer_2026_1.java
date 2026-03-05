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

package io.evitadb.store.wal.schema.reference;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.store.wal.schema.MutationSerializationFunctions;

/**
 * Backward-compatible read-only serializer for {@link CreateReflectedReferenceSchemaMutation}
 * that reads the format without the `indexedComponentsInScopes` and `facetedPartiallyInScopes` fields.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Deprecated(since = "2026.1", forRemoval = true)
public class CreateReflectedReferenceSchemaMutationSerializer_2026_1 extends Serializer<CreateReflectedReferenceSchemaMutation> implements MutationSerializationFunctions {

	@Override
	public void write(Kryo kryo, Output output, CreateReflectedReferenceSchemaMutation mutation) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used for writing.");
	}

	@Override
	public CreateReflectedReferenceSchemaMutation read(Kryo kryo, Input input, Class<? extends CreateReflectedReferenceSchemaMutation> type) {
		final String name = input.readString();
		final String description = input.readString();
		final String deprecationNotice = input.readString();
		final Cardinality cardinality = kryo.readObjectOrNull(input, Cardinality.class);
		final String referencedEntityType = input.readString();
		final String reflectedReferenceName = input.readString();

		final ScopedReferenceIndexType[] indexedInScopes = input.readBoolean() ? readScopedReferenceIndexTypeArray(kryo, input) : null;
		final Scope[] facetedInScopes = input.readBoolean() ? readScopeArray(kryo, input) : null;

		final AttributeInheritanceBehavior attributeInheritanceBehavior = kryo.readObject(input, AttributeInheritanceBehavior.class);
		final int attributesExcludedFromInheritanceLength = input.readVarInt(true);
		final String[] attributesExcludedFromInheritance = new String[attributesExcludedFromInheritanceLength];
		for (int i = 0; i < attributesExcludedFromInheritanceLength; i++) {
			attributesExcludedFromInheritance[i] = input.readString();
		}

		return new CreateReflectedReferenceSchemaMutation(
			name,
			description,
			deprecationNotice,
			cardinality,
			referencedEntityType,
			reflectedReferenceName,
			indexedInScopes,
			null,
			facetedInScopes,
			attributeInheritanceBehavior,
			attributesExcludedFromInheritance
		);
	}

}
