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

import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import java.io.Serial;
import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * The implementation of {@link DataItem} that allows to capture arrays, lists and sets inside Java objects.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Immutable
public record DataItemArray(
	@Nonnull DataItem[] children
) implements DataItem {
	@Serial private static final long serialVersionUID = -6372982252643992079L;

	/**
	 * Method allows to iterate over all items in this array with passed `consumer`. Second argument of the bi-consumer
	 * is a flag `hasNext` - true when the current data item is not the last item in the array.
	 */
	public void forEach(@Nonnull BiConsumer<DataItem, Boolean> consumer) {
		for (int i = 0; i < this.children.length; i++) {
			final DataItem child = this.children[i];
			consumer.accept(child, i < this.children.length - 1);
		}
	}

	@Override
	public void accept(@Nonnull DataItemVisitor visitor) {
		visitor.visit(this);
	}

	@Override
	public int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			2 * MemoryMeasuringConstants.REFERENCE_SIZE +
			MemoryMeasuringConstants.computeArraySize(this.children);
	}

	@Override
	public boolean isEmpty() {
		return this.children.length == 0;
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(this.children);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		DataItemArray that = (DataItemArray) o;
		return Arrays.equals(this.children, that.children);
	}

	@Nonnull
	@Override
	public String toString() {
		return '[' + Arrays.toString(this.children) + ']';
	}
}
