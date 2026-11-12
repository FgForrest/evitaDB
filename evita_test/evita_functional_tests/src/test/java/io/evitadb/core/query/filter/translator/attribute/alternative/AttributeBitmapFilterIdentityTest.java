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

package io.evitadb.core.query.filter.translator.attribute.alternative;

import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.AttributeInRange;
import io.evitadb.api.query.filter.AttributeIs;
import io.evitadb.api.query.filter.AttributeLessThan;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.data.structure.predicate.*;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.attribute.AttributeBetweenTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeInRangeTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeIsTranslatorTestFixture;
import io.evitadb.core.query.filter.translator.attribute.AttributeLessThanTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeValueMatchingTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.test.identity.*;
import io.evitadb.utils.NamingConvention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.core.query.filter.translator.attribute.alternative.AttributeBitmapFilterIdentityFixture.*;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies detached, type-faithful identities of attribute prefetch matchers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Attribute prefetch matcher identity")
@Tag(ENGINE)
@Tag(CACHE)
class AttributeBitmapFilterIdentityTest {
	/** Catalog without schema overrides for the real visitor fixture. */
	private static final SealedCatalogSchema EMPTY_CATALOG = catalogSchema(Map.of());

	/** Runs every construction mode and every field perturbation through the shared verifier. */
	@Test
	@DisplayName("Verifies controlled and opaque construction modes")
	void shouldVerifyIdentityContract() {
		IdentityContractVerifier.verify(AttributeBitmapFilter.class, new AttributeBitmapFilterIdentityFixture());
	}

	/** Plain matcher construction must retain only the executable comparison. */
	@Test
	@DisplayName("Builds the plain matcher without capturing alternative identity metadata")
	void shouldConstructPlainMatcherWithoutIdentityMetadata() {
		final AttributeLessThanTranslator translator = new AttributeLessThanTranslator();
		final AttributeSchemaContract schema = schema(BigDecimal.class, 2);
		final TestVisitor visitor = new TestVisitor(schema, OffsetDateTime.parse("2026-01-01T00:00:00Z"));
		final AttributeValueMatcher matcher = translator.createMainQueryMatcher(
			attributeLessThan("a", new BigDecimal("1.14")), visitor, schema
		);
		assertInstanceOf(AttributeValueMatcher.Comparing.class, matcher);
		assertTrue(matcher.matches(new BigDecimal("1.12")));
		assertThrows(io.evitadb.exception.EvitaInvalidUsageException.class, () ->
			translator.createMainQueryMatcher(attributeLessThan("a", "not a decimal"), visitor, schema));
	}

	/** Equal EvitaQL literals must not erase their runtime argument types. */
	@Test
	@DisplayName("Separates type-ambiguous EvitaQL literals and decimal range precision")
	void shouldPreserveTypesOfEvitaQlLiterals() {
		final AttributeEquals integer = attributeEquals("a", 1);
		final AttributeEquals longInteger = attributeEquals("a", 1L);
		assertEquals(integer.toString(), longInteger.toString());
		assertNotEquals(valueKey(integer), valueKey(longInteger));
		assertEquals(attributeEquals("a", 'x').toString(), attributeEquals("a", "x").toString());
		assertNotEquals(valueKey(attributeEquals("a", 'x')), valueKey(attributeEquals("a", "x")));
		assertNotEquals(valueKey(attributeEquals("a", new BigDecimal("1.0"))),
			valueKey(attributeEquals("a", new BigDecimal("1.00"))));
		final AttributeEquals coarse = attributeEquals("a", BigDecimalNumberRange.between(
			new BigDecimal("1.11"), new BigDecimal("2.22"), 1));
		final AttributeEquals precise = attributeEquals("a", BigDecimalNumberRange.between(
			new BigDecimal("1.11"), new BigDecimal("2.22"), 2));
		assertEquals(coarse.toString(), precise.toString());
		assertNotEquals(valueKey(coarse), valueKey(precise));
		assertNotEquals(valueKey(attributeEquals("a", OffsetDateTime.parse("2026-01-01T00:00:00Z"))),
			valueKey(attributeEquals("a", OffsetDateTime.parse("2026-01-01T01:00:00+01:00"))));
	}

	/** A live constraint or caller-owned argument array cannot rewrite a captured key. */
	@Test
	@DisplayName("Captures immutable arguments when the matcher is constructed")
	void shouldDetachConstraintArguments() {
		final AttributeEquals constraint = attributeEquals("a", 1);
		final AttributeBitmapFilter filter = create(constraint, attributeContent("a"), null, false, null);
		final FormulaKey captured = key(filter);
		constraint.getArguments()[1] = 2L;
		assertEquals(valueKey(attributeEquals("a", 1)), key(filter));
		assertEquals(captured, key(filter));
		assertNotEquals(captured, valueKey(constraint));
	}

	/** Arrays have no canonical scalar formatting and unknown mutable values have no value identity. */
	@Test
	@DisplayName("Keeps arrays, nested arrays, unknown values and raw functions opaque")
	void shouldKeepUnsupportedEncodingsOpaque() {
		for (final Serializable unsupported : new Serializable[]{
			new int[]{1, 2}, new Integer[]{1, 2}, new Integer[][]{{1}}, new StringBuilder("value"), java.time.DayOfWeek.MONDAY
		}) {
			final AttributeEquals constraint = attributeEquals("a", 1);
			constraint.getArguments()[1] = unsupported;
			final AttributeBitmapFilter first = create(constraint, attributeContent("a"), null, false, null);
			assertTrue(key(first).isOpaque());
			assertTrue(key(first).isQueryLocal());
			assertEquals(key(first), key(first));
			assertNotEquals(key(first), key(create(constraint, attributeContent("a"), null, false, null)));
		}
		final AttributeBitmapFilter raw = raw();
		assertTrue(key(raw).isOpaque());
		assertNotEquals(key(raw), key(raw()));
	}

	/** Retained literal characters contribute to the admission weight. */
	@Test
	@DisplayName("Charges long argument text to key weight")
	void shouldChargeSnapshotContent() {
		assertTrue(valueKey(attributeEquals("a", "x".repeat(8192))).getWeight() >
			valueKey(attributeEquals("a", "x")).getWeight());
	}

	/** Builds the controlled alternative key for one equality constraint. */
	@Nonnull
	private static FormulaKey valueKey(@Nonnull AttributeEquals constraint) {
		return key(create(constraint, attributeContent("a"), null, false, null));
	}

	/** Open bounds are explicit argument values, preserving their position without making the matcher opaque. */
	@Test
	@DisplayName("Captures null range bounds as exact typed arguments")
	void shouldCaptureOpenRangeBounds() {
		final FilterConstraint lowerOpen = attributeBetween("a", null, 10);
		final FilterConstraint upperOpen = attributeBetween("a", 10, null);
		final FormulaKey first = key(create(lowerOpen, attributeContent("a"), null, false, null));
		assertFalse(first.isOpaque());
		assertEquals(first, key(create(attributeBetween("a", null, 10), attributeContent("a"), null, false, null)));
		assertNotEquals(first, key(create(upperOpen, attributeContent("a"), null, false, null)));
	}

	/** A numeric argument still uses the query moment when the schema declares a date-time range. */
	@Test
	@DisplayName("Separates numeric date-time-range alternatives at different query moments")
	void shouldCaptureNumericDateTimeRangeFallback() {
		final AttributeInRangeTranslator translator = new AttributeInRangeTranslator();
		final AttributeSchemaContract schema = schema(DateTimeRange.class);
		final AttributeInRange constraint = attributeInRange("a", 1);
		final OffsetDateTime before = OffsetDateTime.parse("2026-01-01T00:00:00Z");
		final OffsetDateTime after = before.plusDays(2);
		final DateTimeRange validity = DateTimeRange.between(before.minusDays(1), before.plusDays(1));
		final TestVisitor firstVisitor = new TestVisitor(schema, before);
		final TestVisitor secondVisitor = new TestVisitor(schema, after);
		final AttributeValueMatcher firstMatcher = translator.createMainQueryMatcher(constraint, firstVisitor, schema);
		final AttributeValueMatcher secondMatcher = translator.createMainQueryMatcher(constraint, secondVisitor, schema);
		assertTrue(firstMatcher.matches(validity));
		assertFalse(secondMatcher.matches(validity));
		assertNotEquals(
			key(translator.createAlternativeBitmapFilter(constraint, firstVisitor, schema, firstMatcher)),
			key(translator.createAlternativeBitmapFilter(constraint, secondVisitor, schema, secondMatcher))
		);
		assertNotEquals(
			key(translator.createEntityFilteringBitmapFilter(constraint, firstVisitor)),
			key(translator.createEntityFilteringBitmapFilter(constraint, secondVisitor))
		);
	}

	/** Deferred schema resolution must retain the moment until a date-time-range schema is encountered. */
	@Test
	@DisplayName("Captures the numeric date-time fallback before deferred schema resolution")
	void shouldCaptureDeferredNumericDateTimeRangeFallback() {
		final AttributeInRangeTranslator translator = new AttributeInRangeTranslator();
		final AttributeInRange constraint = attributeInRange("a", 1);
		final OffsetDateTime before = OffsetDateTime.parse("2026-01-01T00:00:00Z");
		final OffsetDateTime after = before.plusDays(2);
		final AttributeSchemaContract schema = schema(DateTimeRange.class);
		final AttributeBitmapFilter first = translator.createEntityFilteringBitmapFilter(
			constraint, new TestVisitor(schema, before, null, Set.of(Scope.LIVE)));
		final AttributeBitmapFilter second = translator.createEntityFilteringBitmapFilter(
			constraint, new TestVisitor(schema, after, null, Set.of(Scope.LIVE)));
		assertAll(
			() -> assertEquals(before, ((AttributeBitmapFilter.Identity) first.identity()).moment()),
			() -> assertEquals(after, ((AttributeBitmapFilter.Identity) second.identity()).moment()),
			() -> assertNotEquals(key(first), key(second))
		);
	}

	/** Decimal conversion is part of matcher construction even for identical constraint arguments. */
	@Test
	@DisplayName("Separates alternatives whose schemas normalize decimals differently")
	void shouldCaptureSchemaDecimalPrecision() {
		final AttributeLessThanTranslator translator = new AttributeLessThanTranslator();
		final AttributeLessThan constraint = attributeLessThan("a", new BigDecimal("1.14"));
		final AttributeSchemaContract coarse = schema(BigDecimal.class, 1);
		final AttributeSchemaContract precise = schema(BigDecimal.class, 2);
		final OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
		final TestVisitor firstVisitor = new TestVisitor(coarse, now);
		// The resolved matcher schema can be a global definition rather than the scope's local schema.
		final TestVisitor secondVisitor = firstVisitor;
		final AttributeValueMatcher first = translator.createMainQueryMatcher(constraint, firstVisitor, coarse);
		final AttributeValueMatcher second = translator.createMainQueryMatcher(constraint, secondVisitor, precise);
		assertFalse(first.matches(new BigDecimal("1.12")));
		assertTrue(second.matches(new BigDecimal("1.12")));
		assertNotEquals(
			key(translator.createAlternativeBitmapFilter(constraint, firstVisitor, coarse, first)),
			key(translator.createAlternativeBitmapFilter(constraint, secondVisitor, precise, second))
		);
	}

	/** Number-range probes use indexed precision even when the stored bounds are integers. */
	@ParameterizedTest(name = "deferred matcher: {0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("Separates integer range containment results and keys by indexed precision")
	void shouldCaptureIntegerRangeProbePrecision(boolean deferred) {
		assertIntegerRangePrecision(new AttributeInRangeTranslator(), attributeInRange("a", 1), deferred);
	}

	/** Number-range overlap bounds use the same precision as the range index probes. */
	@ParameterizedTest(name = "deferred matcher: {0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("Separates integer range overlap results and keys by indexed precision")
	void shouldCaptureIntegerRangeBoundPrecision(boolean deferred) {
		assertIntegerRangePrecision(new AttributeBetweenTranslator(), attributeBetween("a", 1, 2), deferred);
	}

	/** Executes alternatives with precision-only schema differences against one unchanged integer-range entity. */
	private static <T extends FilterConstraint & AttributeConstraint<FilterConstraint>> void assertIntegerRangePrecision(
		@Nonnull AttributeValueMatchingTranslator<T> translator, @Nonnull T constraint, boolean deferred
	) {
		final OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
		final EntitySchema entitySchema = EntitySchema._internalBuildWithAttributes(
			EntitySchema._internalBuild("product"), 1, Map.of("a", EntityAttributeSchema._internalBuild(
				"a", IntegerNumberRange.class, false, ConflictResolutionOverride.INHERITED)));
		final InitialEntityBuilder builder = new InitialEntityBuilder(entitySchema, 7);
		builder.setAttribute("a", IntegerNumberRange.between(1, 2));
		final Entity entity = builder.toInstance();
		final AttributeBitmapFilter[] alternatives = new AttributeBitmapFilter[2];
		for (int precision = 0; precision < alternatives.length; precision++) {
			final GlobalAttributeSchemaContract schema = GlobalAttributeSchema._internalBuild(
				"a", NamingConvention.generate("a"), null, null, null, null, Set.of(), null, Set.of(), false, false, false,
				IntegerNumberRange.class, null, precision, ConflictResolutionOverride.INHERITED);
			final TestVisitor visitor = new TestVisitor(
				schema, now, deferred ? null : "product", Set.of(Scope.LIVE), catalogSchema(Map.of("a", schema)));
			alternatives[precision] = deferred ?
				translator.createEntityFilteringBitmapFilter(constraint, visitor) :
				translator.createAlternativeBitmapFilter(
					constraint, visitor, schema, translator.createMainQueryMatcher(constraint, visitor, schema));
		}
		assertArrayEquals(new int[]{7}, execute(alternatives[0], entity, now));
		assertArrayEquals(new int[0], execute(alternatives[1], entity, now));
		assertNotEquals(key(alternatives[0]), key(alternatives[1]));
	}

	/** Sortability is unrelated to comparison construction and its default validation. */
	@Test
	@DisplayName("Ignores sortability that a comparison alternative never reads")
	void shouldIgnoreUnrelatedSortability() {
		final AttributeSchemaContract sortable = AttributeSchema._internalBuild(
			"a", null, null, (ScopedAttributeUniquenessType[]) null, null, null,
			new Scope[]{Scope.LIVE}, false, false, false, Integer.class, null, 0,
			ConflictResolutionOverride.INHERITED
		);
		final OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
		final GlobalAttributeSchemaContract sortableOverride = GlobalAttributeSchema._internalBuild(
			"a", null, null, null, null, new Scope[]{Scope.LIVE}, false, false, false,
			Integer.class, null, ConflictResolutionOverride.INHERITED
		);
		final TestVisitor plainCatalog = new TestVisitor(schema(Integer.class), now, null, Set.of(Scope.LIVE),
			catalogSchema(Map.of("a", globalSchema(Integer.class))));
		final TestVisitor sortableCatalog = new TestVisitor(schema(Integer.class), now, null, Set.of(Scope.LIVE),
			catalogSchema(Map.of("a", sortableOverride)));
		final AttributeLessThanTranslator translator = new AttributeLessThanTranslator();
		final AttributeLessThan constraint = attributeLessThan("a", 2);
		final FormulaKey original = comparisonKey(schema(Integer.class));
		assertAll(
			() -> assertEquals(original, comparisonKey(sortable)),
			() -> assertEquals(original, comparisonKey(schema(Integer.class, 2))),
			() -> assertEquals(original, comparisonKey(schema(Integer[].class))),
			() -> assertEquals(
				key(translator.createEntityFilteringBitmapFilter(constraint, plainCatalog)),
				key(translator.createEntityFilteringBitmapFilter(constraint, sortableCatalog))),
			() -> assertNotEquals(
				key(translator.createEntityFilteringBitmapFilter(constraint, plainCatalog, AttributeTrait.SORTABLE)),
				key(translator.createEntityFilteringBitmapFilter(constraint, sortableCatalog, AttributeTrait.SORTABLE))),
			() -> assertEquals(original, comparisonKey(AttributeSchema._internalBuild(
				"a", null, null, (ScopedAttributeUniquenessType[]) null, new Scope[]{Scope.LIVE}, null, null,
				false, false, false, Integer.class, null, 0, ConflictResolutionOverride.INHERITED)))
		);
	}

	/** Builds a default comparison alternative against the supplied resolved schema. */
	@Nonnull
	private static FormulaKey comparisonKey(@Nonnull AttributeSchemaContract schema) {
		final AttributeLessThanTranslator translator = new AttributeLessThanTranslator();
		final AttributeLessThan constraint = attributeLessThan("a", 2);
		final TestVisitor visitor = new TestVisitor(schema, OffsetDateTime.parse("2026-01-01T00:00:00Z"));
		return key(translator.createAlternativeBitmapFilter(
			constraint, visitor, schema, translator.createMainQueryMatcher(constraint, visitor, schema)));
	}

	/** Fixed range matching reads neither the collection name nor the translation scopes. */
	@Test
	@DisplayName("Ignores collection names and scopes unused by a fixed range matcher")
	void shouldIgnoreUnusedExecutionContext() {
		final AttributeInRangeTranslator translator = new AttributeInRangeTranslator();
		final AttributeInRange constraint = attributeInRange("a", 1);
		final AttributeSchemaContract schema = schema(io.evitadb.dataType.IntegerNumberRange.class);
		final OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
		final TestVisitor baseline = new TestVisitor(schema, now);
		final AttributeValueMatcher matcher = translator.createMainQueryMatcher(constraint, baseline, schema);
		final FormulaKey original = key(translator.createAlternativeBitmapFilter(constraint, baseline, schema, matcher));
		assertAll(
			() -> assertEquals(original, key(translator.createAlternativeBitmapFilter(
				constraint, new TestVisitor(schema, now, "product", Set.of(Scope.ARCHIVED)), schema, matcher
			))),
			() -> assertEquals(original, key(translator.createAlternativeBitmapFilter(
				constraint, new TestVisitor(schema, now, "category", Set.of(Scope.LIVE)), schema, matcher
			)))
		);
	}

	/** A fixed matcher with no required traits does not validate against restored request scopes. */
	@Test
	@DisplayName("Ignores restored request scopes when no traits require validation")
	void shouldIgnoreRequestScopesWithoutRequiredTraits() {
		final AttributeInRangeTranslator translator = new AttributeInRangeTranslator();
		final AttributeInRange constraint = attributeInRange("a", 1);
		final AttributeSchemaContract schema = schema(io.evitadb.dataType.IntegerNumberRange.class);
		final OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
		final TestVisitor live = new TestVisitor(schema, now);
		final TestVisitor both = new TestVisitor(schema, now, "product", Set.of(Scope.LIVE, Scope.ARCHIVED));
		final FormulaKey first = live.getProcessingScope().doWithScope(Set.of(Scope.LIVE), () -> key(
			translator.createAlternativeBitmapFilter(
				constraint, live, schema, translator.createMainQueryMatcher(constraint, live, schema))));
		final FormulaKey second = both.getProcessingScope().doWithScope(Set.of(Scope.LIVE), () -> key(
			translator.createAlternativeBitmapFilter(
				constraint, both, schema, translator.createMainQueryMatcher(constraint, both, schema))));
		assertEquals(first, second);
	}

	/** Both presence operators construct their alternatives independently of the value-matcher interface. */
	@Test
	@DisplayName("Captures execution context for attribute presence and absence alternatives")
	void shouldCapturePresenceAndAbsenceContext() {
		final AttributeSchemaContract schema = schema(Integer.class);
		final OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
		final TestVisitor live = new TestVisitor(schema, now, null, Set.of(Scope.LIVE));
		final TestVisitor archived = new TestVisitor(schema, now, null, Set.of(Scope.ARCHIVED));
		assertAll(
			() -> assertNotEquals(presenceKey(attributeIsNull("a"), live),
				presenceKey(attributeIsNull("a"), archived)),
			() -> assertNotEquals(presenceKey(attributeIsNotNull("a"), live),
				presenceKey(attributeIsNotNull("a"), archived))
		);
	}

	/** Calls the same presence factories as translation through a fixture in the translator package. */
	@Nonnull
	private static FormulaKey presenceKey(@Nonnull AttributeIs constraint, @Nonnull TestVisitor visitor) {
		return key(AttributeIsTranslatorTestFixture.createAlternative(constraint, visitor));
	}

	/** Plain type and localization are construction inputs; the entity value supplies its array shape. */
	@Test
	@DisplayName("Captures plain type and localization without redundant array schema traits")
	void shouldCaptureSchemaTraits() {
		final FormulaKey original = betweenKey(schema(Integer.class));
		assertAll(
			() -> assertNotEquals(original, betweenKey(schema(Long.class))),
			() -> assertEquals(original, betweenKey(schema(Integer[].class))),
			() -> assertNotEquals(original, betweenKey(schema(Integer.class, 0, true)))
		);
	}

	/** Constructs a real between matcher and its alternative against exactly the same schema. */
	@Nonnull
	private static FormulaKey betweenKey(@Nonnull AttributeSchemaContract schema) {
		final io.evitadb.core.query.filter.translator.attribute.AttributeBetweenTranslator translator =
			new io.evitadb.core.query.filter.translator.attribute.AttributeBetweenTranslator();
		final io.evitadb.api.query.filter.AttributeBetween constraint = attributeBetween("a", 1, 2);
		final TestVisitor visitor = new TestVisitor(schema, OffsetDateTime.parse("2026-01-01T00:00:00Z"));
		return key(translator.createAlternativeBitmapFilter(
			constraint, visitor, schema, translator.createMainQueryMatcher(constraint, visitor, schema)
		));
	}

	/** Deferred catalog overrides resolve the schema before entities supply their local definitions. */
	@Test
	@DisplayName("Captures deferred schema overrides and omits an unused numeric query moment")
	void shouldCaptureDeferredSchemaOverrides() {
		final AttributeInRangeTranslator translator = new AttributeInRangeTranslator();
		final AttributeInRange constraint = attributeInRange("a", 1);
		final OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
		final SealedCatalogSchema integerCatalog = catalogSchema(Map.of("a", globalSchema(
			io.evitadb.dataType.IntegerNumberRange.class)));
		final SealedCatalogSchema temporalCatalog = catalogSchema(Map.of("a", globalSchema(DateTimeRange.class)));
		final AttributeSchemaContract localSchema = schema(DateTimeRange.class);
		final TestVisitor firstVisitor = new TestVisitor(
			localSchema, now, "product", Set.of(Scope.LIVE), integerCatalog);
		final TestVisitor secondVisitor = new TestVisitor(
			localSchema, now.plusDays(2), "product", Set.of(Scope.LIVE), integerCatalog);
		final TestVisitor differentSchema = new TestVisitor(
			localSchema, now, "product", Set.of(Scope.LIVE), temporalCatalog);
		final TestVisitor differentMoment = new TestVisitor(
			localSchema, now.plusDays(2), "product", Set.of(Scope.LIVE), temporalCatalog);
		final AttributeBitmapFilter first = translator.createEntityFilteringBitmapFilter(constraint, firstVisitor);
		assertNull(((AttributeBitmapFilter.Identity) first.identity()).moment());
		final AttributeBitmapFilter second = translator.createEntityFilteringBitmapFilter(constraint, secondVisitor);
		final AttributeBitmapFilter temporal = translator.createEntityFilteringBitmapFilter(constraint, differentSchema);
		final AttributeBitmapFilter expired = translator.createEntityFilteringBitmapFilter(constraint, differentMoment);
		assertEquals(key(first), key(second));
		assertNotEquals(key(first), key(temporal));
		assertNotEquals(key(temporal), key(expired));
		final EntitySchema entitySchema = EntitySchema._internalBuildWithAttributes(
			EntitySchema._internalBuild("product"), 1, Map.of("a", EntityAttributeSchema._internalBuild(
				"a", DateTimeRange.class, false, ConflictResolutionOverride.INHERITED)));
		final InitialEntityBuilder builder = new InitialEntityBuilder(entitySchema, 1);
		builder.setAttribute("a", DateTimeRange.between(now.minusDays(1), now.plusDays(1)));
		final Entity entity = builder.toInstance();
		assertNull(((AttributeBitmapFilter.Identity) second.identity()).moment());
		assertEquals(now, ((AttributeBitmapFilter.Identity) temporal.identity()).moment());
		assertEquals(now.plusDays(2), ((AttributeBitmapFilter.Identity) expired.identity()).moment());
		assertArrayEquals(new int[0], execute(first, entity, now));
		assertArrayEquals(new int[0], execute(second, entity, now));
		assertArrayEquals(new int[]{1}, execute(temporal, entity, now));
		assertArrayEquals(new int[0], execute(expired, entity, now));
	}

	/** Executes deferred schema resolution and value matching over the same prefetched entity body. */
	@Nonnull
	private static int[] execute(
		@Nonnull AttributeBitmapFilter filter, @Nonnull Entity entity, @Nonnull OffsetDateTime now
	) {
		final ServerEntityDecorator decorated = ServerEntityDecorator.decorate(
			entity, entity.getSchema(), null,
			LocaleSerializablePredicate.DEFAULT_INSTANCE, HierarchySerializablePredicate.DEFAULT_INSTANCE,
			AttributeValueSerializablePredicate.DEFAULT_INSTANCE, AssociatedDataValueSerializablePredicate.DEFAULT_INSTANCE,
			ReferenceContractSerializablePredicate.DEFAULT_INSTANCE, PriceContractSerializablePredicate.DEFAULT_INSTANCE,
			now, null, ServerEntityDecorator.UNKNOWN_CATALOG_VERSION, 0, 0
		);
		// This request-only context never accesses a catalog, a session or an index.
		final QueryPlanningContext planningContext = new QueryPlanningContext(
			null, null, null,
			new EvitaRequest(query(collection("product")), now, EntityReference.class, null),
			Map.of(), Map.of(), NoCacheSupervisor.INSTANCE
		);
		try (final QueryExecutionContext context = new QueryExecutionContext(
			planningContext, true, null, (type, value) -> value
		) {
			/** Supplies the real prefetched entity without accessing a catalog. */
			@Nonnull
			@Override
			public List<ServerEntityDecorator> getPrefetchedEntities() {
				return List.of(decorated);
			}

			/** Uses primary keys as the bitmap identifiers of this single-collection fixture. */
			@Override
			public int translateEntity(@Nonnull EntityContract value) {
				return value.getPrimaryKeyOrThrowException();
			}
		}) {
			return filter.filter(context).getArray();
		}
	}

	/** The resolved schema decides whether the temporal fallback can be used. */
	@Test
	@DisplayName("Identifies implicit query moments using resolved and deferred schema types")
	void shouldIdentifyImplicitQueryMomentFromSchema() {
		final AttributeInRangeTranslator translator = new AttributeInRangeTranslator();
		final AttributeSchemaContract temporal = schema(DateTimeRange.class);
		final AttributeSchemaContract numeric = schema(io.evitadb.dataType.IntegerNumberRange.class);
		assertTrue(translator.readsQueryMoment(attributeInRangeNow("a"), temporal));
		assertTrue(translator.readsQueryMoment(attributeInRange("a", 1), temporal));
		assertTrue(translator.readsQueryMoment(attributeInRange("a", 1), null));
		assertFalse(translator.readsQueryMoment(attributeInRange("a", 1), numeric));
		assertFalse(translator.readsQueryMoment(
			attributeInRange("a", OffsetDateTime.parse("2026-01-01T00:00:00Z")), temporal));
		assertFalse(new io.evitadb.core.query.filter.translator.attribute.AttributeEqualsTranslator()
			.readsQueryMoment(attributeEquals("a", 1), temporal));
	}

	/** Locale selects both the attribute value and the comparison collation. */
	@Test
	@DisplayName("Separates locale-dependent comparison results and keys")
	void shouldCaptureLocaleAndCollation() {
		final AttributeLessThanTranslator translator = new AttributeLessThanTranslator();
		final AttributeLessThan constraint = attributeLessThan("a", "z");
		final AttributeSchemaContract schema = schema(String.class, 0, true);
		final OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
		final TestVisitor english = new TestVisitor(schema, now) {
			/** Selects English collation. */
			@Nonnull
			@Override
			public Locale getLocale() {
				return Locale.ENGLISH;
			}
		};
		final TestVisitor swedish = new TestVisitor(schema, now) {
			/** Selects Swedish collation. */
			@Nonnull
			@Override
			public Locale getLocale() {
				return Locale.forLanguageTag("sv");
			}
		};
		final AttributeValueMatcher first = translator.createMainQueryMatcher(constraint, english, schema);
		final AttributeValueMatcher second = translator.createMainQueryMatcher(constraint, swedish, schema);
		assertTrue(first.matches("ä"));
		assertFalse(second.matches("ä"));
		assertNotEquals(key(translator.createAlternativeBitmapFilter(constraint, english, schema, first)),
			key(translator.createAlternativeBitmapFilter(constraint, swedish, schema, second)));
	}

	/** The fixed alternative explicitly declares the schema that constructed its matcher. */
	@Test
	@DisplayName("Captures the explicitly supplied fixed matcher schema")
	void shouldCaptureExplicitFixedMatcherSchema() {
		final AttributeInRangeTranslator translator = new AttributeInRangeTranslator();
		final AttributeInRange constraint = attributeInRange("a", 1);
		final AttributeSchemaContract schema = schema(io.evitadb.dataType.IntegerNumberRange.class);
		final TestVisitor visitor = new TestVisitor(schema, OffsetDateTime.parse("2026-01-01T00:00:00Z"));
		final AttributeValueMatcher matcher = translator.createMatcher(
			constraint, schema, new io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey(
				null, "a", null), visitor::getNow);
		final AttributeBitmapFilter filter = translator.createAlternativeBitmapFilter(constraint, visitor, schema, matcher);
		assertFalse(key(filter).isOpaque());
		assertEquals(key(filter), key(translator.createAlternativeBitmapFilter(
			constraint, visitor, schema, translator.createMainQueryMatcher(constraint, visitor, schema))));
	}

	/** Creates a global range schema without requiring an index. */
	@Nonnull
	private static GlobalAttributeSchemaContract globalSchema(@Nonnull Class<? extends Serializable> type) {
		return GlobalAttributeSchema._internalBuild(
			"a", null, null, null, null, null, false, false, false, type, null,
			ConflictResolutionOverride.INHERITED
		);
	}

	/** Creates a real catalog schema containing the supplied global overrides. */
	@Nonnull
	private static SealedCatalogSchema catalogSchema(@Nonnull Map<String, GlobalAttributeSchemaContract> attributes) {
		return new CatalogSchemaDecorator(CatalogSchema._internalBuild(
			1, "catalog", NamingConvention.generate("catalog"), null, null, Set.of(), attributes,
			new EntitySchemaProvider() {
				/** No collection schemas are needed by this catalog fixture. */
				@Nonnull
				@Override
				public Collection<EntitySchemaContract> getEntitySchemas() {
					return List.of();
				}

				/** Entity-local schema resolution is supplied by the processing scope. */
				@Nonnull
				@Override
				public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
					return Optional.empty();
				}
			}
		));
	}

	/** Builds a real attribute schema with the given type. */
	@Nonnull
	private static AttributeSchemaContract schema(@Nonnull Class<? extends Serializable> type) {
		return schema(type, 0);
	}

	/** Builds a schema whose decimal normalization uses the supplied precision. */
	@Nonnull
	private static AttributeSchemaContract schema(@Nonnull Class<? extends Serializable> type, int precision) {
		return schema(type, precision, false);
	}

	/** Builds a schema with explicit localization. */
	@Nonnull
	private static AttributeSchemaContract schema(
		@Nonnull Class<? extends Serializable> type, int precision, boolean localized
	) {
		return AttributeSchema._internalBuild(
			"a", null, null, (ScopedAttributeUniquenessType[]) null, null, null, null,
			localized, false, false, type, null, precision, ConflictResolutionOverride.INHERITED
		);
	}

	/**
	 * A visitor with explicit query inputs and no indexes, sufficient for constructing real attribute alternatives.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static class TestVisitor extends FilterByVisitor {
		/** Query moment supplied to matcher construction. */
		private final OffsetDateTime now;
		/** Request scopes remain fixed when a temporary processing scope is active. */
		private final Set<Scope> requestedScopes;
		/** Catalog shared by identity capture and the processing scope's schema accessor. */
		private final SealedCatalogSchema catalogSchema;

		/** Creates the processing scope over the supplied attribute schema. */
		private TestVisitor(@Nonnull AttributeSchemaContract schema, @Nonnull OffsetDateTime now) {
			this(schema, now, "product", Set.of(Scope.LIVE));
		}

		/** Creates a visitor whose execution scope and entity type are explicit. */
		private TestVisitor(
			@Nonnull AttributeSchemaContract schema, @Nonnull OffsetDateTime now,
			@Nullable String entityType, @Nonnull Set<Scope> scopes
		) {
			this(schema, now, entityType, scopes, EMPTY_CATALOG);
		}

		/** Gives identity capture and actual deferred lookup the same catalog overrides. */
		private TestVisitor(
			@Nonnull AttributeSchemaContract schema, @Nonnull OffsetDateTime now,
			@Nullable String entityType, @Nonnull Set<Scope> scopes, @Nonnull SealedCatalogSchema catalogSchema
		) {
			super(
				new ProcessingScope<>(
					GlobalEntityIndex.class, List.of(), scopes, attributeContentAll(),
					entityType == null ? null : EntitySchema._internalBuild(entityType), null,
					new AttributeSchemaAccessor(catalogSchema, null) {
						/** Resolves the schema without accessing a catalog or an index. */
						@Nonnull
						@Override
						public AttributeSchemaContract getAttributeSchema(
							@Nonnull String name, @Nonnull Set<Scope> scopes, @Nonnull AttributeTrait... traits
						) {
							return schema;
						}
					},
					(entity, name, locale) -> Stream.of(entity.getAttributeValue(name, locale))
				), null, List.of(), TargetIndexes.EMPTY
			);
			this.now = now;
			this.requestedScopes = Set.copyOf(scopes);
			this.catalogSchema = catalogSchema;
		}

		/** Returns the explicit query moment. */
		@Nonnull
		@Override
		public OffsetDateTime getNow() {
			return this.now;
		}

		/** Returns the request scopes for the explicit processing scope. */
		@Nonnull
		@Override
		public Set<Scope> getScopes() {
			return this.requestedScopes;
		}

		/** Collection-less visitors use the entity-filtering alternative. */
		@Override
		public boolean isEntityTypeKnown() {
			return getProcessingScope().getEntitySchema() != null;
		}

		/** Supplies the same catalog that the processing scope uses for deferred schema lookup. */
		@Nonnull
		@Override
		public SealedCatalogSchema getCatalogSchema() {
			return this.catalogSchema;
		}

		/** Uses the non-localized attribute accessor. */
		@Nullable
		@Override
		public Locale getLocale() {
			return null;
		}
	}
}
