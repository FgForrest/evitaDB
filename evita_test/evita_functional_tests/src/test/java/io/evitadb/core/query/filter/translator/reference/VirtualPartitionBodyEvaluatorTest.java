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

package io.evitadb.core.query.filter.translator.reference;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencePartitionSplit;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Locale;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link VirtualPartitionBodyEvaluator}: which reference bodies can be answered for the virtual candidates of a
 * split from the candidate set alone, the refusal of every other body whenever a virtual candidate exists, and the
 * owners the virtual part of the answer consists of.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual partition body evaluator")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
class VirtualPartitionBodyEvaluatorTest {
	private static final String REFERENCE_NAME = "relatedProducts";

	@Test
	@DisplayName("every row-exact constraint is answered from the candidate set, in any nesting")
	void shouldAcceptRowExactBodies() {
		final ReferencePartitionSplit split = splitWithVirtualRows();
		final FilterConstraint body = filterBy(
			and(
				attributeEquals("a", 1),
				attributeInSet("a", 1, 2),
				attributeBetween("a", 1, 2),
				attributeLessThan("a", 1),
				attributeLessThanEquals("a", 1),
				attributeGreaterThan("a", 1),
				attributeGreaterThanEquals("a", 1),
				attributeStartsWith("b", "x"),
				attributeEndsWith("b", "x"),
				attributeContains("b", "x"),
				attributeInRange("c", 1),
				or(entityPrimaryKeyInSet(1, 2), attributeEquals("a", 2)),
				// the content of an entityHaving filters the referenced collection, never the reference row
				entityHaving(not(attributeIsNull("d")))
			)
		);
		assertDoesNotThrow(() -> VirtualPartitionBodyEvaluator.assertRowExact(body, split, REFERENCE_NAME));
		assertDoesNotThrow(() -> VirtualPartitionBodyEvaluator.assertRowExact(null, split, REFERENCE_NAME));
	}

	@Test
	@DisplayName("each constraint that is not row-exact is refused, wherever it sits in the body")
	void shouldRefuseEveryBodyThatIsNotRowExact() {
		final ReferencePartitionSplit split = splitWithVirtualRows();
		assertRefused("Not", split, not(attributeEquals("a", 1)));
		assertRefused("AttributeIs", split, and(attributeEquals("a", 1), attributeIs("a", AttributeSpecialValue.NULL)));
		assertRefused("GroupHaving", split, or(attributeEquals("a", 1), groupHaving(attributeEquals("g", 1))));
		assertRefused("EntityLocaleEquals", split, entityLocaleEquals(Locale.ENGLISH));
		assertRefused("FilterInScope", split, inScope(Scope.LIVE, attributeEquals("a", 1)));
	}

	@Test
	@DisplayName("a split without virtual candidates accepts any body, because the index objects answer it")
	void shouldAcceptAnyBodyWithoutVirtualCandidates() {
		final ReferencePartitionSplit split = ReferencePartitions.split(new BaseBitmap(1, 2, 3));
		assertDoesNotThrow(
			() -> VirtualPartitionBodyEvaluator.assertRowExact(not(attributeEquals("a", 1)), split, REFERENCE_NAME)
		);
	}

	@Test
	@DisplayName("the virtual part is a content constant over the owners of the virtual candidates")
	void shouldAnswerTheOwnersOfTheVirtualCandidates() {
		final Formula owners = VirtualPartitionBodyEvaluator.getOwnersFormula(splitWithVirtualRows());
		assertInstanceOf(ConstantFormula.class, owners);
		// partitions 2 and 7 share owner 20 - the owners come out once each, ascending
		assertArrayEquals(new int[]{10, 20, 30}, owners.compute().getArray());
		assertSame(
			EmptyFormula.INSTANCE,
			VirtualPartitionBodyEvaluator.getOwnersFormula(ReferencePartitions.split(new BaseBitmap(1, 2)))
		);
	}

	/**
	 * Asserts the body is refused and the refusal names the constraint and the step that lands it.
	 *
	 * @param constraintName the simple class name of the refused constraint
	 * @param split          the split with virtual candidates
	 * @param body           the body
	 */
	private static void assertRefused(
		@Nonnull String constraintName,
		@Nonnull ReferencePartitionSplit split,
		@Nonnull FilterConstraint body
	) {
		final GenericEvitaInternalError refusal = assertThrows(
			GenericEvitaInternalError.class,
			() -> VirtualPartitionBodyEvaluator.assertRowExact(body, split, REFERENCE_NAME)
		);
		assertTrue(
			refusal.getPrivateMessage().startsWith("Virtual read of `" + constraintName + "` lands in 4c"),
			refusal.getPrivateMessage()
		);
	}

	/**
	 * Creates a split of candidates 1 to 9 over a registry holding rows for partitions 2, 5, 7 and 9 - owned by 20, 10,
	 * 20 again and 30, so the owners are neither in partition order nor distinct.
	 *
	 * @return the split
	 */
	@Nonnull
	private static ReferencePartitionSplit splitWithVirtualRows() {
		final ReferencedTypeEntityIndex typeIndex = new ReferencedTypeEntityIndex(
			1, "product", new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
		);
		typeIndex.getVirtualReferencePartitionIndex().putPartitionOwner(2, 20, 200);
		typeIndex.getVirtualReferencePartitionIndex().putPartitionOwner(5, 10, 500);
		typeIndex.getVirtualReferencePartitionIndex().putPartitionOwner(7, 20, 700);
		typeIndex.getVirtualReferencePartitionIndex().putPartitionOwner(9, 30, 900);
		final ReferencePartitionSplit split = ReferencePartitions.split(
			new BaseBitmap(1, 2, 3, 4, 5, 6, 7, 8, 9), typeIndex
		);
		assertArrayEquals(new int[]{1, 3, 4, 6, 8}, split.getMaterializedPrimaryKeys().getArray());
		return split;
	}

}
