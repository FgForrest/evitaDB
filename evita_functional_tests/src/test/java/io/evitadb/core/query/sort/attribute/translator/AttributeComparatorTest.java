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

package io.evitadb.core.query.sort.attribute.translator;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.index.array.CompositeObjectArray;
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
	@SuppressWarnings({"unchecked", "rawtypes"})
	private final AttributeComparator comparator = new AttributeComparator(
		ATTRIBUTE_NAME, null, EntityAttributeExtractor.INSTANCE, (o1, o2) -> ((Comparable) o1).compareTo(o2)
	);
	@SuppressWarnings({"unchecked", "rawtypes"})
	private final AttributeComparator reverseComparator = new AttributeComparator(
		ATTRIBUTE_NAME, null, EntityAttributeExtractor.INSTANCE, (o1, o2) -> ((Comparable) o2).compareTo(o1)
	);

	@Test
	void shouldSortEntitiesInAscendingOrder() {
		final SealedEntity entity1 = Mockito.mock(SealedEntity.class);
		Mockito.when(entity1.getAttribute(ATTRIBUTE_NAME)).thenReturn("A");
		final SealedEntity entity2 = Mockito.mock(SealedEntity.class);
		Mockito.when(entity2.getAttribute(ATTRIBUTE_NAME)).thenReturn("B");
		assertEquals(-1, comparator.compare(entity1, entity2));
		assertEquals(1, comparator.compare(entity2, entity1));
		assertEquals(0, comparator.compare(entity1, entity1));
	}

	@Test
	void shouldSortEntitiesInDescendingOrder() {
		final SealedEntity entity1 = Mockito.mock(SealedEntity.class);
		Mockito.when(entity1.getAttribute(ATTRIBUTE_NAME)).thenReturn("A");
		final SealedEntity entity2 = Mockito.mock(SealedEntity.class);
		Mockito.when(entity2.getAttribute(ATTRIBUTE_NAME)).thenReturn("B");
		assertEquals(1, reverseComparator.compare(entity1, entity2));
		assertEquals(-1, reverseComparator.compare(entity2, entity1));
		assertEquals(0, reverseComparator.compare(entity1, entity1));
	}

	@Test
	void shouldIdentifyNonSortableEntityA() {
		final SealedEntity entity1 = Mockito.mock(SealedEntity.class);
		Mockito.when(entity1.getAttribute(ATTRIBUTE_NAME)).thenReturn("A");
		final SealedEntity entity2 = Mockito.mock(SealedEntity.class);
		Mockito.when(entity2.getAttribute(ATTRIBUTE_NAME)).thenReturn(null);
		assertEquals(-1, comparator.compare(entity1, entity2));
		assertEquals(1, ((CompositeObjectArray<EntityContract>)comparator.getNonSortedEntities()).getSize());
		assertEquals(entity2, ((CompositeObjectArray<EntityContract>)comparator.getNonSortedEntities()).get(0));

		assertEquals(1, comparator.compare(entity2, entity1));
		assertEquals(2, ((CompositeObjectArray<EntityContract>)comparator.getNonSortedEntities()).getSize());
		assertEquals(entity2, ((CompositeObjectArray<EntityContract>)comparator.getNonSortedEntities()).get(0));
		assertEquals(entity2, ((CompositeObjectArray<EntityContract>)comparator.getNonSortedEntities()).get(1));
	}

}