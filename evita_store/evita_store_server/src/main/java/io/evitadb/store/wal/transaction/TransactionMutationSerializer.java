/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.store.wal.transaction;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.store.dataType.serializer.OffsetDateTimeSerializer;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Serializer for {@link TransactionMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class TransactionMutationSerializer extends Serializer<TransactionMutation> {
	/**
	 * Size of the serialized {@link TransactionMutation} in bytes.
	 *
	 * - serialUUID
	 * - UUID (16 bytes)
	 * - catalogVersion (8 bytes)
	 * - mutationCount (4 bytes)
	 * - walSizeInBytes (8 bytes)
	 */
	public static final int RECORD_SIZE = 8 + 8 + 8 + 8 + 4 + 8 + OffsetDateTimeSerializer.RECORD_SIZE;

	@Override
	public void write(Kryo kryo, Output output, TransactionMutation object) {
		output.writeLong(object.getTransactionId().getMostSignificantBits());
		output.writeLong(object.getTransactionId().getLeastSignificantBits());
		output.writeLong(object.getVersion());
		output.writeInt(object.getMutationCount());
		output.writeLong(object.getWalSizeInBytes());
		kryo.writeObject(output, object.getCommitTimestamp());
	}

	@Override
	public TransactionMutation read(Kryo kryo, Input input, Class<? extends TransactionMutation> type) {
		return new TransactionMutation(
			new UUID(input.readLong(), input.readLong()),
			input.readLong(),
			input.readInt(),
			input.readLong(),
			kryo.readObject(input, OffsetDateTime.class)
		);
	}

}
