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
 * @param referenceName      reference to {@link ReferenceSchemaContract#getName()} that uniquely identifies the reference schema
 * @param primaryKey         reference to {@link Entity#getPrimaryKey()} of the referenced entity. Might be also any integer
 *                           that uniquely identifies some external resource of type {@link ReferenceSchemaContract#getReferencedEntityType()}
 *                           not maintained by Evita.
 * @param internalPrimaryKey internal PK is assigned by evitaDB engine and is used to uniquely identify the
 *                           reference among other references. It is used when multiple references share same
 *                           business key - {@link ReferenceKey} - but differ by other properties (fe.
 *                           reference group or attributes).
 *
 *                           When a reference is created for the first time, internal id is set to a unique
 *                           negative number that is not used by the server side, which assigns positive unique
 *                           numbers to the references on first reference persistence. This allows distinguishing
 *                           references that are not yet persisted from those that are already persistent.
 *
 *                           When standalone key is used:
 *
 *                           - negative number: means that the reference is new and hasn't been yet persisted
 *                           - zero: means we don't know the internal PK
 *                           - positive number: means that the reference is persistent and has been already stored
 *                             in the database
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record ReferenceKey(
	@Nonnull String referenceName,
	int primaryKey,
	int internalPrimaryKey
) implements Serializable, Comparable<ReferenceKey> {
	@Serial private static final long serialVersionUID = -6696053762698997143L;

	public ReferenceKey(@Nonnull String referenceName, int primaryKey) {
		this(referenceName, primaryKey, 0);
	}

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	public int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			// type
			EvitaDataTypes.estimateSize(this.referenceName) +
			// primary key
			2 * MemoryMeasuringConstants.INT_SIZE;
	}

	/**
	 * Positive number means that the reference is persistent and has been already stored in the database.
	 * Which also means that for this particular reference "identity" internal primary key is known and will never change.
	 *
	 * @return true if the internal primary key is known and positive
	 */
	public boolean isKnownInternalPrimaryKey() {
		return this.internalPrimaryKey > 0;
	}

	/**
	 * Determines whether the reference represented by this instance is new or existing.
	 * A reference is considered new if its internal primary key is negative - i.e. id was generated on client and is
	 * unique, and should be used only until the reference is persisted and assigned proper and terminal internal PK.
	 *
	 * @return true if the internal primary key is negative, indicating the reference is new; false otherwise.
	 */
	public boolean isNewReference() {
		return this.internalPrimaryKey < 0;
	}

	/**
	 * Determines whether the reference represented by this instance is unknown.
	 * A reference is considered unknown when its internal primary key value is zero.
	 *
	 * @return true if the internal primary key is zero, indicating the reference is unknown; false otherwise.
	 */
	public boolean isUnknownReference() {
		return this.internalPrimaryKey == 0;
	}

	@Override
	public int compareTo(ReferenceKey o) {
		final int primaryComparison = referenceName().compareTo(o.referenceName());
		if (primaryComparison == 0) {
			final int secondaryComparison = Integer.compare(primaryKey(), o.primaryKey());
			if (secondaryComparison == 0 && internalPrimaryKey() > 0 && o.internalPrimaryKey() > 0) {
				return Integer.compare(internalPrimaryKey(), o.internalPrimaryKey());
			} else {
				return secondaryComparison;
			}
		} else {
			return primaryComparison;
		}
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof final ReferenceKey that)) return false;

		if (!(this.primaryKey == that.primaryKey &&
			this.referenceName.equals(that.referenceName))) {
			return false;
		}

		if (this.internalPrimaryKey > 0 && that.internalPrimaryKey > 0) {
			return this.internalPrimaryKey == that.internalPrimaryKey;
		} else {
			// we don't know the internal PK, assume the keys are equal
			return true;
		}
	}

	@Override
	public int hashCode() {
		int result = this.referenceName.hashCode();
		result = 31 * result + this.primaryKey;
		return result;
	}

	@Nonnull
	@Override
	public String toString() {
		return this.referenceName + ": " + this.primaryKey +
			(this.internalPrimaryKey == 0 ?
				" (unknown)" :
				(this.internalPrimaryKey > 0 ?
					"/" + this.internalPrimaryKey :
					"/" + this.internalPrimaryKey + " (non-persistent)")
			);
	}
}
