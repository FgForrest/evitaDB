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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.requestResponse.data.structure.Entity;

import javax.annotation.Nonnull;

/**
 * This class represents reference to any Evita entity and can is returned by default for all
 * queries that don't require loading additional data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface EntityReferenceContract<T extends Comparable<T> & EntityReferenceContract<T>> extends EntityClassifier, Comparable<T> {

	/**
	 * Reference to {@link Entity#getPrimaryKey()} of the referenced entity. Might be also any integer
	 * that uniquely identifies some external resource of type {@link #getType()} not maintained by Evita.
	 */
	@Nonnull
	Integer getPrimaryKey();

	/**
	 * Default comparison function for EntityReferenceContracts.
	 */
	default int compareReferenceContract(@Nonnull EntityReferenceContract<T> o) {
		final int primaryComparison = Integer.compare(getPrimaryKey(), o.getPrimaryKey());
		if (primaryComparison == 0) {
			return getType().compareTo(o.getType());
		} else {
			return primaryComparison;
		}
	}

}
