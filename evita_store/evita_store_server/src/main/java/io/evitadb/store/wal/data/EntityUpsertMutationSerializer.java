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

package io.evitadb.store.wal.data;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;

import java.util.Collection;

/**
 * Serializer for {@link EntityUpsertMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityUpsertMutationSerializer extends Serializer<EntityUpsertMutation> {

	@Override
	public void write(Kryo kryo, Output output, EntityUpsertMutation mutation) {
		output.writeString(mutation.getEntityType());
		kryo.writeObjectOrNull(output, mutation.getEntityPrimaryKey(), Integer.class);
		kryo.writeObject(output, mutation.expects());
		final Collection<? extends LocalMutation<?, ?>> localMutations = mutation.getLocalMutations();
		output.writeVarInt(localMutations.size(), true);
		for (LocalMutation<?, ?> localMutation : localMutations) {
			kryo.writeClassAndObject(output, localMutation);
		}
	}

	@Override
	public EntityUpsertMutation read(Kryo kryo, Input input, Class<? extends EntityUpsertMutation> type) {
		final String entityType = input.readString();
		final Integer entityPrimaryKey = kryo.readObjectOrNull(input, Integer.class);
		final EntityExistence entityExistence = kryo.readObject(input, EntityExistence.class);
		final int localMutationsSize = input.readVarInt(true);
		final LocalMutation<?, ?>[] localMutations = new LocalMutation<?, ?>[localMutationsSize];
		for (int i = 0; i < localMutationsSize; i++) {
			localMutations[i] = (LocalMutation<?, ?>) kryo.readClassAndObject(input);
		}
		return new EntityUpsertMutation(
			entityType,
			entityPrimaryKey,
			entityExistence,
			localMutations
		);
	}
}
