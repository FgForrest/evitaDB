/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readScopeSet;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.writeScopeSet;

/**
 * This class contains common logic for {@link Serializer} implementations that read/write
 * {@link SortableAttributeCompoundSchema} or its subclasses from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
abstract class AbstractSortableAttributeCompoundSchemaSerializer<T extends SortableAttributeCompoundSchema> extends Serializer<T> {

	@Override
	public void write(Kryo kryo, Output output, T attributeCompoundSchema) {
		output.writeString(attributeCompoundSchema.getName());
		output.writeVarInt(attributeCompoundSchema.getNameVariants().size(), true);
		for (Entry<NamingConvention, String> entry : attributeCompoundSchema.getNameVariants().entrySet()) {
			output.writeVarInt(entry.getKey().ordinal(), true);
			output.writeString(entry.getValue());
		}

		if (attributeCompoundSchema.getDescription() != null) {
			output.writeBoolean(true);
			output.writeString(attributeCompoundSchema.getDescription());
		} else {
			output.writeBoolean(false);
		}
		if (attributeCompoundSchema.getDeprecationNotice() != null) {
			output.writeBoolean(true);
			output.writeString(attributeCompoundSchema.getDeprecationNotice());
		} else {
			output.writeBoolean(false);
		}

		writeScopeSet(kryo, output, attributeCompoundSchema.getIndexedInScopes());

		final List<AttributeElement> attributeElements = attributeCompoundSchema.getAttributeElements();
		output.writeVarInt(attributeElements.size(), true);
		for (AttributeElement attributeElement : attributeElements) {
			output.writeString(attributeElement.attributeName());
			kryo.writeObject(output, attributeElement.direction());
			kryo.writeObject(output, attributeElement.behaviour());
		}

	}

	@Override
	public T read(Kryo kryo, Input input, Class<? extends T> aClass) {
		final String name = input.readString();
		final int nameVariantCount = input.readVarInt(true);
		final Map<NamingConvention, String> nameVariants = CollectionUtils.createLinkedHashMap(nameVariantCount);
		for (int i = 0; i < nameVariantCount; i++) {
			nameVariants.put(
				NamingConvention.values()[input.readVarInt(true)],
				input.readString()
			);
		}

		final String description = input.readBoolean() ? input.readString() : null;
		final String deprecationNotice = input.readBoolean() ? input.readString() : null;

		final EnumSet<Scope> indexedInScopes = readScopeSet(kryo, input);

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

		return createSchemaInstance(
			name, nameVariants, description, deprecationNotice, indexedInScopes, attributeElements
		);
	}


	@Nonnull
	protected abstract T createSchemaInstance(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull EnumSet<Scope> indexedInScopes,
		@Nonnull List<AttributeElement> attributeElements
	);

}
