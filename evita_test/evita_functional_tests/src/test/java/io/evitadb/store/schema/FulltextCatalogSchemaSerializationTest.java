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


package io.evitadb.store.schema;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaSearchableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSearchableMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextAnalyzerSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextLanguageSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextResourceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyFulltextAnalyzerSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyFulltextLanguageSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyFulltextResourceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveFulltextAnalyzerSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveFulltextLanguageSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveFulltextResourceSchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.CatalogSchemaStoragePart;
import io.evitadb.store.catalog.CatalogHeaderKryoConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.test.TestConstants;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the fulltext languages, resources and analyzers of a catalog schema survive the catalog storage and
 * the WAL, and that a catalog schema stored by the 2026.2 release - before these parts existed - still loads.
 */
@DisplayName("Fulltext parts of the catalog schema in storage and WAL")
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(SCHEMA)
@Tag(FULLTEXT)
class FulltextCatalogSchemaSerializationTest {
	/**
	 * The `serialVersionUID` of {@link CatalogSchema} in the 2026.2 release, before the fulltext parts were added.
	 */
	private static final long CATALOG_SCHEMA_2026_2_UID = -1582409928666780011L;
	/**
	 * The current `serialVersionUID` of {@link CatalogSchema}, which carries the fulltext parts.
	 */
	private static final long CATALOG_SCHEMA_UID = -1582409928666780010L;

	/**
	 * Builds a catalog schema carrying every fulltext part and every kind of resource content.
	 *
	 * @return the schema
	 */
	@Nonnull
	private static CatalogSchema fulltextCatalog() {
		final CatalogSchemaContract schema = new InternalCatalogSchemaBuilder(emptyCatalog())
			.withFulltextLanguage("cs", l -> l.wordNumberSplit())
			.withFulltextResource("brands", wordList("puma", "nike"))
			.withFulltextResource(
				"phones", synonymList(synonymGroup("mobil", "telefon"), synonymGroup("tv", "televize"))
			)
			.withFulltextAnalyzer("html-title", a -> a.basedOn(FulltextAnalyzerSchemaContract.TEXT).text(htmlStrip()))
			.withFulltextAnalyzer(
				"names",
				a -> a
					.allLanguages(l -> l.text().words(lowercase(), stopWords(resource("brands"))))
					.language("cs", l -> l.words(lowercase(), czechStemming(protectedWords(resource("brands")))))
					.language("en", l -> l.words(lowercase(), synonyms(resource("phones"))))
			)
			.toInstance();
		return CatalogSchema._internalBuild(schema);
	}

	/**
	 * Creates an empty catalog schema.
	 *
	 * @return the schema
	 */
	@Nonnull
	private static CatalogSchema emptyCatalog() {
		return CatalogSchema._internalBuild(
			TestConstants.TEST_CATALOG,
			NamingConvention.generate(TestConstants.TEST_CATALOG),
			null,
			EnumSet.allOf(CatalogEvolutionMode.class),
			EmptyEntitySchemaAccessor.INSTANCE
		);
	}

	/**
	 * Creates the Kryo the catalog storage composes: the header configurer registers the catalog schema after the
	 * schema configurer and wins, so a backward-compatible reader must live on both.
	 *
	 * @return the Kryo
	 */
	@Nonnull
	private static Kryo createCatalogKryo() {
		return KryoFactory.createKryo(
			SchemaKryoConfigurer.INSTANCE
				.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
				.andThen(SharedClassesConfigurer.INSTANCE)
		);
	}

	/**
	 * Writes the catalog schema as the catalog storage does.
	 *
	 * @param kryo   the catalog Kryo
	 * @param schema the schema
	 * @return the bytes, starting with the schema's `serialVersionUID`
	 */
	@Nonnull
	private static byte[] writeCatalogSchema(@Nonnull Kryo kryo, @Nonnull CatalogSchema schema) {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(1_024);
		try (final Output output = new Output(baos)) {
			kryo.writeObject(output, schema);
		}
		return baos.toByteArray();
	}

	/**
	 * Reads a catalog schema as the catalog storage does. The deserialization context catalog serves only the lookup
	 * of entity schemas, which no assertion here reaches, so a mock is its sole observer.
	 *
	 * @param kryo  the catalog Kryo
	 * @param bytes the bytes
	 * @return the schema
	 */
	@Nonnull
	private static CatalogSchema readCatalogSchema(@Nonnull Kryo kryo, @Nonnull byte[] bytes) {
		try (final Input input = new Input(new ByteArrayInputStream(bytes))) {
			final CatalogSchema schema = CatalogSchemaStoragePart.deserializeWithCatalog(
				Mockito.mock(CatalogContract.class),
				() -> kryo.readObject(input, CatalogSchema.class)
			);
			assertEquals(bytes.length, input.position(), "the whole record is consumed");
			return schema;
		}
	}

	/**
	 * Returns one instance of each fulltext schema mutation, the create and modify ones carrying non-trivial content.
	 *
	 * @return the mutations, each with its class name
	 */
	@Nonnull
	static Stream<Arguments> mutations() {
		final AnalysisStep[] splitting = {standardTokenizer(), wordNumberSplit()};
		final FulltextAnalysisPhases phases = new FulltextAnalysisPhases(
			List.of(htmlStrip()), List.of(lowercase(), stopWords(czechStopWords(), resource("brands")))
		);
		final Map<String, FulltextAnalysisPhases> languages = Map.of(
			"cs", new FulltextAnalysisPhases(null, List.of(czechStemming(protectedWords(resource("brands"))))),
			"en", new FulltextAnalysisPhases(List.of(), null)
		);
		return Stream.of(
			new CreateFulltextLanguageSchemaMutation("cs", splitting),
			new ModifyFulltextLanguageSchemaMutation("cs", new AnalysisStep[]{standardTokenizer()}),
			new RemoveFulltextLanguageSchemaMutation("cs"),
			new CreateFulltextResourceSchemaMutation("brands", wordList("puma", "nike")),
			new ModifyFulltextResourceSchemaMutation("phones", synonymList(synonymGroup("mobil", "telefon"))),
			new RemoveFulltextResourceSchemaMutation("brands"),
			new CreateFulltextAnalyzerSchemaMutation("names", null, phases, languages),
			new ModifyFulltextAnalyzerSchemaMutation(
				"html-title",
				FulltextAnalyzerSchemaContract.TEXT,
				new FulltextAnalysisPhases(List.of(htmlStrip()), null),
				Map.of()
			),
			new RemoveFulltextAnalyzerSchemaMutation("names"),
			// the fields carry the analyzer and the pivot - set, and left to the current value
			new SetAttributeSchemaSearchableMutation("name", new Scope[]{Scope.LIVE}, "html-title", 8),
			new SetAttributeSchemaSearchableMutation("name", new Scope[]{Scope.LIVE}),
			new SetAssociatedDataSchemaSearchableMutation("body", Scope.values(), null, "names", 400),
			new SetAssociatedDataSchemaSearchableMutation("body", Scope.values(), null, null, 400),
			new CreateAttributeSchemaMutation(
				"name", null, null, null, null, null, new Scope[]{Scope.LIVE}, null,
				true, false, false, String.class, null, 0, ConflictResolutionOverride.INHERITED, null, "html-title", 8
			),
			new CreateGlobalAttributeSchemaMutation(
				"title", null, null, null, null, null, null, new Scope[]{Scope.LIVE}, null,
				true, false, false, String.class, null, 0, ConflictResolutionOverride.INHERITED, null, null, 12
			),
			new CreateAssociatedDataSchemaMutation(
				"body", null, null, String.class, true, true, new Scope[]{Scope.LIVE}, null,
				ConflictResolutionOverride.INHERITED, "names", null
			)
		).map(mutation -> Arguments.of(mutation.getClass().getSimpleName(), mutation));
	}

	@Nested
	@DisplayName("Catalog storage")
	class CatalogStorage {

		@Test
		@DisplayName("round-trips languages, resources and analyzers")
		void shouldRoundTripTheFulltextParts() {
			final Kryo kryo = createCatalogKryo();
			final CatalogSchema schema = fulltextCatalog();

			final CatalogSchema restored = readCatalogSchema(kryo, writeCatalogSchema(kryo, schema));

			assertEquals(schema.getFulltextLanguages(), restored.getFulltextLanguages());
			assertEquals(schema.getFulltextResources(), restored.getFulltextResources());
			assertEquals(schema.getFulltextAnalyzers(), restored.getFulltextAnalyzers());
			assertFalse(schema.differsFrom(restored));
		}

		@Test
		@DisplayName("writes the same bytes whatever order the parts were declared in")
		void shouldWriteTheSameBytesWhateverTheDeclarationOrder() {
			final Kryo kryo = createCatalogKryo();
			final CatalogSchema forward = CatalogSchema._internalBuild(
				new InternalCatalogSchemaBuilder(emptyCatalog())
					.withFulltextResource("a", wordList("x"))
					.withFulltextResource("b", wordList("y"))
					.toInstance()
			);
			final CatalogSchema reverse = CatalogSchema._internalBuild(
				new InternalCatalogSchemaBuilder(emptyCatalog())
					.withFulltextResource("b", wordList("y"))
					.withFulltextResource("a", wordList("x"))
					.toInstance()
			);

			assertArrayEquals(writeCatalogSchema(kryo, forward), writeCatalogSchema(kryo, reverse));
		}

		@Test
		@DisplayName("loads a catalog schema stored by the 2026.2 release with no fulltext parts")
		void shouldLoadACatalogSchemaStoredBy2026_2() {
			final Kryo kryo = createCatalogKryo();
			final CatalogSchema schema = emptyCatalog();
			final byte[] current = writeCatalogSchema(kryo, schema);
			// the fulltext parts are appended to the 2026.2 layout as three zero counts, one byte each: without them,
			// and stamped with the release UID, the record is what the release wrote
			final byte[] release = Arrays.copyOf(current, current.length - 3);
			assertArrayEquals(new byte[]{0, 0, 0}, Arrays.copyOfRange(current, current.length - 3, current.length));
			// Kryo writes the UID as the record's first long, little-endian
			final ByteBuffer header = ByteBuffer.wrap(release).order(ByteOrder.LITTLE_ENDIAN);
			assertEquals(CATALOG_SCHEMA_UID, header.getLong(0), "the record starts with the current UID");
			header.putLong(0, CATALOG_SCHEMA_2026_2_UID);

			final CatalogSchema restored = readCatalogSchema(kryo, release);

			assertEquals(TestConstants.TEST_CATALOG, restored.getName());
			assertTrue(restored.getFulltextLanguages().isEmpty());
			assertTrue(restored.getFulltextResources().isEmpty());
			assertTrue(restored.getFulltextAnalyzers().isEmpty());
		}

	}

	@Nested
	@DisplayName("Fields in catalog storage")
	class Fields {

		@Test
		@DisplayName("round-trips the analyzer and the length pivot of every kind of field")
		void shouldRoundTripTheFieldSettings() {
			final Kryo kryo = createCatalogKryo();
			final List<Object> fields = List.of(
				AttributeSchema._internalBuild(
					"brandName", null, null, null, null, null, Set.of(Scope.LIVE), null,
					true, true, false, String.class, null, 0, ConflictResolutionOverride.INHERITED, null,
					"html-title", 8
				),
				EntityAttributeSchema._internalBuild(
					"name", null, null, null, null, null, Set.of(Scope.LIVE), null,
					true, true, false, String.class, null, 0, ConflictResolutionOverride.INHERITED, null,
					"names", 12
				),
				GlobalAttributeSchema._internalBuild(
					"title", (String) null, null, null, null, null, null, new Scope[]{Scope.LIVE}, null,
					true, true, false, String.class, null, 0, ConflictResolutionOverride.INHERITED, null,
					FulltextAnalyzerSchemaContract.TEXT, 40
				),
				AssociatedDataSchema._internalBuild(
					"body", null, null, String.class, true, true, new Scope[]{Scope.LIVE}, null,
					ConflictResolutionOverride.INHERITED, FulltextAnalyzerSchemaContract.HTML_TEXT, 400
				)
			);
			for (final Object field : fields) {
				final ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
				try (final Output output = new Output(baos)) {
					kryo.writeObject(output, field);
				}
				try (final Input input = new Input(baos.toByteArray())) {
					assertEquals(field, kryo.readObject(input, field.getClass()), field.toString());
				}
			}
		}

	}

	@Nested
	@Tag(WAL)
	@DisplayName("WAL")
	class Wal {

		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.store.schema.FulltextCatalogSchemaSerializationTest#mutations")
		@DisplayName("round-trips every fulltext schema mutation and the field mutations carrying the analyzer")
		void shouldRoundTripTheMutation(@Nonnull String name, @Nonnull Object mutation) {
			final Kryo kryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
			final ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
			try (final Output output = new Output(baos)) {
				kryo.writeClassAndObject(output, mutation);
			}
			final byte[] bytes = baos.toByteArray();
			try (final Input input = new Input(bytes)) {
				assertEquals(mutation, kryo.readClassAndObject(input));
				assertEquals(bytes.length, input.position(), "the whole record is consumed");
			}
		}

	}

}
