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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.attribute;

import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Locale;
import java.util.Objects;

/**
 * The class behaves the same as {@link EntityReference} but is used only in {@link GlobalUniqueIndex} to map localized
 * values to entity references of various types.
 *
 * @param type       reference to {@link Entity#getType()} of the referenced entity. Might be also any {@link String}
 *                   that identifies type some external resource not maintained by Evita.
 * @param primaryKey reference to {@link Entity#getPrimaryKey()} of the referenced entity. Might be also any integer
 *                   that uniquely identifies some external resource of type {@link #getType()} not maintained by Evita.
 * @param locale     locale that is connected with an indexed localized value
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Immutable
@ThreadSafe
public record EntityReferenceWithLocale(@Nonnull String type, int primaryKey, @Nullable Locale locale)
	implements EntityReferenceContract<EntityReference> {
	@Serial private static final long serialVersionUID = 7432447904441796055L;

	@Nonnull
	@Override
	public String getType() {
		return type;
	}

	@Nonnull
	@Override
	public Integer getPrimaryKey() {
		return primaryKey;
	}

	@Override
	public int compareTo(@Nonnull EntityReference o) {
		return compareReferenceContract(o);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || !EntityReferenceContract.class.isAssignableFrom(o.getClass())) return false;
		EntityReferenceContract<?> that = (EntityReferenceContract<?>) o;
		return primaryKey == that.getPrimaryKey() && type.equals(that.getType());
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, primaryKey);
	}

	@Override
	public String toString() {
		return type + ": " + primaryKey + (locale == null ? "" : ":" + locale);
	}

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	public int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			// type
			EvitaDataTypes.estimateSize(type) +
			// primary key
			MemoryMeasuringConstants.INT_SIZE +
			// locale
			MemoryMeasuringConstants.REFERENCE_SIZE;
	}

}
