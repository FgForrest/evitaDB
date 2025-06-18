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
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;

import java.util.UUID;

/**
 * Serializer for {@link ModifyCatalogSchemaMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ModifyCatalogSchemaMutationSerializer extends Serializer<ModifyCatalogSchemaMutation> {

	@Override
	public void write(Kryo kryo, Output output, ModifyCatalogSchemaMutation mutation) {
		output.writeString(mutation.getCatalogName());
		if (mutation.getSessionId() == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			kryo.writeObject(output, mutation.getSessionId());
		}
		final LocalCatalogSchemaMutation[] schemaMutations = mutation.getSchemaMutations();
		output.writeVarInt(schemaMutations.length, true);
		for (LocalCatalogSchemaMutation schemaMutation : schemaMutations) {
			kryo.writeClassAndObject(output, schemaMutation);
		}
	}

	@Override
	public ModifyCatalogSchemaMutation read(Kryo kryo, Input input, Class<? extends ModifyCatalogSchemaMutation> type) {
		final String catalogName = input.readString();
		final UUID sessionId = input.readBoolean() ? kryo.readObject(input, UUID.class) : null;
		final int length = input.readVarInt(true);
		final LocalCatalogSchemaMutation[] schemaMutations = new LocalCatalogSchemaMutation[length];
		for (int i = 0; i < length; i++) {
			schemaMutations[i] = (LocalCatalogSchemaMutation) kryo.readClassAndObject(input);
		}
		return new ModifyCatalogSchemaMutation(catalogName, sessionId, schemaMutations);
	}
}
