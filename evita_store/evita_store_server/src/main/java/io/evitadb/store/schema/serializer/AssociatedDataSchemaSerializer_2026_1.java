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
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * This {@link Serializer} implementation reads {@link AssociatedDataSchema} from the pre-2026.2
 * binary format that predates the granular conflict-resolution field. It is retained to keep
 * released 2026.1 data readable after an in-place upgrade; writing is unsupported.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @deprecated the current version stores the associated data conflict-resolution override
 */
@Deprecated(since = "2026.2", forRemoval = true)
@RequiredArgsConstructor
public class AssociatedDataSchemaSerializer_2026_1 extends Serializer<AssociatedDataSchema> {

	@Override
	public void write(Kryo kryo, Output output, AssociatedDataSchema associatedDataSchema) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public AssociatedDataSchema read(Kryo kryo, Input input, Class<? extends AssociatedDataSchema> aClass) {
		final String name = input.readString();
		final int nameVariantCount = input.readVarInt(true);
		final Map<NamingConvention, String> nameVariants = CollectionUtils.createLinkedHashMap(nameVariantCount);
		for(int i = 0; i < nameVariantCount; i++) {
			nameVariants.put(
				NamingConvention.values()[input.readVarInt(true)],
				input.readString()
			);
		}
		//noinspection unchecked
		final Class<? extends Serializable> type = kryo.readClass(input).getType();
		final boolean localized = input.readBoolean();
		final boolean nullable = input.readBoolean();
		final String description = input.readBoolean() ? input.readString() : null;
		final String deprecationNotice = input.readBoolean() ? input.readString() : null;
		return AssociatedDataSchema._internalBuild(
			name, nameVariants, description, deprecationNotice, type, localized, nullable,
			ConflictResolutionOverride.INHERITED
		);
	}

}
