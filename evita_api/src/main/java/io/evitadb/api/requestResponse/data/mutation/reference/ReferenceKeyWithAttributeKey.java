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

package io.evitadb.api.requestResponse.data.mutation.reference;


import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Represents a composite key combining a reference key and an attribute key.
 * This class facilitates operations that require both the {@link ReferenceKey} and the {@link AttributeKey}.
 * The class ensures proper ordering and comparison for its instances by implementing the {@link Comparable} interface.
 * Additionally, it provides equality checks and a hash code implementation based on its constituent keys.
 *
 * This class is immutable and thread-safe.
 *
 * Implements methods to:
 * - Compare instances based on their reference key and attribute key.
 * - Evaluate equality and generate hash codes consistently.
 *
 * Suitable for usage in scenarios where entities with associated attribute keys need to be ordered, filtered, or stored in collections requiring
 * comparison or uniqueness constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record ReferenceKeyWithAttributeKey(
	@Nonnull ReferenceKey referenceKey,
	@Nonnull AttributeKey attributeKey
)
	implements Comparable<ReferenceKeyWithAttributeKey>, Serializable {
	@Serial private static final long serialVersionUID = 773755868610382953L;

	public ReferenceKeyWithAttributeKey(@Nonnull ReferenceKey referenceKey, @Nonnull AttributeKey attributeKey) {
		this.referenceKey = referenceKey;
		this.attributeKey = attributeKey;
	}

	@Override
	public int compareTo(ReferenceKeyWithAttributeKey o) {
		final int entityReferenceComparison = ReferenceKey.FULL_COMPARATOR.compare(this.referenceKey, o.referenceKey);
		if (entityReferenceComparison == 0) {
			return this.attributeKey.compareTo(o.attributeKey);
		} else {
			return entityReferenceComparison;
		}
	}

}
