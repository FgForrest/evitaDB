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

package io.evitadb.dataType.data;

import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * The implementation of {@link DataItem} that allows to capture simple values in the Java objects and arrays.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Immutable
public record DataItemValue(
	@Nullable Serializable value
) implements DataItem {
	@Serial private static final long serialVersionUID = -4959145032931390293L;

	@Override
	public int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			2 * MemoryMeasuringConstants.REFERENCE_SIZE +
			EvitaDataTypes.estimateSize(this.value);
	}

	@Override
	public boolean isEmpty() {
		return false;
	}

	@Override
	public void accept(@Nonnull DataItemVisitor visitor) {
		visitor.visit(this);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		DataItemValue value1 = (DataItemValue) o;
		return Objects.equals(this.value, value1.value);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.value);
	}

	@Nonnull
	@Override
	public String toString() {
		return this.value == null ? "NULL" : this.value.toString();
	}
}
