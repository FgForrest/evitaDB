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
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import lombok.RequiredArgsConstructor;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;

import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readScopeSet;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readScopedReferenceIndexTypeArray;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.writeScopeSet;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.writeScopedReferenceIndexTypeArray;

/**
 * This {@link Serializer} implementation reads/writes {@link ReferenceSchema} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class ReferenceSchemaSerializer extends Serializer<ReferenceSchema> {
	private static final Function<String, EntitySchemaContract> IMPOSSIBLE_EXCEPTION_PRODUCER = s -> {
		throw new GenericEvitaInternalError("Sanity check!");
	};

	@Override
	public void write(Kryo kryo, Output output, ReferenceSchema referenceSchema) {
		output.writeString(referenceSchema.getName());
		output.writeVarInt(referenceSchema.getNameVariants().size(), true);
		for (Entry<NamingConvention, String> entry : referenceSchema.getNameVariants().entrySet()) {
			output.writeVarInt(entry.getKey().ordinal(), true);
			output.writeString(entry.getValue());
		}
		output.writeString(referenceSchema.getReferencedEntityType());
		output.writeBoolean(referenceSchema.isReferencedEntityTypeManaged());

		final Map<NamingConvention, String> entityTypeNameVariants = referenceSchema.isReferencedEntityTypeManaged() ?
			Collections.emptyMap() : referenceSchema.getEntityTypeNameVariants(IMPOSSIBLE_EXCEPTION_PRODUCER);
		output.writeVarInt(entityTypeNameVariants.size(), true);
		for (Entry<NamingConvention, String> entry : entityTypeNameVariants.entrySet()) {
			output.writeVarInt(entry.getKey().ordinal(), true);
			output.writeString(entry.getValue());
		}
		kryo.writeObject(output, referenceSchema.getCardinality());
		output.writeString(referenceSchema.getReferencedGroupType());
		output.writeBoolean(referenceSchema.isReferencedGroupTypeManaged());

		final Map<NamingConvention, String> groupTypeNameVariants = referenceSchema.isReferencedGroupTypeManaged() ?
			Collections.emptyMap() : referenceSchema.getGroupTypeNameVariants(IMPOSSIBLE_EXCEPTION_PRODUCER);
		output.writeVarInt(groupTypeNameVariants.size(), true);
		for (Entry<NamingConvention, String> entry : groupTypeNameVariants.entrySet()) {
			output.writeVarInt(entry.getKey().ordinal(), true);
			output.writeString(entry.getValue());
		}

		writeScopedReferenceIndexTypeArray(kryo, output, referenceSchema.getReferenceIndexTypeInScopes());
		writeScopeSet(kryo, output, referenceSchema.getFacetedInScopes());

		kryo.writeObject(output, referenceSchema.getAttributes());

		if (referenceSchema.getDescription() != null) {
			output.writeBoolean(true);
			output.writeString(referenceSchema.getDescription());
		} else {
			output.writeBoolean(false);
		}
		if (referenceSchema.getDeprecationNotice() != null) {
			output.writeBoolean(true);
			output.writeString(referenceSchema.getDeprecationNotice());
		} else {
			output.writeBoolean(false);
		}

		final Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds = referenceSchema.getSortableAttributeCompounds();
		output.writeVarInt(sortableAttributeCompounds.size(), true);
		for (SortableAttributeCompoundSchemaContract sortableAttributeCompound : sortableAttributeCompounds.values()) {
			kryo.writeObject(output, sortableAttributeCompound);
		}

	}

	@Override
	public ReferenceSchema read(Kryo kryo, Input input, Class<? extends ReferenceSchema> aClass) {
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
		final boolean referencedEntityTypeManaged = input.readBoolean();
		final int entityTypeNameVariantCount = input.readVarInt(true);
		final Map<NamingConvention, String> entityTypeNameVariants = CollectionUtils.createLinkedHashMap(entityTypeNameVariantCount);
		for(int i = 0; i < entityTypeNameVariantCount; i++) {
			entityTypeNameVariants.put(
				NamingConvention.values()[input.readVarInt(true)],
				input.readString()
			);
		}
		final Cardinality cardinality = kryo.readObject(input, Cardinality.class);
		final String groupType = input.readString();
		final boolean referencedGroupTypeManaged = input.readBoolean();
		final int groupTypeNameVariantCount = input.readVarInt(true);
		final Map<NamingConvention, String> groupTypeNameVariants = CollectionUtils.createLinkedHashMap(groupTypeNameVariantCount);
		for(int i = 0; i < groupTypeNameVariantCount; i++) {
			groupTypeNameVariants.put(
				NamingConvention.values()[input.readVarInt(true)],
				input.readString()
			);
		}
		final Map<Scope, ReferenceIndexType> indexedInScopes = readScopedReferenceIndexTypeArray(kryo, input);
		final EnumSet<Scope> facetedInScopes = readScopeSet(kryo, input);

		@SuppressWarnings("unchecked") final Map<String, AttributeSchemaContract> attributes = kryo.readObject(input, Map.class);
		final String description = input.readBoolean() ? input.readString() : null;
		final String deprecationNotice = input.readBoolean() ? input.readString() : null;

		final int sortableAttributeCompoundsCount = input.readVarInt(true);
		final Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds = CollectionUtils.createHashMap(sortableAttributeCompoundsCount);
		for (int i = 0; i < sortableAttributeCompoundsCount; i++) {
			final SortableAttributeCompoundSchema sortableCompoundSchema = kryo.readObject(input, SortableAttributeCompoundSchema.class);
			sortableAttributeCompounds.put(
				sortableCompoundSchema.getName(),
				sortableCompoundSchema
			);
		}

		return ReferenceSchema._internalBuild(
			name, nameVariants, description, deprecationNotice,
			cardinality,
			entityType, entityTypeNameVariants, referencedEntityTypeManaged,
			groupType, groupTypeNameVariants, referencedGroupTypeManaged,
			indexedInScopes,
			facetedInScopes,
			attributes, sortableAttributeCompounds
		);
	}

}
