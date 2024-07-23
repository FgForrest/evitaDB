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

package io.evitadb.store.wal.schema.reference;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;

/**
 * Serializer for {@link ModifyReferenceAttributeSchemaMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ModifyReferenceAttributeSchemaMutationSerializer extends Serializer<ModifyReferenceAttributeSchemaMutation> {

	@Override
	public void write(Kryo kryo, Output output, ModifyReferenceAttributeSchemaMutation mutation) {
		output.writeString(mutation.getName());
		kryo.writeClassAndObject(output, mutation.getAttributeSchemaMutation());
	}

	@Override
	public ModifyReferenceAttributeSchemaMutation read(Kryo kryo, Input input, Class<? extends ModifyReferenceAttributeSchemaMutation> type) {
		return new ModifyReferenceAttributeSchemaMutation(
			input.readString(),
			(ReferenceSchemaMutation) kryo.readClassAndObject(input)
		);
	}
}
