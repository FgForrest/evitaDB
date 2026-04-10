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
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.store.wal.schema.MutationSerializationFunctions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Serializer for {@link SetReferenceSchemaFacetedMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SetReferenceSchemaFacetedMutationSerializer extends Serializer<SetReferenceSchemaFacetedMutation> implements MutationSerializationFunctions {

	@Override
	public void write(Kryo kryo, Output output, SetReferenceSchemaFacetedMutation mutation) {
		output.writeString(mutation.getName());

		if (mutation.getFacetedInScopes() == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			writeScopeArray(kryo, output, mutation.getFacetedInScopes());
		}
		writeScopedFacetedPartiallyArray(kryo, output, mutation.getFacetedPartiallyInScopes());
	}

	@Override
	public SetReferenceSchemaFacetedMutation read(Kryo kryo, Input input, Class<? extends SetReferenceSchemaFacetedMutation> type) {
		final String name = input.readString();
		final Scope[] facetedInScopes = input.readBoolean() ? readScopeArray(kryo, input) : null;
		final ScopedFacetedPartially[] facetedPartiallyInScopes = readScopedFacetedPartiallyArray(kryo, input);
		return new SetReferenceSchemaFacetedMutation(name, facetedInScopes, facetedPartiallyInScopes);
	}

	/**
	 * Writes a nullable array of {@link ScopedFacetedPartially} to the output.
	 * A boolean presence flag is written first, followed by the array contents if present.
	 *
	 * @param kryo   the Kryo instance to use for serialization
	 * @param output the Output instance to write to
	 * @param array  the nullable array of ScopedFacetedPartially to serialize
	 */
	public static void writeScopedFacetedPartiallyArray(
		@Nonnull Kryo kryo,
		@Nonnull Output output,
		@Nullable ScopedFacetedPartially[] array
	) {
		if (array == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			output.writeVarInt(array.length, true);
			for (ScopedFacetedPartially entry : array) {
				kryo.writeObject(output, entry.scope());
				if (entry.expression() == null) {
					output.writeBoolean(false);
				} else {
					output.writeBoolean(true);
					kryo.writeObject(output, entry.expression());
				}
			}
		}
	}

	/**
	 * Reads a nullable array of {@link ScopedFacetedPartially} from the input.
	 * Expects a boolean presence flag first, then the array contents if present.
	 *
	 * @param kryo  the Kryo instance to use for deserialization
	 * @param input the Input instance to read from
	 * @return the nullable array of ScopedFacetedPartially, or null if not present
	 */
	@Nullable
	public static ScopedFacetedPartially[] readScopedFacetedPartiallyArray(
		@Nonnull Kryo kryo,
		@Nonnull Input input
	) {
		if (!input.readBoolean()) {
			return null;
		}
		final int size = input.readVarInt(true);
		final ScopedFacetedPartially[] result = new ScopedFacetedPartially[size];
		for (int i = 0; i < size; i++) {
			final Scope scope = kryo.readObject(input, Scope.class);
			final Expression expression = input.readBoolean()
				? kryo.readObject(input, Expression.class)
				: null;
			result[i] = new ScopedFacetedPartially(scope, expression);
		}
		return result;
	}

}
