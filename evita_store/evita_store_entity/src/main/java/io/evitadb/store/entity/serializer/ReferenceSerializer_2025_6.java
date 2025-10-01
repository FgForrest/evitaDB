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

package io.evitadb.store.entity.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * This {@link Serializer} implementation reads/writes {@link Reference} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @deprecated This serializer is deprecated and should not be used.
 */
@Deprecated(since = "2025.6", forRemoval = true)
@RequiredArgsConstructor
public class ReferenceSerializer_2025_6 extends Serializer<Reference> {

	@Override
	public void write(Kryo kryo, Output output, Reference reference) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public Reference read(Kryo kryo, Input input, Class<? extends Reference> type) {
		final EntitySchema schema = EntitySchemaContext.getEntitySchema();
		final int version = input.readVarInt(true);
		final String referenceName = input.readString();
		final int entityPrimaryKey = input.readInt();
		final boolean dropped = input.readBoolean();
		final boolean groupExists = input.readBoolean();
		final GroupEntityReference group;
		if (groupExists) {
			final int groupVersion = input.readVarInt(true);
			final int groupPrimaryKey = input.readInt();
			final boolean groupDropped = input.readBoolean();
			final String groupType = Objects.requireNonNull(schema.getReferenceOrThrowException(referenceName).getReferencedGroupType());
			group = new GroupEntityReference(groupType, groupPrimaryKey, groupVersion, groupDropped);
		} else {
			group = null;
		}
		final int attributeCount = input.readVarInt(true);
		final LinkedHashMap<AttributeKey, AttributeValue> attributes = CollectionUtils.createLinkedHashMap(attributeCount);
		for (int i = 0; i < attributeCount; i++) {
			final AttributeValue attributeValue = kryo.readObject(input, AttributeValue.class);
			attributes.put(attributeValue.key(), attributeValue);
		}

		return new Reference(
			schema,
			schema.getReferenceOrThrowException(referenceName),
			version, new ReferenceKey(referenceName, entityPrimaryKey, -1),
			group, attributes, dropped
		);
	}

}
