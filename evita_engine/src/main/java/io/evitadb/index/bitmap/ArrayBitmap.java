/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.bitmap;

import io.evitadb.index.array.CompositeIntArray;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Arrays;
import java.util.Objects;
import java.util.PrimitiveIterator.OfInt;

/**
 * This class bridges {@link ArrayBitmap} interface over {@link io.evitadb.index.array.CompositeIntArray} delegate.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class ArrayBitmap implements Bitmap {
	@Serial private static final long serialVersionUID = -3942282119857234L;
	private static final String ERROR_REMOVALS_NOT_SUPPORTED = "ArrayBitmap doesn't support removals!";
	private final CompositeIntArray intArray;

	public ArrayBitmap(int... recordId) {
		this.intArray = new CompositeIntArray(recordId);
	}

	@Override
	public boolean add(int recordId) {
		if (!intArray.contains(recordId)) {
			intArray.add(recordId);
			return true;
		} else {
			return false;
		}
	}

	@Override
	public void addAll(int... recordId) {
		intArray.addAll(recordId, 0, recordId.length);
	}

	@Override
	public void addAll(@Nonnull Bitmap recordIds) {
		final int[] recs = recordIds.getArray();
		intArray.addAll(recs, 0, recs.length);
	}

	@Override
	public boolean remove(int recordId) {
		throw new UnsupportedOperationException(ERROR_REMOVALS_NOT_SUPPORTED);
	}

	@Override
	public void removeAll(int... recordId) {
		throw new UnsupportedOperationException(ERROR_REMOVALS_NOT_SUPPORTED);
	}

	@Override
	public void removeAll(@Nonnull Bitmap recordIds) {
		throw new UnsupportedOperationException(ERROR_REMOVALS_NOT_SUPPORTED);
	}

	@Override
	public boolean contains(int recordId) {
		return intArray.contains(recordId);
	}

	@Override
	public int indexOf(int recordId) {
		return intArray.indexOf(recordId);
	}

	@Override
	public int get(int index) {
		return intArray.get(index);
	}

	@Override
	public int[] getRange(int start, int end) {
		return intArray.getRange(start, end);
	}

	@Override
	public int getFirst() {
		return intArray.get(0);
	}

	@Override
	public int getLast() {
		return intArray.get(intArray.getSize() - 1);
	}

	@Override
	public int[] getArray() {
		return intArray.toArray();
	}

	@Nonnull
	@Override
	public OfInt iterator() {
		return intArray.iterator();
	}

	@Override
	public boolean isEmpty() {
		return intArray.isEmpty();
	}

	@Override
	public int size() {
		return intArray.getSize();
	}

	@Override
	public int hashCode() {
		return Objects.hash(intArray);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		ArrayBitmap that = (ArrayBitmap) o;
		return intArray.equals(that.intArray);
	}

	@Override
	public String toString() {
		return Arrays.toString(intArray.toArray());
	}

}
