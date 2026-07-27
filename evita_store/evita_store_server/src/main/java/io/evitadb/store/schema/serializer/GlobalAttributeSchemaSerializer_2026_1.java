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
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.GlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.EnumMap;
import java.util.EnumSet;

/**
 * This {@link Serializer} implementation reads {@link AttributeSchema} from the pre-2026.2 binary
 * format that predates the granular conflict-resolution field. It is retained to keep released
 * 2026.1 data readable after an in-place upgrade; writing is unsupported.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @deprecated the current version stores the attribute conflict-resolution override
 */
@Deprecated(since = "2026.2", forRemoval = true)
@RequiredArgsConstructor
public class GlobalAttributeSchemaSerializer_2026_1 extends Serializer<GlobalAttributeSchema> {

	@Override
	public void write(Kryo kryo, Output output, GlobalAttributeSchema attributeSchema) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Override
	public GlobalAttributeSchema read(Kryo kryo, Input input, Class<? extends GlobalAttributeSchema> aClass) {
		final String name = input.readString();
		final Class type = kryo.readClass(input).getType();
		final Object defaultValue = input.readBoolean() ? kryo.readClassAndObject(input) : null;

		final int uqSize = input.readVarInt(true);
		final EnumMap<Scope, AttributeUniquenessType> unique = new EnumMap<>(Scope.class);
		for (int i = 0; i < uqSize; i++) {
			final Scope scope = kryo.readObject(input, Scope.class);
			final AttributeUniquenessType uqType = kryo.readObject(input, AttributeUniquenessType.class);
			unique.put(scope, uqType);
		}

		final int guqSize = input.readVarInt(true);
		final EnumMap<Scope, GlobalAttributeUniquenessType> uniqueGlobally = new EnumMap<>(Scope.class);
		for (int i = 0; i < guqSize; i++) {
			final Scope scope = kryo.readObject(input, Scope.class);
			final GlobalAttributeUniquenessType uqType = kryo.readObject(input, GlobalAttributeUniquenessType.class);
			uniqueGlobally.put(scope, uqType);
		}

		final EnumSet<Scope> filterable = EntitySchemaSerializer.readScopeSet(kryo, input);
		final EnumSet<Scope> sortable = EntitySchemaSerializer.readScopeSet(kryo, input);

		final boolean localized = input.readBoolean();
		final boolean nullable = input.readBoolean();
		final boolean representative = input.readBoolean();
		final int indexedDecimalPlaces = input.readInt();
		final String description = input.readBoolean() ? input.readString() : null;
		final String deprecationNotice = input.readBoolean() ? input.readString() : null;
		return GlobalAttributeSchema._internalBuild(
			name, description, deprecationNotice,
			unique,
			uniqueGlobally,
			filterable,
			sortable,
			localized, nullable, representative,
			type, (Serializable) defaultValue, indexedDecimalPlaces,
			ConflictResolutionOverride.INHERITED
		);
	}

}
