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

package io.evitadb.store.wal.schema;


import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface MutationSerializationFunctions {

	/**
	 * Serializes an array of Scope objects to the given Kryo output.
	 *
	 * @param kryo   the Kryo instance to use for serialization
	 * @param output the Output instance to write to
	 * @param scopes the array of Scope objects to serialize
	 */
	default void writeScopeArray(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull Scope[] scopes) {
		output.writeVarInt(scopes.length, true);
		for (Scope scope : scopes) {
			kryo.writeObject(output, scope);
		}
	}

	/**
	 * Reads an array of Scope objects from the given Kryo input.
	 *
	 * @param kryo  the Kryo instance to use for deserialization
	 * @param input the Input instance to read from
	 * @return the array of Scope objects that were read from the input
	 */
	@Nonnull
	default Scope[] readScopeArray(@Nonnull Kryo kryo, @Nonnull Input input) {
		int size = input.readVarInt(true);
		Scope[] scopes = new Scope[size];
		for (int i = 0; i < size; i++) {
			scopes[i] = kryo.readObject(input, Scope.class);
		}
		return scopes;
	}

}
