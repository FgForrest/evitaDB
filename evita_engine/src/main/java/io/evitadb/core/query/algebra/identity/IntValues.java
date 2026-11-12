/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.query.algebra.identity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Detached immutable bounded integer content owned by an exact token.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class IntValues {
	/** Copied content, never exposed. */
	private final int[] values;
	/** Content hash computed once. */
	private final long hash64;

	/** Copies content accepted by the token factory. */
	IntValues(@Nonnull int[] values) {
		if (values.length > IdentityToken.CONTENT_LIMIT) {
			throw new IllegalArgumentException("Exact content exceeds the content limit.");
		}
		this.values = values.clone();
		long hash = KeyAccumulator.mix(0, values.length);
		for (final int value : this.values) {
			hash = KeyAccumulator.mix(hash, value);
		}
		this.hash64 = hash;
	}

	/** Returns the number of retained integers. */
	public int size() {
		return this.values.length;
	}

	/** Returns the precomputed content hash. */
	public long hash64() {
		return this.hash64;
	}

	/** Compares content lexicographically after an equal hash. */
	int compareTo(@Nonnull IntValues other) {
		return Arrays.compare(this.values, other.values);
	}

	/** Compares exact integer content. */
	@Override
	public boolean equals(@Nullable Object other) {
		return this == other || other instanceof final IntValues that && Arrays.equals(this.values, that.values);
	}

	/** Returns a hash consistent with content equality. */
	@Override
	public int hashCode() {
		return (int) (this.hash64 ^ (this.hash64 >>> 32));
	}
}
