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

package io.evitadb.externalApi.api.catalog.dataApi.builder.constraint;

import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocatorWithReference;
import lombok.Data;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * Ancestor for keys representing cacheable elements in a constraint tree.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Data
public abstract class CacheableElementKey {

	/**
	 * Defines for which constraint this object is relevant.
	 */
	@Nonnull
	protected final ConstraintType containerType;
	/**
	 * Data locator used in the built container.
	 */
	@Nonnull
	protected final DataLocator dataLocator;

	/**
	 * Creates hash ultimately identifying this key. Two keys that are equal generates same hash.
	 */
	@Nonnull
	public abstract String toHash();

	protected long hashContainerType(LongHashFunction hashFunction) {
		return hashFunction.hashChars(getContainerType().name());
	}

	protected long hashDataLocator(@Nonnull LongHashFunction hashFunction) {
		final long dataLocatorNameHash = hashFunction.hashChars(getDataLocator().getClass().getName());
		final long entityTypeHash = hashFunction.hashChars(getDataLocator().entityType());
		final long dataLocatorHash;
		if (getDataLocator() instanceof final DataLocatorWithReference dataLocatorWithReference) {
			dataLocatorHash = hashFunction.hashLongs(new long[] {
				dataLocatorNameHash,
				entityTypeHash,
				hashFunction.hashChars(
					Optional.ofNullable(dataLocatorWithReference.referenceName())
						.orElse("")
				)
			});
		} else {
			dataLocatorHash = hashFunction.hashLongs(new long[] { dataLocatorNameHash, entityTypeHash });
		}
		return dataLocatorHash;
	}
}
