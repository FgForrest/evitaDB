/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.data.structure;


import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.StringUtils;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;

import static io.evitadb.utils.StringUtils.serializableArrayToString;

/**
 * Compound key that identifies a specific reference by its {@link ReferenceKey} together with a
 * fixed-size, ordered set of representative attribute values.
 *
 * The representative attribute values serve two purposes:
 * - they provide a stable, domain-specific tie-breaker when multiple references share the same
 *   {@link #referenceName()} and {@link #primaryKey()}, and
 * - they allow creation of a deterministic, total (or domain-constrained) ordering.
 *
 * Equality and hashing are based on:
 * - the {@link ReferenceKey} compared via {@link ReferenceKey#equalsInGeneral}, and
 * - element-wise equality of the {@link #representativeAttributeValues()} array.
 *
 * Natural ordering implemented by {@link #compareTo(RepresentativeReferenceKey)} sorts by
 * {@link #referenceName()}, then {@link #primaryKey()}, and finally lexicographically by the
 * representative attribute values. The natural ordering expects both compared keys to carry the same
 * number of representative values and throws an exception when they do not. If you need a more
 * permissive ordering that handles different array lengths, use {@link #GENERIC_COMPARATOR} instead.
 *
 * This record is immutable and safe to use as a key in maps or elements of sets.
 *
 * Note: unless the {@link ReferenceKey} is an "unknown" reference, the constructor creates a new
 * {@link ReferenceKey} instance from the pair (referenceName, primaryKey) to normalize any extra
 * flags the original instance might carry. This guarantees consistent equality and hashing semantics.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record RepresentativeReferenceKey(
	@Nonnull ReferenceKey referenceKey,
	@Nonnull Serializable[] representativeAttributeValues
) implements Serializable, Comparable<RepresentativeReferenceKey> {
	/**
	 * Comparator that provides a permissive total ordering: it sorts by reference name, then primary
	 * key, and finally lexicographically by the representative attribute values using
	 * {@link ArrayUtils#compare(Object[], Object[])}. Unlike {@link #compareTo(RepresentativeReferenceKey)},
	 * it does not require the arrays to have the same length.
	 */
	public static final Comparator<RepresentativeReferenceKey> GENERIC_COMPARATOR = new GenericReferenceKeyComparator();

	/**
	 * Creates a key for the provided {@link ReferenceKey} with no representative attribute values.
	 *
	 * This is a convenience constructor equivalent to passing an empty array to the other
	 * constructor. If the supplied reference is not marked as unknown, it is normalized to a new
	 * {@link ReferenceKey} containing only the pair (referenceName, primaryKey).
	 *
	 * @param referenceKey the reference identifier; must not be null
	 */
	public RepresentativeReferenceKey(
		@Nonnull ReferenceKey referenceKey
	) {
		this(
			referenceKey,
            ArrayUtils.EMPTY_SERIALIZABLE_ARRAY
		);
	}

	/**
	 * Creates a key for the provided {@link ReferenceKey} and a fixed-size array of representative
	 * attribute values.
	 *
	 * Unless the reference is unknown, a new {@link ReferenceKey} is created from
	 * (referenceName, primaryKey) to normalize any additional state the original instance might have.
	 * This normalization ensures stable equality and hashing.
	 *
	 * The representative attribute values are used as a lexicographical tie-breaker in comparisons.
	 * All compared instances are expected to carry the same number of values when using
	 * {@link #compareTo(RepresentativeReferenceKey)}.
	 *
	 * @param referenceKey the reference identifier; must not be null
	 * @param representativeAttributeValues the ordered attribute values used for comparison; must not be null
	 */
	public RepresentativeReferenceKey(
		@Nonnull ReferenceKey referenceKey,
		@Nonnull Serializable[] representativeAttributeValues
	) {
		this.referenceKey = referenceKey.isUnknownReference() ?
				referenceKey : new ReferenceKey(referenceKey.referenceName(), referenceKey.primaryKey());
		this.representativeAttributeValues = representativeAttributeValues;
	}

	/**
	 * Returns the name of the referenced entity type.
	 *
	 * @return non-null reference name
	 */
	@Nonnull
	public String referenceName() {
		return this.referenceKey.referenceName();
	}

	/**
	 * Returns the primary key of the referenced entity.
	 *
	 * @return primary key value
	 */
	public int primaryKey() {
		return this.referenceKey.primaryKey();
	}

	/**
	 * Natural ordering comparator.
	 *
	 * Sorts by:
	 * 1) reference name,
	 * 2) primary key,
	 * 3) lexicographically by representative attribute values.
	 *
	 * Both compared instances must contain the same number of representative attribute values. When the
	 * lengths differ, a {@link GenericEvitaInternalError} is thrown to signal a misuse in the calling
	 * code. If you need a more permissive comparator that tolerates different lengths, use
	 * {@link #GENERIC_COMPARATOR}.
	 *
	 * @param thatDis the other key to compare with; must not be null
	 * @return negative if this comes before the other key, zero if equal, positive if after
	 */
	@Override
	public int compareTo(@Nonnull RepresentativeReferenceKey thatDis) {
		final int nameComparison = this.referenceName().compareTo(thatDis.referenceName());
		if (nameComparison == 0) {
			final int pkComparison = Integer.compare(this.primaryKey(), thatDis.primaryKey());
			if (pkComparison == 0) {
				final Serializable[] thisRepAV = this.representativeAttributeValues;
				final Serializable[] thatRepAV = thatDis.representativeAttributeValues;
				if (thisRepAV.length != thatRepAV.length) {
					throw new GenericEvitaInternalError(
						"Incomparable representative attribute values: " +
							"this=" + serializableArrayToString(thisRepAV) + ", that=" + serializableArrayToString(thatRepAV)
					);
				} else {
					for (int i = 0; i < thisRepAV.length; i++) {
						@SuppressWarnings("rawtypes") final Comparable thisAV = (Comparable) thisRepAV[i];
						@SuppressWarnings("rawtypes") final Comparable thatAV = (Comparable) thatRepAV[i];
						@SuppressWarnings("unchecked") final int avComparison = thisAV.compareTo(thatAV);
						if (avComparison != 0) {
							return avComparison;
						}
					}
					return 0;
				}
			} else {
				return pkComparison;
			}
		} else {
			return nameComparison;
		}
	}

	/**
	 * Compares this key to another object for equality.
	 *
	 * Two keys are equal when their {@link ReferenceKey}s are equal according to
	 * {@link ReferenceKey#equalsInGeneral} and their representative attribute value arrays are equal
	 * element-by-element.
	 *
	 * @param o the object to compare to
	 * @return true when equal, false otherwise
	 */
	@Override
	public boolean equals(Object o) {
		if (!(o instanceof final RepresentativeReferenceKey that)) return false;

		return this.referenceKey.equalsInGeneral(that.referenceKey) &&
			Arrays.equals(this.representativeAttributeValues, that.representativeAttributeValues);
	}

	/**
	 * Computes a hash code consistent with {@link #equals(Object)}.
	 *
	 * The hash is derived from the normalized {@link ReferenceKey} and the contents of the
	 * representative attribute value array.
	 *
	 * @return hash code value
	 */
	@Override
	public int hashCode() {
		int result = this.referenceKey.hashCode();
		result = 31 * result + Arrays.hashCode(this.representativeAttributeValues);
		return result;
	}

	/**
	 * Returns a human-readable representation in the form:
	 * referenceKey: [value1, value2, ...]
	 *
	 * Intended to aid debugging and logs.
	 *
	 * @return non-null string representation
	 */
	@Nonnull
	@Override
	public String toString() {
		return this.referenceKey + ": [" + StringUtils.serializableArrayToString(this.representativeAttributeValues) + "]";
	}

	/**
	 * Comparator implementation for comparing instances of {@link RepresentativeReferenceKey}.
	 * This comparator first compares by the {@link ReferenceKey#referenceName()} in natural order.
	 * If the reference names are equal, it then compares by the {@link ReferenceKey#primaryKey()}.
	 * When both match, it compares the {@link #representativeAttributeValues()} lexicographically.
	 * Implements {@link Serializable} to allow usage in serialization contexts.
	 */
	@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
	public static class GenericReferenceKeyComparator implements Comparator<RepresentativeReferenceKey>, Serializable {
		@Serial private static final long serialVersionUID = -8844802046787394781L;

		@Override
		public int compare(RepresentativeReferenceKey o1, RepresentativeReferenceKey o2) {
			final int primaryComparison = o1.referenceName().compareTo(o2.referenceName());
			if (primaryComparison == 0) {
				final int secondComparison = Integer.compare(o1.primaryKey(), o2.primaryKey());
				if (secondComparison == 0) {
					final Serializable[] rav1 = o1.representativeAttributeValues();
					final Serializable[] rav2 = o2.representativeAttributeValues();
					return ArrayUtils.compare(rav1, rav2);
				}
				return secondComparison;
			} else {
				return primaryComparison;
			}
		}

	}

}
