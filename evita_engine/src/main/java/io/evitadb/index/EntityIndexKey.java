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

package io.evitadb.index;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.dataType.Scope;
import io.evitadb.store.spi.model.storageParts.index.EntityIndexKeyAccessor;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

import static java.util.Optional.ofNullable;

/**
 * This class is key for accessing {@link EntityIndex} that is the most optimal for the client query.
 *
 * @param type          type of the index
 * @param scope         scope of the index (archive or living data set)
 * @param discriminator additional object that distinguishes multiple indexes of same {@link #type}
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public record EntityIndexKey(
	@Nonnull EntityIndexType type,
	@Nonnull Scope scope,
	@Nullable Serializable discriminator
) implements IndexKey, Comparable<EntityIndexKey>, EntityIndexKeyAccessor {
	@Serial private static final long serialVersionUID = -3243859875585872256L;

	public EntityIndexKey(@Nonnull EntityIndexType type) {
		this(type, Scope.DEFAULT_SCOPE, null);
	}

	public EntityIndexKey(@Nonnull EntityIndexType type, @Nonnull Scope scope) {
		this(type, scope, null);
	}

	public EntityIndexKey(@Nonnull EntityIndexType type, @Nonnull Serializable discriminator) {
		this(type, Scope.DEFAULT_SCOPE, discriminator);
	}

	public EntityIndexKey(@Nonnull EntityIndexType type, @Nonnull Scope scope, @Nullable Serializable discriminator) {
		this.type = type;
		this.scope = scope;
		if (discriminator instanceof ReferenceKey rk) {
			this.discriminator = rk.isUnknownReference() ?
				rk : new ReferenceKey(rk.referenceName(), rk.primaryKey());
		} else {
			this.discriminator = discriminator;
		}
	}

	@Override
	public EntityIndexKey entityIndexKey() {
		return this;
	}

	@Override
	public int compareTo(@Nonnull EntityIndexKey o) {
		final int typeComparison = this.type.compareTo(o.type);
		if (typeComparison == 0) {
			return switch (this.type) {
				case GLOBAL -> Integer.compare(this.scope.ordinal(), o.scope.ordinal());
				case REFERENCED_ENTITY_TYPE -> {
					final String thisDiscriminator = (String) Objects.requireNonNull(this.discriminator);
					final String thatDiscriminator = (String) Objects.requireNonNull(o.discriminator);
					yield thisDiscriminator.compareTo(thatDiscriminator);
				}
				case REFERENCED_ENTITY, REFERENCED_HIERARCHY_NODE -> {
					final ReferenceKey thisDiscriminator = (ReferenceKey) Objects.requireNonNull(this.discriminator);
					final ReferenceKey thatDiscriminator = (ReferenceKey) Objects.requireNonNull(o.discriminator);
					final int nameComparison = thisDiscriminator.referenceName().compareTo(
						thatDiscriminator.referenceName());
					if (nameComparison == 0) {
						yield Integer.compare(thisDiscriminator.primaryKey(), thatDiscriminator.primaryKey());
					} else {
						yield nameComparison;
					}
				}
			};
		} else {
			return typeComparison;
		}
	}

	@Nonnull
	@Override
	public String toString() {
		return StringUtils.capitalize(this.scope.name().toLowerCase()) + " index: " +
			this.type + ofNullable(this.discriminator).map(it -> " - " + it).orElse("");
	}

}
