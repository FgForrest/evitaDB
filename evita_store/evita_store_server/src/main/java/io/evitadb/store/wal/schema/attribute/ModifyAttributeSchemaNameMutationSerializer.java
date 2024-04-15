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

package io.evitadb.store.wal.schema.attribute;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaNameMutation;

/**
 * Serializer for {@link ModifyAttributeSchemaNameMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ModifyAttributeSchemaNameMutationSerializer extends Serializer<ModifyAttributeSchemaNameMutation> {

	@Override
	public void write(Kryo kryo, Output output, ModifyAttributeSchemaNameMutation mutation) {
		output.writeString(mutation.getName());
		output.writeString(mutation.getNewName());
	}

	@Override
	public ModifyAttributeSchemaNameMutation read(Kryo kryo, Input input, Class<? extends ModifyAttributeSchemaNameMutation> type) {
		return new ModifyAttributeSchemaNameMutation(
			input.readString(),
			input.readString()
		);
	}
}
