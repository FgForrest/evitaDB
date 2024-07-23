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
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Map;
import java.util.Map.Entry;

/**
 * This {@link Serializer} implementation reads/writes {@link AssociatedDataSchema} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class AssociatedDataSchemaSerializer extends Serializer<AssociatedDataSchema> {

	@Override
	public void write(Kryo kryo, Output output, AssociatedDataSchema associatedDataSchema) {
		output.writeString(associatedDataSchema.getName());
		output.writeVarInt(associatedDataSchema.getNameVariants().size(), true);
		for (Entry<NamingConvention, String> entry : associatedDataSchema.getNameVariants().entrySet()) {
			output.writeVarInt(entry.getKey().ordinal(), true);
			output.writeString(entry.getValue());
		}
		kryo.writeClass(output, associatedDataSchema.getType());
		output.writeBoolean(associatedDataSchema.isLocalized());
		output.writeBoolean(associatedDataSchema.isNullable());
		if (associatedDataSchema.getDescription() != null) {
			output.writeBoolean(true);
			output.writeString(associatedDataSchema.getDescription());
		} else {
			output.writeBoolean(false);
		}
		if (associatedDataSchema.getDeprecationNotice() != null) {
			output.writeBoolean(true);
			output.writeString(associatedDataSchema.getDeprecationNotice());
		} else {
			output.writeBoolean(false);
		}
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
			name, nameVariants, description, deprecationNotice, type, localized, nullable
		);
	}

}
