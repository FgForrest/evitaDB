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
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySortableAttributeCompoundSchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.store.dataType.serializer.HeterogeneousMapSerializer;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import java.util.Currency;
import java.util.EnumSet;
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

	/**
	 * Serializes an EnumSet of Scope objects to the given Kryo output.
	 *
	 * @param kryo   the Kryo instance to use for serialization
	 * @param output the Output instance to write to
	 * @param scopes the EnumSet of Scope objects to serialize
	 */
	static void writeScopeSet(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull Set<Scope> scopes) {
		output.writeVarInt(scopes.size(), true);
		for (Scope filterableInScope : scopes) {
			kryo.writeObject(output, filterableInScope);
		}
	}

	/**
	 * Reads an EnumSet of Scope objects from the given Kryo input.
	 *
	 * @param kryo  the Kryo instance to use for deserialization
	 * @param input the Input instance to read from
	 * @return the EnumSet of Scope objects that were read from the input
	 */
	@Nonnull
	static EnumSet<Scope> readScopeSet(@Nonnull Kryo kryo, @Nonnull Input input) {
		int size = input.readVarInt(true);
		final EnumSet<Scope> scopes = EnumSet.noneOf(Scope.class);
		for (int i = 0; i < size; i++) {
			scopes.add(kryo.readObject(input, Scope.class));
		}
		return scopes;
	}

	/**
	 * Serializes a map of {@code Scope} to {@code ReferenceIndexType} into a Kryo {@code Output}.
	 * This method writes the size of the map and then serializes each key-value pair into the output
	 * using the provided Kryo instance.
	 *
	 * @param kryo         the Kryo instance to use for serialization
	 * @param output       the Output instance to write the serialized data to
	 * @param indexTypeMap the map of {@code Scope} to {@code ReferenceIndexType} to serialize
	 */
	static void writeScopedReferenceIndexTypeArray(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull Map<Scope, ReferenceIndexType> indexTypeMap) {
		output.writeVarInt(indexTypeMap.size(), true);
		for (Entry<Scope, ReferenceIndexType> entry : indexTypeMap.entrySet()) {
			kryo.writeObject(output, entry.getKey());
			kryo.writeObject(output, entry.getValue());
		}
	}

	/**
	 * Reads a map of {@code Scope} to {@code ReferenceIndexType} from a Kryo {@code Input} stream.
	 * This method first reads the size of the map and then deserializes each key-value pair
	 * using the provided Kryo instance.
	 *
	 * @param kryo  the Kryo instance used for deserialization
	 * @param input the Input stream to read the serialized data from
	 * @return a map of {@code Scope} to {@code ReferenceIndexType} that was deserialized from the input
	 */
	@Nonnull
	static Map<Scope, ReferenceIndexType> readScopedReferenceIndexTypeArray(@Nonnull Kryo kryo, @Nonnull Input input) {
		int size = input.readVarInt(true);
		final Map<Scope, ReferenceIndexType> indexTypeMap = CollectionUtils.createHashMap(size);
		for (int i = 0; i < size; i++) {
			final Scope scope = kryo.readObject(input, Scope.class);
			final ReferenceIndexType referenceIndexType = kryo.readObject(input, ReferenceIndexType.class);
			indexTypeMap.put(scope, referenceIndexType);
		}
		return indexTypeMap;
	}

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
		writeScopeSet(kryo, output, entitySchema.getHierarchyIndexedInScopes());
		output.writeBoolean(entitySchema.isWithPrice());
		writeScopeSet(kryo, output, entitySchema.getPriceIndexedInScopes());
		output.writeInt(entitySchema.getIndexedPricePlaces(), true);
		kryo.writeObject(output, entitySchema.getLocales());
		kryo.writeObject(output, entitySchema.getCurrencies());
		kryo.writeObject(output, entitySchema.getAttributes(), this.heterogeneousSerializer);
		kryo.writeObject(output, entitySchema.getAssociatedData());
		kryo.writeObject(output, entitySchema.getReferences(), this.heterogeneousSerializer);
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

		final Map<String, EntitySortableAttributeCompoundSchemaContract> sortableAttributeCompounds = entitySchema.getSortableAttributeCompounds();
		output.writeVarInt(sortableAttributeCompounds.size(), true);
		for (EntitySortableAttributeCompoundSchemaContract sortableAttributeCompound : sortableAttributeCompounds.values()) {
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
		final EnumSet<Scope> hierarchyIndexedInScopes = readScopeSet(kryo, input);
		final boolean withPrice = input.readBoolean();
		final EnumSet<Scope> priceIndexedInScopes = readScopeSet(kryo, input);
		final int indexedPricePlaces = input.readInt(true);
		@SuppressWarnings("unchecked") final Set<Locale> locales = kryo.readObject(input, LinkedHashSet.class);
		@SuppressWarnings("unchecked") final Set<Currency> currencies = kryo.readObject(input, LinkedHashSet.class);
		@SuppressWarnings("unchecked") final Map<String, EntityAttributeSchemaContract> attributeSchema = kryo.readObject(input, LinkedHashMap.class, this.heterogeneousSerializer);
		@SuppressWarnings("unchecked") final Map<String, AssociatedDataSchemaContract> associatedDataSchema = kryo.readObject(input, LinkedHashMap.class);
		@SuppressWarnings("unchecked") final Map<String, ReferenceSchemaContract> referenceSchema = kryo.readObject(input, LinkedHashMap.class, this.heterogeneousSerializer);
		@SuppressWarnings("unchecked") final Set<EvolutionMode> evolutionMode = kryo.readObject(input, Set.class);
		final String description = input.readBoolean() ? input.readString() : null;
		final String deprecationNotice = input.readBoolean() ? input.readString() : null;

		final int sortableAttributeCompoundsCount = input.readVarInt(true);
		final Map<String, EntitySortableAttributeCompoundSchemaContract> sortableAttributeCompounds = CollectionUtils.createHashMap(sortableAttributeCompoundsCount);
		for (int i = 0; i < sortableAttributeCompoundsCount; i++) {
			final EntitySortableAttributeCompoundSchemaContract compoundSchemaContract = kryo.readObject(input, EntitySortableAttributeCompoundSchema.class);
			sortableAttributeCompounds.put(
				compoundSchemaContract.getName(),
				compoundSchemaContract
			);
		}

		return EntitySchema._internalBuild(
			version,
			entityName, nameVariants, description, deprecationNotice,
			withGeneratedPrimaryKey,
			withHierarchy,
			hierarchyIndexedInScopes,
			withPrice,
			priceIndexedInScopes,
			indexedPricePlaces,
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
