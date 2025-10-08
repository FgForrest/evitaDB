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
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Comparator;

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
) implements Serializable {
	@Serial private static final long serialVersionUID = -6696053762698997143L;
	/**
	 * Comparator that compares only by {@link ReferenceKey#referenceName()} and {@link ReferenceKey#primaryKey()}.
	 */
	public static final Comparator<ReferenceKey> GENERIC_COMPARATOR = new GenericReferenceKeyComparator();
	/**
	 * Comparator that compares by {@link ReferenceKey#referenceName()}, {@link ReferenceKey#primaryKey()} and
	 * {@link ReferenceKey#internalPrimaryKey()} (only when both internal PKs are known).
	 */
	public static final Comparator<ReferenceKey> FULL_COMPARATOR = new FullReferenceKeyComparator();

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

	/**
	 * Performs equality check ignoring the internal primary key.
	 * @param o object to compare with
	 * @return true if the objects are equal in general, false otherwise
	 */
	public boolean equalsInGeneral(@Nullable Object o) {
		if (!(o instanceof final ReferenceKey that)) {
			return false;
		}

		return this.primaryKey == that.primaryKey &&
			this.referenceName.equals(that.referenceName);
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
				" (generic)" :
				(this.internalPrimaryKey > 0 ?
					"/" + this.internalPrimaryKey :
					"/" + this.internalPrimaryKey + " (non-persistent)")
			);
	}

	/**
	 * Comparator implementation for comparing instances of {@link ReferenceKey}.
	 * This comparator first compares by the {@link ReferenceKey#referenceName()} in natural order.
	 * If the reference names are equal, it then compares by the {@link ReferenceKey#primaryKey()}.
	 * Implements {@link Serializable} to allow usage in serialization contexts.
	 */
	@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
	public static class GenericReferenceKeyComparator implements Comparator<ReferenceKey>, Serializable {
		@Serial private static final long serialVersionUID = -3461972704657163113L;

		@Override
		public int compare(ReferenceKey o1, ReferenceKey o2) {
			final int primaryComparison = o1.referenceName().compareTo(o2.referenceName());
			if (primaryComparison == 0) {
				return Integer.compare(o1.primaryKey(), o2.primaryKey());
			} else {
				return primaryComparison;
			}
		}

	}

	/**
	 * A comparator class for ordering instances of {@link ReferenceKey}, primarily based on their reference name,
	 * and secondarily on their primary key and internal primary key (but only if the internal primary key is known,
	 * which means it was already assigned).
	 *
	 * This class implements a comparison logic to ensure consistent ordering of {@link ReferenceKey} objects:
	 * 1. The comparison first evaluates the {@code referenceName} of the keys. The names are compared lexicographically.
	 * 2. If the {@code referenceName} is equal for both keys, the comparison proceeds with the {@code primaryKey}.
	 *    The primary keys are compared numerically.
	 * 3. If both the {@code referenceName} and {@code primaryKey} are equal, the comparison checks the
	 *    {@code internalPrimaryKey}. The {@code internalPrimaryKey} is taken into account only if both objects
	 *    represent references that are not marked as "unknown" or "new".
	 *
	 * The comparator is Serializable, enabling its use in distributed or persistence contexts.
	 *
	 * Note that references with unknown internal primary keys (determined via {@link ReferenceKey#isUnknownReference()})
	 * take precedence based on the comparison of {@code primaryKey} alone, ignoring the internal primary key.
	 *
	 * This comparator ensures a stable and consistent ordering of reference keys that respects multiple tiers of
	 * key values.
	 */
	@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
	public static class FullReferenceKeyComparator implements Comparator<ReferenceKey>, Serializable {
		@Serial private static final long serialVersionUID = 690430745790727107L;

		@Override
		public int compare(ReferenceKey o1, ReferenceKey o2) {
			final int primaryComparison = o1.referenceName().compareTo(o2.referenceName());
			if (primaryComparison == 0) {
				final int secondaryComparison = Integer.compare(o1.primaryKey(), o2.primaryKey());
				if (secondaryComparison == 0 && !(o1.isUnknownReference() || o2.isUnknownReference())) {
					return Integer.compare(o1.internalPrimaryKey(), o2.internalPrimaryKey());
				} else {
					return secondaryComparison;
				}
			} else {
				return primaryComparison;
			}
		}

	}

}
