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

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies contract of {@link GlobalUniqueIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class GlobalUniqueIndexTest implements TimeBoundedTestSupport {
	private final Catalog catalog = Mockito.mock(Catalog.class);
	private final EntityReferenceWithLocale productRef = new EntityReferenceWithLocale(Entities.PRODUCT, 1, null);
	private final EntityReferenceWithLocale localizedProduct2EnglishRef = new EntityReferenceWithLocale(Entities.PRODUCT, 2, Locale.ENGLISH);
	private final EntityReferenceWithLocale localizedProduct2FrenchRef = new EntityReferenceWithLocale(Entities.PRODUCT, 2, Locale.FRENCH);
	private final EntityReferenceWithLocale localizedProduct3Ref = new EntityReferenceWithLocale(Entities.PRODUCT, 3, Locale.ENGLISH);
	private final GlobalUniqueIndex tested = new GlobalUniqueIndex(
		Scope.LIVE, new AttributeKey("whatever"), String.class, new HashMap<>(), new HashMap<>()
	);

	@BeforeEach
	void setUp() {
		final EntityCollection productCollection = Mockito.mock(EntityCollection.class);
		Mockito.when(productCollection.getEntityTypePrimaryKey()).thenReturn(1);
		Mockito.when(productCollection.getEntityType()).thenReturn(Entities.PRODUCT);
		Mockito.when(this.catalog.getCollectionForEntityPrimaryKeyOrThrowException(1)).thenReturn(productCollection);
		Mockito.when(this.catalog.getCollectionForEntityOrThrowException(Entities.PRODUCT)).thenReturn(productCollection);
		this.tested.attachToCatalog(null, this.catalog);
	}

	@Test
	void shouldRegisterUniqueValueAndRetrieveItBack() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, null, 1);
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("A", null).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("B", null).orElse(null));
	}

	@Test
	void shouldRegisterLocalizedUniqueValueAndRetrieveItBack() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 2);
		this.tested.registerUniqueKey("B", Entities.PRODUCT, Locale.FRENCH, 2);
		this.tested.registerUniqueKey("C", Entities.PRODUCT, Locale.ENGLISH, 3);
		assertEquals(this.localizedProduct2EnglishRef, this.tested.getEntityReferenceByUniqueValue("A", Locale.ENGLISH).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("A", Locale.FRENCH).orElse(null));
		assertEquals(this.localizedProduct2FrenchRef, this.tested.getEntityReferenceByUniqueValue("B", Locale.FRENCH).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("B", Locale.ENGLISH).orElse(null));
		assertEquals(this.localizedProduct3Ref, this.tested.getEntityReferenceByUniqueValue("C", Locale.ENGLISH).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("E", null).orElse(null));
	}

	@Test
	void shouldFailToRegisterDuplicateValues() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, null, 1);
		assertThrows(UniqueValueViolationException.class, () -> this.tested.registerUniqueKey("A", Entities.PRODUCT, null, 2));
	}

	@Test
	void shouldFailToRegisterDuplicateLocalizedValues() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 1);
		assertThrows(UniqueValueViolationException.class, () -> this.tested.registerUniqueKey("A", Entities.PRODUCT, Locale.GERMAN, 2));
	}

	@Test
	void shouldUnregisterPreviouslyRegisteredValue() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, null, 1);
		assertEquals(this.productRef, this.tested.unregisterUniqueKey("A", Entities.PRODUCT, null, 1));
		assertNull(this.tested.getEntityReferenceByUniqueValue("A", null).orElse(null));
	}

	@Test
	void shouldUnregisterPreviouslyRegisteredLocalizedValue() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 2);
		this.tested.registerUniqueKey("B", Entities.PRODUCT, Locale.FRENCH, 2);
		this.tested.registerUniqueKey("C", Entities.PRODUCT, Locale.ENGLISH, 3);
		assertEquals(this.localizedProduct2EnglishRef, this.tested.unregisterUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 2));
		assertEquals(this.localizedProduct2FrenchRef, this.tested.unregisterUniqueKey("B", Entities.PRODUCT, Locale.FRENCH, 2));
		assertEquals(this.localizedProduct3Ref, this.tested.unregisterUniqueKey("C", Entities.PRODUCT, Locale.ENGLISH, 3));

		assertNull(this.tested.getEntityReferenceByUniqueValue("A", Locale.ENGLISH).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("B", Locale.FRENCH).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("C", Locale.ENGLISH).orElse(null));
	}

	@Test
	void shouldFailToUnregisterUnknownValue() {
		assertThrows(IllegalArgumentException.class, () -> this.tested.unregisterUniqueKey("B", Entities.PRODUCT, null, 1));
		assertThrows(IllegalArgumentException.class, () -> this.tested.unregisterUniqueKey("B", Entities.PRODUCT, Locale.ENGLISH, 1));
	}

	@Test
	void shouldRegisterAndPartialUnregisterValues() {
		this.tested.registerUniqueKey(new String[]{"A", "B", "C"}, Entities.PRODUCT, null, 1);
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("A", null).orElse(null));
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("B", null).orElse(null));
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("C", null).orElse(null));

		this.tested.unregisterUniqueKey(new String[]{"B", "C"}, Entities.PRODUCT, null, 1);
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("A", null).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("B", null).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("C", null).orElse(null));
	}

	@Test
	void shouldRegisterAndPartialUnregisterLocalizedValues() {
		this.tested.registerUniqueKey(new String[]{"A", "B", "C"}, Entities.PRODUCT, Locale.ENGLISH, 1);
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("A", Locale.ENGLISH).orElse(null));
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("B", Locale.ENGLISH).orElse(null));
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("C", Locale.ENGLISH).orElse(null));

		this.tested.unregisterUniqueKey(new String[]{"B", "C"}, Entities.PRODUCT, Locale.ENGLISH, 1);
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("A", Locale.ENGLISH).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("B", Locale.ENGLISH).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("C", Locale.ENGLISH).orElse(null));
	}

	@ParameterizedTest(name = "GlobalUniqueIndex should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
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

						final GlobalUniqueIndex newGlobalUniqueIndex = new GlobalUniqueIndex(
							Scope.LIVE,
							committed.getAttributeKey(),
							committed.getType(),
							new HashMap<>(committed.getUniqueValueToEntityReference()),
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
