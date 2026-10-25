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

package io.evitadb.api.requestResponse.schema.analysis;

import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Objects;

/**
 * Named parameter of an analysis step. Values use the evitaDB data types, including their arrays.
 *
 * @param name nonblank parameter name
 * @param value non-null supported value
 */
public record AnalysisStepParameter(@Nonnull String name, @Nonnull Serializable value) implements Serializable {

	/**
	 * Validates the parameter and copies array values so the description remains immutable.
	 */
	public AnalysisStepParameter {
		Assert.notNull(name, "Analysis parameter name must not be null.");
		Assert.isTrue(!name.isBlank(), "Analysis parameter name must not be blank.");
		Assert.notNull(value, "Analysis parameter value must not be null.");
		Assert.isTrue(
			EvitaDataTypes.isSupportedTypeOrItsArray(value.getClass()),
			"Unsupported analysis parameter type: " + value.getClass().getName()
		);
		value = copyValue(value);
	}

	/**
	 * Returns the parameter value, defensively copying arrays.
	 */
	@Nonnull
	@Override
	public Serializable value() {
		return copyValue(this.value);
	}

	/** Compares parameter names and values, including array contents. */
	@Override
	public boolean equals(@Nullable Object object) {
		return this == object || object instanceof AnalysisStepParameter that &&
			this.name.equals(that.name) && Objects.deepEquals(this.value, that.value);
	}

	/** Hashes the parameter consistently with array-aware equality. */
	@Override
	public int hashCode() {
		return 31 * this.name.hashCode() + Arrays.deepHashCode(new Object[]{this.value}) - 31;
	}

	/**
	 * Copies supported array values; supported scalar values are immutable.
	 */
	@Nonnull
	static Serializable copyValue(@Nonnull Serializable value) {
		if (!value.getClass().isArray()) {
			return value;
		}
		final int length = Array.getLength(value);
		final Serializable copy = (Serializable) Array.newInstance(value.getClass().getComponentType(), length);
		System.arraycopy(value, 0, copy, 0, length);
		return copy;
	}
}
