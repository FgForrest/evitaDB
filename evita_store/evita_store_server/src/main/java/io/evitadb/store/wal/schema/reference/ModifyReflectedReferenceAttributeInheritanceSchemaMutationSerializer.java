/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.store.wal.schema.reference;


import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReflectedReferenceAttributeInheritanceSchemaMutation;

/**
 * Serializer for {@link ModifyReflectedReferenceAttributeInheritanceSchemaMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ModifyReflectedReferenceAttributeInheritanceSchemaMutationSerializer extends Serializer<ModifyReflectedReferenceAttributeInheritanceSchemaMutation> {

	@Override
	public void write(Kryo kryo, Output output, ModifyReflectedReferenceAttributeInheritanceSchemaMutation mutation) {
		output.writeString(mutation.getName());
		kryo.writeObject(output, mutation.getAttributesInheritanceBehavior());
		final String[] attributesExcludedFromInheritance = mutation.getAttributeInheritanceFilter();
		output.writeVarInt(attributesExcludedFromInheritance.length, true);
		for (String attribute : attributesExcludedFromInheritance) {
			output.writeString(attribute);
		}
	}

	@Override
	public ModifyReflectedReferenceAttributeInheritanceSchemaMutation read(Kryo kryo, Input input, Class<? extends ModifyReflectedReferenceAttributeInheritanceSchemaMutation> type) {
		final String name = input.readString();
		final AttributeInheritanceBehavior attributeInheritanceBehavior = kryo.readObject(input, AttributeInheritanceBehavior.class);
		final int attributesExcludedFromInheritanceLength = input.readVarInt(true);
		final String[] attributesExcludedFromInheritance = new String[attributesExcludedFromInheritanceLength];
		for (int i = 0; i < attributesExcludedFromInheritanceLength; i++) {
			attributesExcludedFromInheritance[i] = input.readString();
		}
		return new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
			name, attributeInheritanceBehavior, attributesExcludedFromInheritance
		);
	}

}