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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.wal.schema.sortableAttributeCompound;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutation;

/**
 * Serializer for {@link CreateSortableAttributeCompoundSchemaMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class CreateSortableAttributeCompoundSchemaMutationSerializer extends Serializer<CreateSortableAttributeCompoundSchemaMutation> {

	@Override
	public void write(Kryo kryo, Output output, CreateSortableAttributeCompoundSchemaMutation mutation) {
		output.writeString(mutation.getName());
		output.writeString(mutation.getDescription());
		output.writeString(mutation.getDeprecationNotice());
		final AttributeElement[] attributeElements = mutation.getAttributeElements();
		output.writeVarInt(attributeElements.length, true);
		for (AttributeElement attributeElement : attributeElements) {
			output.writeString(attributeElement.attributeName());
			kryo.writeObject(output, attributeElement.direction());
			kryo.writeObject(output, attributeElement.behaviour());
		}
	}

	@Override
	public CreateSortableAttributeCompoundSchemaMutation read(Kryo kryo, Input input, Class<? extends CreateSortableAttributeCompoundSchemaMutation> type) {
		final String name = input.readString();
		final String description = input.readString();
		final String deprecationNotice = input.readString();
		final int attributeElementsLength = input.readVarInt(true);
		final AttributeElement[] attributeElements = new AttributeElement[attributeElementsLength];
		for (int i = 0; i < attributeElementsLength; i++) {
			final String attributeName = input.readString();
			final OrderDirection direction = kryo.readObject(input, OrderDirection.class);
			final OrderBehaviour behaviour = kryo.readObject(input, OrderBehaviour.class);
			attributeElements[i] = new AttributeElement(attributeName, direction, behaviour);
		}
		return new CreateSortableAttributeCompoundSchemaMutation(
			name, description, deprecationNotice, attributeElements
		);
	}

}
