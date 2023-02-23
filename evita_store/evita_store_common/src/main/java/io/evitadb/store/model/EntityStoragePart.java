/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.store.model;

import io.evitadb.api.requestResponse.data.structure.Entity;

/**
 * Implementations of this interface represents parts of the {@link Entity} object that
 * are stored as separated records on the disk. Separating {@link Entity} data into several
 * parts allows optimization of the data regarding usual reading scenarios. Separating entity data into the parts should
 * take into an account following prospectives:
 *
 * - what data are usually read together
 * - the data size
 * - bigger data chunks will mean a lot of data will not be used after deserialization due to query constraints
 * - many tiny data chunks will mean more seeks in the data file and each data chunk is accompanied by a cost
 * of dead weight of storage record overhead
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface EntityStoragePart extends StoragePart {

	/**
	 * Returns true if contents of the part were really changed.
	 */
	boolean isDirty();

	/**
	 * Returns true if contents of the part are effectively empty and may be removed from the data storage.
	 */
	boolean isEmpty();
}
