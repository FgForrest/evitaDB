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


import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ComparableReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

/**
 * Extended entity reference that maintains a mapping of reassigned primary keys for entity references.
 * This class is used during entity mutations when references need to be tracked with their newly assigned
 * internal primary keys after persistence.
 *
 * <p>When references are first created, they are assigned temporary negative internal primary keys.
 * Upon persistence to the database, these temporary keys are replaced with positive permanent internal
 * primary keys assigned by the server. This class maintains the mapping between the original reference
 * keys (with temporary internal PKs) and the reference keys with their newly assigned permanent internal PKs.</p>
 *
 * <p>This is particularly useful in scenarios where:</p>
 * <ul>
 *     <li>Multiple references share the same business key but differ in properties</li>
 *     <li>Client code needs to track which references were assigned which internal primary keys after persistence</li>
 *     <li>References need to be looked up by their original temporary keys to find their permanent counterparts</li>
 * </ul>
 *
 * <p>Class is immutable and thread-safe - it supports caching entities in a shared cache accessed by many threads.</p>
 *
 * @param type                      Reference to {@link Entity#getType()} of the referenced entity. Might be also any {@link String}
 *                                  that identifies a type of some external resource not maintained by Evita.
 * @param primaryKey                Reference to {@link Entity#getPrimaryKey()} of the referenced entity. Might be also any integer
 *                                  that uniquely identifies some external resource of type {@link #getType()} not maintained by Evita.
 * @param reassignedReferenceKeys   Mapping from original {@link ReferenceKey} (with temporary internal PK) to the new
 *                                  {@link ReferenceKey} with permanent internal PK assigned by the server after persistence.
 *                                  The map uses {@link ComparableReferenceKey} as keys for efficient lookup and comparison.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Immutable
@ThreadSafe
public record EntityReferenceWithAssignedPrimaryKeys(
	@Nonnull String type,
	int primaryKey,
	@Nonnull Map<ComparableReferenceKey, ReferenceKey> reassignedReferenceKeys
) implements EntityReferenceContract, Serializable {
	@Serial private static final long serialVersionUID = -7906246282304342266L;

	@Override
	@Nonnull
	public String getType() {
		return this.type;
	}

	@Nonnull
	@Override
	public Integer getPrimaryKey() {
		return this.primaryKey;
	}

	/**
	 * Finds the reference key associated with the reassigned primary key in the internal mapping.
	 *
	 * @param referenceKey the original {@link ReferenceKey} to look up in the reassigned keys map,
	 *                     must not be null
	 * @return the {@link ReferenceKey} corresponding to the reassigned primary key if found,
	 *         or null if no matching key exists
	 */
	@Nullable
	public ReferenceKey findReferenceKeysWithReassignedPrimaryKey(@Nonnull ReferenceKey referenceKey) {
		return this.reassignedReferenceKeys.get(
			new ComparableReferenceKey(referenceKey)
		);
	}

	@Override
	public int compareTo(@Nonnull EntityReferenceContract o) {
		return compareReferenceContract(o);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || !EntityReferenceContract.class.isAssignableFrom(o.getClass())) return false;
		EntityReferenceContract that = (EntityReferenceContract) o;
		return this.primaryKey == that.getPrimaryKey() && this.type.equals(that.getType());
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.type, this.primaryKey);
	}

	@Nonnull
	@Override
	public String toString() {
		return this.type + ": " + this.primaryKey;
	}

}