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

package io.evitadb.dataType;


import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Iterator;
import java.util.List;

/**
 * Simple wrapper around list of data. Represents single page of data with all elements in provided list.
 * The implementation is not meant to be used in public API, it's just a helper class for internal use.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public final class PlainChunk<T extends Serializable> implements DataChunk<T> {
	@Serial private static final long serialVersionUID = -5608217485991191285L;
	@Nonnull private final List<T> dataList;

	/**
	 * Creates a new PlainChunk wrapping the given collection. The collection
	 * is eagerly converted to a {@link List} at construction time to satisfy
	 * the {@link DataChunk} thread-safety and immutability contract.
	 *
	 * @param data the collection of elements to wrap
	 */
	public PlainChunk(@Nonnull java.util.Collection<T> data) {
		this.dataList = data instanceof List<T> ?
			(List<T>) data : List.copyOf(data);
	}

	@Nonnull
	@Override
	public List<T> getData() {
		return this.dataList;
	}

	@Override
	public int getTotalRecordCount() {
		return this.dataList.size();
	}

	@Override
	public boolean isFirst() {
		return true;
	}

	@Override
	public boolean isLast() {
		return true;
	}

	@Override
	public boolean hasPrevious() {
		return false;
	}

	@Override
	public boolean hasNext() {
		return false;
	}

	@Override
	public boolean isSinglePage() {
		return true;
	}

	@Override
	public boolean isEmpty() {
		return this.dataList.isEmpty();
	}

	@Nonnull
	@Override
	public Iterator<T> iterator() {
		return this.dataList.iterator();
	}

}
