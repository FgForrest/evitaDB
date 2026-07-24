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
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySortableAttributeCompoundSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.store.shared.serializer.dataType.HeterogeneousMapSerializer;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;

import java.util.Currency;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readNameVariants;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readScopeSet;

/**
 * This {@link Serializer} implementation reads {@link EntitySchema} from the pre-2026.2 binary
 * format that predates the granular conflict-resolution field. It is retained to keep released
 * 2026.1 data readable after an in-place upgrade; writing is unsupported.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @deprecated the current version stores the entity conflict-resolution settings
 */
@Deprecated(since = "2026.2", forRemoval = true)
public class EntitySchemaSerializer_2026_1 extends Serializer<EntitySchema> {
	private final HeterogeneousMapSerializer<Object, Object> heterogeneousSerializer = new HeterogeneousMapSerializer<>(LinkedHashMap::new);

	@Override
	public void write(Kryo kryo, Output output, EntitySchema entitySchema) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public EntitySchema read(Kryo kryo, Input input, Class<? extends EntitySchema> aClass) {
		final int version = input.readInt();
		final String entityName = input.readString();
		final Map<NamingConvention, String> nameVariants = readNameVariants(input);
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
			null,
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
