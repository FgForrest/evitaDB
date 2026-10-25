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

package io.evitadb.index;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.Scope;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.analysis.AttributeNormalizationResolver;
import io.evitadb.index.attribute.EntityAttributeIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.api.index.EntityIndexType.REFERENCED_ENTITY_TYPE;
import static io.evitadb.api.index.EntityIndexType.REFERENCED_GROUP_ENTITY;
import static io.evitadb.api.requestResponse.schema.Cardinality.ZERO_OR_MORE;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises raw write/removal routing independently of the query translators scheduled for P4a-2.
 */
@Tag(INDEXING)
@DisplayName("Declared String normalization in index writes")
class DeclaredStringNormalizationIndexTest {
	private static final Locale TURKISH = Locale.forLanguageTag("tr");
	private static final EntitySchemaContract SCHEMA = new InternalEntitySchemaBuilder(
		CatalogSchema._internalBuild(
			APITestConstants.TEST_CATALOG, NamingConvention.generate(APITestConstants.TEST_CATALOG), null,
			EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
		), EntitySchema._internalBuild("Product")
	)
		.withLocale(TURKISH)
		.withReferenceToEntity("brand", "Brand", ZERO_OR_MORE,
			r -> r.indexedForFilteringAndPartitioning()
				.withAttribute("tags", String[].class, a -> a.filterable().normalized(lowercase(), trim())))
		.withAttribute("code", String.class, a -> a.filterable().sortable().normalized(
			lowercase(), asciiFolding(), trim(), removeCharacters("-")
		))
		.withAttribute("tags", String[].class, a -> a.filterable().normalized(lowercase(), trim()))
		.withAttribute("title", String.class, a -> a.localized().unique().normalized(lowercase()))
		.toInstance();

	@Test
	@DisplayName("Array elements collapse into keys and remove by raw spelling")
	void shouldNormalizeEveryArrayElementAndRemoveRawSpellings() {
		final EntityAttributeIndex index = new EntityAttributeIndex("Product",
			declaredResolver());
		final AttributeSchemaContract schema = SCHEMA.getAttribute("tags").orElseThrow();
		index.insertFilterAttribute(null, schema, Set.of(TURKISH), null,
			new String[]{" ONE ", "one", " TWO "}, 1, false);
		final FilterIndex filter = index.getFilterIndex(new AttributeIndexKey(null, "tags", null));
		assertArrayEquals(new int[]{1}, filter.getRecordsEqualToFormula("one").compute().getArray());
		assertArrayEquals(new int[]{1}, filter.getRecordsEqualToFormula("two").compute().getArray());
		index.removeFilterAttribute(null, schema, Set.of(TURKISH), null,
			new String[]{" ONE ", "one", " TWO "}, 1);
		assertTrue(index.getFilterIndexes().isEmpty());
	}

	@Test
	@DisplayName("Localized filter and cross-locale uniqueness bind distinct fold locales")
	void shouldBindDifferentLocalesToFilterAndCrossLocaleUniqueTrees() {
		final EntityAttributeIndex index = new EntityAttributeIndex("Product",
			declaredResolver());
		final AttributeSchemaContract schema = SCHEMA.getAttribute("title").orElseThrow();
		index.insertUniqueAttribute(null, schema, Set.of(TURKISH), Scope.LIVE, TURKISH, "TITLE", 1);
		index.insertFilterAttribute(null, schema, Set.of(TURKISH), TURKISH, "TITLE", 1, false);
		assertArrayEquals(new int[]{1}, index.getFilterIndex(new AttributeIndexKey(null, "title", TURKISH))
			.getRecordsEqualToFormula("tıtle").compute().getArray());
		assertEquals(1, index.getUniqueIndex(null, schema, Scope.LIVE, null)
			.getRecordIdByUniqueValue("title"));
		index.removeUniqueAttribute(null, schema, Set.of(TURKISH), Scope.LIVE, TURKISH, "TITLE", 1);
		index.removeFilterAttribute(null, schema, Set.of(TURKISH), TURKISH, "TITLE", 1);
		assertTrue(index.getUniqueIndexes().isEmpty());
	}

	@Test
	@DisplayName("Filter and owner sort store the same compiled key")
	void shouldShareDeclaredKeysBetweenFilterAndOwnerSort() {
		final EntityAttributeIndex index = new EntityAttributeIndex("Product",
			declaredResolver());
		final AttributeSchemaContract schema = SCHEMA.getAttribute("code").orElseThrow();
		final String raw = " Á-B ";
		final String expected = new AnalysisStepRegistry().compile(schema.getNormalization(), null).apply(raw);
		index.insertSortAttribute(null, schema, Set.of(TURKISH), null, raw, 1);
		index.insertFilterAttribute(null, schema, Set.of(TURKISH), null, raw, 1, false);
		assertEquals(expected, index.getSortIndex(null, schema, null).getValueOfRecord(1).orElseThrow());
		assertArrayEquals(new int[]{1}, index.getFilterIndex(new AttributeIndexKey(null, "code", null))
			.getRecordsEqualToFormula(expected).compute().getArray());
		index.removeSortAttribute(null, schema, Set.of(TURKISH), null, raw, 1);
		index.removeFilterAttribute(null, schema, Set.of(TURKISH), null, raw, 1);
		assertTrue(index.getSortIndexes().isEmpty());
	}
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Reference and group counters preserve colliding element multiplicity")
	void shouldCountCollidingRawElementsWithOneBoundReferenceFunction(boolean group) {
		final AttributeNormalizationResolver resolver =
			declaredResolver();
		final ReferenceSchemaContract reference =
			SCHEMA.getReference("brand").orElseThrow();
		final AttributeSchemaContract attribute =
			reference.getAttribute("tags").orElseThrow();
		final EntityIndex index = group
			? new ReducedGroupEntityIndex(1, "Product", new EntityIndexKey(
				REFERENCED_GROUP_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(
					new ReferenceKey("brand", 1))), false, resolver)
			: new ReferencedTypeEntityIndex(1, "Product", new EntityIndexKey(
				REFERENCED_ENTITY_TYPE, Scope.LIVE, "brand"), false, resolver);
		index.insertFilterAttribute(reference, attribute, Set.of(TURKISH), null,
			new String[]{" ONE ", "one"}, 10, false);
		index.insertFilterAttribute(reference, attribute, Set.of(TURKISH), null,
			new String[]{"One"}, 10, false);
		index.removeFilterAttribute(reference, attribute, Set.of(TURKISH), null,
			new String[]{" ONE ", "one"}, 10);
		assertArrayEquals(new int[]{10}, index.getFilterIndex(new AttributeIndexKey("brand", "tags", null))
			.getInvertedIndex().getRecordsEqualTo("one").getArray());
		index.removeFilterAttribute(reference, attribute, Set.of(TURKISH), null, new String[]{"One"}, 10);
		assertNull(index.getFilterIndex(new AttributeIndexKey("brand", "tags", null)));
	}

	@Test
	@DisplayName("Tree creation binds once and captures the cached compiler function")
	void shouldResolveOnlyAtTreeCreationAndKeepTheCompiledFunction() {
		final AtomicInteger resolutions = new AtomicInteger();
		final AnalysisStepRegistry registry = new AnalysisStepRegistry();
		final EntityAttributeIndex index = new EntityAttributeIndex("Product", (reference, schema, locale) -> {
			resolutions.incrementAndGet();
			return registry.compile(schema.getNormalization(), locale);
		});
		final AttributeSchemaContract schema =
			SCHEMA.getAttribute("code").orElseThrow();
		index.insertFilterAttribute(null, schema, Set.of(TURKISH), null, " A-B ", 1, false);
		index.insertFilterAttribute(null, schema, Set.of(TURKISH), null, "ab", 2, false);
		assertSame(registry.compile(schema.getNormalization(), null),
			index.getFilterIndex(new AttributeIndexKey(null, "code", null)).getInvertedIndex().getNormalizer());
		index.removeFilterAttribute(null, schema, Set.of(TURKISH), null, " A-B ", 1);
		index.removeFilterAttribute(null, schema, Set.of(TURKISH), null, "ab", 2);
		assertEquals(1, resolutions.get());
	}

	@Test
	@DisplayName("Cross-locale uniqueness rejects raw spellings that compile to the same key")
	void shouldRejectAnotherOwnerOfTheDeclaredUniqueKey() {
		final EntityAttributeIndex index = new EntityAttributeIndex("Product",
			declaredResolver());
		final AttributeSchemaContract schema = SCHEMA.getAttribute("title").orElseThrow();
		index.insertUniqueAttribute(null, schema, Set.of(TURKISH), Scope.LIVE, TURKISH, "TITLE", 1);
		assertThrows(UniqueValueViolationException.class,
			() -> index.insertUniqueAttribute(null, schema, Set.of(TURKISH), Scope.LIVE, TURKISH, "title", 2));
	}

	@Test
	@DisplayName("An empty normalized String remains an indexed present value")
	void shouldRetainEmptyNormalizedKeysUntilTheLastRecordIsRemoved() {
		final EntityAttributeIndex index = new EntityAttributeIndex("Product",
			declaredResolver());
		final AttributeSchemaContract schema = SCHEMA.getAttribute("code").orElseThrow();
		index.insertFilterAttribute(null, schema, Set.of(TURKISH), null, " - ", 1, false);
		index.insertFilterAttribute(null, schema, Set.of(TURKISH), null, "-", 2, false);
		index.removeFilterAttribute(null, schema, Set.of(TURKISH), null, " - ", 1);
		assertArrayEquals(new int[]{2}, index.getFilterIndex(new AttributeIndexKey(null, "code", null))
			.getInvertedIndex().getRecordsEqualTo("").getArray());
		index.removeFilterAttribute(null, schema, Set.of(TURKISH), null, "-", 2);
		assertNull(index.getFilterIndex(new AttributeIndexKey(null, "code", null)));
	}

	/** Isolates raw function-routing tests from collection schema adoption and its separately tested policy store. */
	@Nonnull
	private static AttributeNormalizationResolver declaredResolver() {
		final AnalysisStepRegistry registry = new AnalysisStepRegistry();
		return (reference, attribute, locale) -> registry.compile(attribute.getNormalization(), locale);
	}

}
