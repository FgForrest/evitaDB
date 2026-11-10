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
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.NamingConvention;

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
 * Read-only snapshot of the v2026.2.2 {@link ReflectedReferenceSchema} layout, before text composition metadata.
 * A reference read through it declares no composition mode and carries the default shared weight 0.5.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @deprecated the current layout additionally stores the composition modes and the shared weight; can be removed
 *             once no catalog written by a release up to v2026.2.2 is still in use.
 */
@Deprecated(since = "2026.3", forRemoval = true)
public class ReflectedReferenceSchemaSerializer_2026_2_2 extends Serializer<ReflectedReferenceSchema> {

	@Override
	public void write(Kryo kryo, Output output, ReflectedReferenceSchema referenceSchema) {
		throw new UnsupportedOperationException("This serializer is read-only and cannot write legacy records.");
	}

	@Override
	public ReflectedReferenceSchema read(Kryo kryo, Input input, Class<? extends ReflectedReferenceSchema> aClass) {
		final String name = input.readString();
		final Map<NamingConvention, String> nameVariants = readNameVariants(input);
		final String entityType = input.readString();
		final String reflectedReferenceName = input.readString();
		final Cardinality cardinality = kryo.readObjectOrNull(input, Cardinality.class);

		final Map<Scope, ReferenceIndexType> indexedInScopes =
			input.readBoolean() ? readScopedReferenceIndexTypeArray(kryo, input) : null;
		final Map<Scope, Set<ReferenceIndexedComponents>> indexedComponentsInScopes =
			input.readBoolean() ? readIndexedComponentsMap(kryo, input) : null;
		final EnumSet<Scope> facetedInScopes = input.readBoolean() ? readScopeSet(kryo, input) : null;

		final Map<Scope, Expression> facetedPartiallyInScopes =
			input.readBoolean() ? readFacetedPartiallyMap(kryo, input) : null;

		final Map<Scope, Map<String, HistogramIndexDefinition>> bucketedInScopes =
			readBucketedHistogramMap(kryo, input);
		final Map<Scope, Expression> bucketedPartiallyInScopes = readFacetedPartiallyMap(kryo, input);

		@SuppressWarnings("unchecked") final Map<String, AttributeSchemaContract> attributes =
			kryo.readObject(input, Map.class);

		final String description = kryo.readObjectOrNull(input, String.class);
		final String deprecationNotice = kryo.readObjectOrNull(input, String.class);

		final Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds =
			readSortableAttributeCompounds(kryo, input);

		final AttributeInheritanceBehavior attributeInheritanceBehavior =
			kryo.readObject(input, AttributeInheritanceBehavior.class);
		final int attributesExcludedFromInheritanceCount = input.readVarInt(true);
		final String[] attributesExcludedFromInheritance = new String[attributesExcludedFromInheritanceCount];
		for (int i = 0; i < attributesExcludedFromInheritanceCount; i++) {
			attributesExcludedFromInheritance[i] = input.readString();
		}

		return ReflectedReferenceSchema._internalBuild(
			name, nameVariants, description, deprecationNotice,
			entityType, reflectedReferenceName, cardinality,
			indexedInScopes, indexedComponentsInScopes, facetedInScopes, facetedPartiallyInScopes,
			bucketedInScopes, bucketedPartiallyInScopes,
			attributes, sortableAttributeCompounds,
			attributeInheritanceBehavior, attributesExcludedFromInheritance,
			null, ReflectedReferenceSchema.DEFAULT_SEARCHABLE_THROUGH_WEIGHT
		);
	}

}
