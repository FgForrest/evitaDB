/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized stress test for {@link GlobalUniqueIndex}. Verifies the contract under
 * random sequences of register/unregister operations applied within transactional commit boundaries.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class LongRunningGlobalUniqueIndexTest implements TimeBoundedTestSupport {
	private final Catalog catalog = Mockito.mock(Catalog.class);

	@BeforeEach
	void setUp() {
		final EntityCollection productCollection = Mockito.mock(EntityCollection.class);
		Mockito.when(productCollection.getEntityTypePrimaryKey()).thenReturn(1);
		Mockito.when(productCollection.getEntityType()).thenReturn(Entities.PRODUCT);
		Mockito.when(this.catalog.getCollectionForEntityPrimaryKeyOrThrowException(1)).thenReturn(productCollection);
		Mockito.when(this.catalog.getCollectionForEntityOrThrowException(Entities.PRODUCT)).thenReturn(productCollection);
	}

	@ParameterizedTest(name = "GlobalUniqueIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final Map<String, Integer> mapToCompare = new HashMap<>();
		final Set<Integer> currentRecordSet = new HashSet<>();
		final GlobalUniqueIndex initialUniqueIndex = new GlobalUniqueIndex(Scope.LIVE, new AttributeKey("code"), String.class);
		initialUniqueIndex.attachToCatalog(null, this.catalog);

		runFor(
			input,
			1_000,
			new TestState(
				new StringBuilder(),
				1,
				initialUniqueIndex
			),
			(random, testState) -> {
				final StringBuilder codeBuffer = testState.code();
				codeBuffer.append("final Classifier product = new Classifier(Entities.PRODUCT);\nfinal GlobalUniqueIndex uniqueIndex = new GlobalUniqueIndex(\"code\", String.class);\n")
					.append(
						mapToCompare.entrySet()
							.stream()
							.map(it -> "uniqueIndex.registerUniqueKey(\"" + it.getKey() + "\"," + it.getValue() + ");")
							.collect(Collectors.joining("\n"))
					)
					.append("\nOps:\n");
				final GlobalUniqueIndex transactionalUniqueIndex = testState.initialState();
				final AtomicReference<GlobalUniqueIndex> committedResult = new AtomicReference<>();

				assertStateAfterCommit(
					transactionalUniqueIndex,
					original -> {
						try {
							final int operationsInTransaction = random.nextInt(100);
							for (int i = 0; i < operationsInTransaction; i++) {
								final int length = transactionalUniqueIndex.size();
								if ((random.nextBoolean() || length < 10) && length < 50) {
									// insert new item
									final String newValue = Character.toString(65 + random.nextInt(28)) + "_" + ((testState.iteration() * 100) + i);
									int newRecId;
									do {
										newRecId = random.nextInt(initialCount * 2);
									} while (currentRecordSet.contains(newRecId));
									mapToCompare.put(newValue, newRecId);
									currentRecordSet.add(newRecId);

									codeBuffer.append("uniqueIndex.registerUniqueKey(\"").append(newValue).append("\", product, ").append(newRecId).append(");\n");
									transactionalUniqueIndex.registerUniqueKey(newValue, Entities.PRODUCT, null, newRecId);
								} else {
									// remove existing item
									final Iterator<Entry<String, Integer>> it = mapToCompare.entrySet().iterator();
									Entry<String, Integer> valueToRemove = null;
									for (int j = 0; j < random.nextInt(length) + 1; j++) {
										valueToRemove = it.next();
									}
									it.remove();
									currentRecordSet.remove(valueToRemove.getValue());

									codeBuffer.append("uniqueIndex.unregisterUniqueKey(\"").append(valueToRemove.getKey()).append("\", product,").append(valueToRemove.getValue()).append(");\n");
									transactionalUniqueIndex.unregisterUniqueKey(valueToRemove.getKey(), Entities.PRODUCT, null, valueToRemove.getValue());
								}
							}
						} catch (Exception ex) {
							fail("\n" + codeBuffer, ex);
						}
					},
					(original, committed) -> {
						final EntityReference[] expected = currentRecordSet.stream()
							.map(it -> new EntityReference(Entities.PRODUCT, it))
							.sorted()
							.toArray(EntityReference[]::new);

						committed.attachToCatalog(null, this.catalog);
						assertArrayEquals(
							expected,
							committed.getEntityReferences(),
							"\nExpected: " + Arrays.toString(expected) + "\n" +
								"Actual:  " + Arrays.toString(committed.getEntityReferences()) + "\n\n" +
								codeBuffer
						);

						final GlobalUniqueIndex.InlineSnapshot snapshot = committed.inlineSnapshot();
						final GlobalUniqueIndex newGlobalUniqueIndex = new GlobalUniqueIndex(
							Scope.LIVE,
							committed.getAttributeKey(),
							committed.getType(),
							snapshot.values(),
							snapshot.payloads(),
							new HashMap<>(committed.getLocaleIndex())
						);
						newGlobalUniqueIndex.attachToCatalog(null, this.catalog);
						committedResult.set(newGlobalUniqueIndex);
					}
				);

				return new TestState(
					new StringBuilder(),
					testState.iteration() + 1,
					committedResult.get()
				);
			}
		);
	}

	private record TestState(
		StringBuilder code,
		int iteration,
		GlobalUniqueIndex initialState
	) {}

}
