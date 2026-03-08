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
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.NamingConvention;
import lombok.RequiredArgsConstructor;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readFacetedPartiallyMap;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readIndexedComponentsMap;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readNameVariants;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readScopeSet;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readScopedReferenceIndexTypeArray;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readSortableAttributeCompounds;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.writeFacetedPartiallyMap;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.writeIndexedComponentsMap;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.writeNameVariants;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.writeScopeSet;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.writeScopedReferenceIndexTypeArray;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.writeSortableAttributeCompounds;

/**
 * This {@link Serializer} implementation reads/writes {@link ReflectedReferenceSchema} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class ReflectedReferenceSchemaSerializer extends Serializer<ReflectedReferenceSchema> {

	@Override
	public void write(Kryo kryo, Output output, ReflectedReferenceSchema referenceSchema) {
		output.writeString(referenceSchema.getName());
		writeNameVariants(output, referenceSchema.getNameVariants());
		output.writeString(referenceSchema.getReferencedEntityType());
		output.writeString(referenceSchema.getReflectedReferenceName());

		kryo.writeObjectOrNull(
			output,
			referenceSchema.isCardinalityInherited() ? null : referenceSchema.getCardinality(),
			Cardinality.class
		);

		if (referenceSchema.isIndexedInherited()) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			writeScopedReferenceIndexTypeArray(kryo, output, referenceSchema.getReferenceIndexTypeInScopes());
		}
		if (referenceSchema.isIndexedComponentsInherited()) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			writeIndexedComponentsMap(kryo, output, referenceSchema.getIndexedComponentsInScopes());
		}
		if (referenceSchema.isFacetedInherited()) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			writeScopeSet(kryo, output, referenceSchema.getFacetedInScopes());
		}

		final Map<Scope, Expression> facetedPartiallyInScopes = referenceSchema.isFacetedInherited() ?
			null : referenceSchema.getFacetedPartiallyInScopes();
		if (facetedPartiallyInScopes == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			writeFacetedPartiallyMap(kryo, output, facetedPartiallyInScopes);
		}

		kryo.writeObject(output, referenceSchema.getDeclaredAttributes());

		kryo.writeObjectOrNull(
			output,
			referenceSchema.isDescriptionInherited() ? null : referenceSchema.getDescription(),
			String.class
		);

		kryo.writeObjectOrNull(
			output,
			referenceSchema.isDeprecatedInherited() ? null : referenceSchema.getDeprecationNotice(),
			String.class
		);

		writeSortableAttributeCompounds(kryo, output, referenceSchema.getDeclaredSortableAttributeCompounds().values());

		kryo.writeObject(output, referenceSchema.getAttributesInheritanceBehavior());
		output.writeVarInt(referenceSchema.getAttributeInheritanceFilter().length, true);
		for (String attributeName : referenceSchema.getAttributeInheritanceFilter()) {
			output.writeString(attributeName);
		}
	}

	@Override
	public ReflectedReferenceSchema read(Kryo kryo, Input input, Class<? extends ReflectedReferenceSchema> aClass) {
		final String name = input.readString();
		final Map<NamingConvention, String> nameVariants = readNameVariants(input);
		final String entityType = input.readString();
		final String reflectedReferenceName = input.readString();
		final Cardinality cardinality = kryo.readObjectOrNull(input, Cardinality.class);

		final Map<Scope, ReferenceIndexType> indexedInScopes = input.readBoolean() ? readScopedReferenceIndexTypeArray(kryo, input) : null;
		final Map<Scope, Set<ReferenceIndexedComponents>> indexedComponentsInScopes = input.readBoolean() ? readIndexedComponentsMap(kryo, input) : null;
		final EnumSet<Scope> facetedInScopes = input.readBoolean() ? readScopeSet(kryo, input) : null;

		final Map<Scope, Expression> facetedPartiallyInScopes =
			input.readBoolean() ? readFacetedPartiallyMap(kryo, input) : null;

		@SuppressWarnings("unchecked") final Map<String, AttributeSchemaContract> attributes = kryo.readObject(input, Map.class);

		final String description = kryo.readObjectOrNull(input, String.class);
		final String deprecationNotice = kryo.readObjectOrNull(input, String.class);

		final Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds = readSortableAttributeCompounds(kryo, input);

		final AttributeInheritanceBehavior attributeInheritanceBehavior = kryo.readObject(input, AttributeInheritanceBehavior.class);
		final int attributesExcludedFromInheritanceCount = input.readVarInt(true);
		final String[] attributesExcludedFromInheritance = new String[attributesExcludedFromInheritanceCount];
		for (int i = 0; i < attributesExcludedFromInheritanceCount; i++) {
			attributesExcludedFromInheritance[i] = input.readString();
		}

		return ReflectedReferenceSchema._internalBuild(
			name, nameVariants, description, deprecationNotice,
			entityType, reflectedReferenceName, cardinality,
			indexedInScopes, indexedComponentsInScopes, facetedInScopes, facetedPartiallyInScopes,
			attributes, sortableAttributeCompounds,
			attributeInheritanceBehavior, attributesExcludedFromInheritance
		);
	}

}
