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
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;

/**
 * This {@link Serializer} implementation reads/writes {@link AttributeSchema} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class GlobalAttributeSchemaSerializer extends Serializer<GlobalAttributeSchema> {

	@Override
	public void write(Kryo kryo, Output output, GlobalAttributeSchema attributeSchema) {
		output.writeString(attributeSchema.getName());
		kryo.writeClass(output, attributeSchema.getType());
		if (attributeSchema.getDefaultValue() == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			kryo.writeClassAndObject(output, attributeSchema.getDefaultValue());
		}
		output.writeVarInt(attributeSchema.getUniquenessType().ordinal(), true);
		output.writeVarInt(attributeSchema.getGlobalUniquenessType().ordinal(), true);
		output.writeBoolean(attributeSchema.isLocalized());
		output.writeBoolean(attributeSchema.isFilterable());
		output.writeBoolean(attributeSchema.isSortable());
		output.writeBoolean(attributeSchema.isNullable());
		output.writeBoolean(attributeSchema.isRepresentative());
		output.writeInt(attributeSchema.getIndexedDecimalPlaces());
		if (attributeSchema.getDescription() != null) {
			output.writeBoolean(true);
			output.writeString(attributeSchema.getDescription());
		} else {
			output.writeBoolean(false);
		}
		if (attributeSchema.getDeprecationNotice() != null) {
			output.writeBoolean(true);
			output.writeString(attributeSchema.getDeprecationNotice());
		} else {
			output.writeBoolean(false);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Override
	public GlobalAttributeSchema read(Kryo kryo, Input input, Class<? extends GlobalAttributeSchema> aClass) {
		final String name = input.readString();
		final Class type = kryo.readClass(input).getType();
		final Object defaultValue = input.readBoolean() ? kryo.readClassAndObject(input) : null;
		final AttributeUniquenessType unique = AttributeUniquenessType.values()[input.readVarInt(true)];
		final GlobalAttributeUniquenessType uniqueGlobally = GlobalAttributeUniquenessType.values()[input.readVarInt(true)];
		final boolean localized = input.readBoolean();
		final boolean filterable = input.readBoolean();
		final boolean sortable = input.readBoolean();
		final boolean nullable = input.readBoolean();
		final boolean representative = input.readBoolean();
		final int indexedDecimalPlaces = input.readInt();
		final String description = input.readBoolean() ? input.readString() : null;
		final String deprecationNotice = input.readBoolean() ? input.readString() : null;
		return GlobalAttributeSchema._internalBuild(
			name, description, deprecationNotice,
			unique, uniqueGlobally, filterable, sortable, localized, nullable, representative,
			type, (Serializable) defaultValue, indexedDecimalPlaces
		);
	}

}
