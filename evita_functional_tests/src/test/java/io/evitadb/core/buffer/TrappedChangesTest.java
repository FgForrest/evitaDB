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

package io.evitadb.core.buffer;

import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for {@link TrappedChanges} functionality.
 */
class TrappedChangesTest {

	/**
	 * Tests that TrappedChanges correctly handles individual storage part changes.
	 * Verifies that:
	 * - Initially the trapped changes container is empty
	 * - Individual changes can be added using addChangeToStore()
	 * - The count is correctly maintained
	 * - Changes can be retrieved in the order they were added via iterator
	 */
	@Test
	@DisplayName("Should handle individual storage part changes correctly")
	void shouldHandleIndividualChanges() {
		final TrappedChanges trappedChanges = new TrappedChanges();

		// Initially should be empty
		assertEquals(0, trappedChanges.getTrappedChangesCount());
		assertFalse(trappedChanges.getTrappedChangesIterator().hasNext());

		// Add individual changes
		final TestStoragePart part1 = new TestStoragePart(1L);
		final TestStoragePart part2 = new TestStoragePart(2L);

		trappedChanges.addChangeToStore(part1);
		trappedChanges.addChangeToStore(part2);

		// Verify count
		assertEquals(2, trappedChanges.getTrappedChangesCount());

		// Verify iterator
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		assertTrue(iterator.hasNext());
		assertEquals(part1, iterator.next());
		assertTrue(iterator.hasNext());
		assertEquals(part2, iterator.next());
		assertFalse(iterator.hasNext());
	}

	/**
	 * Tests that TrappedChanges correctly handles iterator-based changes.
	 * Verifies that:
	 * - An iterator of storage parts can be added using addIterator()
	 * - The count reflects the total number of items from the iterator
	 * - All items from the iterator can be retrieved in the same order
	 * - The iterator size parameter is correctly used for counting
	 */
	@Test
	@DisplayName("Should handle iterator-based storage part changes correctly")
	void shouldHandleIteratorChanges() {
		final TrappedChanges trappedChanges = new TrappedChanges();

		// Create test data
		final List<StoragePart> parts = List.of(
			new TestStoragePart(10L),
			new TestStoragePart(20L),
			new TestStoragePart(30L)
		);

		// Add iterator
		trappedChanges.addIterator(parts.iterator(), parts.size());

		// Verify count
		assertEquals(3, trappedChanges.getTrappedChangesCount());

		// Verify iterator
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		final List<StoragePart> result = new ArrayList<>();
		while (iterator.hasNext()) {
			result.add(iterator.next());
		}

		assertEquals(parts, result);
	}

	/**
	 * Tests that TrappedChanges correctly combines individual and iterator-based changes.
	 * Verifies that:
	 * - Individual changes and iterator changes can be mixed in the same container
	 * - The total count includes both individual and iterator changes
	 * - Individual changes are retrieved first, followed by iterator changes
	 * - The order within each type of change is preserved
	 */
	@Test
	@DisplayName("Should combine individual and iterator-based changes correctly")
	void shouldCombineIndividualAndIteratorChanges() {
		final TrappedChanges trappedChanges = new TrappedChanges();

		// Add individual changes
		final TestStoragePart individual1 = new TestStoragePart(1L);
		final TestStoragePart individual2 = new TestStoragePart(2L);
		trappedChanges.addChangeToStore(individual1);
		trappedChanges.addChangeToStore(individual2);

		// Add iterator changes
		final List<StoragePart> iteratorParts = List.of(
			new TestStoragePart(10L),
			new TestStoragePart(20L)
		);
		trappedChanges.addIterator(iteratorParts.iterator(), iteratorParts.size());

		// Verify total count
		assertEquals(4, trappedChanges.getTrappedChangesCount());

		// Verify all changes are accessible
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		final List<StoragePart> result = new ArrayList<>();
		while (iterator.hasNext()) {
			result.add(iterator.next());
		}

		assertEquals(4, result.size());
		// Individual changes should come first
		assertEquals(individual1, result.get(0));
		assertEquals(individual2, result.get(1));
		// Then iterator changes
		assertEquals(iteratorParts.get(0), result.get(2));
		assertEquals(iteratorParts.get(1), result.get(3));
	}

	/**
	 * Tests that TrappedChanges correctly handles multiple iterators added sequentially.
	 * Verifies that:
	 * - Multiple iterators can be added to the same container
	 * - The total count includes items from all iterators
	 * - All items from all iterators are accessible through the main iterator
	 * - Items from different iterators are combined properly
	 */
	@Test
	@DisplayName("Should handle multiple iterators correctly")
	void shouldHandleMultipleIterators() {
		final TrappedChanges trappedChanges = new TrappedChanges();

		// Add first iterator
		final List<StoragePart> parts1 = List.of(new TestStoragePart(10L), new TestStoragePart(20L));
		trappedChanges.addIterator(parts1.iterator(), parts1.size());

		// Add second iterator
		final List<StoragePart> parts2 = List.of(new TestStoragePart(30L));
		trappedChanges.addIterator(parts2.iterator(), parts2.size());

		// Verify count
		assertEquals(3, trappedChanges.getTrappedChangesCount());

		// Verify all elements are accessible
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		final List<StoragePart> result = new ArrayList<>();
		while (iterator.hasNext()) {
			result.add(iterator.next());
		}

		assertEquals(3, result.size());
	}

	/**
	 * Test implementation of StoragePart for testing purposes.
	 */
	private record TestStoragePart(long pk) implements StoragePart {

		@Nonnull
		@Override
		public Long getStoragePartPK() {
			return this.pk;
		}

		@Override
		public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
			return this.pk;
		}

		@Nonnull
		@Override
		public String toString() {
			return "TestStoragePart{pk=" + this.pk + "}";
		}
	}
}
