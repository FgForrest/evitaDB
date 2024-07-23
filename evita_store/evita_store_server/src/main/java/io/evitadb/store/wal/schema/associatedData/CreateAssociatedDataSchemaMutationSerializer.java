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

package io.evitadb.store.wal.schema.associatedData;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;

import java.io.Serializable;

/**
 * Serializer for {@link CreateAssociatedDataSchemaMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class CreateAssociatedDataSchemaMutationSerializer extends Serializer<CreateAssociatedDataSchemaMutation> {

	@Override
	public void write(Kryo kryo, Output output, CreateAssociatedDataSchemaMutation mutation) {
		output.writeString(mutation.getName());
		output.writeString(mutation.getDescription());
		output.writeString(mutation.getDeprecationNotice());
		kryo.writeClass(output, mutation.getType());
		output.writeBoolean(mutation.isLocalized());
		output.writeBoolean(mutation.isNullable());
	}

	@Override
	public CreateAssociatedDataSchemaMutation read(Kryo kryo, Input input, Class<? extends CreateAssociatedDataSchemaMutation> type) {
		final String name = input.readString();
		final String description = input.readString();
		final String deprecationNotice = input.readString();
		//noinspection unchecked
		final Class<? extends Serializable> theType = kryo.readClass(input).getType();
		final boolean localized = input.readBoolean();
		final boolean nullable = input.readBoolean();
		return new CreateAssociatedDataSchemaMutation(
			name,
			description,
			deprecationNotice,
			theType,
			localized,
			nullable
		);
	}

}
