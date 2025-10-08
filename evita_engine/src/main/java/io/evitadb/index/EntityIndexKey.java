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

import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.spi.model.storageParts.index.EntityIndexKeyAccessor;
import io.evitadb.utils.Assert;
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

	public EntityIndexKey(@Nonnull EntityIndexType type, @Nonnull Scope scope, @Nullable Serializable discriminator) {
		this.type = type;
		this.scope = scope;
		if (discriminator instanceof RepresentativeReferenceKey rrk) {
			Assert.isPremiseValid(
				type == EntityIndexType.REFERENCED_ENTITY,
				() -> "When using `" + rrk + "` (RepresentativeReferenceKey) as discriminator, " +
					"the index type must be " + EntityIndexType.REFERENCED_ENTITY + "!"
			);
			this.discriminator = rrk;
		} else if (discriminator instanceof String str) {
			Assert.isPremiseValid(
				type == EntityIndexType.REFERENCED_ENTITY_TYPE,
				() -> "When using " + RepresentativeReferenceKey.class.getSimpleName() + " (String) as discriminator, " +
					"the index type must be " + EntityIndexType.REFERENCED_ENTITY_TYPE + "!"
			);
			this.discriminator = str;
		} else if (discriminator == null) {
			Assert.isPremiseValid(
				type == EntityIndexType.GLOBAL,
				() -> "When no discriminator is used, the index type must be " + EntityIndexType.GLOBAL + "!"
			);
			this.discriminator = null;
		} else {
			throw new GenericEvitaInternalError(
				"Discriminator must be either String (for " + EntityIndexType.REFERENCED_ENTITY_TYPE + ") " +
					"or RepresentativeReferenceKey (for " + EntityIndexType.REFERENCED_ENTITY + ")!"
			);
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
					final String thisDis = (String) Objects.requireNonNull(this.discriminator);
					final String thatDis = (String) Objects.requireNonNull(o.discriminator);
					yield thisDis.compareTo(thatDis);
				}
				case REFERENCED_ENTITY, REFERENCED_HIERARCHY_NODE -> {
					final RepresentativeReferenceKey thisDis = (RepresentativeReferenceKey) Objects.requireNonNull(this.discriminator);
					final RepresentativeReferenceKey thatDis = (RepresentativeReferenceKey) Objects.requireNonNull(o.discriminator);
					yield thisDis.compareTo(thatDis);
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
