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
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.filter.VirtualPartitionRows;
import io.evitadb.core.query.filter.VirtualPartitionRows.InScopeReading;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link VirtualPartitionBodyEvaluator}: which reference bodies are answered for the virtual candidates of a
 * split from the candidate set alone, which are answered exactly, that every constraint a reference body admits has
 * one of the two readings - so the refusal never fires - and the owners the virtual part of the
 * answer consists of.
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
		assertTrue(VirtualPartitionBodyEvaluator.isRowExact(body, REFERENCE_NAME));
		assertTrue(VirtualPartitionBodyEvaluator.isRowExact(null, REFERENCE_NAME));
	}

	@Test
	@DisplayName("each constraint that is not row-exact makes the body answered exactly, wherever it sits")
	void shouldAnswerExactlyEveryBodyThatIsNotRowExact() {
		assertAnsweredExactly(not(attributeEquals("a", 1)));
		assertAnsweredExactly(and(attributeEquals("a", 1), attributeIs("a", AttributeSpecialValue.NULL)));
		assertAnsweredExactly(or(attributeEquals("a", 1), groupHaving(attributeEquals("g", 1))));
		assertAnsweredExactly(entityLocaleEquals(Locale.ENGLISH));
		assertAnsweredExactly(inScope(Scope.LIVE, attributeEquals("a", 1)));
		// the content of a groupHaving filters the group collection - a row-exact one still needs the group leaf
		assertAnsweredExactly(groupHaving(not(attributeEquals("g", 1))));
	}

	@Test
	@DisplayName("every constraint a reference body admits has a virtual reading - the refusal never fires")
	void shouldReadEveryConstraintAReferenceBodyAdmits() {
		final Set<Class<?>> admitted = new HashSet<>(32);
		for (final ConstraintDescriptor descriptor : ConstraintDescriptorProvider.getAllConstraints()) {
			if (
				descriptor.type() == ConstraintType.FILTER &&
					descriptor.supportedIn().contains(ConstraintDomain.REFERENCE)
			) {
				admitted.add(descriptor.constraintClass());
			}
		}
		// the container of a facet summary filter - never translated by the filter visitor, so never in a body
		assertTrue(admitted.remove(FilterGroupBy.class), "The catalogue must list `filterGroupBy`");
		// admitted in a reference body by the Java API, whatever its domain says
		admitted.add(EntityLocaleEquals.class);
		admitted.add(FilterBy.class);
		// a floor, so a catalogue that resolved to nothing cannot pass
		assertTrue(admitted.size() >= 20, "The catalogue must list the constraints of a reference body: " + admitted);
		for (final Class<?> constraintClass : admitted) {
			//noinspection unchecked
			assertTrue(
				VirtualPartitionBodyEvaluator.hasVirtualReading((Class<? extends FilterConstraint>) constraintClass),
				"Constraint `" + constraintClass.getSimpleName() + "` is admitted in a reference body but has no " +
					"virtual reading"
			);
		}
	}

	@Test
	@DisplayName("a constraint with no virtual reading is refused, never answered")
	void shouldRefuseAConstraintWithNoVirtualReading() {
		final GenericEvitaInternalError refusal = assertThrows(
			GenericEvitaInternalError.class,
			() -> VirtualPartitionBodyEvaluator.isRowExact(
				and(attributeEquals("a", 1), priceInCurrency("CZK")), REFERENCE_NAME
			)
		);
		assertTrue(
			refusal.getPrivateMessage().startsWith("Virtual read of `PriceInCurrency` is not defined"),
			refusal.getPrivateMessage()
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

	@Test
	@DisplayName("the exactly answered part holds the owners of the matching virtual candidates only")
	void shouldAnswerTheOwnersOfTheMatchingVirtualCandidates() {
		final ReferencePartitionSplit split = splitWithVirtualRows();
		// partition 7 matches, partition 2 of the same owner does not - the owner stays
		assertArrayEquals(
			new int[]{20, 30},
			VirtualPartitionBodyEvaluator.getOwnersFormula(split, new BaseBitmap(7, 9)).compute().getArray()
		);
		assertSame(EmptyFormula.INSTANCE, VirtualPartitionBodyEvaluator.getOwnersFormula(split, new BaseBitmap()));
		// a materialized candidate in the verdict contributes nothing
		assertSame(EmptyFormula.INSTANCE, VirtualPartitionBodyEvaluator.getOwnersFormula(split, new BaseBitmap(1, 3)));
	}

	@Test
	@DisplayName("the virtual rows are split per type index, in partition order, with owner and target")
	void shouldSplitTheVirtualRowsPerTypeIndex() {
		final ReferencedTypeEntityIndex live = typeIndex(1, Scope.LIVE);
		final ReferencedTypeEntityIndex archived = typeIndex(2, Scope.ARCHIVED);
		live.getVirtualReferencePartitionIndex().putPartitionOwner(4, 40, 400);
		archived.getVirtualReferencePartitionIndex().putPartitionOwner(3, 30, 300);
		archived.getVirtualReferencePartitionIndex().putPartitionOwner(6, 60, 600);
		final VirtualPartitionRows[] rows = VirtualPartitionRows.of(
			ReferencePartitions.split(new BaseBitmap(1, 3, 4, 6), live, archived), InScopeReading.OTHER_SCOPES_UNMET
		);
		assertEquals(2, rows.length);
		final VirtualPartitionRows liveRows = rows[0].getScope() == Scope.LIVE ? rows[0] : rows[1];
		final VirtualPartitionRows archivedRows = rows[0].getScope() == Scope.LIVE ? rows[1] : rows[0];
		assertArrayEquals(new int[]{4}, liveRows.getPartitionPrimaryKeys().getArray());
		assertArrayEquals(new int[]{3, 6}, archivedRows.getPartitionPrimaryKeys().getArray());
		assertEquals(60, archivedRows.getOwnerPrimaryKey(1));
		assertEquals(600, archivedRows.getTargetPrimaryKey(1));
		assertSame(InScopeReading.OTHER_SCOPES_UNMET, archivedRows.getInScopeReading());
		assertArrayEquals(
			new int[]{6},
			archivedRows.select(row -> archivedRows.getTargetPrimaryKey(row) > 300).compute().getArray()
		);
		assertSame(EmptyFormula.INSTANCE, archivedRows.select(row -> false));
	}

	/**
	 * Asserts the body has a virtual reading and is answered exactly rather than from the candidate set.
	 *
	 * @param body the body
	 */
	private static void assertAnsweredExactly(@Nonnull FilterConstraint body) {
		assertFalse(VirtualPartitionBodyEvaluator.isRowExact(body, REFERENCE_NAME), body.toString());
	}

	/**
	 * Creates an empty referenced-type entity index of the reference.
	 *
	 * @param primaryKey the primary key of the index
	 * @param scope      its scope
	 * @return the index
	 */
	@Nonnull
	private static ReferencedTypeEntityIndex typeIndex(int primaryKey, @Nonnull Scope scope) {
		return new ReferencedTypeEntityIndex(
			primaryKey, "product", new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, REFERENCE_NAME)
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
		final ReferencedTypeEntityIndex typeIndex = typeIndex(1, Scope.LIVE);
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
