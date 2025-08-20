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

package io.evitadb.api.requestResponse.data.mutation.reference;

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Reference key represents a unique identifier of the {@link ReferenceContract}.
 *
 * @param referenceName reference to {@link ReferenceSchemaContract#getName()} that uniquely identifies the reference schema
 * @param primaryKey    reference to {@link Entity#getPrimaryKey()} of the referenced entity. Might be also any integer
 *                      that uniquely identifies some external resource of type {@link ReferenceSchemaContract#getReferencedEntityType()}
 *                      not maintained by Evita.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record ReferenceKey(@Nonnull String referenceName, int primaryKey) implements Serializable, Comparable<ReferenceKey> {
	@Serial private static final long serialVersionUID = -1234554594699404014L;

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	public int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			// type
			EvitaDataTypes.estimateSize(this.referenceName) +
			// primary key
			MemoryMeasuringConstants.INT_SIZE;
	}

	@Override
	public int compareTo(ReferenceKey o) {
		final int primaryComparison = referenceName().compareTo(o.referenceName());
		if (primaryComparison == 0) {
			return Integer.compare(primaryKey(), o.primaryKey());
		} else {
			return primaryComparison;
		}
	}

	@Nonnull
	@Override
	public String toString() {
		return this.referenceName + ": " + this.primaryKey;
	}

}
