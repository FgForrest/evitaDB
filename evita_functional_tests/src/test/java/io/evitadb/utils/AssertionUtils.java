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

package io.evitadb.utils;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Transaction;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.transactionalMemory.TransactionalLayerProducer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Class contains shared assertions across functional tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AssertionUtils {

	/**
	 * Compares computation result of the formula with the expected contents and reports failure when the contents
	 * differ from expected ones.
	 */
	public static void assertFormulaResultsIn(Formula formula, int[] expectedContents) {
		assertIteratorContains(formula.compute().iterator(), expectedContents);
	}

	/**
	 * Compares iterator contents with the expected contents and reports failure when the contents
	 * differ from expected ones.
	 */
	public static <T> void assertIteratorContains(Iterator<T> it, T[] expectedContents) {
		int index = -1;
		while (it.hasNext()) {
			final T nextObj = it.next();
			assertTrue(expectedContents.length > index + 1);
			assertEquals(expectedContents[++index], nextObj);
		}
		assertEquals(
			expectedContents.length, index + 1,
			"There are more expected objects than int array produced by iterator!"
		);
	}

	/**
	 * Compares iterator contents with the expected contents and reports failure when the contents
	 * differ from expected ones.
	 */
	public static void assertIteratorContains(OfInt it, int[] expectedContents) {
		int index = -1;
		while (it.hasNext()) {
			final int nextInt = it.next();
			assertTrue(expectedContents.length > index + 1);
			assertEquals(expectedContents[++index], nextInt);
		}
		assertEquals(
			expectedContents.length, index + 1,
			"There are more expected objects than int array produced by iterator!"
		);
	}

	/**
	 * This method executes operation in lambda `doInTransaction` on `tested` instance and verifies the results visible
	 * after transactional memory is committed in `verifyAfterCommit` lambda.
	 */
	public static <S, X, T extends TransactionalLayerProducer<X, S>> void assertStateAfterCommit(T tested, Consumer<T> doInTransaction, BiConsumer<T, S> verifyAfterCommit) {
		Transaction.executeInTransactionIfProvided(
			Transaction.createMockTransactionForTests(),
			() -> {
				AtomicReference<S> copyHolder = new AtomicReference<>();
				final Transaction transaction = Transaction.getTransaction().orElseThrow();
				transaction.addTransactionCommitHandler(transactionalLayer -> {
					final S stateCopyWithCommittedChanges = transactionalLayer.getStateCopyWithCommittedChanges(tested, null);
					copyHolder.set(stateCopyWithCommittedChanges);
				});

				try {
					doInTransaction.accept(tested);
				} catch (Throwable ex) {
					transaction.setRollbackOnly();
					throw ex;
				} finally {
					transaction.close();
				}

				final S committedCopy = copyHolder.get();
				verifyAfterCommit.accept(tested, committedCopy);
			}
		);
	}

	/**
	 * This method executes operation in lambda `doInTransaction` on `tested` instance and verifies the results visible
	 * after transactional memory is rollbacked in `verifyAfterRollback` lambda.
	 */
	public static <S, X, T extends TransactionalLayerProducer<X, S>> void assertStateAfterRollback(T tested, Consumer<T> doInTransaction, BiConsumer<T, S> verifyAfterRollback) {
		Transaction.executeInTransactionIfProvided(
			Transaction.createMockTransactionForTests(),
			() -> {
				AtomicReference<S> copyHolder = new AtomicReference<>();
				final Transaction transaction = Transaction.getTransaction().orElseThrow();
				transaction.addTransactionCommitHandler(transactionalLayer -> {
					final S stateCopyWithCommittedChanges = transactionalLayer.getStateCopyWithCommittedChanges(tested, null);
					copyHolder.set(stateCopyWithCommittedChanges);
				});

				doInTransaction.accept(tested);

				transaction.setRollbackOnly();
				transaction.close();

				final S committedCopy = copyHolder.get();
				verifyAfterRollback.accept(tested, committedCopy);
			}
		);
	}

	/**
	 * Verifies that `originalEntities` filtered by `predicate` match exactly contents of the `resultToVerify`.
	 */
	public static void assertResultIs(@Nonnull List<SealedEntity> originalEntities, @Nonnull Predicate<SealedEntity> predicate, @Nonnull List<EntityReference> resultToVerify) {
		assertResultIs(null, originalEntities, predicate, resultToVerify);
	}

	/**
	 * Verifies that `originalEntities` filtered by `predicate` match exactly contents of the `resultToVerify`.
	 */
	public static void assertResultIs(@Nullable String message, @Nonnull List<SealedEntity> originalEntities, @Nonnull Predicate<SealedEntity> predicate, @Nonnull List<EntityReference> resultToVerify) {
		@SuppressWarnings("ConstantConditions") final int[] expectedResult = originalEntities.stream().filter(predicate).mapToInt(EntityContract::getPrimaryKey).toArray();
		assertFalse(ArrayUtils.isEmpty(expectedResult), "Expected result should never be empty - this would cause false positive tests!");
		assertResultEquals(
			message,
			resultToVerify,
			expectedResult
		);
	}

	/**
	 * Verifies that `record` primary keys exactly match passed `reference` ids. Both lists are sorted naturally before
	 * the comparison is executed.
	 */
	public static void assertResultEquals(@Nullable String message, @Nonnull List<EntityReference> records, @Nonnull int... reference) {
		final List<Integer> recordsCopy = records.stream().map(EntityReference::getPrimaryKey).sorted().collect(Collectors.toList());
		Arrays.sort(reference);

		assertSortedResultEquals(message, recordsCopy, reference);
	}

	/**
	 * Verifies that `record` primary keys exactly match passed `reference` ids in the exactly same ordering.
	 */
	public static void assertSortedResultEquals(@Nullable String message, @Nonnull List<Integer> records, @Nonnull int... reference) {
		final Set<Integer> expectedRecords = new HashSet<>(records);
		final Set<Integer> foundRecords = Arrays.stream(reference).boxed().collect(Collectors.toSet());
		final Set<Integer> difference = foundRecords.size() > expectedRecords.size() ? new HashSet<>(foundRecords) : new HashSet<>(expectedRecords);
		difference.removeAll(foundRecords.size() > expectedRecords.size() ? expectedRecords : foundRecords);
		assertEquals(
			reference.length, records.size(),
			ofNullable(message).map(it -> it + "\n").orElse("") +
				"\nExpected ids: " + Arrays.stream(reference).mapToObj(String::valueOf).collect(Collectors.joining(", ")) + "\n" +
				"  Actual ids: " + records.stream().map(String::valueOf).collect(Collectors.joining(", ")) + "\n" +
				"Unsorted arrays: " + (expectedRecords.equals(foundRecords) ? "match" : "differ " + difference.stream().map(Object::toString).collect(Collectors.joining(", ")))
		);
		for (int i = 0; i < reference.length; i++) {
			assertEquals(
				reference[i], records.get(i),
				ofNullable(message).map(it -> it + "\n").orElse("") +
					"\nExpected ids: " + Arrays.stream(reference).mapToObj(String::valueOf).collect(Collectors.joining(", ")) + "\n" +
					"  Actual ids: " + records.stream().map(String::valueOf).collect(Collectors.joining(", ")) + "\n" +
					"Unsorted arrays: " + (expectedRecords.equals(foundRecords) ? "match" : "differ " + difference.stream().map(Object::toString).collect(Collectors.joining(", ")))
			);
		}
	}

	/**
	 * Verifies that `record` primary keys exactly match passed `reference` ids. Both lists are sorted naturally before
	 * the comparison is executed.
	 */
	public static void assertResultEquals(List<EntityReference> records, int... reference) {
		assertResultEquals(null, records, reference);
	}

	/**
	 * Verifies that `record` primary keys exactly match passed `reference` ids in the exactly same ordering.
	 */
	public static void assertSortedResultEquals(@Nonnull List<Integer> records, @Nonnull int... reference) {
		assertSortedResultEquals(null, records, reference);
	}

	/**
	 * Returns true if examined array starts with the same elements as `with` array.
	 */
	public static void assertArrayStartsWith(Integer[] with, Integer[] examinedArray) {
		for (int i = 0; i < examinedArray.length; i++) {
			final Integer id = examinedArray[i];
			if (with.length > i) {
				assertEquals(
					id, with[i],
					"Array: " + Arrays.toString(examinedArray) + " doesn't start with " + Arrays.toString(with)
				);
			}
		}
	}

	/**
	 * Verifies whether the passed arrays differ one from another. Arrays are expected to be of the same size.
	 */
	public static void assertArrayAreDifferent(int[] arrayA, int[] arrayB) {
		assertEquals(arrayA.length, arrayB.length, "Both arrays should have same size.");
		for (int i = 0; i < arrayA.length; i++) {
			int i1 = arrayA[i];
			int i2 = arrayB[i];
			if (i1 != i2) {
				return;
			}
		}
		fail("Arrays are exactly the same!");
	}

}
