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

package io.evitadb.api.requestResponse.mutation.conflict;


import io.evitadb.api.exception.ConflictingCatalogCommutativeMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.dataType.NumberRange;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Attribute-level conflict key for serializing concurrent mutations of an attribute delta change.
 *
 * @see ConflictKey
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record AttributeDeltaConflictKey(
	@Nonnull String entityType,
	@Nullable Integer entityPrimaryKey,
	@Nonnull AttributeKey attributeKey,
	@Nonnull Number deltaValue,
	@Nullable NumberRange<?> allowedRange
) implements CommutativeConflictKey<Number> {

    @Nonnull
    @Override
    public Number aggregate(@Nonnull Number one, @Nonnull Number two) {
        return NumberUtils.sum(one, two);
    }

    @Override
    public boolean isConstrainedToRange() {
        return this.allowedRange != null;
    }

    @Override
    public void assertInAllowedRange(
        @Nonnull String catalogName,
        long catalogVersion,
        @Nonnull Number accumulatedValue
    ) {
        if (this.allowedRange != null && !this.allowedRange.isWithin(accumulatedValue)) {
            throw new ConflictingCatalogCommutativeMutationException(
                catalogName,
                this,
                catalogVersion,
                "The accumulated value `" + accumulatedValue + "` for " + this +
                    " is outside the allowed range `" + this.allowedRange + "`."
            );
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AttributeDeltaConflictKey that)) return false;

        return this.entityType.equals(that.entityType) &&
            Objects.equals(this.deltaValue, that.deltaValue) &&
            Objects.equals(this.entityPrimaryKey, that.entityPrimaryKey) &&
            this.attributeKey.equals(that.attributeKey) &&
            Objects.equals(this.allowedRange, that.allowedRange);
    }

    @Override
    public int hashCode() {
        int result = this.entityType.hashCode();
        result = 31 * result + Objects.hashCode(this.entityPrimaryKey);
        result = 31 * result + this.attributeKey.hashCode();
        return result;
    }

    /**
	 * Returns a concise, human-readable representation of this conflict key.
	 *
	 * @return non-null string representation
	 */
	@Nonnull
	@Override
	public String toString() {
		return "attribute delta `" + this.attributeKey + "` of entity `" + this.entityType + "` with primary key `" + this.entityPrimaryKey + '`';
	}

}
