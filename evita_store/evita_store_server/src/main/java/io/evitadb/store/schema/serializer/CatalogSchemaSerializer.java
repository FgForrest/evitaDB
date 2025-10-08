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
import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.store.entity.model.schema.CatalogSchemaStoragePart;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

/**
 * This {@link Serializer} implementation reads/writes {@link CatalogSchema} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class CatalogSchemaSerializer extends Serializer<CatalogSchema> {

	@Override
	public void write(Kryo kryo, Output output, CatalogSchema catalogSchema) {
		output.writeInt(catalogSchema.version());
		output.writeString(
			Optional.ofNullable(CatalogSchemaStoragePart.getSerializationCatalogName())
				.orElseGet(catalogSchema::getName)
		);
		final Map<NamingConvention, String> nameVariants = Optional.ofNullable(CatalogSchemaStoragePart.getSerializationCatalogNameVariants())
			.orElseGet(catalogSchema::getNameVariants);
		output.writeVarInt(nameVariants.size(), true);
		for (Entry<NamingConvention, String> entry : nameVariants.entrySet()) {
			output.writeVarInt(entry.getKey().ordinal(), true);
			output.writeString(entry.getValue());
		}
		if (catalogSchema.getDescription() != null) {
			output.writeBoolean(true);
			output.writeString(catalogSchema.getDescription());
		} else {
			output.writeBoolean(false);
		}
		kryo.writeObject(output, catalogSchema.getCatalogEvolutionMode());
		kryo.writeObject(output, catalogSchema.getAttributes());
	}

	@Override
	public CatalogSchema read(Kryo kryo, Input input, Class<? extends CatalogSchema> aClass) {
		final int version = input.readInt();
		final String catalogName = input.readString();
		final int nameVariantCount = input.readVarInt(true);
		final Map<NamingConvention, String> nameVariants = CollectionUtils.createLinkedHashMap(nameVariantCount);
		for(int i = 0; i < nameVariantCount; i++) {
			nameVariants.put(
				NamingConvention.values()[input.readVarInt(true)],
				input.readString()
			);
		}
		final String description = input.readBoolean() ? input.readString() : null;
		@SuppressWarnings("unchecked") final Set<CatalogEvolutionMode> catalogEvolutionMode = kryo.readObject(input, Set.class);
		@SuppressWarnings("unchecked") final Map<String, GlobalAttributeSchemaContract> attributeSchema = kryo.readObject(input, LinkedHashMap.class);
		final CatalogContract theCatalog = CatalogSchemaStoragePart.getDeserializationContextCatalog();
		return CatalogSchema._internalBuild(
			version,
			catalogName, nameVariants, description, catalogEvolutionMode,
			attributeSchema,
			new DeserializedEntitySchemaAccessor(theCatalog)
		);
	}

	/**
	 * A class that provides access to deserialized entity schemas.
	 */
	@RequiredArgsConstructor
	private static class DeserializedEntitySchemaAccessor implements EntitySchemaProvider {
		private final CatalogContract theCatalog;

		@Nonnull
		@Override
		public Collection<EntitySchemaContract> getEntitySchemas() {
			return this.theCatalog.getEntitySchemaIndex().values();
		}

		@Nonnull
		@Override
		public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
			return this.theCatalog.getEntitySchema(entityType).map(EntitySchemaContract.class::cast);
		}
	}
}
