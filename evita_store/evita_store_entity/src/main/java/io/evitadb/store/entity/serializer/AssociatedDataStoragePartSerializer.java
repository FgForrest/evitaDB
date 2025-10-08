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
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart;
import io.evitadb.store.service.KeyCompressor;
import lombok.RequiredArgsConstructor;

import static java.util.Optional.ofNullable;

/**
 * This {@link Serializer} implementation reads/writes {@link AssociatedDataStoragePart} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class AssociatedDataStoragePartSerializer extends Serializer<AssociatedDataStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, AssociatedDataStoragePart object) {
		final long uniqueId = ofNullable(object.getStoragePartPK()).orElseGet(() -> object.computeUniquePartIdAndSet(this.keyCompressor));
		output.writeLong(uniqueId);
		output.writeInt(object.getEntityPrimaryKey());
		kryo.writeObject(output, object.getValue());
	}

	@Override
	public AssociatedDataStoragePart read(Kryo kryo, Input input, Class<? extends AssociatedDataStoragePart> type) {
		final long totalBefore = input.total();
		final long uniquePartId = input.readLong();
		final int entityPrimaryKey = input.readInt();
		final AssociatedDataValue associatedDataValue = kryo.readObject(input, AssociatedDataValue.class);
		return new AssociatedDataStoragePart(
			uniquePartId, entityPrimaryKey, associatedDataValue,
			Math.toIntExact(input.total() - totalBefore)
		);
	}

}
