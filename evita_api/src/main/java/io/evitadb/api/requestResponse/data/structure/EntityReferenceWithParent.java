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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * This class is used as nested object in {@link EntityContract} to reference parent entity. It's almost the same as
 * {@link EntityReference} but it contains an optional parent reference.
 *
 * Class is immutable on purpose - we want to support caching the entities in a shared cache and accessed by many threads.
 *
 * @param type         Reference to {@link Entity#getType()} of the referenced entity. Might be also any {@link String}
 *                     that identifies type some external resource not maintained by Evita.
 * @param primaryKey   Reference to {@link Entity#getPrimaryKey()} of the referenced entity. Might be also any integer
 *                     that uniquely identifies some external resource of type {@link #getType()} not maintained by Evita.
 * @param parentEntity Optional reference to {@link Entity#getParent()} of the referenced entity.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Immutable
@ThreadSafe
public record EntityReferenceWithParent(
	@Nonnull String type,
	int primaryKey,
	@Nullable EntityClassifierWithParent parentEntity
) implements EntityReferenceContract<EntityReferenceWithParent>, EntityClassifierWithParent, Serializable {
	@Serial private static final long serialVersionUID = -4893251747273825997L;

	@Nonnull
	@Override
	public String getType() {
		return this.type;
	}

	@Nonnull
	@Override
	public Integer getPrimaryKey() {
		return this.primaryKey;
	}

	@Nonnull
	@Override
	public Optional<EntityClassifierWithParent> getParentEntity() {
		return Optional.ofNullable(this.parentEntity);
	}

	@Override
	public int compareTo(@Nonnull EntityReferenceWithParent o) {
		return compareReferenceContract(o);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		EntityReferenceWithParent that = (EntityReferenceWithParent) o;
		return this.primaryKey == that.primaryKey && Objects.equals(this.type, that.type) && Objects.equals(this.parentEntity, that.parentEntity);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.type, this.primaryKey, this.parentEntity);
	}

	@Nonnull
	@Override
	public String toString() {
		return this.type + ": " + this.primaryKey + (this.parentEntity != null ? " (↰ " + this.parentEntity.getPrimaryKey() + ")" : "");
	}

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	public int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			// type
			EvitaDataTypes.estimateSize(this.type) +
			// primary key
			MemoryMeasuringConstants.INT_SIZE +
			// parent
			MemoryMeasuringConstants.REFERENCE_SIZE;
	}

}
