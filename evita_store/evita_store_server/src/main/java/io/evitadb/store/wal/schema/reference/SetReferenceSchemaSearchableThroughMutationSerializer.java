/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.requestResponse.schema.ReferenceSearchMode;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceSearchMode;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaSearchableThroughMutation;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Serializer of the metadata-only reference text composition update.
 *
 * Its {@link #writeSearchModes(Kryo, Output, ScopedReferenceSearchMode[])} and
 * {@link #readSearchModes(Kryo, Input)} also store the declaration of both create mutations. They keep null - keep the
 * current modes - distinct from an empty array, which removes every mode.
 */
public class SetReferenceSchemaSearchableThroughMutationSerializer
	extends Serializer<SetReferenceSchemaSearchableThroughMutation> {

	@Override
	public void write(
		@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull SetReferenceSchemaSearchableThroughMutation mutation
	) {
		output.writeString(mutation.getName());
		writeSearchModes(kryo, output, mutation.getSearchableThroughInScopes());
		kryo.writeObjectOrNull(output, mutation.getWeight(), Float.class);
	}

	@Nonnull
	@Override
	public SetReferenceSchemaSearchableThroughMutation read(
		@Nonnull Kryo kryo, @Nonnull Input input,
		@Nonnull Class<? extends SetReferenceSchemaSearchableThroughMutation> type
	) {
		return new SetReferenceSchemaSearchableThroughMutation(
			input.readString(), readSearchModes(kryo, input), kryo.readObjectOrNull(input, Float.class)
		);
	}

	/**
	 * Writes a nullable complete scope declaration; null retains its distinct keep-current meaning.
	 *
	 * @param kryo Kryo providing name-based enum serialization
	 * @param output destination for the declaration
	 * @param modes complete scope declaration, or null to keep the current scopes
	 */
	public static void writeSearchModes(
		@Nonnull Kryo kryo, @Nonnull Output output, @Nullable ScopedReferenceSearchMode[] modes
	) {
		output.writeBoolean(modes != null);
		if (modes != null) {
			output.writeVarInt(modes.length, true);
			for (final ScopedReferenceSearchMode mode : modes) {
				kryo.writeObject(output, mode.scope());
				kryo.writeObject(output, mode.mode());
			}
		}
	}

	/**
	 * Reads a nullable complete scope declaration.
	 *
	 * @param kryo Kryo providing name-based enum serialization
	 * @param input source of the declaration
	 * @return complete scope declaration, or null to keep the current scopes
	 */
	@Nullable
	public static ScopedReferenceSearchMode[] readSearchModes(@Nonnull Kryo kryo, @Nonnull Input input) {
		if (!input.readBoolean()) {
			return null;
		}
		final int count = input.readVarInt(true);
		final ScopedReferenceSearchMode[] modes = new ScopedReferenceSearchMode[count];
		for (int i = 0; i < count; i++) {
			modes[i] = new ScopedReferenceSearchMode(
				kryo.readObject(input, Scope.class), kryo.readObject(input, ReferenceSearchMode.class)
			);
		}
		return modes;
	}
}
