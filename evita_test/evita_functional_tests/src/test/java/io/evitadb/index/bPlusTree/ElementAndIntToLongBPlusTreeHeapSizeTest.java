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

package io.evitadb.index.bPlusTree;

import io.evitadb.utils.JolHeapSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.function.ToIntFunction;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the heap-size walk over the two int-keyed trees: {@link TransactionalElementBPlusTree}, which backs the
 * price indexes, and {@link TransactionalIntToLongBPlusTree}, which backs `TransactionalUnorderedIntArray` and
 * through it {@link io.evitadb.index.attribute.SortIndex}.
 *
 * # Why the element tree carries the interesting case
 *
 * It is the **one production consumer of spine-only sizing**. A `PriceListAndCurrencyPriceRefIndex` holds the very
 * same `PriceRecord` instances as the super index of its price-list / currency combination — its own javadoc says
 * so, and the disk-load rebuild exists precisely to collapse Kryo's per-index duplicates back onto them. So a ref
 * index must charge its tree's **spine** while the super index, the real owner, charges the bodies. Counting them
 * in both places would multiply the whole price payload by the number of reference-reduced indexes.
 *
 * The int-to-long tree is the opposite extreme and worth pinning for exactly that reason: its keys and values are
 * both primitives, so there is no ownership question at all and no sizer overload.
 *
 * @author Claude (B+ tree heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(PRICE)
@DisplayName("Int-keyed B+ tree heap-size reporting")
class ElementAndIntToLongBPlusTreeHeapSizeTest {
	/**
	 * Stand-in for a stored element. A plain named class with one `int` field, deliberately **not** the production
	 * `PriceRecord`: what these assertions verify is the tree's own arithmetic and the sizer plumbing, neither of
	 * which depends on the element type, and a payload JOL can walk cleanly keeps the measurement honest instead of
	 * fighting the tooling.
	 */
	private static final class Element {
		private final int id;

		private Element(int id) {
			this.id = id;
		}

		private int id() {
			return this.id;
		}
	}

	/**
	 * Extracts the tree key from an element.
	 *
	 * Deliberately a **named class** rather than an `Element::id` method reference: every leaf of this tree holds
	 * the extractor, so a JOL walk of the node graph reaches it — and a method reference is a *hidden class* whose
	 * field offsets JOL refuses to read. A named class is walkable, so it can be subtracted as the shared root it
	 * is: one instance handed to every node of the tree.
	 */
	private static final class KeyExtractor implements ToIntFunction<Element> {

		@Override
		public int applyAsInt(Element value) {
			return value.id();
		}
	}

	/**
	 * The single extractor instance this test's trees share.
	 */
	private static final ToIntFunction<Element> KEY = new KeyExtractor();

	/**
	 * Builds the element instances the tree will hold, kept by the caller because a JOL walk subtracts shared roots
	 * **by identity** - equal-but-distinct elements would not be removed from the measurement.
	 *
	 * @param entries how many elements to build
	 * @return the elements
	 */
	@Nonnull
	private static Element[] elements(int entries) {
		final Element[] elements = new Element[entries];
		for (int i = 0; i < entries; i++) {
			elements[i] = new Element(i);
		}
		return elements;
	}

	/**
	 * Builds an element tree over exactly the given instances.
	 *
	 * @param elements the elements to insert
	 * @return the populated tree
	 */
	@Nonnull
	private static TransactionalElementBPlusTree<Element> buildElementTree(@Nonnull Element[] elements) {
		final TransactionalElementBPlusTree<Element> tree =
			new TransactionalElementBPlusTree<>(16, 7, 15, 7, Element.class, KEY);
		for (final Element element : elements) {
			tree.insert(element);
		}
		return tree;
	}

	/**
	 * Builds the shared-root set an element-tree measurement must exclude: the elements the caller still owns, the
	 * key extractor every leaf points at, and the component type the leaves keep to allocate their arrays.
	 *
	 * @param elements the stored element instances
	 * @return the roots to subtract from a JOL walk
	 */
	@Nonnull
	private static Object[] sharedRootsWith(@Nonnull Element[] elements) {
		final Object[] sharedRoots = new Object[elements.length + 2];
		sharedRoots[0] = KEY;
		sharedRoots[1] = Element.class;
		System.arraycopy(elements, 0, sharedRoots, 2, elements.length);
		return sharedRoots;
	}

	@Nested
	@DisplayName("prices the element tree spine separately from its bodies")
	class ElementTreeOwnership {

		@Test
		void shouldMatchMeasuredHeapWhenTheBodiesAreExcluded() {
			final Element[] records = elements(500);
			final TransactionalElementBPlusTree<Element> tree = buildElementTree(records);

			// subtracting every stored record from the JOL walk leaves exactly the spine - which is precisely the
			// figure a reference-reduced price index must report
			assertEquals(
				JolHeapSize.ownedSize(tree.getRoot(), sharedRootsWith(records)),
				tree.getNodeGraphHeapSizeInBytes(element -> 0L)
			);
		}

		@Test
		void shouldMatchMeasuredHeapWhenTheBodiesArePriced() {
			final TransactionalElementBPlusTree<Element> tree = buildElementTree(elements(500));

			// and the same walk with a real sizer accounts for the bodies too - the figure the SUPER index reports,
			// since it is the one that owns them
			assertEquals(
				JolHeapSize.ownedSize(tree.getRoot(), KEY, Element.class),
				tree.getNodeGraphHeapSizeInBytes(JolHeapSize::ownedSize)
			);
		}

		@Test
		void shouldDifferFromTheSpineByExactlyTheBodies() {
			final Element[] elements = elements(500);
			final TransactionalElementBPlusTree<Element> tree = buildElementTree(elements);

			final long spineOnly = tree.getHeapSizeInBytes();
			final long withBodies = tree.getHeapSizeInBytes(JolHeapSize::ownedSize);

			// the gap is exactly the elements and nothing else - this is the invariant the price split rests on:
			// a reference-reduced index reports `spineOnly`, the super index that owns the bodies adds precisely
			// their footprint, and between them each object is charged once
			long bodies = 0;
			for (final Element element : elements) {
				bodies += JolHeapSize.ownedSize(element);
			}
			assertEquals(bodies, withBodies - spineOnly);
			assertTrue(bodies > 0, "the fixture must actually store something for this to mean anything");
		}
	}

	@Nested
	@DisplayName("prices the primitive int-to-long tree outright")
	class IntToLongTree {

		@Test
		void shouldMatchMeasuredHeapForItsNodeGraph() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(16, 7, 15, 7);
			for (int i = 0; i < 500; i++) {
				tree.insert(i, (long) i * 1_000);
			}

			// keys and values are primitives, so nothing is shared and nothing is borrowed - the walk and the
			// measurement have to agree with no exclusions at all
			assertEquals(JolHeapSize.ownedSize(tree.getRoot()), tree.getNodeGraphHeapSizeInBytes());
		}

		@Test
		void shouldAddOnlyTheTreesOwnObjectOnTopOfItsNodeGraph() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(16, 7, 15, 7);
			for (int i = 0; i < 500; i++) {
				tree.insert(i, (long) i * 1_000);
			}

			final long own = tree.getHeapSizeInBytes() - tree.getNodeGraphHeapSizeInBytes();
			assertTrue(own > 0 && own < 256, "the tree's own object should be a small constant, was " + own);
		}
	}

}
