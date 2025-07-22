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

package io.evitadb.store.schema.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import lombok.RequiredArgsConstructor;

import java.util.EnumSet;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readScopeSet;

/**
 * This {@link Serializer} implementation reads/writes {@link ReferenceSchema} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Deprecated(since = "2025.5", forRemoval = true)
@RequiredArgsConstructor
public class ReflectedReferenceSchemaSerializer_2025_5 extends Serializer<ReflectedReferenceSchema> {

	@Override
	public void write(Kryo kryo, Output output, ReflectedReferenceSchema referenceSchema) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public ReflectedReferenceSchema read(Kryo kryo, Input input, Class<? extends ReflectedReferenceSchema> aClass) {
		final String name = input.readString();
		final int nameVariantCount = input.readVarInt(true);
		final Map<NamingConvention, String> nameVariants = CollectionUtils.createLinkedHashMap(nameVariantCount);
		for(int i = 0; i < nameVariantCount; i++) {
			nameVariants.put(
				NamingConvention.values()[input.readVarInt(true)],
				input.readString()
			);
		}
		final String entityType = input.readString();
		final String reflectedReferenceName = input.readString();
		final Cardinality cardinality = kryo.readObjectOrNull(input, Cardinality.class);

		final EnumSet<Scope> indexedInScopes = input.readBoolean() ? readScopeSet(kryo, input) : null;
		final EnumSet<Scope> facetedInScopes = input.readBoolean() ? readScopeSet(kryo, input) : null;

		@SuppressWarnings("unchecked") final Map<String, AttributeSchemaContract> attributes = kryo.readObject(input, Map.class);

		final String description = kryo.readObjectOrNull(input, String.class);
		final String deprecationNotice = kryo.readObjectOrNull(input, String.class);

		final int sortableAttributeCompoundsCount = input.readVarInt(true);
		final Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds = CollectionUtils.createHashMap(sortableAttributeCompoundsCount);
		for (int i = 0; i < sortableAttributeCompoundsCount; i++) {
			final SortableAttributeCompoundSchema sortableCompoundSchema = kryo.readObject(input, SortableAttributeCompoundSchema.class);
			sortableAttributeCompounds.put(
				sortableCompoundSchema.getName(),
				sortableCompoundSchema
			);
		}

		final AttributeInheritanceBehavior attributeInheritanceBehavior = kryo.readObject(input, AttributeInheritanceBehavior.class);
		final int attributesExcludedFromInheritanceCount = input.readVarInt(true);
		final String[] attributesExcludedFromInheritance = new String[attributesExcludedFromInheritanceCount];
		for (int i = 0; i < attributesExcludedFromInheritanceCount; i++) {
			attributesExcludedFromInheritance[i] = input.readString();
		}

		return ReflectedReferenceSchema._internalBuild(
			name, nameVariants, description, deprecationNotice,
			entityType, reflectedReferenceName, cardinality,
			indexedInScopes == null ?
				null :
				indexedInScopes
					.stream()
					.collect(
						Collectors.toMap(
							Function.identity(),
							scope -> ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING
						)
					),
			facetedInScopes,
			attributes, sortableAttributeCompounds,
			attributeInheritanceBehavior, attributesExcludedFromInheritance
		);
	}

}
