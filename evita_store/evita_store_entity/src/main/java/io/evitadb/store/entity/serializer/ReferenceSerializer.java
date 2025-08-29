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
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

/**
 * This {@link Serializer} implementation reads/writes {@link Reference} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class ReferenceSerializer extends Serializer<Reference> {

	@Override
	public void write(Kryo kryo, Output output, Reference reference) {
		output.writeVarInt(reference.version(), true);
		final ReferenceKey referenceKey = reference.getReferenceKey();
		Assert.isPremiseValid(referenceKey.isKnownInternalPrimaryKey(), "Reference internal id must be positive!");
		output.writeVarInt(referenceKey.internalPrimaryKey(), true);
		output.writeString(referenceKey.referenceName());
		output.writeInt(referenceKey.primaryKey());
		output.writeBoolean(reference.dropped());
		final Optional<GroupEntityReference> group = reference.getGroup();
		output.writeBoolean(group.isPresent());
		group.ifPresent(it -> {
			output.writeVarInt(it.version(), true);
			output.writeInt(it.getPrimaryKey());
			output.writeBoolean(it.dropped());
		});
		final Collection<AttributeValue> attributes = reference.getAttributeValues();
		output.writeVarInt(attributes.size(), true);
		// the attributes locales are always sorted to ensure the same order of attributes in the serialized form
		attributes.stream().sorted(Comparator.comparing(AttributeValue::key))
			.forEach(attribute -> kryo.writeObject(output, attribute));
	}

	@Override
	public Reference read(Kryo kryo, Input input, Class<? extends Reference> type) {
		final EntitySchema schema = EntitySchemaContext.getEntitySchema();
		final int version = input.readVarInt(true);
		final int internalPrimaryKey = input.readVarInt(true);
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
			version,
			new ReferenceKey(referenceName, entityPrimaryKey, internalPrimaryKey),
			group, attributes, dropped
		);
	}

}
