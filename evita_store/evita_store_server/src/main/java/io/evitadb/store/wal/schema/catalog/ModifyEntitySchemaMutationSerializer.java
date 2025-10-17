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

package io.evitadb.store.wal.schema.catalog;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;

/**
 * Serializer for {@link ModifyEntitySchemaMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ModifyEntitySchemaMutationSerializer extends Serializer<ModifyEntitySchemaMutation> {

	@Override
	public void write(Kryo kryo, Output output, ModifyEntitySchemaMutation mutation) {
		output.writeString(mutation.getName());
		final EntitySchemaMutation[] schemaMutations = mutation.getSchemaMutations();
		output.writeVarInt(schemaMutations.length, true);
		for (EntitySchemaMutation schemaMutation : schemaMutations) {
			kryo.writeClassAndObject(output, schemaMutation);
		}
	}

	@Override
	public ModifyEntitySchemaMutation read(Kryo kryo, Input input, Class<? extends ModifyEntitySchemaMutation> type) {
		final String entityType = input.readString();
		final int length = input.readVarInt(true);
		final LocalEntitySchemaMutation[] schemaMutations = new LocalEntitySchemaMutation[length];
		for (int i = 0; i < length; i++) {
			schemaMutations[i] = (LocalEntitySchemaMutation) kryo.readClassAndObject(input);
		}
		return new ModifyEntitySchemaMutation(entityType, schemaMutations);
	}
}
