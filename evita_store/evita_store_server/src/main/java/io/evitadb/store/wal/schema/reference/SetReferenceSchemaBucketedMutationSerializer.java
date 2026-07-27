/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaBucketedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.store.wal.schema.MutationSerializationFunctions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Serializer for {@link SetReferenceSchemaBucketedMutation}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class SetReferenceSchemaBucketedMutationSerializer
	extends Serializer<SetReferenceSchemaBucketedMutation>
	implements MutationSerializationFunctions {

	@Override
	public void write(Kryo kryo, Output output, SetReferenceSchemaBucketedMutation mutation) {
		output.writeString(mutation.getName());

		if (mutation.getBucketedInScopes() == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			writeScopedHistogramIndexDefinitionArray(kryo, output, mutation.getBucketedInScopes());
		}
		writeScopedBucketedPartiallyArray(kryo, output, mutation.getBucketedPartiallyInScopes());
	}

	@Override
	public SetReferenceSchemaBucketedMutation read(
		Kryo kryo, Input input, Class<? extends SetReferenceSchemaBucketedMutation> type
	) {
		final String name = input.readString();
		final ScopedHistogramIndexDefinition[] bucketedInScopes = input.readBoolean()
			? readScopedHistogramIndexDefinitionArray(kryo, input) : null;
		final ScopedBucketedPartially[] bucketedPartiallyInScopes = readScopedBucketedPartiallyArray(kryo, input);
		return new SetReferenceSchemaBucketedMutation(name, bucketedInScopes, bucketedPartiallyInScopes);
	}

	/**
	 * Writes a non-null array of {@link ScopedHistogramIndexDefinition} to the output.
	 * Each entry is written as: Scope + String (nameOfTheIndex) + nullable Expression
	 * (valueExpression) + nullable Expression (assignedWhen).
	 *
	 * @param kryo   the Kryo instance to use for serialization
	 * @param output the Output instance to write to
	 * @param array  the array of ScopedHistogramIndexDefinition to serialize
	 */
	public static void writeScopedHistogramIndexDefinitionArray(
		@Nonnull Kryo kryo,
		@Nonnull Output output,
		@Nonnull ScopedHistogramIndexDefinition[] array
	) {
		output.writeVarInt(array.length, true);
		for (final ScopedHistogramIndexDefinition entry : array) {
			kryo.writeObject(output, entry.scope());
			output.writeString(entry.nameOfTheIndex());
			if (entry.valueExpression() == null) {
				output.writeBoolean(false);
			} else {
				output.writeBoolean(true);
				kryo.writeObject(output, entry.valueExpression());
			}
			if (entry.assignedWhen() == null) {
				output.writeBoolean(false);
			} else {
				output.writeBoolean(true);
				kryo.writeObject(output, entry.assignedWhen());
			}
		}
	}

	/**
	 * Reads a non-null array of {@link ScopedHistogramIndexDefinition} from the input.
	 * Each entry is read as: Scope + String (nameOfTheIndex) + nullable Expression
	 * (valueExpression) + nullable Expression (assignedWhen).
	 *
	 * @param kryo  the Kryo instance to use for deserialization
	 * @param input the Input instance to read from
	 * @return the array of ScopedHistogramIndexDefinition
	 */
	@Nonnull
	public static ScopedHistogramIndexDefinition[] readScopedHistogramIndexDefinitionArray(
		@Nonnull Kryo kryo,
		@Nonnull Input input
	) {
		final int size = input.readVarInt(true);
		final ScopedHistogramIndexDefinition[] result = new ScopedHistogramIndexDefinition[size];
		for (int i = 0; i < size; i++) {
			final Scope scope = kryo.readObject(input, Scope.class);
			final String nameOfTheIndex = input.readString();
			final Expression valueExpression = input.readBoolean()
				? kryo.readObject(input, Expression.class)
				: null;
			final Expression assignedWhen = input.readBoolean()
				? kryo.readObject(input, Expression.class)
				: null;
			result[i] = new ScopedHistogramIndexDefinition(
				scope, nameOfTheIndex, valueExpression, assignedWhen
			);
		}
		return result;
	}

	/**
	 * Writes a nullable array of {@link ScopedBucketedPartially} to the output.
	 * A boolean presence flag is written first, followed by the array contents if present.
	 *
	 * @param kryo   the Kryo instance to use for serialization
	 * @param output the Output instance to write to
	 * @param array  the nullable array of ScopedBucketedPartially to serialize
	 */
	public static void writeScopedBucketedPartiallyArray(
		@Nonnull Kryo kryo,
		@Nonnull Output output,
		@Nullable ScopedBucketedPartially[] array
	) {
		if (array == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			output.writeVarInt(array.length, true);
			for (final ScopedBucketedPartially entry : array) {
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
	 * Reads a nullable array of {@link ScopedBucketedPartially} from the input.
	 * Expects a boolean presence flag first, then the array contents if present.
	 *
	 * @param kryo  the Kryo instance to use for deserialization
	 * @param input the Input instance to read from
	 * @return the nullable array of ScopedBucketedPartially, or null if not present
	 */
	@Nullable
	public static ScopedBucketedPartially[] readScopedBucketedPartiallyArray(
		@Nonnull Kryo kryo,
		@Nonnull Input input
	) {
		if (!input.readBoolean()) {
			return null;
		}
		final int size = input.readVarInt(true);
		final ScopedBucketedPartially[] result = new ScopedBucketedPartially[size];
		for (int i = 0; i < size; i++) {
			final Scope scope = kryo.readObject(input, Scope.class);
			final Expression expression = input.readBoolean()
				? kryo.readObject(input, Expression.class)
				: null;
			result[i] = new ScopedBucketedPartially(scope, expression);
		}
		return result;
	}

}
