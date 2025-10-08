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

package io.evitadb.core.query.sort.attribute.translator;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.dataType.array.CompositeObjectArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies {@link AttributeComparator} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class AttributeComparatorTest {
	public static final String ATTRIBUTE_NAME = "test";
	private final AttributeComparator comparator = new AttributeComparator(
		ATTRIBUTE_NAME, Integer.class, null, OrderDirection.ASC
	);
	private final AttributeComparator reverseComparator = new AttributeComparator(
		ATTRIBUTE_NAME, Integer.class, null, OrderDirection.DESC
	);

	@BeforeEach
	void setUp() {
		this.comparator.prepareFor(100);
		this.reverseComparator.prepareFor(100);
	}

	@Test
	void shouldSortEntitiesInAscendingOrder() {
		final SealedEntity entity1 = Mockito.mock(SealedEntity.class);
		Mockito.when(entity1.getPrimaryKeyOrThrowException()).thenReturn(1);
		Mockito.when(entity1.getAttribute(ATTRIBUTE_NAME)).thenReturn("A");
		final SealedEntity entity2 = Mockito.mock(SealedEntity.class);
		Mockito.when(entity2.getPrimaryKeyOrThrowException()).thenReturn(2);
		Mockito.when(entity2.getAttribute(ATTRIBUTE_NAME)).thenReturn("B");
		assertEquals(-1, this.comparator.compare(entity1, entity2));
		assertEquals(1, this.comparator.compare(entity2, entity1));
		assertEquals(0, this.comparator.compare(entity1, entity1));
	}

	@Test
	void shouldSortEntitiesInDescendingOrder() {
		final SealedEntity entity1 = Mockito.mock(SealedEntity.class);
		Mockito.when(entity1.getPrimaryKeyOrThrowException()).thenReturn(1);
		Mockito.when(entity1.getAttribute(ATTRIBUTE_NAME)).thenReturn("A");
		final SealedEntity entity2 = Mockito.mock(SealedEntity.class);
		Mockito.when(entity2.getPrimaryKeyOrThrowException()).thenReturn(2);
		Mockito.when(entity2.getAttribute(ATTRIBUTE_NAME)).thenReturn("B");
		assertEquals(1, this.reverseComparator.compare(entity1, entity2));
		assertEquals(-1, this.reverseComparator.compare(entity2, entity1));
		assertEquals(0, this.reverseComparator.compare(entity1, entity1));
	}

	@Test
	void shouldIdentifyNonSortableEntityA() {
		final SealedEntity entity1 = Mockito.mock(SealedEntity.class);
		Mockito.when(entity1.getPrimaryKeyOrThrowException()).thenReturn(1);
		Mockito.when(entity1.getAttribute(ATTRIBUTE_NAME)).thenReturn("A");
		final SealedEntity entity2 = Mockito.mock(SealedEntity.class);
		Mockito.when(entity2.getPrimaryKeyOrThrowException()).thenReturn(2);
		Mockito.when(entity2.getAttribute(ATTRIBUTE_NAME)).thenReturn(null);
		assertEquals(-1, this.comparator.compare(entity1, entity2));
		assertEquals(1, ((CompositeObjectArray<EntityContract>) this.comparator.getNonSortedEntities()).getSize());
		assertEquals(entity2, ((CompositeObjectArray<EntityContract>) this.comparator.getNonSortedEntities()).get(0));
	}

}
