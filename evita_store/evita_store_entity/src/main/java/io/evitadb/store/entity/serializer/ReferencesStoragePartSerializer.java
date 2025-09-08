/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.store.entity.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;

/**
 * This {@link Serializer} implementation reads/writes {@link ReferencesStoragePart} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferencesStoragePartSerializer extends Serializer<ReferencesStoragePart> {

	@Override
	public void write(Kryo kryo, Output output, ReferencesStoragePart object) {
		// then continue with serialization
		output.writeInt(object.getEntityPrimaryKey());
		output.writeVarInt(object.getLastUsedPrimaryKey(), true);

		final Reference[] references = object.getReferences();
		output.writeVarInt(references.length, true);
		for (Reference reference : references) {
			kryo.writeObject(output, reference);
		}
	}

	@Override
	public ReferencesStoragePart read(Kryo kryo, Input input, Class<? extends ReferencesStoragePart> type) {
		final long totalBefore = input.total();
		final int entityPrimaryKey = input.readInt();

		final int lastAssignedPrimaryKey = input.readVarInt(true);
		final int referenceCount = input.readVarInt(true);
		final Reference[] references = new Reference[referenceCount];
		for (int i = 0; i < referenceCount; i++) {
			references[i] = kryo.readObject(input, Reference.class);
		}

		return new ReferencesStoragePart(
			entityPrimaryKey, lastAssignedPrimaryKey, references,
			Math.toIntExact(input.total() - totalBefore)
		);
	}

}
