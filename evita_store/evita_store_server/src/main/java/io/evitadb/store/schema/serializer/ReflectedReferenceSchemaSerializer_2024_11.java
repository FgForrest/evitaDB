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

package io.evitadb.store.schema.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import lombok.RequiredArgsConstructor;

import java.util.EnumSet;
import java.util.Map;
import java.util.Map.Entry;

/**
 * This {@link Serializer} implementation reads/writes {@link ReferenceSchema} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Deprecated
@RequiredArgsConstructor
public class ReflectedReferenceSchemaSerializer_2024_11 extends Serializer<ReflectedReferenceSchema> {

	@Override
	public void write(Kryo kryo, Output output, ReflectedReferenceSchema referenceSchema) {
		output.writeString(referenceSchema.getName());
		output.writeVarInt(referenceSchema.getNameVariants().size(), true);
		for (Entry<NamingConvention, String> entry : referenceSchema.getNameVariants().entrySet()) {
			output.writeVarInt(entry.getKey().ordinal(), true);
			output.writeString(entry.getValue());
		}
		output.writeString(referenceSchema.getReferencedEntityType());
		output.writeString(referenceSchema.getReflectedReferenceName());

		kryo.writeObjectOrNull(
			output,
			referenceSchema.isCardinalityInherited() ? null : referenceSchema.getCardinality(),
			Cardinality.class
		);

		kryo.writeObjectOrNull(
			output,
			referenceSchema.isFacetedInherited() ? null : referenceSchema.isFaceted(),
			Boolean.class
		);

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

		final Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds = referenceSchema.getDeclaredSortableAttributeCompounds();
		output.writeVarInt(sortableAttributeCompounds.size(), true);
		for (SortableAttributeCompoundSchemaContract sortableAttributeCompound : sortableAttributeCompounds.values()) {
			kryo.writeObject(output, sortableAttributeCompound);
		}

		kryo.writeObject(output, referenceSchema.getAttributesInheritanceBehavior());
		output.writeVarInt(referenceSchema.getAttributeInheritanceFilter().length, true);
		for (String attributeName : referenceSchema.getAttributeInheritanceFilter()) {
			output.writeString(attributeName);
		}
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
		final Boolean faceted = kryo.readObjectOrNull(input, Boolean.class);

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
			EnumSet.of(Scope.DEFAULT_SCOPE),
			faceted ? EnumSet.of(Scope.DEFAULT_SCOPE) : EnumSet.noneOf(Scope.class),
			attributes,
			sortableAttributeCompounds,
			attributeInheritanceBehavior, attributesExcludedFromInheritance
		);
	}

}
