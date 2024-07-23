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

package io.evitadb.index;

import io.evitadb.store.spi.model.storageParts.index.EntityIndexKeyAccessor;
import lombok.Data;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

import static java.util.Optional.ofNullable;

/**
 * This class is key for accessing {@link EntityIndex} that is the most optimal for the client query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Data
public class EntityIndexKey implements IndexKey, Comparable<EntityIndexKey>, EntityIndexKeyAccessor {
	@Serial private static final long serialVersionUID = 3548327054604327542L;

	/**
	 * Index by type classification.
	 */
	private final EntityIndexType type;

	/**
	 * Additional object that distinguishes multiple indexes of same {@link #type}.
	 */
	private final Serializable discriminator;

	public EntityIndexKey(@Nonnull EntityIndexType type) {
		this.type = type;
		this.discriminator = null;
	}

	public EntityIndexKey(@Nonnull EntityIndexType type, @Nonnull Serializable discriminator) {
		this.type = type;
		this.discriminator = discriminator;
	}

	@Override
	public EntityIndexKey entityIndexKey() {
		return this;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Override
	public int compareTo(EntityIndexKey o) {
		final int firstComparison = type.compareTo(o.type);
		if (firstComparison == 0 && discriminator != null) {
			if (discriminator instanceof Comparable) {
				return o.discriminator != null ? ((Comparable) discriminator).compareTo(o.discriminator) : -1;
			} else if (Objects.equals(discriminator, o.discriminator)) {
				return 0;
			} else {
				return o.discriminator != null ? Integer.compare(System.identityHashCode(discriminator), System.identityHashCode(o.discriminator)) : -1;
			}
		} else {
			return firstComparison;
		}
	}

	@Override
	public String toString() {
		return "Index:" + type + ofNullable(discriminator).map(it -> " (" + it + ')').orElse("");
	}

}
