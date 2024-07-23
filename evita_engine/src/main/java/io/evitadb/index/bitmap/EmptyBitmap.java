/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.index.bitmap;

import io.evitadb.dataType.iterator.EmptyIntIterator;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.PrimitiveIterator.OfInt;

/**
 * This implementation of {@link EmptyBitmap} interface is immutable always empty bitmap, that could be safely used in
 * multithreaded access for representing empty value.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
public class EmptyBitmap implements Bitmap {
	@Serial private static final long serialVersionUID = -1544760619146901988L;
	public static final EmptyBitmap INSTANCE = new EmptyBitmap();
	private static final int[] EMPTY_INT_ARRAY = new int[0];
	private static final String ERROR_READ_ONLY = "Empty bitmap is read only.";
	public static final String ERROR_EMPTY_BITMAP = "Bitmap is empty!";

	private EmptyBitmap() {
	}

	@Override
	public boolean add(int recordId) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public void addAll(int... recordId) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public void addAll(@Nonnull Bitmap recordIds) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public boolean remove(int recordId) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public void removeAll(int... recordId) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public void removeAll(@Nonnull Bitmap recordIds) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public boolean contains(int recordId) {
		return false;
	}

	@Override
	public int indexOf(int recordId) {
		return -1;
	}

	@Override
	public int get(int index) {
		throw new IndexOutOfBoundsException(ERROR_EMPTY_BITMAP);
	}

	@Override
	public int[] getRange(int start, int end) {
		throw new IndexOutOfBoundsException(ERROR_EMPTY_BITMAP);
	}

	@Override
	public int getFirst() {
		throw new IndexOutOfBoundsException(ERROR_EMPTY_BITMAP);
	}

	@Override
	public int getLast() {
		throw new IndexOutOfBoundsException(ERROR_EMPTY_BITMAP);
	}

	@Override
	public int[] getArray() {
		return EMPTY_INT_ARRAY;
	}

	@Nonnull
	@Override
	public OfInt iterator() {
		return EmptyIntIterator.INSTANCE;
	}

	@Override
	public boolean isEmpty() {
		return true;
	}

	@Override
	public int size() {
		return 0;
	}

	@Override
	public String toString() {
		return "EMPTY";
	}

}
