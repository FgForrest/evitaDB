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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.model;

import javax.annotation.Nonnull;

/**
 * Implementations of this interface can create {@link StoragePart} for storing into the file offset index
 * on demand. This mechanism is good for object we don't want to store / convert to storage parts immediately but rather
 * buffer their updates in original objects and store them once a while. Examples of such objects are {@link io.evitadb.index.EntityIndex}
 * objects that got updated with each entity upsert/removal and we would need to convert and store them multiple times
 * in a single transaction which would incur performance penalty.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface StoragePartFactory {

	/**
	 * This method plays well with {@link RecordWithCompressedId} interface that uniquely identifies the object before
	 * it's actually stored and its primary key is assigned. Method must create {@link StoragePart} object for passed
	 * original key or throw an exception.
	 */
	@Nonnull
	StoragePart createStoragePart(@Nonnull Object originalKey) throws IllegalArgumentException;

}
