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
import io.evitadb.store.entity.exception.StoredVersionNotSupportedException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.io.ObjectStreamClass;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * This serializer stores serializable classes along with their `serialVersionUID` and check that number during
 * deserialization.
 *
 * If number doesn't match in deserialization it tries to find proper {@link Serializer} implementation for that particular
 * `serialVersionUID` and use it for deserialization of the data in old format. If no proper implementation is found
 * {@link StoredVersionNotSupportedException} is thrown.
 *
 * Skipping follows the same dispatch: {@link #skip(Kryo, Input, Class)} delegates to the matching serializer, and one
 * that is not a {@link SkippableSerializer} reads the payload and discards it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class SerialVersionBasedSerializer<T> extends Serializer<T> implements SkippableSerializer<T> {
	/** Serializer used for the current version of the target class. */
	private final Serializer<T> currentVersionSerializer;
	/** Serial version UID of the current target class. */
	private final long currentSerializerUID;
	/** Target class named in invalid registration errors. */
	private final Class<T> targetClass;
	/** Historical readers in registration order, with primitive UIDs for allocation-free lookup. */
	private List<BackwardCompatibleSerializer<T>> backwardCompatibleSerializers;

	/**
	 * Creates a versioned serializer for the target class using its current payload serializer.
	 */
	public SerialVersionBasedSerializer(@Nonnull Serializer<T> currentVersionSerializer, @Nonnull Class<T> targetClass) {
		this.currentSerializerUID = ObjectStreamClass.lookup(targetClass).getSerialVersionUID();
		this.currentVersionSerializer = currentVersionSerializer;
		this.targetClass = targetClass;
	}

	/**
	 * Allows to register {@link Serializer} implementation that allows to read serialized data of particular
	 * old version of the class that was connected with certain `serialVersionUID` number. This allows us to evolve
	 * Java class structures through the time while still be able to deserialize old binary data even if they are
	 * not fully compatible with the current state. Each historical `serialVersionUID` may be registered only once
	 * and must differ from the current version's UID.
	 *
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the UID is already registered or belongs to the
	 *                                                       current version of the target class
	 */
	@Nonnull
	public SerialVersionBasedSerializer<T> addBackwardCompatibleSerializer(
		long serialVersionUID,
		@Nonnull Serializer<T> backwardCompatibleSerializer
	) {
		Assert.isPremiseValid(
			serialVersionUID != this.currentSerializerUID,
			() -> "Cannot register a backward compatible serializer for " + this.targetClass.getName() +
				" under the current serialVersionUID " + serialVersionUID + "."
		);
		if (this.backwardCompatibleSerializers == null) {
			this.backwardCompatibleSerializers = new ArrayList<>(4);
		}
		for (int i = 0; i < this.backwardCompatibleSerializers.size(); i++) {
			Assert.isPremiseValid(
				this.backwardCompatibleSerializers.get(i).serialVersionUID() != serialVersionUID,
				() -> "A backward compatible serializer for " + this.targetClass.getName() +
					" is already registered under serialVersionUID " + serialVersionUID + "."
			);
		}
		this.backwardCompatibleSerializers.add(
			new BackwardCompatibleSerializer<>(serialVersionUID, backwardCompatibleSerializer)
		);
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
		return resolveSerializer(input.readLong(), type).read(kryo, input, type);
	}

	/**
	 * Consumes the version prefix and delegates payload skipping to the matching reader. Readers without a skip
	 * implementation decode and discard their result, preserving support for every registered historical layout.
	 *
	 * @throws StoredVersionNotSupportedException when the stored `serialVersionUID` matches neither the current version
	 *                                            nor any registered backward compatible serializer
	 */
	@Override
	public void skip(@Nonnull Kryo kryo, @Nonnull Input input, @Nonnull Class<? extends T> type) {
		final Serializer<T> serializer = resolveSerializer(input.readLong(), type);
		if (serializer instanceof SkippableSerializer<?> skippable) {
			@SuppressWarnings("unchecked") final SkippableSerializer<T> typed = (SkippableSerializer<T>) skippable;
			typed.skip(kryo, input, type);
		} else {
			serializer.read(kryo, input, type);
		}
	}

	/**
	 * Returns the current payload reader for a storage-part-local coverage hand-over, leaving version dispatch intact.
	 */
	@Nonnull
	Serializer<T> getCurrentVersionSerializer() {
		return this.currentVersionSerializer;
	}

	/**
	 * Selects a reader without boxing the UID or allocating on a successful lookup. Only the unsupported-version
	 * branch materializes the set of historical UIDs required by the existing exception contract.
	 */
	@Nonnull
	private Serializer<T> resolveSerializer(long serializedUID, @Nonnull Class<? extends T> type) {
		if (serializedUID == this.currentSerializerUID) {
			return this.currentVersionSerializer;
		}
		if (this.backwardCompatibleSerializers != null) {
			// Only a handful of historical readers exist per type; the primitive comparison avoids both boxing
			// and hashing, and the overwhelmingly common historical layout is found by the first comparison.
			for (int i = 0; i < this.backwardCompatibleSerializers.size(); i++) {
				final BackwardCompatibleSerializer<T> entry = this.backwardCompatibleSerializers.get(i);
				if (entry.serialVersionUID() == serializedUID) {
					return entry.serializer();
				}
			}
		}
		final Set<Long> supportedVersions;
		if (this.backwardCompatibleSerializers == null) {
			supportedVersions = Collections.emptySet();
		} else {
			supportedVersions = CollectionUtils.createLinkedHashSet(this.backwardCompatibleSerializers.size());
			for (int i = 0; i < this.backwardCompatibleSerializers.size(); i++) {
				supportedVersions.add(this.backwardCompatibleSerializers.get(i).serialVersionUID());
			}
		}
		throw new StoredVersionNotSupportedException(type, serializedUID, supportedVersions);
	}

	/**
	 * One historical reader and its unboxed UID, retained in registration order.
	 *
	 * @param serialVersionUID historical UID identifying the payload layout
	 * @param serializer reader of that layout
	 * @param <T> the serialized type
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record BackwardCompatibleSerializer<T>(long serialVersionUID, @Nonnull Serializer<T> serializer) {
	}

}
