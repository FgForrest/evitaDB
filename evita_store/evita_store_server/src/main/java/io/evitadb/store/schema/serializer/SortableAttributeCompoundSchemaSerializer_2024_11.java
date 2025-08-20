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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This {@link Serializer} implementation reads/writes {@link ReferenceSchema} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Deprecated(since = "2024.11", forRemoval = true)
@RequiredArgsConstructor
public class SortableAttributeCompoundSchemaSerializer_2024_11 extends Serializer<SortableAttributeCompoundSchema> {

	@Override
	public void write(Kryo kryo, Output output, SortableAttributeCompoundSchema attributeCompoundSchema) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public SortableAttributeCompoundSchema read(Kryo kryo, Input input, Class<? extends SortableAttributeCompoundSchema> aClass) {
		final String name = input.readString();
		final int nameVariantCount = input.readVarInt(true);
		final Map<NamingConvention, String> nameVariants = CollectionUtils.createLinkedHashMap(nameVariantCount);
		for(int i = 0; i < nameVariantCount; i++) {
			nameVariants.put(
				NamingConvention.values()[input.readVarInt(true)],
				input.readString()
			);
		}

		final String description = input.readBoolean() ? input.readString() : null;
		final String deprecationNotice = input.readBoolean() ? input.readString() : null;

		final int attributeElementCount = input.readVarInt(true);
		final List<AttributeElement> attributeElements = new ArrayList<>(attributeElementCount);
		for (int i = 0; i < attributeElementCount; i++) {
			attributeElements.add(
				new AttributeElement(
					input.readString(),
					kryo.readObject(input, OrderDirection.class),
					kryo.readObject(input, OrderBehaviour.class)
				)
			);
		}

		return SortableAttributeCompoundSchema._internalBuild(
			name, nameVariants, description, deprecationNotice,
			Scope.DEFAULT_SCOPES,
			attributeElements
		);
	}

}
