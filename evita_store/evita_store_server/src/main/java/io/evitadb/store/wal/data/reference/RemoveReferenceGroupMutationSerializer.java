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

package io.evitadb.store.wal.data.reference;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import lombok.EqualsAndHashCode;

/**
 * Serializer for {@link RemoveReferenceGroupMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class RemoveReferenceGroupMutationSerializer extends Serializer<RemoveReferenceGroupMutation> {

	@Override
	public void write(Kryo kryo, Output output, RemoveReferenceGroupMutation mutation) {
		final ReferenceKey referenceKey = mutation.getReferenceKey();
		output.writeString(referenceKey.referenceName());
		output.writeInt(referenceKey.primaryKey());
	}

	@Override
	public RemoveReferenceGroupMutation read(Kryo kryo, Input input, Class<? extends RemoveReferenceGroupMutation> type) {
		return new RemoveReferenceGroupMutation(
			new ReferenceKey(
				input.readString(),
				input.readInt()
			)
		);
	}

}
