/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Cursor over the containers of a {@link RoaringArray}, visited in ascending key order.
 *
 * An implementation is a lightweight view bound to a backing array: {@link #advance()} moves it to
 * the next container and {@link #getContainer()} returns {@code null} once the cursor has run past
 * the last one. {@link FastAggregation} keeps one pointer per input bitmap in a
 * {@link java.util.PriorityQueue} (ordered via {@link #compareTo(ContainerPointer)}) to merge many
 * bitmaps key by key.
 */
interface ContainerPointer extends Comparable<ContainerPointer>, Cloneable {
	/**
	 * Moves the cursor to the next container. Once advanced past the last container
	 * {@link #getContainer()} returns {@code null}.
	 */
	void advance();

	/**
	 * Creates an independent copy positioned at the same container.
	 *
	 * @return an independent clone of this pointer
	 */
	@Nonnull
	ContainerPointer clone();

	/**
	 * Orders pointers by ascending {@link #key()} (read as unsigned); ties are broken by descending
	 * {@link #getCardinality()} so that the largest container for a given key is polled first.
	 *
	 * @param o the pointer to compare against
	 * @return negative, zero or positive as per {@link Comparable#compareTo}
	 */
	@Override
	int compareTo(@Nonnull ContainerPointer o);

	/**
	 * Returns the cardinality of the current container.
	 *
	 * @return the cardinality
	 */
	int getCardinality();

	/**
	 * Returns the container the cursor currently points at, or {@code null} when the cursor has moved
	 * past the last container. Callers rely on the {@code null} result to detect exhaustion.
	 *
	 * @return the current container, or {@code null} if there is none
	 */
	@Nullable
	Container getContainer();

	/**
	 * Tells whether the current container is a {@link BitmapContainer}.
	 *
	 * @return {@code true} if it is a bitmap container
	 */
	boolean isBitmapContainer();

	/**
	 * Tells whether the current container is a {@link RunContainer}.
	 *
	 * @return {@code true} if it is a run container
	 */
	boolean isRunContainer();

	/**
	 * Returns the 16-bit key marking the container's position within the roaring bitmap, to be
	 * interpreted as an unsigned integer.
	 *
	 * @return the key
	 */
	char key();
}
