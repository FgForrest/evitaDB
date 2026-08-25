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
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * This {@link Serializer} implementation reads {@link EntityAttributeSchema} from the binary format shipped by release
 * 2026.2 - the shape that predates the per-scope {@link FilterIndexCapability filter index capabilities}. That format
 * ends with the conflict-resolution override and carries no capability section at all.
 *
 * The substitution for the absent section is `null`, which {@link EntityAttributeSchema} normalizes into an empty map:
 * an attribute stored before capabilities existed is plainly filterable and asks the filter index for no acceleration,
 * which is exactly what an empty map means.
 *
 * This serializer only reads - writes always go through the current {@link EntityAttributeSchemaSerializer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @deprecated kept for backward compatibility; can be removed once no catalog written before filter index
 *             capabilities were introduced is still in use.
 */
@Deprecated(since = "2026.2", forRemoval = true)
@RequiredArgsConstructor
public class EntityAttributeSchemaSerializer_2026_2 extends Serializer<EntityAttributeSchema> {

	@Override
	public void write(Kryo kryo, Output output, EntityAttributeSchema attributeSchema) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used for writing.");
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	@Override
	public EntityAttributeSchema read(Kryo kryo, Input input, Class<? extends EntityAttributeSchema> aClass) {
		final String name = input.readString();
		final int nameVariantCount = input.readVarInt(true);
		final Map<NamingConvention, String> nameVariants = CollectionUtils.createLinkedHashMap(nameVariantCount);
		for (int i = 0; i < nameVariantCount; i++) {
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
		final boolean representative = input.readBoolean();
		final int indexedDecimalPlaces = input.readInt();
		final String description = input.readBoolean() ? input.readString() : null;
		final String deprecationNotice = input.readBoolean() ? input.readString() : null;
		final ConflictResolutionOverride conflictResolutionOverride =
			kryo.readObject(input, ConflictResolutionOverride.class);
		return EntityAttributeSchema._internalBuild(
			name, nameVariants, description, deprecationNotice,
			unique, filterable, null, sortable, localized, nullable, representative,
			type, (Serializable) defaultValue, indexedDecimalPlaces, conflictResolutionOverride
		);
	}

}
