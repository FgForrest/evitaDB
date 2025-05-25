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

package io.evitadb.store.dataType.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.exception.StoredVersionNotSupportedException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.ObjectStreamClass;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static java.util.Optional.ofNullable;

/**
 * This serializer stores serializable classes along with their `serialVersionUID` and check that number during
 * deserialization.
 *
 * If number doesn't match in deserialization it tries to find proper {@link Serializer} implementation for that particular
 * `serialVersionUID` and use it for deserialization of the data in old format. If no proper implementation is found
 * {@link StoredVersionNotSupportedException} is thrown.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class SerialVersionBasedSerializer<T> extends Serializer<T> {
	private final Serializer<T> currentVersionSerializer;
	private final long currentSerializerUID;
	private Map<Long, Serializer<T>> backwardCompatibleSerializers;

	public SerialVersionBasedSerializer(@Nonnull Serializer<T> currentVersionSerializer, @Nonnull Class<T> targetClass) {
		this.currentSerializerUID = ObjectStreamClass.lookup(targetClass).getSerialVersionUID();;
		this.currentVersionSerializer = currentVersionSerializer;
	}

	/**
	 * Allows to register {@link Serializer} implementation that allows to read serialized data of particular
	 * old version of the class that was connected with certain `serialVersionUID` number. This allows us to evolve
	 * Java class structures through the time while still be able to deserialize old binary data even if they are
	 * not fully compatible with the current state.
	 */
	@Nonnull
	public SerialVersionBasedSerializer<T> addBackwardCompatibleSerializer(long serialVersionUID, @Nonnull Serializer<T> backwardCompatibleSerializer) {
		final Map<Long, Serializer<T>> serializerIndex = ofNullable(this.backwardCompatibleSerializers)
			.orElseGet(() -> {
				this.backwardCompatibleSerializers = new HashMap<>(32);
				return this.backwardCompatibleSerializers;
			});
		serializerIndex.put(serialVersionUID, backwardCompatibleSerializer);
		return this;
	}

	/**
	 * Writes the instance along with `serialVersionUID` information.
	 */
	@Override
	public final void write(Kryo kryo, Output output, T object) {
		// write version of the schema object definition
		// this becomes crucial so that we can evolve schema structure along with Evita progress
		// without loosing ability to read schemas stored in old versions
		output.writeLong(this.currentSerializerUID);
		this.currentVersionSerializer.write(kryo, output, object);
	}

	/**
	 * Reads class from the input stream but first it checks whether the stored `serialVersionUID` matches the current
	 * one for particular class. If it doesn't match it tries to find compatible {@link Serializer} instance and use
	 * it for deserialization.
	 *
	 * @throws StoredVersionNotSupportedException when serialVersionUID in the binary stream doesn't match current version
	 *                                            of the class and no proper backward compatible serializer that would allow to read old data is available
	 */
	@Override
	public T read(Kryo kryo, Input input, Class<? extends T> type) throws StoredVersionNotSupportedException {
		final long serializedUID = input.readLong();
		// fast line - this will be the most common way
		if (serializedUID == this.currentSerializerUID) {
			return this.currentVersionSerializer.read(kryo, input, type);
		} else {
			final Serializer<T> backwardCompatibleSerializer = ofNullable(this.backwardCompatibleSerializers)
				.map(it -> it.get(serializedUID))
				.orElse(null);
			Assert.isTrue(
				backwardCompatibleSerializer != null,
				() -> new StoredVersionNotSupportedException(
					type, serializedUID,
					ofNullable(this.backwardCompatibleSerializers).map(Map::keySet).orElse(Collections.emptySet())
				)
			);
			return backwardCompatibleSerializer.read(kryo, input, type);
		}
	}

}
