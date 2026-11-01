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
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.store.wal.schema.MutationSerializationFunctions;

import javax.annotation.Nonnull;

/**
 * Serializer for {@link SetReferenceSchemaIndexedMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SetReferenceSchemaIndexedMutationSerializer extends Serializer<SetReferenceSchemaIndexedMutation> implements MutationSerializationFunctions {

	@Override
	public void write(Kryo kryo, Output output, SetReferenceSchemaIndexedMutation mutation) {
		output.writeString(mutation.getName());

		if (mutation.getIndexedInScopes() == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			writeScopedReferenceIndexTypeArray(kryo, output, mutation.getIndexedInScopes());
		}

		if (mutation.getIndexedComponentsInScopes() == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			writeScopedReferenceIndexedComponentsArray(kryo, output, mutation.getIndexedComponentsInScopes());
		}
	}

	@Override
	public SetReferenceSchemaIndexedMutation read(Kryo kryo, Input input, Class<? extends SetReferenceSchemaIndexedMutation> type) {
		return new SetReferenceSchemaIndexedMutation(
			input.readString(),
			input.readBoolean() ? readScopedReferenceIndexTypeArray(kryo, input) : null,
			input.readBoolean() ? readScopedReferenceIndexedComponentsArray(kryo, input) : null
		);
	}

	/**
	 * Returns the mutation a format without indexed components stands for. Their readers create it without
	 * components, and a mutation that makes the index types inherited without components now leaves the components
	 * of a reflected reference as they are. When those formats were written, the constructor gave such a mutation
	 * {@link ScopedReferenceIndexedComponents#EMPTY}, which declares the components empty - so it gets them here and
	 * replays as it did before.
	 *
	 * @param mutation the mutation read from a format without indexed components
	 * @return the mutation with the components it was replayed with before
	 */
	@Nonnull
	static SetReferenceSchemaIndexedMutation withComponentsOfFormatWithoutThem(
		@Nonnull SetReferenceSchemaIndexedMutation mutation
	) {
		return mutation.getIndexedInScopes() == null && mutation.getIndexedComponentsInScopes() == null
			? new SetReferenceSchemaIndexedMutation(mutation.getName(), null, ScopedReferenceIndexedComponents.EMPTY)
			: mutation;
	}

}
