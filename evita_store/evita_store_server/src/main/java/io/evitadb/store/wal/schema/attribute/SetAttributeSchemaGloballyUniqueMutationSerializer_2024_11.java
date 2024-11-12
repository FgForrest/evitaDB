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

package io.evitadb.store.wal.schema.attribute;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaGloballyUniqueMutation;

/**
 * Serializer for {@link SetAttributeSchemaGloballyUniqueMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Deprecated
public class SetAttributeSchemaGloballyUniqueMutationSerializer_2024_11 extends Serializer<SetAttributeSchemaGloballyUniqueMutation> {

	@Override
	public void write(Kryo kryo, Output output, SetAttributeSchemaGloballyUniqueMutation mutation) {
		output.writeString(mutation.getName());
		kryo.writeObject(output, mutation.getUniqueGlobally());
	}

	@Override
	public SetAttributeSchemaGloballyUniqueMutation read(Kryo kryo, Input input, Class<? extends SetAttributeSchemaGloballyUniqueMutation> type) {
		return new SetAttributeSchemaGloballyUniqueMutation(
			input.readString(),
			kryo.readObject(input, GlobalAttributeUniquenessType.class)
		);
	}

}