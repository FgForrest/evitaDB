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
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.dataType.Scope;
import io.evitadb.store.wal.schema.MutationSerializationFunctions;

import static io.evitadb.store.wal.schema.reference.SetReferenceSchemaBucketedMutationSerializer.readScopedBucketedPartiallyArray;
import static io.evitadb.store.wal.schema.reference.SetReferenceSchemaBucketedMutationSerializer.readScopedHistogramIndexDefinitionArray;
import static io.evitadb.store.wal.schema.reference.SetReferenceSchemaFacetedMutationSerializer.readScopedFacetedPartiallyArray;

/**
 * Backward-compatible read-only serializer for {@link CreateReferenceSchemaMutation} serialized in the immediate
 * pre-conflict format: the full layout — including `indexedComponentsInScopes`, `facetedPartiallyInScopes`,
 * `bucketedInScopes` and `bucketedPartiallyInScopes` — but without the trailing `conflictResolutionOverride` field. It
 * reconstructs the mutation defaulting the override to
 * {@link io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride#INHERITED}. Writing in this legacy
 * format is no longer supported.
 *
 * This complements {@link CreateReferenceSchemaMutationSerializer_2026_1}, which reads an older layout that predates the
 * `indexedComponentsInScopes`/`facetedPartiallyInScopes`/bucketed fields.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Deprecated(since = "2026.2", forRemoval = true)
public class CreateReferenceSchemaMutationSerializer_2026_2 extends Serializer<CreateReferenceSchemaMutation> implements MutationSerializationFunctions {

	@Override
	public void write(Kryo kryo, Output output, CreateReferenceSchemaMutation mutation) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used for writing.");
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

		final ScopedReferenceIndexType[] indexedInScopes = readScopedReferenceIndexTypeArray(kryo, input);
		final Scope[] facetedInScopes = readScopeArray(kryo, input);
		// read indexed components with null-check
		final ScopedReferenceIndexedComponents[] indexedComponentsInScopes =
			input.readBoolean() ? readScopedReferenceIndexedComponentsArray(kryo, input) : null;
		// read facetedPartially expressions
		final ScopedFacetedPartially[] facetedPartiallyInScopes = readScopedFacetedPartiallyArray(kryo, input);
		// read bucketed histogram definitions
		final ScopedHistogramIndexDefinition[] bucketedInScopes = readScopedHistogramIndexDefinitionArray(kryo, input);
		// read bucketedPartially expressions
		final ScopedBucketedPartially[] bucketedPartiallyInScopes = readScopedBucketedPartiallyArray(kryo, input);

		// the pre-conflict format carries no override — the 14-arg constructor defaults it to INHERITED
		return new CreateReferenceSchemaMutation(
			name,
			description,
			deprecationNotice,
			cardinality,
			referencedEntityType,
			referencedEntityTypeManaged,
			referencedGroupType,
			referencedGroupTypeManaged,
			indexedInScopes,
			indexedComponentsInScopes,
			facetedInScopes,
			facetedPartiallyInScopes,
			bucketedInScopes,
			bucketedPartiallyInScopes
		);
	}

}
