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


import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Thin wrapper over {@link ReferenceKey} that implements {@link Comparable} so that it can be used to sort collections.
 *
 * @param referenceKey - reference key to be wrapped
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record ComparableReferenceKey(
	@Nonnull ReferenceKey referenceKey
)
	implements Comparable<ComparableReferenceKey>, Serializable {
	@Serial private static final long serialVersionUID = -7888384551447045181L;

	public ComparableReferenceKey(@Nonnull ReferenceKey referenceKey) {
		this.referenceKey = referenceKey;
	}

	@Override
	public int compareTo(ComparableReferenceKey o) {
		return ReferenceKey.FULL_COMPARATOR.compare(this.referenceKey, o.referenceKey);
	}

}
