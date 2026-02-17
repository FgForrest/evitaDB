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

package io.evitadb.api.query.require;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityContentRequireCombiningCollector} verifying stream collector contract, supplier, accumulator,
 * combiner, finisher, and integration with stream operations.
 *
 * @author evitaDB
 */
@DisplayName("EntityContentRequireCombiningCollector")
class EntityContentRequireCombiningCollectorTest {

	@Nested
	@DisplayName("Collector contract")
	class CollectorContractTest {

		@Test
		@DisplayName("should provide supplier that creates DefaultPrefetchRequirementCollector")
		void shouldProvideSupplierThatCreatesDefaultPrefetchRequirementCollector() {
			final EntityContentRequireCombiningCollector collector = new EntityContentRequireCombiningCollector();
			final Supplier<FetchRequirementCollector> supplier = collector.supplier();

			final FetchRequirementCollector result = supplier.get();

			assertNotNull(result);
			assertInstanceOf(DefaultPrefetchRequirementCollector.class, result);
		}

		@Test
		@DisplayName("should provide accumulator that adds requirements to collector")
		void shouldProvideAccumulatorThatAddsRequirementsToCollector() {
			final EntityContentRequireCombiningCollector collector = new EntityContentRequireCombiningCollector();
			final BiConsumer<FetchRequirementCollector, EntityContentRequire> accumulator = collector.accumulator();
			final FetchRequirementCollector fetchCollector = new DefaultPrefetchRequirementCollector();

			accumulator.accept(fetchCollector, attributeContent("code"));

			assertEquals(1, fetchCollector.getRequirementsToPrefetch().length);
			assertInstanceOf(AttributeContent.class, fetchCollector.getRequirementsToPrefetch()[0]);
		}

		@Test
		@DisplayName("should provide combiner that merges two collectors")
		void shouldProvideCombinerThatMergesTwoCollectors() {
			final EntityContentRequireCombiningCollector collector = new EntityContentRequireCombiningCollector();
			final BinaryOperator<FetchRequirementCollector> combiner = collector.combiner();

			final FetchRequirementCollector collector1 = new DefaultPrefetchRequirementCollector();
			collector1.addRequirementsToPrefetch(attributeContent("code"));

			final FetchRequirementCollector collector2 = new DefaultPrefetchRequirementCollector();
			collector2.addRequirementsToPrefetch(associatedDataContent("description"));

			final FetchRequirementCollector combined = combiner.apply(collector1, collector2);

			assertSame(collector1, combined);
			assertEquals(2, combined.getRequirementsToPrefetch().length);
		}

		@Test
		@DisplayName("should provide finisher that extracts requirements array")
		void shouldProvideFinisherThatExtractsRequirementsArray() {
			final EntityContentRequireCombiningCollector collector = new EntityContentRequireCombiningCollector();
			final Function<FetchRequirementCollector, EntityContentRequire[]> finisher = collector.finisher();

			final FetchRequirementCollector fetchCollector = new DefaultPrefetchRequirementCollector();
			fetchCollector.addRequirementsToPrefetch(attributeContent("code"), associatedDataContent("description"));

			final EntityContentRequire[] result = finisher.apply(fetchCollector);

			assertNotNull(result);
			assertEquals(2, result.length);
		}

		@Test
		@DisplayName("should provide empty characteristics set")
		void shouldProvideEmptyCharacteristicsSet() {
			final EntityContentRequireCombiningCollector collector = new EntityContentRequireCombiningCollector();
			final Set<Collector.Characteristics> characteristics = collector.characteristics();

			assertNotNull(characteristics);
			assertTrue(characteristics.isEmpty());
		}
	}

	@Nested
	@DisplayName("Stream integration")
	class StreamIntegrationTest {

		@Test
		@DisplayName("should collect single requirement from stream")
		void shouldCollectSingleRequirementFromStream() {
			final EntityContentRequire[] result = Stream.of(attributeContent("code"))
				.collect(new EntityContentRequireCombiningCollector());

			assertEquals(1, result.length);
			assertInstanceOf(AttributeContent.class, result[0]);
		}

		@Test
		@DisplayName("should collect and combine multiple requirements from stream")
		void shouldCollectAndCombineMultipleRequirementsFromStream() {
			final EntityContentRequire[] result = Stream.of(
					attributeContent("code"),
					attributeContent("name"),
					associatedDataContent("description")
				)
				.collect(new EntityContentRequireCombiningCollector());

			assertEquals(2, result.length);

			// Find AttributeContent in results - should be combined
			final AttributeContent attrContent = findRequirement(result, AttributeContent.class);
			assertNotNull(attrContent);
			assertArrayEquals(new String[]{"code", "name"}, attrContent.getAttributeNames());

			// Find AssociatedDataContent in results
			final AssociatedDataContent assocContent = findRequirement(result, AssociatedDataContent.class);
			assertNotNull(assocContent);
		}

		@Test
		@DisplayName("should collect empty stream to empty array")
		void shouldCollectEmptyStreamToEmptyArray() {
			final EntityContentRequire[] result = Stream.<EntityContentRequire>empty()
				.collect(new EntityContentRequireCombiningCollector());

			assertNotNull(result);
			assertEquals(0, result.length);
		}

		@Test
		@DisplayName("should handle parallel stream collection")
		void shouldHandleParallelStreamCollection() {
			final EntityContentRequire[] result = List.of(
					attributeContent("code"),
					attributeContent("name"),
					associatedDataContent("description"),
					dataInLocales(Locale.ENGLISH)
				)
				.parallelStream()
				.collect(new EntityContentRequireCombiningCollector());

			assertEquals(3, result.length);
		}
	}

	@Nested
	@DisplayName("Requirement combining")
	class RequirementCombiningTest {

		@Test
		@DisplayName("should combine attribute content requirements")
		void shouldCombineAttributeContentRequirements() {
			final EntityContentRequire[] result = Stream.of(
					attributeContent("code"),
					attributeContent("name", "description")
				)
				.collect(new EntityContentRequireCombiningCollector());

			assertEquals(1, result.length);
			assertInstanceOf(AttributeContent.class, result[0]);
			final AttributeContent combined = (AttributeContent) result[0];
			assertArrayEquals(new String[]{"code", "name", "description"}, combined.getAttributeNames());
		}

		@Test
		@DisplayName("should handle all-attributes override")
		void shouldHandleAllAttributesOverride() {
			final EntityContentRequire[] result = Stream.of(
					attributeContent("code"),
					attributeContentAll()
				)
				.collect(new EntityContentRequireCombiningCollector());

			assertEquals(1, result.length);
			assertInstanceOf(AttributeContent.class, result[0]);
			final AttributeContent combined = (AttributeContent) result[0];
			assertTrue(combined.isAllRequested());
		}

		@Test
		@DisplayName("should keep separate requirements of different types")
		void shouldKeepSeparateRequirementsOfDifferentTypes() {
			final EntityContentRequire[] result = Stream.of(
					attributeContent("code"),
					associatedDataContent("description"),
					priceContentAll()
				)
				.collect(new EntityContentRequireCombiningCollector());

			assertEquals(3, result.length);
		}

		@Test
		@DisplayName("should combine data in locales requirements")
		void shouldCombineDataInLocalesRequirements() {
			final EntityContentRequire[] result = Stream.of(
					dataInLocales(Locale.ENGLISH),
					dataInLocales(new Locale("cs"))
				)
				.collect(new EntityContentRequireCombiningCollector());

			assertEquals(1, result.length);
			assertInstanceOf(DataInLocales.class, result[0]);
		}
	}

	/**
	 * Finds the first requirement of the specified type in the array.
	 *
	 * @param requirements the array of requirements to search
	 * @param type the type of requirement to find
	 * @param <T> the requirement type
	 * @return the first matching requirement or null if not found
	 */
	private static <T extends EntityContentRequire> T findRequirement(
		@Nonnull EntityContentRequire[] requirements,
		@Nonnull Class<T> type
	) {
		for (final EntityContentRequire requirement : requirements) {
			if (type.isInstance(requirement)) {
				return type.cast(requirement);
			}
		}
		return null;
	}

}
