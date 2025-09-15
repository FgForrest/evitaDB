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

package io.evitadb.index;


import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;

import static io.evitadb.utils.StringUtils.serializableArrayToString;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record RepresentativeReferenceKey(
	@Nonnull ReferenceKey referenceKey,
	@Nonnull Serializable[] representativeAttributeValues
) implements Serializable, Comparable<RepresentativeReferenceKey> {

	public RepresentativeReferenceKey(
		@Nonnull String referenceName,
		int primaryKey
	) {
		this(
			new ReferenceKey(referenceName, primaryKey),
			ArrayUtils.EMPTY_SERIALIZABLE_ARRAY
		);
	}

	public RepresentativeReferenceKey(
		@Nonnull ReferenceKey referenceKey
	) {
		this(
			referenceKey,
            ArrayUtils.EMPTY_SERIALIZABLE_ARRAY
		);
	}

	public RepresentativeReferenceKey(
		@Nonnull ReferenceKey referenceKey,
		@Nonnull Serializable[] representativeAttributeValues
	) {
		this.referenceKey = referenceKey.isUnknownReference() ?
				referenceKey : new ReferenceKey(referenceKey.referenceName(), referenceKey.primaryKey());
		this.representativeAttributeValues = representativeAttributeValues;
	}

	@Nonnull
	public String referenceName() {
		return this.referenceKey.referenceName();
	}

	public int primaryKey() {
		return this.referenceKey.primaryKey();
	}

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

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof final RepresentativeReferenceKey that)) return false;

		return this.referenceKey.equalsInGeneral(that.referenceKey) &&
			Arrays.equals(this.representativeAttributeValues, that.representativeAttributeValues);
	}

	@Override
	public int hashCode() {
		int result = this.referenceKey.hashCode();
		result = 31 * result + Arrays.hashCode(this.representativeAttributeValues);
		return result;
	}

	@Nonnull
	@Override
	public String toString() {
		return this.referenceKey + ": [" + StringUtils.serializableArrayToString(this.representativeAttributeValues) + "]";
	}
}
