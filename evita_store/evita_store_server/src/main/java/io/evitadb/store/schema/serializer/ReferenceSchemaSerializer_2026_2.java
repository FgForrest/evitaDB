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

package io.evitadb.store.schema.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.NamingConvention;
import lombok.RequiredArgsConstructor;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readBucketedHistogramMap;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readFacetedPartiallyMap;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readIndexedComponentsMap;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readNameVariants;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readScopeSet;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readScopedReferenceIndexTypeArray;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readSortableAttributeCompounds;

/**
 * Backward-compatible read-only serializer for {@link ReferenceSchema} that reads the immediate-pre-conflict format:
 * the full layout — including `indexedComponentsInScopes`, `facetedPartiallyInScopes`, `bucketedInScopes` and
 * `bucketedPartiallyInScopes` — but without the trailing `conflictResolutionOverride` field. The override defaults to
 * {@link ConflictResolutionOverride#INHERITED}. Writing in this legacy format is no longer supported.
 *
 * This complements {@link ReferenceSchemaSerializer_2026_1}, which reads an older layout that predates the
 * `indexedComponentsInScopes`/`facetedPartiallyInScopes` fields.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Deprecated(since = "2026.2", forRemoval = true)
@RequiredArgsConstructor
public class ReferenceSchemaSerializer_2026_2 extends Serializer<ReferenceSchema> {

	@Override
	public void write(Kryo kryo, Output output, ReferenceSchema referenceSchema) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used for writing.");
	}

	@Override
	public ReferenceSchema read(Kryo kryo, Input input, Class<? extends ReferenceSchema> aClass) {
		final String name = input.readString();
		final Map<NamingConvention, String> nameVariants = readNameVariants(input);
		final String entityType = input.readString();
		final boolean referencedEntityTypeManaged = input.readBoolean();
		final Map<NamingConvention, String> entityTypeNameVariants = readNameVariants(input);
		final Cardinality cardinality = kryo.readObject(input, Cardinality.class);
		final String groupType = input.readString();
		final boolean referencedGroupTypeManaged = input.readBoolean();
		final Map<NamingConvention, String> groupTypeNameVariants = readNameVariants(input);
		final Map<Scope, ReferenceIndexType> indexedInScopes = readScopedReferenceIndexTypeArray(kryo, input);
		final Map<Scope, Set<ReferenceIndexedComponents>> indexedComponentsInScopes = readIndexedComponentsMap(kryo, input);
		final EnumSet<Scope> facetedInScopes = readScopeSet(kryo, input);

		final Map<Scope, Expression> facetedPartiallyInScopes = readFacetedPartiallyMap(kryo, input);

		final Map<Scope, Map<String, HistogramIndexDefinition>> bucketedInScopes = readBucketedHistogramMap(kryo, input);
		// reuse faceted partially serializer — same Map<Scope, Expression> shape
		final Map<Scope, Expression> bucketedPartiallyInScopes = readFacetedPartiallyMap(kryo, input);

		@SuppressWarnings("unchecked") final Map<String, AttributeSchemaContract> attributes = kryo.readObject(input, Map.class);
		final String description = input.readBoolean() ? input.readString() : null;
		final String deprecationNotice = input.readBoolean() ? input.readString() : null;

		final Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds = readSortableAttributeCompounds(kryo, input);

		// the pre-conflict format carries no override — default it to INHERITED
		return ReferenceSchema._internalBuild(
			name, nameVariants, description, deprecationNotice,
			cardinality,
			entityType, entityTypeNameVariants, referencedEntityTypeManaged,
			groupType, groupTypeNameVariants, referencedGroupTypeManaged,
			indexedInScopes,
			indexedComponentsInScopes,
			facetedInScopes,
			facetedPartiallyInScopes,
			bucketedInScopes,
			bucketedPartiallyInScopes,
			attributes, sortableAttributeCompounds, ConflictResolutionOverride.INHERITED
		);
	}

}
