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


package io.evitadb.externalApi;

import java.math.BigDecimal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.*;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.analysis.SynonymGroup;
import io.evitadb.api.requestResponse.schema.analysis.SynonymList;
import io.evitadb.api.requestResponse.schema.mutation.*;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.*;
import io.evitadb.api.requestResponse.schema.mutation.catalog.*;
import io.evitadb.api.requestResponse.schema.mutation.reference.*;
import io.evitadb.core.Evita;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.Scope;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static java.math.BigDecimal.valueOf;
import static org.junit.jupiter.api.Assertions.*;

/** Independent declaration fixtures and literal assertions shared by the three schema-read transports. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FulltextSchemaFunctionalTestSupport {
	/** Prepares only catalog and collection prerequisites; every fulltext declaration is sent through the API. */
	@Nonnull
	public static String prepare(@Nonnull Evita evita) {
		final String catalog = FulltextMutationFunctionalTestSupport.prepare(evita,
			new FulltextMutationFunctionalTestSupport.MutationCase(
				FulltextMutationTestSupport.catalogMutations().findFirst().orElseThrow(), true));
		evita.updateCatalog(catalog, session -> {
			session.getEntitySchemaOrThrow("owner").openForWrite().withoutGeneratedPrimaryKey()
				.withLocale(Locale.ENGLISH).updateVia(session);
			session.getEntitySchemaOrThrow("target").openForWrite().withoutGeneratedPrimaryKey()
				.withLocale(Locale.ENGLISH).updateVia(session);
		});
		return catalog;
	}

	/**
	 * Declares every fulltext schema kind, including reference metadata, reference attributes and author samples.
	 *
	 * @return the schema mutation declarations sent through each transport
	 */
	@Nonnull
	public static List<LocalCatalogSchemaMutation> declarations() {
		final List<SchemaMutation> fields = FulltextMutationTestSupport.mutations(valueOf(0)).toList();
		final CreateGlobalAttributeSchemaMutation global = new CreateGlobalAttributeSchemaMutation(
			"globalText", null, null, null, null, null, null, Scope.values(), null, true, true, false,
			String.class, null, 0, ConflictResolutionOverride.INHERITED, null, "html-text", 73, valueOf(0));
		final CreateAttributeSchemaMutation attribute = new CreateAttributeSchemaMutation(
			"text", null, null, null, null, null, Scope.values(), null, true, true, false,
			String.class, null, 0, ConflictResolutionOverride.INHERITED, null, "html-text", 73, valueOf(0));
		final CreateAssociatedDataSchemaMutation data = new CreateAssociatedDataSchemaMutation(
			"text", null, null, ComplexDataObject.class, true, true, Scope.values(),
			ExpressionFactory.parse("$value.title"),
			ConflictResolutionOverride.INHERITED, "html-text", 73, valueOf(0));
		final LocalCatalogSchemaMutation owner = new ModifyEntitySchemaMutation("owner",
			attribute, data,
			(CreateReferenceSchemaMutation) fields.get(6),
			new ModifyReferenceAttributeSchemaMutation("related", attribute),
			new UseGlobalAttributeSchemaMutation("globalText"),
			new CreateReflectedReferenceSchemaMutation("reflected", null, null, Cardinality.ZERO_OR_MORE,
				"target", "original", null, null, null, null,
				ScopedHistogramIndexDefinition.EMPTY, ScopedBucketedPartially.EMPTY,
				ReflectedReferenceSchemaContract.AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT, new String[0],
				new ScopedReferenceSearchMode[]{
					new ScopedReferenceSearchMode(Scope.LIVE, ReferenceSearchMode.COMPOSITION),
					new ScopedReferenceSearchMode(Scope.ARCHIVED, ReferenceSearchMode.COMPOSITION)}, valueOf(0)));
		final LocalCatalogSchemaMutation target = new ModifyEntitySchemaMutation("target",
			new ModifyReferenceAttributeSchemaMutation("original", attribute),
			new CreateAssociatedDataSchemaMutation("content", null, null, String.class, true, true,
				Scope.values(), null, ConflictResolutionOverride.INHERITED, "html-text", 73, valueOf(0)));
		return Stream.concat(Stream.of(global, owner, target,
			new CreateFulltextResourceSchemaMutation("synonyms",
				new SynonymList(List.of(new SynonymGroup(List.of("quick", "fast")),
				new SynonymGroup(List.of("bright", "light"))))),
			new CreateFulltextAnalyzerSchemaMutation("standalone", null,
				new FulltextAnalysisPhases(List.of(), List.of()), Map.of(), List.of())),
				FulltextMutationTestSupport.catalogMutations()
			.filter(mutation -> mutation.getClass().getSimpleName().startsWith("Create"))
			.map(LocalCatalogSchemaMutation.class::cast)).toList();
	}

	/**
	 * Writes one searchable target and three owners, two of which refer to it. The owners' own text never holds the
	 * queried word, so an owner can match only through the reference.
	 */
	public static void insertSharedTarget(@Nonnull Evita evita, @Nonnull String catalog) {
		evita.updateCatalog(catalog, session -> {
			session.upsertEntity(session.createNewEntity("target", 10)
				.setAssociatedData("content", Locale.ENGLISH, "quartz lantern"));
			session.upsertEntity(session.createNewEntity("owner", 1)
				.setAttribute("text", Locale.ENGLISH, "plain").setReference("related", 10));
			session.upsertEntity(session.createNewEntity("owner", 2)
				.setAttribute("text", Locale.ENGLISH, "plain").setReference("related", 10));
			session.upsertEntity(session.createNewEntity("owner", 3).setAttribute("text", Locale.ENGLISH, "plain"));
		});
	}

	/**
	 * Literal settings expected from any searchable field in the fixture, including the explicit zero weight;
	 * `expression` is true for the associated data that declares the `$value.title` text expression.
	 */
	public static void assertField(@Nonnull JsonNode field, boolean expression) {
		assertEquals(read("[\"LIVE\",\"ARCHIVED\"]"), field.path("searchable"));
		assertEquals("html-text", field.path("analyzer").asText());
		assertEquals(73, field.path("lengthPivot").asInt());
		assertEquals("0", field.path("searchWeight").asText());
		if (expression) {
			assertEquals("$value.title", field.path("textExpression").asText());
		} else {
			assertFalse(field.hasNonNull("textExpression"));
		}
	}

	/** Literal composition settings expected on regular and reflected references. */
	public static void assertReference(@Nonnull JsonNode reference) {
		assertEquals(read("""
			[{"scope":"LIVE","mode":"COMPOSITION"},{"scope":"ARCHIVED","mode":"COMPOSITION"}]
			"""), reference.path("searchableThrough"));
		assertEquals("0", reference.path("searchableThroughWeight").asText());
	}

	/**
	 * Checks catalog declarations independently of production serializers, including sample order and empty output.
	 *
	 * @param catalog the catalog schema response from GraphQL or REST
	 */
	public static void assertCatalog(@Nonnull JsonNode catalog) {
		assertEquals(read("""
			[{"language":"en","wordSplitting":[{"step":"standard","parameters":{}},
			{"step":"word-number-split","parameters":{}}]}]
			"""), catalog.path("fulltextLanguages"));
		assertNames(catalog.path("fulltextResources"), "synonyms", "terms");
		final JsonNode synonyms = catalog.path("fulltextResources").path(0).path("content");
		assertEquals("SYNONYM_LIST", synonyms.path("kind").asText());
		assertFalse(synonyms.hasNonNull("words"));
		assertEquals(read("[{\"words\":[\"quick\",\"fast\"]},{\"words\":[\"bright\",\"light\"]}]"),
			synonyms.path("groups"));
		final JsonNode terms = catalog.path("fulltextResources").path(1).path("content");
		assertEquals("WORD_LIST", terms.path("kind").asText());
		assertEquals(read("[\"and\",\"the\"]"), terms.path("words"));
		assertFalse(terms.hasNonNull("groups"));
		assertNames(catalog.path("fulltextAnalyzers"), "custom", "standalone");
		final JsonNode standalone = catalog.path("fulltextAnalyzers").path(1);
		assertFalse(standalone.hasNonNull("basedOn"));
		assertEquals(read("{\"text\":[],\"words\":[]}"), standalone.path("allLanguages"));
		assertEquals(read("[]"), standalone.path("languages"));
		assertEquals(read("[]"), standalone.path("samples"));
		final JsonNode analyzer = catalog.path("fulltextAnalyzers").path(0);
		assertEquals("custom", analyzer.path("name").asText());
		assertEquals(100, analyzer.path("samples").size());
		assertEquals("Příliš žluťoučký kůň 東京", analyzer.path("samples").path(0).asText());
		assertEquals("ž".repeat(1_000), analyzer.path("samples").path(1).asText());
		assertEquals("sample 99", analyzer.path("samples").path(99).asText());
		assertEquals("text", analyzer.path("basedOn").asText());
		assertEquals(read("[{\"step\":\"html-strip\",\"parameters\":{}}]"), analyzer.path("allLanguages").path("text"));
		assertEquals(read("""
			[{"step":"lowercase","parameters":{}},
			 {"step":"stop-words","parameters":{"words":["english-stop-words"]}},
			 {"step":"english-stemming","parameters":{"protected-words":["english-stop-words"]}}]
			"""), analyzer.path("allLanguages").path("words"));
		assertEquals(read("""
			[{"language":"de","phases":{"text":[],"words":null}},
			 {"language":"en","phases":{"text":null,"words":[]}}]
			"""), analyzer.path("languages"));
	}

	/** Requires an array with exactly the expected named entries before inspecting their settings. */
	public static void assertNames(@Nonnull JsonNode collection, @Nonnull String... expected) {
		assertTrue(collection.isArray(), collection.toString());
		assertEquals(expected.length, collection.size());
		final List<String> names = new ArrayList<>();
		collection.forEach(entry -> names.add(entry.path("name").asText()));
		assertEquals(Arrays.stream(expected).sorted().toList(), names.stream().sorted().toList());
	}

	/** Requires an object with exactly the expected keys before inspecting its values. */
	public static void assertKeys(@Nonnull JsonNode collection, @Nonnull String... expected) {
		assertTrue(collection.isObject(), collection.toString());
		assertEquals(expected.length, collection.size());
		final List<String> names = new ArrayList<>();
		collection.fieldNames().forEachRemaining(names::add);
		assertEquals(Arrays.stream(expected).sorted().toList(), names.stream().sorted().toList());
	}

	/** Parses a transport response or an independent literal expectation. */
	@Nonnull
	@SneakyThrows
	public static JsonNode read(@Nonnull String json) {
		return new ObjectMapper().readTree(json);
	}
}
