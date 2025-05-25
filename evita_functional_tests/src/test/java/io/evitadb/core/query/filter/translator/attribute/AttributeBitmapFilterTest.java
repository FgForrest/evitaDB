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

package io.evitadb.core.query.filter.translator.attribute;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.core.EvitaSession;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.filter.translator.TestQueryExecutionContext;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeBitmapFilter;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
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
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.core.query.filter.translator.attribute.AbstractAttributeComparisonTranslator.getPredicate;
import static io.evitadb.core.query.filter.translator.attribute.AbstractAttributeStringSearchTranslator.transformPredicate;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies behaviour of {@link AttributeBitmapFilter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class AttributeBitmapFilterTest {
	public static final String NUMBER_RANGE = "numberRange";
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
		final AttributeBitmapFilter filter = new AttributeBitmapFilter(
			DataGenerator.ATTRIBUTE_PRIORITY,
			AttributeContent.ALL_ATTRIBUTES,
			(entitySchema, attributeName, attributeTraits) -> this.attributeSchemaAccessor.getAttributeSchema(entitySchema, attributeName, TEST_SCOPES, attributeTraits),
			(entityContract, theAttributeName) -> Stream.of(entityContract.getAttributeValue(theAttributeName, null)),
			attributeSchema -> AttributeBetweenTranslator.getComparablePredicate(o -> (java.io.Serializable) o, Comparator.naturalOrder(), from, to)
		);

		final Bitmap result = filter.filter(
			createTestFilterByVisitor(
				filterBy(
					attributeBetween(DataGenerator.ATTRIBUTE_PRIORITY, from, to)
				),
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
		final AttributeBitmapFilter filter = new AttributeBitmapFilter(
			NUMBER_RANGE,
			AttributeContent.ALL_ATTRIBUTES,
			(entitySchema, attributeName, attributeTraits) -> this.attributeSchemaAccessor.getAttributeSchema(entitySchema, attributeName, TEST_SCOPES, attributeTraits),
			(entityContract, theAttributeName) -> Stream.of(entityContract.getAttributeValue(theAttributeName, null)),
			attributeSchema -> AttributeBetweenTranslator.getNumberRangePredicate(40, 50)
		);
		final Bitmap result = filter.filter(
			createTestFilterByVisitor(
				filterBy(
					attributeBetween(NUMBER_RANGE, 40, 50)
				),
				filter
			)
		);

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
		final AttributeBitmapFilter filter = new AttributeBitmapFilter(
			DataGenerator.ATTRIBUTE_VALIDITY,
			AttributeContent.ALL_ATTRIBUTES,
			(entitySchema, attributeName, attributeTraits) -> this.attributeSchemaAccessor.getAttributeSchema(entitySchema, attributeName, TEST_SCOPES, attributeTraits),
			(entityContract, theAttributeName) -> Stream.of(entityContract.getAttributeValue(theAttributeName, null)),
			attributeSchema -> AttributeBetweenTranslator.getDateTimePredicate(from, to)
		);

		final Bitmap result = filter.filter(
			createTestFilterByVisitor(
				filterBy(
					attributeBetween(DataGenerator.ATTRIBUTE_VALIDITY, from, to)
				),
				filter)
		);

		assertFalse(result.isEmpty());
		for (int ePK : result.getArray()) {
			final DateTimeRange range = this.entities.get(ePK).getAttribute(DataGenerator.ATTRIBUTE_VALIDITY);
			assertNotNull(range);
			assertTrue(range.overlaps(DateTimeRange.between(from, to)));
		}
	}

	@Test
	void shouldFilterByNumberRangeWithin() {
		final AttributeBitmapFilter filter = new AttributeBitmapFilter(
			NUMBER_RANGE,
			AttributeContent.ALL_ATTRIBUTES,
			(entitySchema, attributeName, attributeTraits) -> this.attributeSchemaAccessor.getAttributeSchema(entitySchema, attributeName, TEST_SCOPES, attributeTraits),
			(entityContract, theAttributeName) -> Stream.of(entityContract.getAttributeValue(theAttributeName, null)),
			attributeSchema -> AttributeInRangeTranslator.getNumberRangePredicate(45)
		);
		final Bitmap result = filter.filter(
			createTestFilterByVisitor(filterBy(
				attributeInRange(NUMBER_RANGE, 45)
			), filter)
		);

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
		final AttributeBitmapFilter filter = new AttributeBitmapFilter(
			DataGenerator.ATTRIBUTE_VALIDITY,
			AttributeContent.ALL_ATTRIBUTES,
			(entitySchema, attributeName, attributeTraits) -> this.attributeSchemaAccessor.getAttributeSchema(entitySchema, attributeName, TEST_SCOPES, attributeTraits),
			(entityContract, theAttributeName) -> Stream.of(entityContract.getAttributeValue(theAttributeName, null)),
			attributeSchema -> AttributeInRangeTranslator.getDateTimeRangePredicate(theMoment)
		);

		final Bitmap result = filter.filter(
			createTestFilterByVisitor(filterBy(
				attributeInRange(DataGenerator.ATTRIBUTE_VALIDITY, theMoment)
			), filter)
		);

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
		final AttributeBitmapFilter filter = new AttributeBitmapFilter(
			attributeName,
			AttributeContent.ALL_ATTRIBUTES,
			(entitySchema, theAttributeName, attributeTraits) -> this.attributeSchemaAccessor.getAttributeSchema(entitySchema, theAttributeName, TEST_SCOPES, attributeTraits),
			(entityContract, theAttributeName) -> Stream.of(entityContract.getAttributeValue(theAttributeName, null)),
			attributeSchema -> transformPredicate(theValue -> AttributeContainsTranslator.createPredicate().test(theValue, textToSearch))
		);

		final Bitmap result = filter.filter(
			createTestFilterByVisitor(filterBy(
				attributeContains(attributeName, textToSearch)
			), filter)
		);

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
		final AttributeBitmapFilter filter = new AttributeBitmapFilter(
			attributeName,
			AttributeContent.ALL_ATTRIBUTES,
			(entitySchema, theAttributeName, attributeTraits) -> this.attributeSchemaAccessor.getAttributeSchema(entitySchema, theAttributeName, TEST_SCOPES, attributeTraits),
			(entityContract, theAttributeName) -> Stream.of(entityContract.getAttributeValue(theAttributeName, null)),
			attributeSchema -> transformPredicate(theValue -> AttributeEndsWithTranslator.createPredicate().test(theValue, textToSearch))
		);

		final Bitmap result = filter.filter(
			createTestFilterByVisitor(filterBy(
				attributeEndsWith(attributeName, textToSearch)
			), filter)
		);

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
		final AttributeBitmapFilter filter = new AttributeBitmapFilter(
			attributeName,
			AttributeContent.ALL_ATTRIBUTES,
			(entitySchema, theAttributeName, attributeTraits) -> this.attributeSchemaAccessor.getAttributeSchema(entitySchema, theAttributeName, TEST_SCOPES, attributeTraits),
			(entityContract, theAttributeName) -> Stream.of(entityContract.getAttributeValue(theAttributeName, null)),
			attributeSchema -> transformPredicate(theValue -> AttributeStartsWithTranslator.createPredicate().test(theValue, textToSearch))
		);

		final Bitmap result = filter.filter(
			createTestFilterByVisitor(filterBy(
				attributeStartsWith(attributeName, textToSearch)
			), filter)
		);

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
		final AttributeBitmapFilter filter = new AttributeBitmapFilter(
			attributeName,
			AttributeContent.ALL_ATTRIBUTES,
			(entitySchema, theAttributeName, attributeTraits) -> this.attributeSchemaAccessor.getAttributeSchema(entitySchema, theAttributeName, TEST_SCOPES, attributeTraits),
			(entityContract, theAttributeName) -> Stream.of(entityContract.getAttributeValue(theAttributeName, null)),
			attributeSchema -> getPredicate(
				this.attributeSchemaAccessor.getAttributeSchema(attributeName, TEST_SCOPES),
				new AttributeKey(attributeName), theNumber, result -> result >= 0
			)
		);

		final Bitmap result = filter.filter(
			createTestFilterByVisitor(filterBy(
				attributeGreaterThanEquals(attributeName, theNumber)
			), filter)
		);

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
		final AttributeBitmapFilter filter = new AttributeBitmapFilter(
			attributeName,
			AttributeContent.ALL_ATTRIBUTES,
			(entitySchema, theAttributeName, attributeTraits) -> this.attributeSchemaAccessor.getAttributeSchema(entitySchema, theAttributeName, TEST_SCOPES, attributeTraits),
			(entityContract, theAttributeName) -> Stream.of(entityContract.getAttributeValue(theAttributeName, null)),
			attributeSchema -> getPredicate(
				this.attributeSchemaAccessor.getAttributeSchema(attributeName, TEST_SCOPES),
				new AttributeKey(attributeName), theNumber, result -> result > 0
			)
		);

		final Bitmap result = filter.filter(
			createTestFilterByVisitor(filterBy(
				attributeGreaterThan(attributeName, theNumber)
			), filter)
		);

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
		final AttributeBitmapFilter filter = new AttributeBitmapFilter(
			attributeName,
			AttributeContent.ALL_ATTRIBUTES,
			(entitySchema, theAttributeName, attributeTraits) -> this.attributeSchemaAccessor.getAttributeSchema(entitySchema, theAttributeName, TEST_SCOPES, attributeTraits),
			(entityContract, theAttributeName) -> Stream.of(entityContract.getAttributeValue(theAttributeName, null)),
			attributeSchema -> getPredicate(
				this.attributeSchemaAccessor.getAttributeSchema(attributeName, TEST_SCOPES),
				new AttributeKey(attributeName), theNumber, result -> result < 0
			)
		);

		final Bitmap result = filter.filter(
			createTestFilterByVisitor(filterBy(
				attributeLessThan(attributeName, theNumber)
			), filter)
		);

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
		final AttributeBitmapFilter filter = new AttributeBitmapFilter(
			attributeName,
			AttributeContent.ALL_ATTRIBUTES,
			(entitySchema, theAttributeName, attributeTraits) -> this.attributeSchemaAccessor.getAttributeSchema(entitySchema, theAttributeName, TEST_SCOPES, attributeTraits),
			(entityContract, theAttributeName) -> Stream.of(entityContract.getAttributeValue(theAttributeName, null)),
			attributeSchema -> getPredicate(
				this.attributeSchemaAccessor.getAttributeSchema(attributeName, TEST_SCOPES),
				new AttributeKey(attributeName), theNumber, result -> result <= 0
			)
		);

		final Bitmap result = filter.filter(
			createTestFilterByVisitor(filterBy(
				attributeLessThanEquals(attributeName, theNumber)
			), filter)
		);

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
