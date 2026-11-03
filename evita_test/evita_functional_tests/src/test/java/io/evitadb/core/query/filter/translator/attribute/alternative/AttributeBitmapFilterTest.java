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

package io.evitadb.core.query.filter.translator.attribute.alternative;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.AttributeBetween;
import io.evitadb.api.query.filter.AttributeContains;
import io.evitadb.api.query.filter.AttributeEndsWith;
import io.evitadb.api.query.filter.AttributeGreaterThan;
import io.evitadb.api.query.filter.AttributeGreaterThanEquals;
import io.evitadb.api.query.filter.AttributeInRange;
import io.evitadb.api.query.filter.AttributeLessThan;
import io.evitadb.api.query.filter.AttributeLessThanEquals;
import io.evitadb.api.query.filter.AttributeStartsWith;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.filter.translator.TestQueryExecutionContext;
import io.evitadb.core.query.filter.translator.attribute.AttributeBetweenTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeContainsTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeEndsWithTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeGreaterThanEqualsTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeGreaterThanTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeInRangeTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeLessThanEqualsTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeLessThanTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeStartsWithTranslator;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * This test verifies behaviour of {@link AttributeBitmapFilter}: the filter built from the
 * {@link AttributeValueMatcher} a translator creates selects the prefetched entities holding a value the matcher
 * accepts, and only them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Tag(ENGINE)
@Tag(FILTER)
@Tag(ATTRIBUTE)
class AttributeBitmapFilterTest {
	public static final String NUMBER_RANGE = "numberRange";
	private static final Supplier<OffsetDateTime> NOW =
		() -> OffsetDateTime.of(2007, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	private static final AttributeBetweenTranslator BETWEEN = new AttributeBetweenTranslator();
	private static final AttributeInRangeTranslator IN_RANGE = new AttributeInRangeTranslator();
	private static final AttributeContainsTranslator CONTAINS = new AttributeContainsTranslator();
	private static final AttributeEndsWithTranslator ENDS_WITH = new AttributeEndsWithTranslator();
	private static final AttributeStartsWithTranslator STARTS_WITH = new AttributeStartsWithTranslator();
	private static final AttributeGreaterThanTranslator GREATER_THAN = new AttributeGreaterThanTranslator();
	private static final AttributeGreaterThanEqualsTranslator GREATER_THAN_EQUALS =
		new AttributeGreaterThanEqualsTranslator();
	private static final AttributeLessThanTranslator LESS_THAN = new AttributeLessThanTranslator();
	private static final AttributeLessThanEqualsTranslator LESS_THAN_EQUALS = new AttributeLessThanEqualsTranslator();
	private static final int SEED = 40;
	public static final EnumSet<Scope> TEST_SCOPES = EnumSet.of(Scope.LIVE);
	private AttributeSchemaAccessor attributeSchemaAccessor;
	private EntitySchemaContract entitySchema;
	private Map<Integer, SealedEntity> entities;

	@BeforeEach
	void setUp() {
		final DataGenerator dataGenerator = new DataGenerator();
		final CatalogSchema catalogSchema = CatalogSchema._internalBuild(
			TestConstants.TEST_CATALOG,
			NamingConvention.generate(TestConstants.TEST_CATALOG),
			null,
			EnumSet.allOf(CatalogEvolutionMode.class),
			new EmptyEntitySchemaAccessor()
		);
		final EvitaSession mockSession = Mockito.mock(EvitaSession.class);
		Mockito.when(mockSession.getCatalogSchema()).thenReturn(new CatalogSchemaDecorator(catalogSchema));
		this.entities = dataGenerator.generateEntities(
				dataGenerator.getSampleProductSchema(
					mockSession,
					EntitySchemaBuilder::toInstance,
					schemaBuilder -> schemaBuilder
						.withoutGeneratedPrimaryKey()
						.withAttribute(NUMBER_RANGE, IntegerNumberRange.class)
				),
				(serializable, faker) -> null,
				SEED
			)
			.limit(50)
			.collect(
				Collectors.toMap(
					EntityContract::getPrimaryKey,
					EntityBuilder::toInstance
				)
			);
		this.entitySchema = this.entities.values().iterator().next().getSchema();
		this.attributeSchemaAccessor = new AttributeSchemaAccessor(catalogSchema, this.entitySchema);
	}

	@Test
	void shouldFilterByNumberBetween() {
		final long from = 20000L;
		final long to = 80000L;
		final AttributeBetween constraint = attributeBetween(DataGenerator.ATTRIBUTE_PRIORITY, from, to);
		final AttributeBitmapFilter filter = createFilter(
			DataGenerator.ATTRIBUTE_PRIORITY,
			attributeSchema -> BETWEEN.createMatcher(constraint, attributeSchema, keyOf(attributeSchema), NOW)
		);

		final Bitmap result = filter.filter(
			createTestFilterByVisitor(
				filterBy(constraint),
				filter
			)
		);

		assertFalse(result.isEmpty());
		for (int ePK : result.getArray()) {
			final Long priority = this.entities.get(ePK).getAttribute(DataGenerator.ATTRIBUTE_PRIORITY);
			assertNotNull(priority);
			assertTrue(priority >= from && priority <= to);
		}
	}

	@Test
	void shouldFilterByNumberRangeOverlap() {
		final AttributeBetween constraint = attributeBetween(NUMBER_RANGE, 40, 50);
		final AttributeBitmapFilter filter = createFilter(
			NUMBER_RANGE,
			attributeSchema -> BETWEEN.createMatcher(constraint, attributeSchema, keyOf(attributeSchema), NOW)
		);
		final Bitmap result = filter.filter(createTestFilterByVisitor(filterBy(constraint), filter));

		assertFalse(result.isEmpty());
		for (int ePK : result.getArray()) {
			final IntegerNumberRange range = this.entities.get(ePK).getAttribute(NUMBER_RANGE);
			assertNotNull(range);
			assertTrue(range.overlaps(IntegerNumberRange.between(40, 50)));
		}
	}

	@Test
	void shouldFilterByDateTimeRangeOverlap() {
		final OffsetDateTime from = OffsetDateTime.of(2007, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
		final OffsetDateTime to = OffsetDateTime.of(2008, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
		final AttributeBetween constraint = attributeBetween(DataGenerator.ATTRIBUTE_VALIDITY, from, to);
		final AttributeBitmapFilter filter = createFilter(
			DataGenerator.ATTRIBUTE_VALIDITY,
			attributeSchema -> BETWEEN.createMatcher(constraint, attributeSchema, keyOf(attributeSchema), NOW)
		);

		final Bitmap result = filter.filter(createTestFilterByVisitor(filterBy(constraint), filter));

		assertFalse(result.isEmpty());
		for (int ePK : result.getArray()) {
			final DateTimeRange range = this.entities.get(ePK).getAttribute(DataGenerator.ATTRIBUTE_VALIDITY);
			assertNotNull(range);
			assertTrue(range.overlaps(DateTimeRange.between(from, to)));
		}
	}

	@Test
	void shouldFilterByNumberRangeWithin() {
		final AttributeInRange constraint = attributeInRange(NUMBER_RANGE, 45);
		final AttributeBitmapFilter filter = createFilter(
			NUMBER_RANGE,
			attributeSchema -> IN_RANGE.createMatcher(constraint, attributeSchema, keyOf(attributeSchema), NOW)
		);
		final Bitmap result = filter.filter(createTestFilterByVisitor(filterBy(constraint), filter));

		assertFalse(result.isEmpty());
		for (int ePK : result.getArray()) {
			final IntegerNumberRange range = this.entities.get(ePK).getAttribute(NUMBER_RANGE);
			assertNotNull(range);
			assertTrue(range.isWithin(45));
		}
	}

	@Test
	void shouldFilterByDateTimeRangeWithin() {
		final OffsetDateTime theMoment = OffsetDateTime.of(2007, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
		final AttributeInRange constraint = attributeInRange(DataGenerator.ATTRIBUTE_VALIDITY, theMoment);
		final AttributeBitmapFilter filter = createFilter(
			DataGenerator.ATTRIBUTE_VALIDITY,
			attributeSchema -> IN_RANGE.createMatcher(constraint, attributeSchema, keyOf(attributeSchema), NOW)
		);

		final Bitmap result = filter.filter(createTestFilterByVisitor(filterBy(constraint), filter));

		assertFalse(result.isEmpty());
		for (int ePK : result.getArray()) {
			final DateTimeRange range = this.entities.get(ePK).getAttribute(DataGenerator.ATTRIBUTE_VALIDITY);
			assertNotNull(range);
			assertTrue(range.isWithin(theMoment));
		}
	}

	@Test
	void shouldFilterByStringContains() {
		final String attributeName = DataGenerator.ATTRIBUTE_CODE;
		final String textToSearch = "Hat";
		final AttributeContains constraint = attributeContains(attributeName, textToSearch);
		final AttributeBitmapFilter filter = createFilter(
			attributeName,
			attributeSchema -> CONTAINS.createMatcher(constraint, attributeSchema, keyOf(attributeSchema), NOW)
		);

		final Bitmap result = filter.filter(createTestFilterByVisitor(filterBy(constraint), filter));

		assertFalse(result.isEmpty());
		for (int ePK : result.getArray()) {
			final String attribute = this.entities.get(ePK).getAttribute(attributeName);
			assertNotNull(attribute);
			assertTrue(attribute.contains(textToSearch));
		}
	}

	@Test
	void shouldFilterByStringEndsWith() {
		final String attributeName = DataGenerator.ATTRIBUTE_CODE;
		final String textToSearch = "1";
		final AttributeEndsWith constraint = attributeEndsWith(attributeName, textToSearch);
		final AttributeBitmapFilter filter = createFilter(
			attributeName,
			attributeSchema -> ENDS_WITH.createMatcher(constraint, attributeSchema, keyOf(attributeSchema), NOW)
		);

		final Bitmap result = filter.filter(createTestFilterByVisitor(filterBy(constraint), filter));

		assertFalse(result.isEmpty());
		for (int ePK : result.getArray()) {
			final String attribute = this.entities.get(ePK).getAttribute(attributeName);
			assertNotNull(attribute);
			assertTrue(attribute.endsWith(textToSearch));
		}
	}

	@Test
	void shouldFilterByStringStartsWith() {
		final String attributeName = DataGenerator.ATTRIBUTE_CODE;
		final String textToSearch = "Practical";
		final AttributeStartsWith constraint = attributeStartsWith(attributeName, textToSearch);
		final AttributeBitmapFilter filter = createFilter(
			attributeName,
			attributeSchema -> STARTS_WITH.createMatcher(constraint, attributeSchema, keyOf(attributeSchema), NOW)
		);

		final Bitmap result = filter.filter(createTestFilterByVisitor(filterBy(constraint), filter));

		assertFalse(result.isEmpty());
		for (int ePK : result.getArray()) {
			final String attribute = this.entities.get(ePK).getAttribute(attributeName);
			assertNotNull(attribute);
			assertTrue(attribute.startsWith(textToSearch));
		}
	}

	@Test
	void shouldFilterByNumberGreaterThanEquals() {
		final String attributeName = DataGenerator.ATTRIBUTE_QUANTITY;
		final BigDecimal theNumber = new BigDecimal("200");
		final AttributeGreaterThanEquals constraint = attributeGreaterThanEquals(attributeName, theNumber);
		final AttributeBitmapFilter filter = createFilter(
			attributeName,
			attributeSchema -> GREATER_THAN_EQUALS.createMatcher(
				constraint, attributeSchema, keyOf(attributeSchema), NOW
			)
		);

		final Bitmap result = filter.filter(createTestFilterByVisitor(filterBy(constraint), filter));

		assertFalse(result.isEmpty());
		for (int ePK : result.getArray()) {
			final BigDecimal attribute = this.entities.get(ePK).getAttribute(attributeName);
			assertNotNull(attribute);
			assertTrue(attribute.compareTo(theNumber) >= 0);
		}
	}

	@Test
	void shouldFilterByNumberGreaterThan() {
		final String attributeName = DataGenerator.ATTRIBUTE_QUANTITY;
		final BigDecimal theNumber = new BigDecimal("200");
		final AttributeGreaterThan constraint = attributeGreaterThan(attributeName, theNumber);
		final AttributeBitmapFilter filter = createFilter(
			attributeName,
			attributeSchema -> GREATER_THAN.createMatcher(constraint, attributeSchema, keyOf(attributeSchema), NOW)
		);

		final Bitmap result = filter.filter(createTestFilterByVisitor(filterBy(constraint), filter));

		assertFalse(result.isEmpty());
		for (int ePK : result.getArray()) {
			final BigDecimal attribute = this.entities.get(ePK).getAttribute(attributeName);
			assertNotNull(attribute);
			assertTrue(attribute.compareTo(theNumber) > 0);
		}
	}

	@Test
	void shouldFilterByNumberLesserThan() {
		final String attributeName = DataGenerator.ATTRIBUTE_QUANTITY;
		final BigDecimal theNumber = new BigDecimal("200");
		final AttributeLessThan constraint = attributeLessThan(attributeName, theNumber);
		final AttributeBitmapFilter filter = createFilter(
			attributeName,
			attributeSchema -> LESS_THAN.createMatcher(constraint, attributeSchema, keyOf(attributeSchema), NOW)
		);

		final Bitmap result = filter.filter(createTestFilterByVisitor(filterBy(constraint), filter));

		assertFalse(result.isEmpty());
		for (int ePK : result.getArray()) {
			final BigDecimal attribute = this.entities.get(ePK).getAttribute(attributeName);
			assertNotNull(attribute);
			assertTrue(attribute.compareTo(theNumber) < 0);
		}
	}

	@Test
	void shouldFilterByNumberLesserThanEquals() {
		final String attributeName = DataGenerator.ATTRIBUTE_QUANTITY;
		final BigDecimal theNumber = new BigDecimal("200");
		final AttributeLessThanEquals constraint = attributeLessThanEquals(attributeName, theNumber);
		final AttributeBitmapFilter filter = createFilter(
			attributeName,
			attributeSchema -> LESS_THAN_EQUALS.createMatcher(constraint, attributeSchema, keyOf(attributeSchema), NOW)
		);

		final Bitmap result = filter.filter(createTestFilterByVisitor(filterBy(constraint), filter));

		assertFalse(result.isEmpty());
		for (int ePK : result.getArray()) {
			final BigDecimal attribute = this.entities.get(ePK).getAttribute(attributeName);
			assertNotNull(attribute);
			assertTrue(attribute.compareTo(theNumber) <= 0);
		}
	}

	@Test
	void shouldFilterByNumberIsNull() {
		final String attributeName = DataGenerator.ATTRIBUTE_QUANTITY;
		final AttributeBitmapFilter filter = new AttributeBitmapFilter(
			attributeName,
			AttributeContent.ALL_ATTRIBUTES,
			(entitySchema, theAttributeName, attributeTraits) -> this.attributeSchemaAccessor.getAttributeSchema(entitySchema, theAttributeName, TEST_SCOPES, attributeTraits),
			(entityContract, theAttributeName) -> Stream.of(entityContract.getAttributeValue(theAttributeName, null)),
			attributeSchema -> optionalStream -> optionalStream.noneMatch(Optional::isPresent)
		);

		final Bitmap result = filter.filter(
			createTestFilterByVisitor(filterBy(
				attributeIsNull(attributeName)
			), filter)
		);

		assertFalse(result.isEmpty());
		for (int ePK : result.getArray()) {
			final BigDecimal attribute = this.entities.get(ePK).getAttribute(attributeName);
			assertNull(attribute);
		}
	}

	@Test
	void shouldFilterByNumberIsNotNull() {
		final String attributeName = DataGenerator.ATTRIBUTE_QUANTITY;
		final AttributeBitmapFilter filter = new AttributeBitmapFilter(
			attributeName,
			AttributeContent.ALL_ATTRIBUTES,
			(entitySchema, theAttributeName, attributeTraits) -> this.attributeSchemaAccessor.getAttributeSchema(entitySchema, theAttributeName, TEST_SCOPES, attributeTraits),
			(entityContract, theAttributeName) -> Stream.of(entityContract.getAttributeValue(theAttributeName, null)),
			attributeSchema -> optionalStream -> optionalStream.anyMatch(Optional::isPresent)
		);

		final Bitmap result = filter.filter(
			createTestFilterByVisitor(filterBy(
				attributeIsNotNull(attributeName)
			), filter)
		);

		assertFalse(result.isEmpty());
		for (int ePK : result.getArray()) {
			final BigDecimal attribute = this.entities.get(ePK).getAttribute(attributeName);
			assertNotNull(attribute);
		}
	}

	@Test
	void shouldCreateTheMatcherOncePerEntityType() {
		final AtomicInteger created = new AtomicInteger();
		final AttributeBetween constraint = attributeBetween(DataGenerator.ATTRIBUTE_PRIORITY, 20000L, 80000L);
		final AttributeBitmapFilter filter = createFilter(
			DataGenerator.ATTRIBUTE_PRIORITY,
			attributeSchema -> {
				created.incrementAndGet();
				return BETWEEN.createMatcher(constraint, attributeSchema, keyOf(attributeSchema), NOW);
			}
		);

		filter.filter(createTestFilterByVisitor(filterBy(constraint), filter));

		assertEquals(1, created.get());
	}

	@Test
	void shouldSelectNoEntityHoldingNoValue() {
		final String attributeName = DataGenerator.ATTRIBUTE_QUANTITY;
		final AttributeLessThan constraint = attributeLessThan(attributeName, new BigDecimal("1000000"));
		final AttributeBitmapFilter filter = createFilter(
			attributeName,
			attributeSchema -> LESS_THAN.createMatcher(constraint, attributeSchema, keyOf(attributeSchema), NOW)
		);

		final Bitmap result = filter.filter(createTestFilterByVisitor(filterBy(constraint), filter));

		final long withValue = this.entities.values().stream()
			.filter(it -> it.getAttribute(attributeName) != null)
			.count();
		assertTrue(withValue < this.entities.size());
		assertEquals(withValue, result.size());
		for (int ePK : result.getArray()) {
			assertNotNull(this.entities.get(ePK).getAttribute(attributeName));
		}
	}

	@Test
	void shouldSelectEntityWhenAnyValueOfTheStreamMatches() {
		final AttributeBetween constraint = attributeBetween(DataGenerator.ATTRIBUTE_PRIORITY, 20000L, 80000L);
		final Function<AttributeSchemaContract, AttributeValueMatcher> matcherFactory =
			attributeSchema -> BETWEEN.createMatcher(constraint, attributeSchema, keyOf(attributeSchema), NOW);
		final AttributeBitmapFilter singleValueFilter = createFilter(DataGenerator.ATTRIBUTE_PRIORITY, matcherFactory);
		final Bitmap expected = singleValueFilter.filter(
			createTestFilterByVisitor(filterBy(constraint), singleValueFilter)
		);
		assertFalse(expected.isEmpty());

		// the value of the entity follows an absent value and a value outside the interval
		final AttributeBitmapFilter filter = createFilter(
			DataGenerator.ATTRIBUTE_PRIORITY,
			(entityContract, theAttributeName) -> Stream.of(
				Optional.<AttributeValue>empty(),
				Optional.of(new AttributeValue(new AttributeKey(theAttributeName), 1L)),
				entityContract.getAttributeValue(theAttributeName, null)
			),
			matcherFactory
		);
		final Bitmap result = filter.filter(createTestFilterByVisitor(filterBy(constraint), filter));

		assertArrayEquals(expected.getArray(), result.getArray());
	}

	@Test
	void shouldSelectNothingWhenNoEntityIsPrefetched() {
		final AttributeBetween constraint = attributeBetween(DataGenerator.ATTRIBUTE_PRIORITY, 20000L, 80000L);
		final AttributeBitmapFilter filter = createFilter(
			DataGenerator.ATTRIBUTE_PRIORITY,
			attributeSchema -> BETWEEN.createMatcher(constraint, attributeSchema, keyOf(attributeSchema), NOW)
		);

		assertTrue(filter.filter(new TestQueryExecutionContext(
			this.entitySchema,
			Query.query(collection(Entities.PRODUCT), filterBy(constraint), require(filter.getEntityRequire())),
			Collections.emptyMap()
		)).isEmpty());
	}

	/**
	 * Creates the filter of the attribute over the entities of the test, the matcher being created from the schema of
	 * the attribute.
	 */
	@Nonnull
	private AttributeBitmapFilter createFilter(
		@Nonnull String attributeName,
		@Nonnull Function<AttributeSchemaContract, AttributeValueMatcher> matcherFactory
	) {
		return createFilter(
			attributeName,
			(entityContract, theAttributeName) -> Stream.of(entityContract.getAttributeValue(theAttributeName, null)),
			matcherFactory
		);
	}

	/**
	 * Creates the filter of the attribute over the entities of the test, reading the values of an entity with the
	 * given accessor.
	 */
	@Nonnull
	private AttributeBitmapFilter createFilter(
		@Nonnull String attributeName,
		@Nonnull BiFunction<EntityContract, String, Stream<Optional<AttributeValue>>> attributeValueAccessor,
		@Nonnull Function<AttributeSchemaContract, AttributeValueMatcher> matcherFactory
	) {
		return AttributeBitmapFilter.matching(
			attributeName,
			(entitySchema, theAttributeName, attributeTraits) -> this.attributeSchemaAccessor.getAttributeSchema(
				entitySchema, theAttributeName, TEST_SCOPES, attributeTraits
			),
			attributeValueAccessor,
			matcherFactory
		);
	}

	/**
	 * Returns the key of the filter index of the not localized attribute.
	 */
	@Nonnull
	private static AttributeIndexKey keyOf(@Nonnull AttributeSchemaContract attributeSchema) {
		return AttributeIndex.createAttributeKey(null, attributeSchema, null);
	}

	@Nonnull
	private TestQueryExecutionContext createTestFilterByVisitor(FilterBy filterBy, AttributeBitmapFilter filter) {
		return new TestQueryExecutionContext(
			this.entitySchema,
			Query.query(
				collection(Entities.PRODUCT),
				filterBy,
				require(
					filter.getEntityRequire()
				)
			),
			this.entities
		);
	}

	private static class EmptyEntitySchemaAccessor implements EntitySchemaProvider {
		@Nonnull
		@Override
		public Collection<EntitySchemaContract> getEntitySchemas() {
			return Collections.emptyList();
		}

		@Nonnull
		@Override
		public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
			return Optional.empty();
		}
	}
}
