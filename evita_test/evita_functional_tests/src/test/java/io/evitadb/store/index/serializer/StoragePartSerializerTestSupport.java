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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;

/**
 * Test-scope helper that round-trips {@link StoragePart} instances through the exact production dispatch wired by
 * {@link io.evitadb.store.index.IndexStoragePartConfigurer} (i.e. through
 * {@link io.evitadb.store.entity.serializer.SerialVersionBasedSerializer}, including the 8-byte serial-version-uid
 * prefix), so per-step serializer tests exercise the same path production uses rather than a bare serializer.
 *
 * It also supports lazy-upgrade testing: {@link #decode} reads uid-prefixed bytes back through the production
 * dispatcher, which routes them to the registered backward-compatible reader for that uid. The deprecated readers'
 * write paths deliberately throw, so each lazy-upgrade test hand-encodes its legacy blob (uid prefix + the exact old
 * wire layout) locally before decoding it here.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class StoragePartSerializerTestSupport {

	private StoragePartSerializerTestSupport() {
		throw new UnsupportedOperationException("This is a utility class and cannot be instantiated!");
	}

	/**
	 * Serializes the given part through the production Kryo dispatch (uid-prefixed) and reads it straight back.
	 *
	 * @param kryo a Kryo instance wired with {@link io.evitadb.store.index.IndexStoragePartConfigurer}
	 * @param part the storage part to round-trip
	 * @param type the concrete storage-part class
	 * @param <T>  the storage-part type
	 * @return the deserialized copy, dispatched exactly as production would dispatch it
	 */
	@Nonnull
	public static <T extends StoragePart> T roundTrip(@Nonnull Kryo kryo, @Nonnull T part, @Nonnull Class<T> type) {
		final byte[] bytes = encodeCurrent(kryo, part);
		return decode(kryo, bytes, type);
	}

	/**
	 * Serializes the given part through the production Kryo dispatch (current serializer + uid prefix).
	 *
	 * @param kryo a Kryo instance wired with {@link io.evitadb.store.index.IndexStoragePartConfigurer}
	 * @param part the storage part to serialize
	 * @return the serialized bytes (uid-prefixed)
	 */
	@Nonnull
	public static byte[] encodeCurrent(@Nonnull Kryo kryo, @Nonnull StoragePart part) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			kryo.writeObject(output, part);
		}
		return os.toByteArray();
	}

	/**
	 * Reads a uid-prefixed blob back through the production dispatcher.
	 *
	 * @param kryo  a Kryo instance wired with {@link io.evitadb.store.index.IndexStoragePartConfigurer}
	 * @param bytes the uid-prefixed bytes (current or legacy)
	 * @param type  the concrete storage-part class
	 * @param <T>   the storage-part type
	 * @return the deserialized part
	 */
	@Nonnull
	public static <T extends StoragePart> T decode(@Nonnull Kryo kryo, @Nonnull byte[] bytes, @Nonnull Class<T> type) {
		try (final Input input = new Input(bytes)) {
			return kryo.readObject(input, type);
		}
	}

}
