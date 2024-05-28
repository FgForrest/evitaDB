/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.store.dataType.serializer.HeterogeneousMapSerializer;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;

import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * This {@link Serializer} implementation reads/writes {@link EntitySchema} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class EntitySchemaSerializer extends Serializer<EntitySchema> {
	private final HeterogeneousMapSerializer<Object, Object> heterogeneousSerializer = new HeterogeneousMapSerializer<>(LinkedHashMap::new);

	@Override
	public void write(Kryo kryo, Output output, EntitySchema entitySchema) {
		output.writeInt(entitySchema.version());
		output.writeString(entitySchema.getName());
		output.writeVarInt(entitySchema.getNameVariants().size(), true);
		for (Entry<NamingConvention, String> entry : entitySchema.getNameVariants().entrySet()) {
			output.writeVarInt(entry.getKey().ordinal(), true);
			output.writeString(entry.getValue());
		}
		output.writeBoolean(entitySchema.isWithGeneratedPrimaryKey());
		output.writeBoolean(entitySchema.isWithHierarchy());
		output.writeBoolean(entitySchema.isWithPrice());
		output.writeInt(entitySchema.getIndexedPricePlaces(), true);
		kryo.writeObject(output, entitySchema.getLocales());
		kryo.writeObject(output, entitySchema.getCurrencies());
		kryo.writeObject(output, entitySchema.getAttributes(), heterogeneousSerializer);
		kryo.writeObject(output, entitySchema.getAssociatedData());
		kryo.writeObject(output, entitySchema.getReferences(), heterogeneousSerializer);
		kryo.writeObject(output, entitySchema.getEvolutionMode());
		if (entitySchema.getDescription() != null) {
			output.writeBoolean(true);
			output.writeString(entitySchema.getDescription());
		} else {
			output.writeBoolean(false);
		}
		if (entitySchema.getDeprecationNotice() != null) {
			output.writeBoolean(true);
			output.writeString(entitySchema.getDeprecationNotice());
		} else {
			output.writeBoolean(false);
		}

		final Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds = entitySchema.getSortableAttributeCompounds();
		output.writeVarInt(sortableAttributeCompounds.size(), true);
		for (SortableAttributeCompoundSchemaContract sortableAttributeCompound : sortableAttributeCompounds.values()) {
			kryo.writeObject(output, sortableAttributeCompound);
		}
	}

	@Override
	public EntitySchema read(Kryo kryo, Input input, Class<? extends EntitySchema> aClass) {
		final int version = input.readInt();
		final String entityName = input.readString();
		final int nameVariantCount = input.readVarInt(true);
		final Map<NamingConvention, String> nameVariants = CollectionUtils.createLinkedHashMap(nameVariantCount);
		for(int i = 0; i < nameVariantCount; i++) {
			nameVariants.put(
				NamingConvention.values()[input.readVarInt(true)],
				input.readString()
			);
		}
		final boolean withGeneratedPrimaryKey = input.readBoolean();
		final boolean withHierarchy = input.readBoolean();
		final boolean withPrice = input.readBoolean();
		final int indexedPricePlaces = input.readInt(true);
		@SuppressWarnings("unchecked") final Set<Locale> locales = kryo.readObject(input, LinkedHashSet.class);
		@SuppressWarnings("unchecked") final Set<Currency> currencies = kryo.readObject(input, LinkedHashSet.class);
		@SuppressWarnings("unchecked") final Map<String, EntityAttributeSchemaContract> attributeSchema = kryo.readObject(input, LinkedHashMap.class, heterogeneousSerializer);
		@SuppressWarnings("unchecked") final Map<String, AssociatedDataSchemaContract> associatedDataSchema = kryo.readObject(input, LinkedHashMap.class);
		@SuppressWarnings("unchecked") final Map<String, ReferenceSchemaContract> referenceSchema = kryo.readObject(input, LinkedHashMap.class, heterogeneousSerializer);
		@SuppressWarnings("unchecked") final Set<EvolutionMode> evolutionMode = kryo.readObject(input, Set.class);
		final String description = input.readBoolean() ? input.readString() : null;
		final String deprecationNotice = input.readBoolean() ? input.readString() : null;

		final int sortableAttributeCompoundsCount = input.readVarInt(true);
		final Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds = CollectionUtils.createHashMap(sortableAttributeCompoundsCount);
		for (int i = 0; i < sortableAttributeCompoundsCount; i++) {
			final SortableAttributeCompoundSchema compoundSchemaContract = kryo.readObject(input, SortableAttributeCompoundSchema.class);
			sortableAttributeCompounds.put(
				compoundSchemaContract.getName(),
				compoundSchemaContract
			);
		}

		return EntitySchema._internalBuild(
			version,
			entityName, nameVariants, description, deprecationNotice,
			withGeneratedPrimaryKey, withHierarchy,
			withPrice, indexedPricePlaces,
			locales,
			currencies,
			attributeSchema,
			associatedDataSchema,
			referenceSchema,
			evolutionMode,
			sortableAttributeCompounds
		);
	}

}
