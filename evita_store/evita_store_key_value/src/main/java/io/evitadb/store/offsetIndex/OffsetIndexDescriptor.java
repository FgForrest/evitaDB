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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.offsetIndex;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.store.compressor.ReadOnlyKeyCompressor;
import io.evitadb.store.compressor.ReadWriteKeyCompressor;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.model.PersistentStorageDescriptor;
import io.evitadb.store.service.KeyCompressor;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Map;
import java.util.function.Function;

/**
 * This DTO contains all data that needs to be known when {@link OffsetIndex} is flushed to the disk.
 * It contains pointer to the last OffsetIndex fragment as well as all class with ids that has been registered during
 * OffsetIndex serialization process. These data needs to be stored elsewhere and used for correct OffsetIndex reinitialization.
 *
 * Descriptor is evitaDB agnostic and can be used separately along with OffsetIndex object without any specifics tied
 * to the Evita objects.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Immutable
@ThreadSafe
public class OffsetIndexDescriptor implements PersistentStorageDescriptor {

	/**
	 * Descriptor version is incremented with each update. Version is not stored on the disk, it serves only to distinguish
	 * whether there is any change made in the header and whether it needs to be persisted on disk.
	 */
	private final long version;
	/**
	 * Contains location of the last OffsetIndex fragment for this version of the header / collection.
	 */
	@Nullable private final FileLocation fileLocation;
	/**
	 * Implementation of {@link KeyCompressor} that allows adding new entries during write.
	 */
	@Getter @Nonnull private final ReadWriteKeyCompressor writeKeyCompressor;
	/**
	 * Implementation of {@link KeyCompressor} that allows only reading existing entries.
	 */
	@Getter @Nonnull private final KeyCompressor readOnlyKeyCompressor;
	/**
	 * {@link Kryo} instance used for writing data to the output stream.
	 */
	@Getter @Nonnull private final Kryo writeKryo;
	/**
	 * Reference to the function that allows creating new {@link VersionedKryo} instances for reading {@link OffsetIndex}
	 * contents using up-to-date configuration specified in {@link VersionedKryoKeyInputs}.
	 *
	 * This function is passed from outside in constructor.
	 */
	@Getter @Nonnull private final Function<VersionedKryoKeyInputs, VersionedKryo> kryoFactory;
	/**
	 * Reference to the function that allows creating new {@link VersionedKryo} instances for reading {@link OffsetIndex}.
	 * This is internal object that wraps {@link #kryoFactory} and allows only to propagate passed version that reflects
	 * the key changes in {@link VersionedKryoKeyInputs}.
	 */
	@Getter @Nonnull private final Function<Long, VersionedKryo> readKryoFactory;
	/**
	 * Contains information about the share of the living records compared to total size of all the records in the file.
	 */
	@Getter private final double activeRecordShare;

	public OffsetIndexDescriptor(
		@Nonnull PersistentStorageDescriptor offsetIndexHeader,
		@Nonnull Function<VersionedKryoKeyInputs, VersionedKryo> kryoFactory,
		double activeRecordShare
	) {
		this(
			offsetIndexHeader.version(),
			offsetIndexHeader.fileLocation(),
			offsetIndexHeader.compressedKeys(),
			kryoFactory,
			activeRecordShare
		);
	}

	public OffsetIndexDescriptor(
		long version,
		@Nullable FileLocation fileLocation,
		@Nonnull Map<Integer, Object> compressedKeys,
		@Nonnull Function<VersionedKryoKeyInputs, VersionedKryo> kryoFactory,
		double activeRecordShare
	) {
		this.version = version;
		this.fileLocation = fileLocation;
		this.kryoFactory = kryoFactory;
		// create writable instances
		this.writeKeyCompressor = new ReadWriteKeyCompressor(compressedKeys);
		this.readOnlyKeyCompressor = new ReadOnlyKeyCompressor(compressedKeys);
		this.writeKryo = kryoFactory.apply(new VersionedKryoKeyInputs(writeKeyCompressor, 1));
		// create read only instances
		this.readKryoFactory = updatedVersion -> kryoFactory.apply(
			new VersionedKryoKeyInputs(readOnlyKeyCompressor, updatedVersion)
		);
		this.activeRecordShare = activeRecordShare;
	}

	public OffsetIndexDescriptor(
		@Nullable FileLocation fileLocation,
		@Nonnull OffsetIndexDescriptor fileOffsetIndexDescriptor,
		double activeRecordShare
	) {
		this.version = fileOffsetIndexDescriptor.version() + 1;
		this.fileLocation = fileLocation;
		this.kryoFactory = fileOffsetIndexDescriptor.kryoFactory;
		// keep all write instances
		this.writeKeyCompressor = fileOffsetIndexDescriptor.writeKeyCompressor;
		this.readOnlyKeyCompressor = new ReadOnlyKeyCompressor(this.writeKeyCompressor.getKeys());
		this.writeKryo = fileOffsetIndexDescriptor.writeKryo;
		// reset read only instances according to current state of write instances
		this.readKryoFactory = updatedVersion -> kryoFactory.apply(
			new VersionedKryoKeyInputs(
				this.readOnlyKeyCompressor,
				updatedVersion
			)
		);
		this.activeRecordShare = activeRecordShare;
	}

	/**
	 * Returns true if there were any changes in {@link VersionedKryoKeyInputs} that require purging kryo pools.
	 */
	public boolean resetDirty() {
		return writeKeyCompressor.resetDirtyFlag();
	}

	@Override
	public long version() {
		return version;
	}

	@Nonnull
	@Override
	public FileLocation fileLocation() {
		return fileLocation;
	}

	@Override
	@Nonnull
	public Map<Integer, Object> compressedKeys() {
		return writeKeyCompressor.getKeys();
	}

}
