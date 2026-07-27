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

package io.evitadb.index.attribute;

import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.ValueColumnFactory;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Comparator;

/**
 * Payload-agnostic building blocks shared by the two bucket-tree-backed unique indexes: {@link OwnerUniqueIndex}
 * (value → `int` record id) and {@link GlobalUniqueIndex} (value → packed-`long` entity tuple).
 *
 * The two indexes cannot share a common base class — {@link OwnerUniqueIndex} is bound into the sealed
 * {@link UniqueIndex} hierarchy (whose {@link UniqueIndexView} sibling owns no value tree at all), while
 * {@link GlobalUniqueIndex} is a standalone catalog-level structure implementing different interfaces — and their
 * per-record state diverges by payload width (a `long` packed tuple vs a plain `int` record id), which a generic base
 * could only unify by boxing on the per-record hot path. The genuinely identical value-ordering and page-stream
 * plumbing is therefore centralised here as stateless static helpers instead of through inheritance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class UniqueIndexBPlusTreeSupport {
	/**
	 * Block-size geometry of the value bucket tree — a 256-entry leaf with the matching minimum split thresholds,
	 * identical for both unique-index flavours.
	 */
	private static final int VALUE_BLOCK_SIZE = 256;
	private static final int MIN_VALUE_BLOCK_SIZE = VALUE_BLOCK_SIZE / 2 - 1;
	private static final int MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(MIN_VALUE_BLOCK_SIZE / 2.0) - 1);

	/**
	 * Natural-order comparator for every value type whose `compareTo` is consistent with `equals`. Typed over the
	 * heterogeneous self-comparable key {@code Comparable<?>} (the tree's runtime key type is decided per attribute), so
	 * the cast bridging {@link Comparator#naturalOrder()}'s recursive `T extends Comparable<? super T>` bound to this
	 * erased face is the one unavoidable unchecked step.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static final Comparator<Comparable<?>> NATURAL_ORDER = (Comparator) Comparator.naturalOrder();
	/**
	 * Exact total order for {@link BigDecimal} consistent with {@link BigDecimal#equals}: compares by numeric value and
	 * breaks ties by scale, so `1.0` and `1.00` remain distinct unique keys (unlike the scale-collapsing natural order).
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static final Comparator<Comparable<?>> BIG_DECIMAL_EXACT_ORDER =
		(Comparator) Comparator.comparing((BigDecimal a) -> a).thenComparingInt(BigDecimal::scale);

	private UniqueIndexBPlusTreeSupport() {
		throw new UnsupportedOperationException("This class is a static-helper holder and must not be instantiated.");
	}

	/**
	 * The value order used by a unique-index value tree: natural order, or the scale-preserving exact order for
	 * {@link BigDecimal} (where uniqueness must keep `1.0` and `1.00` distinct).
	 *
	 * @param plainType the plain (array-unwrapped) attribute type
	 * @return the comparator that orders the tree keys
	 */
	@Nonnull
	static Comparator<Comparable<?>> comparatorFor(@Nonnull Class<?> plainType) {
		return BigDecimal.class.isAssignableFrom(plainType) ? BIG_DECIMAL_EXACT_ORDER : NATURAL_ORDER;
	}

	/**
	 * Resolves the plain (array-unwrapped) attribute type that drives the comparator and leaf-column choice.
	 *
	 * @param attributeType the declared attribute type (possibly an array type)
	 * @return the element type for an array attribute, otherwise the attribute type itself
	 */
	@Nonnull
	static Class<? extends Serializable> plainTypeOf(@Nonnull Class<? extends Serializable> attributeType) {
		//noinspection unchecked
		return attributeType.isArray() ? (Class<? extends Serializable>) attributeType.getComponentType() : attributeType;
	}

	/**
	 * Creates a fresh, empty value tree with a single-`long` payload column (used by {@link GlobalUniqueIndex} to hold
	 * the packed entity tuple), ordered by the given comparator and with a front-coded leaf column for String keys.
	 *
	 * @param plainType  the plain (array-unwrapped) attribute type
	 * @param comparator the value order
	 * @return the fresh empty long-payload bucket tree
	 */
	@Nonnull
	static TransactionalBucketBPlusTree<?> newLongPayloadTree(@Nonnull Class<?> plainType, @Nonnull Comparator<Comparable<?>> comparator) {
		return buildTree(plainType, comparator, true);
	}

	/**
	 * Creates a fresh, empty value tree with a single-`int` payload column (used by {@link OwnerUniqueIndex} to hold the
	 * owning record id), ordered by the given comparator and with a front-coded leaf column for String keys.
	 *
	 * @param plainType  the plain (array-unwrapped) attribute type
	 * @param comparator the value order
	 * @return the fresh empty int-payload bucket tree
	 */
	@Nonnull
	static TransactionalBucketBPlusTree<?> newIntPayloadTree(@Nonnull Class<?> plainType, @Nonnull Comparator<Comparable<?>> comparator) {
		return buildTree(plainType, comparator, false);
	}

	/**
	 * Builds a fresh, empty bucket value tree with the chosen payload width. The tree key is the heterogeneous
	 * self-comparable {@code Comparable} whose concrete type is fixed only at runtime, so the generic key/comparator/
	 * column-factory wiring into the parameterized tree constructor is the one place the erased key forces unchecked
	 * casts — they are confined to this single helper rather than smeared across every call site.
	 *
	 * @param plainType   the plain (array-unwrapped) attribute type (selects the leaf column kind)
	 * @param comparator  the value order
	 * @param longPayload `true` for a single-`long` payload column ({@link GlobalUniqueIndex}), `false` for single-`int`
	 *                    ({@link OwnerUniqueIndex})
	 * @return the fresh empty bucket value tree
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static TransactionalBucketBPlusTree<?> buildTree(
		@Nonnull Class<?> plainType, @Nonnull Comparator<Comparable<?>> comparator, boolean longPayload
	) {
		// the erased key forces every generic argument raw together: a parameterized comparator beside a raw key type
		// gives the tree's `K` two conflicting bounds, so the key type, comparator and factory are all raw and infer `K` once
		final Class keyType = Comparable.class;
		final ValueColumnFactory factory = ValueColumnFactory.forKey(plainType, comparator);
		return longPayload
			? TransactionalBucketBPlusTree.withLongPayload(
				VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
				keyType, (Comparator) comparator, factory)
			: new TransactionalBucketBPlusTree(
				VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
				keyType, comparator, factory);
	}

}
