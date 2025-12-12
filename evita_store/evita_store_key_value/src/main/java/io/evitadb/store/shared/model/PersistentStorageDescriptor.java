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

package io.evitadb.store.shared.model;

import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;

import javax.annotation.Nonnull;

/**
 * Interface describes basic properties of the persistent storage descriptor that refers to a single file with Kryo
 * serialized records. The descriptor is a key for understanding the contents of the binary file. Without descriptor
 * the file is unreadable.
 *
 * Descriptor contains crucial information for setting up the Kryo instance (see {@link #compressedKeys()} as well as
 * the location of its final fragment {@link #fileLocation()} that allows back-reading of all previous descriptors.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface PersistentStorageDescriptor extends StorageDescriptor {

	/**
	 * Contains location of the last file offset index fragment for this version of the header / collection.
	 */
	@Nonnull
	FileLocation fileLocation();

}
