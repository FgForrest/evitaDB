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
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * This {@link Serializer} implementation reads/writes {@link AttributeSchema} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @deprecated in current version the attribute schema contains representative flag
 */
@Deprecated(since = "2025.6", forRemoval = true)
@RequiredArgsConstructor
public class AttributeSchemaSerializer_2025_6 extends Serializer<AttributeSchema> {

	@Override
	public void write(Kryo kryo, Output output, AttributeSchema attributeSchema) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	@Override
	public AttributeSchema read(Kryo kryo, Input input, Class<? extends AttributeSchema> aClass) {
		final String name = input.readString();
		final int nameVariantCount = input.readVarInt(true);
		final Map<NamingConvention, String> nameVariants = CollectionUtils.createLinkedHashMap(nameVariantCount);
		for(int i = 0; i < nameVariantCount; i++) {
			nameVariants.put(
				NamingConvention.values()[input.readVarInt(true)],
				input.readString()
			);
		}
		final Class type = kryo.readClass(input).getType();
		final Object defaultValue = input.readBoolean() ? kryo.readClassAndObject(input) : null;

		final int uqSize = input.readVarInt(true);
		final EnumMap<Scope, AttributeUniquenessType> unique = new EnumMap<>(Scope.class);
		for (int i = 0; i < uqSize; i++) {
			final Scope scope = kryo.readObject(input, Scope.class);
			final AttributeUniquenessType uqType = kryo.readObject(input, AttributeUniquenessType.class);
			unique.put(scope, uqType);
		}

		final EnumSet<Scope> filterable = EntitySchemaSerializer.readScopeSet(kryo, input);
		final EnumSet<Scope> sortable = EntitySchemaSerializer.readScopeSet(kryo, input);

		final boolean localized = input.readBoolean();
		final boolean nullable = input.readBoolean();
		final int indexedDecimalPlaces = input.readInt();
		final String description = input.readBoolean() ? input.readString() : null;
		final String deprecationNotice = input.readBoolean() ? input.readString() : null;
		return AttributeSchema._internalBuild(
			name, nameVariants, description, deprecationNotice,
			unique, filterable, sortable, localized, nullable, false,
			type, (Serializable) defaultValue, indexedDecimalPlaces
		);
	}

}
