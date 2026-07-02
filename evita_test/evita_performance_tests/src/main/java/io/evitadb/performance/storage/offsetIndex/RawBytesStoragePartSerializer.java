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

package io.evitadb.performance.storage.offsetIndex;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

/**
 * Minimal length-prefixed Kryo serializer for {@link RawBytesStoragePart}. Writes the primary key
 * followed by a 4-byte length and the raw payload bytes — no compression, no dedup, no nesting.
 * Designed to make the on-disk record size *exactly* `8 + 4 + data.length` bytes so the benchmark
 * can dial per-record payload size precisely.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class RawBytesStoragePartSerializer extends Serializer<RawBytesStoragePart> {

	@Override
	public void write(Kryo kryo, Output output, RawBytesStoragePart object) {
		output.writeLong(object.primaryKey());
		final byte[] data = object.data();
		output.writeInt(data.length);
		output.writeBytes(data);
	}

	@Override
	public RawBytesStoragePart read(Kryo kryo, Input input, Class<? extends RawBytesStoragePart> type) {
		final long primaryKey = input.readLong();
		final int length = input.readInt();
		final byte[] data = input.readBytes(length);
		return new RawBytesStoragePart(primaryKey, data);
	}
}
