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

package io.evitadb.api.functional.schema;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.requestResponse.schema.ReferenceSearchMode.COMPOSITION;
import static io.evitadb.index.fulltext.FulltextIndexTestSupport.impactsOf;
import static io.evitadb.index.fulltext.FulltextIndexTestSupport.postingsOf;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/** Real session validation, durable metadata and index-neutral edits of searchable-through references. */
@DisplayName("Reference composition schema in embedded evitaDB")
@Tag(ENGINE)
@Tag(SCHEMA)
@Tag(REFERENCE)
@Tag(FULLTEXT)
class ReferenceSearchableThroughSchemaTest implements EvitaTestSupport {
	private static final String PRODUCT = "Product";
	private static final String BLOCK = "ContentBlock";
	private static final String BLOCKS = "blocks";
	private static final String PRODUCTS = "products";
	private TestPaths paths;
	private Evita evita;

	/** Starts isolated storage with checkpoints disabled for deterministic crash-copy WAL replay. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReferenceSearchableThroughSchemaTest");
		this.evita = new Evita(configuration(this.paths));
		this.evita.defineCatalog(TEST_CATALOG);
	}

	/** Closes real storage and removes the test directories. */
	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/** The absence of reference indexing is refused by updateVia itself. */
	@Test
	@DisplayName("should refuse declaration where the reference is not indexed")
	void shouldRefuseNonIndexedScopeImmediately() {
		assertInvalidDeclaration(reference -> reference.nonIndexed().searchableThrough(COMPOSITION), "LIVE");
	}

	/** Indexing LIVE does not permit text composition in ARCHIVED. */
	@Test
	@DisplayName("should check every requested scope immediately")
	void shouldRefuseUnindexedArchivedScopeImmediately() {
		assertInvalidDeclaration(reference -> reference.indexedForFiltering()
			.searchableThroughInScope(COMPOSITION, Scope.LIVE, Scope.ARCHIVED), "ARCHIVED");
	}

	/** A group-only component set cannot provide reverse owner lookup. */
	@Test
	@DisplayName("should refuse declaration without the referenced entity component")
	void shouldRefuseMissingEntityComponentImmediately() {
		assertInvalidDeclaration(reference -> reference.indexedForFiltering()
			.indexedWithComponentsInScope(Scope.LIVE, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)
			.searchableThrough(COMPOSITION), "LIVE");
	}

	/** External references cannot compose text maintained outside evitaDB. */
	@Test
	@DisplayName("should refuse an unmanaged target immediately")
	void shouldRefuseUnmanagedTargetImmediately() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			final InvalidSchemaMutationException error = assertThrows(InvalidSchemaMutationException.class,
				() -> session.defineEntitySchema(PRODUCT)
					.withReferenceTo(BLOCKS, "ExternalContent", Cardinality.ZERO_OR_MORE,
						reference -> reference.indexedForFiltering().searchableThrough(COMPOSITION))
					.updateVia(session));
			assertContext(error, "LIVE");
		});
	}

	/** Invalid weights are rejected before a schema mutation reaches storage. */
	@ParameterizedTest
	@ValueSource(floats = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.1f})
	@DisplayName("should refuse a non-finite or negative weight immediately")
	void shouldRefuseInvalidWeightsImmediately(float weight) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(BLOCK).updateVia(session);
			final InvalidSchemaMutationException error = assertThrows(InvalidSchemaMutationException.class,
				() -> session.defineEntitySchema(PRODUCT)
					.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE,
						reference -> reference.indexedForFiltering().searchableThrough(COMPOSITION, weight))
					.updateVia(session));
			assertTrue(error.getMessage().contains("finite and non-negative"), error.getMessage());
			assertTrue(error.getMessage().contains(BLOCKS), error.getMessage());
		});
	}

	/** The index requirement remains in force when an existing declaration is edited. */
	@Test
	@DisplayName("should refuse withdrawing an index needed by a declared scope")
	void shouldRefuseWithdrawingIndexImmediately() {
		defineComposedReferences();
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			final InvalidSchemaMutationException error = assertThrows(InvalidSchemaMutationException.class,
				() -> session.getEntitySchemaOrThrow(PRODUCT).openForWrite()
					.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE,
						reference -> reference.nonIndexed(Scope.ARCHIVED)).updateVia(session));
			assertContext(error, "ARCHIVED");
		});
	}

	/** Leaving the index flag on does not permit withdrawing its reverse-owner component. */
	@Test
	@DisplayName("should refuse withdrawing the referenced entity component")
	void shouldRefuseWithdrawingEntityComponentImmediately() {
		defineComposedReferences();
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			final InvalidSchemaMutationException error = assertThrows(InvalidSchemaMutationException.class,
				() -> session.getEntitySchemaOrThrow(PRODUCT).openForWrite()
					.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE,
						reference -> reference.indexedWithComponentsInScope(
							Scope.LIVE, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)).updateVia(session));
			assertContext(error, "LIVE");
		});
	}

	/** Explicit reflected declarations use their own weight and no inherited modes. */
	@Test
	@DisplayName("should keep normal and independent reflected declarations after catalog restart")
	void shouldKeepNormalAndReflectedSettingsAfterRestart() {
		defineComposedReferences();
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final ReferenceSchemaContract original = session.getEntitySchemaOrThrow(PRODUCT)
				.getReference(BLOCKS).orElseThrow();
			final ReflectedReferenceSchemaContract reflected = assertInstanceOf(ReflectedReferenceSchemaContract.class,
				session.getEntitySchemaOrThrow(BLOCK).getReference(PRODUCTS).orElseThrow());
			assertEquals(COMPOSITION, original.getSearchableThrough());
			assertTrue(reflected.getSearchableThroughInScopes().isEmpty());
			assertEquals(0.5f, reflected.getSearchableThroughWeight());
			return null;
		});
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrow(BLOCK).openForWrite()
				.withReflectedReferenceToEntity(PRODUCTS, PRODUCT, BLOCKS,
					reference -> reference.searchableThroughInScope(COMPOSITION, 0f, Scope.ARCHIVED)
					.withDescription("Referenced products").deprecated("Explicit composition"))
				.updateVia(session);
		});
		this.evita.close();
		this.evita = new Evita(configuration(this.paths));
		this.evita.waitUntilFullyInitialized();
		assertComposedSettings(this.evita, 0.75f, 0f);
	}

	/** A reflected editor rejects its own invalid weight before updateVia returns. */
	@Test
	@DisplayName("should refuse invalid reflected weight immediately")
	void shouldRefuseInvalidReflectedWeightImmediately() {
		defineComposedReferences();
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			final InvalidSchemaMutationException error = assertThrows(InvalidSchemaMutationException.class,
				() -> session.getEntitySchemaOrThrow(BLOCK).openForWrite()
					.withReflectedReferenceToEntity(PRODUCTS, PRODUCT, BLOCKS,
						reference -> reference.searchableThrough(COMPOSITION, Float.NaN)).updateVia(session));
			assertTrue(error.getMessage().contains("finite and non-negative"), error.getMessage());
		});
	}

	/** Reflected modes require indexing even when the reflected editor opts out explicitly. */
	@Test
	@DisplayName("should refuse an explicitly non-indexed reflected declaration immediately")
	void shouldRefuseNonIndexedReflectedDeclarationImmediately() {
		defineComposedReferences();
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			final InvalidSchemaMutationException error = assertThrows(InvalidSchemaMutationException.class,
				() -> session.getEntitySchemaOrThrow(BLOCK).openForWrite()
					.withReflectedReferenceToEntity(PRODUCTS, PRODUCT, BLOCKS,
						reference -> reference.nonIndexed().searchableThrough(COMPOSITION)).updateVia(session));
			assertTrue(error.getMessage().contains(PRODUCTS), error.getMessage());
			assertTrue(error.getMessage().contains("LIVE"), error.getMessage());
		});
	}

	/** A bound reflected builder checks its completed index mutation during APPLY replay. */
	@Test
	@DisplayName("should refuse a reflected index withdrawal before updateVia returns")
	void shouldRefuseReflectedIndexWithdrawalImmediately() {
		defineComposedReferences();
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrow(BLOCK).openForWrite()
				.withReflectedReferenceToEntity(PRODUCTS, PRODUCT, BLOCKS,
					reference -> reference.searchableThroughInScope(COMPOSITION, Scope.ARCHIVED))
				.updateVia(session);
		});
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			final InvalidSchemaMutationException error = assertThrows(InvalidSchemaMutationException.class,
				() -> session.getEntitySchemaOrThrow(BLOCK).openForWrite()
					.withReflectedReferenceToEntity(PRODUCTS, PRODUCT, BLOCKS,
						reference -> reference.nonIndexed(Scope.ARCHIVED)
							.indexedWithComponentsInScope(Scope.LIVE, ReferenceIndexedComponents.REFERENCED_ENTITY))
					.updateVia(session));
			assertTrue(error.getMessage().contains(PRODUCTS), error.getMessage());
			assertTrue(error.getMessage().contains("ARCHIVED"), error.getMessage());
		});
	}

	/** Clearing the old mode first keeps bound reflected builder replay valid. */
	@Test
	@DisplayName("should let a bound reflected builder withdraw composition before its index")
	void shouldWithdrawReflectedCompositionBeforeIndex() {
		defineComposedReferences();
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrow(BLOCK).openForWrite()
				.withReflectedReferenceToEntity(PRODUCTS, PRODUCT, BLOCKS,
					reference -> reference.searchableThroughInScope(COMPOSITION, Scope.ARCHIVED)).updateVia(session);
		});
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrow(BLOCK).openForWrite()
				.withReflectedReferenceToEntity(PRODUCTS, PRODUCT, BLOCKS,
					reference -> reference.nonSearchableThroughInScope(Scope.ARCHIVED).nonIndexed(Scope.ARCHIVED)
						.indexedWithComponentsInScope(Scope.LIVE, ReferenceIndexedComponents.REFERENCED_ENTITY))
				.updateVia(session);
			final ReferenceSchemaContract reference = session.getEntitySchemaOrThrow(BLOCK)
				.getReferenceOrThrowException(PRODUCTS);
			assertNull(reference.getSearchableThrough(Scope.ARCHIVED));
			assertFalse(reference.isIndexedInScope(Scope.ARCHIVED));
		});
	}

	/** A folded create must validate explicit settings before the session closes. */
	@Test
	@DisplayName("should refuse an invalid reflected create before updateVia returns")
	void shouldRefuseInvalidReflectedCreateImmediately() {
		defineComposedReferences();
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrow(BLOCK).openForWrite()
				.withoutReferenceTo(PRODUCTS).updateVia(session);
		});
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			final InvalidSchemaMutationException error = assertThrows(InvalidSchemaMutationException.class,
				() -> session.getEntitySchemaOrThrow(BLOCK).openForWrite()
					.withReflectedReferenceToEntity(PRODUCTS, PRODUCT, BLOCKS,
						reference -> reference.indexedForFilteringInScope(Scope.LIVE)
							.searchableThroughInScope(COMPOSITION, Scope.ARCHIVED)).updateVia(session));
			assertTrue(error.getMessage().contains(PRODUCTS), error.getMessage());
			assertTrue(error.getMessage().contains("ARCHIVED"), error.getMessage());
		});
	}

	/** Only inherited prerequisites depend on the original, which can be defined later in the same session. */
	@Test
	@DisplayName("should allow the reflected original to be defined later in the same session")
	void shouldAllowReflectedOriginalDefinedLaterInSameSession() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(BLOCK)
				.withReflectedReferenceToEntity(PRODUCTS, PRODUCT, BLOCKS,
					reference -> reference.searchableThrough(COMPOSITION, 0.7f)).updateVia(session);
			session.defineEntitySchema(PRODUCT)
				.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE,
					reference -> reference.indexedForFiltering()).updateVia(session);
		});
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final ReferenceSchemaContract reflected = session.getEntitySchemaOrThrow(BLOCK)
				.getReferenceOrThrowException(PRODUCTS);
			assertEquals(COMPOSITION, reflected.getSearchableThrough());
			assertEquals(0.7f, reflected.getSearchableThroughWeight());
			assertTrue(reflected.isIndexed());
		});
	}

	/** Only final validation sees a reflected reference's index after its original changes. */
	@Test
	@DisplayName("should refuse a reflected mode when its inherited index disappears at session close")
	void shouldValidateInheritedIndexAtSessionClose() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(BLOCK).withoutGeneratedPrimaryKey().updateVia(session);
			session.defineEntitySchema(PRODUCT).withoutGeneratedPrimaryKey()
				.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE,
					reference -> reference.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED))
				.updateVia(session);
			session.getEntitySchemaOrThrow(BLOCK).openForWrite()
				.withReflectedReferenceToEntity(PRODUCTS, PRODUCT, BLOCKS,
					reference -> reference.searchableThroughInScope(COMPOSITION, Scope.ARCHIVED))
				.updateVia(session);
			session.goLiveAndClose();
		});
		final RollbackException rollback = assertThrows(RollbackException.class,
			() -> this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getEntitySchemaOrThrow(PRODUCT).openForWrite()
					.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE,
						reference -> reference.nonIndexed(Scope.ARCHIVED)).updateVia(session);
			}));
		final InvalidSchemaMutationException error = assertInstanceOf(InvalidSchemaMutationException.class,
			rollback.getCause());
		assertTrue(error.getMessage().contains(PRODUCTS), error.getMessage());
		assertTrue(error.getMessage().contains(BLOCK), error.getMessage());
		assertTrue(error.getMessage().contains("ARCHIVED"), error.getMessage());
	}

	/** Retargeting must reject composition as soon as the target becomes unmanaged. */
	@Test
	@DisplayName("should reject changing a declared reference target to an unmanaged type immediately")
	void shouldRejectRetargetingToUnmanagedType() {
		defineComposedReferences();
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrow(BLOCK).openForWrite().withoutReferenceTo(PRODUCTS).updateVia(session);
			final InvalidSchemaMutationException error = assertThrows(InvalidSchemaMutationException.class,
				() -> session.getEntitySchemaOrThrow(PRODUCT).openForWrite()
					.withReferenceTo(BLOCKS, "ExternalContent", Cardinality.ZERO_OR_MORE).updateVia(session));
			assertContext(error, "LIVE");
		});
	}

	/** WAL replay reads create and later set mutations, including explicit reflected metadata. */
	@Test
	@DisplayName("should replay normal and reflected searchable-through metadata from a WAL gap")
	void shouldReplayMetadataFromWal() throws IOException {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(BLOCK).withoutGeneratedPrimaryKey().updateVia(session);
			session.defineEntitySchema(PRODUCT).withoutGeneratedPrimaryKey().updateVia(session);
			session.goLiveAndClose();
		});
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrow(PRODUCT).openForWrite()
				.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE,
					reference -> reference.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
						.searchableThroughInScope(COMPOSITION, 0.75f, Scope.LIVE, Scope.ARCHIVED))
				.updateVia(session);
			session.getEntitySchemaOrThrow(BLOCK).openForWrite()
				.withReflectedReferenceToEntity(PRODUCTS, PRODUCT, BLOCKS,
					reference -> reference.searchableThroughInScope(COMPOSITION, 0.125f, Scope.ARCHIVED))
				.updateVia(session);
		});
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrow(PRODUCT).openForWrite()
				.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE,
					reference -> reference.searchableThroughInScope(COMPOSITION, 1.25f, Scope.LIVE, Scope.ARCHIVED))
				.updateVia(session);
			session.getEntitySchemaOrThrow(BLOCK).openForWrite()
				.withReflectedReferenceToEntity(PRODUCTS, PRODUCT, BLOCKS,
					reference -> reference.searchableThroughInScope(COMPOSITION, 0.25f, Scope.ARCHIVED))
				.updateVia(session);
		});
		final Catalog catalog = catalog(this.evita);
		assertTrue(catalog.getVersion() > catalog.getPersistenceService().getLastCatalogVersion());
		final TestPaths recoveryPaths = createTestPaths("ReferenceCompositionWalReplay");
		try {
			try (final Stream<Path> files = Files.walk(this.paths.storage())) {
				for (final Path path : files.toList()) {
					final Path destination = recoveryPaths.storage().resolve(this.paths.storage().relativize(path));
					if (Files.isDirectory(path)) {
						Files.createDirectories(destination);
					} else {
						Files.createDirectories(destination.getParent());
						Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
			try (final Evita recovered = new Evita(configuration(recoveryPaths))) {
				recovered.waitUntilFullyInitialized();
				assertComposedSettings(recovered, 1.25f, 0.25f);
			}
		} finally {
			cleanupTestPaths(recoveryPaths);
		}
	}

	/** Withdrawing text on the target side is allowed even while owners compose it. */
	@Test
	@DisplayName("should allow referenced fields to stop being searchable")
	void shouldAllowTargetFieldsToBecomeNonSearchable() {
		defineComposedReferences();
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrow(BLOCK).openForWrite().withLocale(Locale.ENGLISH)
				.withAssociatedData("content", String.class, data -> data.localized().searchable()).updateVia(session);
		});
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrow(BLOCK).openForWrite()
				.withAssociatedData("content", String.class, data -> data.nonSearchable()).updateVia(session);
		});
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertEquals(COMPOSITION, session.getEntitySchemaOrThrow(PRODUCT).getReference(BLOCKS).orElseThrow()
				.getSearchableThrough());
			assertFalse(session.getEntitySchemaOrThrow(BLOCK).getAssociatedData("content").orElseThrow()
				.isSearchableInAnyScope());
			return null;
		});
	}

	/** Declaring, reweighting and removing modes do not change any existing index object or fulltext generation. */
	@Test
	@DisplayName("should preserve indexes and fulltext generations across metadata edits on stored entities")
	void shouldLeavePopulatedIndexesUntouched() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(BLOCK).withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAssociatedData("content", String.class, data -> data.localized().searchable()).updateVia(session);
			session.defineEntitySchema(PRODUCT).withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAttribute("title", String.class, attribute -> attribute.localized().searchable())
				.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE,
					reference -> reference.indexedForFiltering()).updateVia(session);
			session.upsertEntity(session.createNewEntity(BLOCK, 1)
				.setAssociatedData("content", Locale.ENGLISH, "amber"));
			for (int id = 1; id <= 2; id++) {
				session.upsertEntity(session.createNewEntity(PRODUCT, id)
					.setAttribute("title", Locale.ENGLISH, "green").setReference(BLOCKS, 1));
			}
			session.goLiveAndClose();
		});
		final Map<Integer, EntityIndex> productIndexes = indexes(this.evita, PRODUCT);
		final Map<Integer, EntityIndex> blockIndexes = indexes(this.evita, BLOCK);
		final FulltextIndex productText = fulltext(this.evita, PRODUCT);
		final FulltextIndex blockText = fulltext(this.evita, BLOCK);
		final int titleId = productText.getFieldId(FulltextFieldKey.attribute("title"));
		final int contentId = blockText.getFieldId(FulltextFieldKey.associatedData("content"));
		final long titleFingerprint = productText.getIndexFingerprint(titleId);
		final long contentFingerprint = blockText.getIndexFingerprint(contentId);
		final byte[] titleImpacts = impactsOf(productText, titleId, "green");
		final byte[] contentImpacts = impactsOf(blockText, contentId, "amber");
		for (final float weight : new float[]{0.5f, 2f, 0f}) {
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getEntitySchemaOrThrow(PRODUCT).openForWrite()
					.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE,
						reference -> reference.searchableThrough(COMPOSITION, weight)).updateVia(session);
			});
			assertIndexesUnchanged(productIndexes, indexes(this.evita, PRODUCT));
			assertIndexesUnchanged(blockIndexes, indexes(this.evita, BLOCK));
			assertSame(productText, fulltext(this.evita, PRODUCT));
			assertSame(blockText, fulltext(this.evita, BLOCK));
			assertMatches(PRODUCT, "green", 1, 2);
			assertMatches(BLOCK, "amber", 1);
		}
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrow(PRODUCT).openForWrite()
				.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE,
					ReferenceSchemaEditor::nonSearchableThrough).updateVia(session);
		});
		assertIndexesUnchanged(productIndexes, indexes(this.evita, PRODUCT));
		assertIndexesUnchanged(blockIndexes, indexes(this.evita, BLOCK));
		assertEquals(titleId, fulltext(this.evita, PRODUCT).getFieldId(FulltextFieldKey.attribute("title")));
		assertEquals(contentId, fulltext(this.evita, BLOCK).getFieldId(FulltextFieldKey.associatedData("content")));
		assertEquals(titleFingerprint, fulltext(this.evita, PRODUCT).getIndexFingerprint(titleId));
		assertEquals(contentFingerprint, fulltext(this.evita, BLOCK).getIndexFingerprint(contentId));
		assertFalse(fulltext(this.evita, PRODUCT).isFieldRetired(titleId));
		assertFalse(fulltext(this.evita, BLOCK).isFieldRetired(contentId));
		assertEquals(1, productText.getFieldCount());
		assertEquals(1, blockText.getFieldCount());
		assertArrayEquals(new int[]{1, 2}, postingsOf(productText, titleId, "green").getArray());
		assertArrayEquals(new int[]{1}, postingsOf(blockText, contentId, "amber").getArray());
		assertArrayEquals(titleImpacts, impactsOf(fulltext(this.evita, PRODUCT), titleId, "green"));
		assertArrayEquals(contentImpacts, impactsOf(fulltext(this.evita, BLOCK), contentId, "amber"));
		assertMatches(PRODUCT, "green", 1, 2);
		assertMatches(BLOCK, "amber", 1);
	}

	/** Declares a bidirectional managed reference, with composition on its original side only. */
	private void defineComposedReferences() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(BLOCK).withoutGeneratedPrimaryKey().updateVia(session);
			session.defineEntitySchema(PRODUCT).withoutGeneratedPrimaryKey()
				.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE,
					reference -> reference.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
						.searchableThroughInScope(COMPOSITION, 0.75f, Scope.LIVE, Scope.ARCHIVED))
				.updateVia(session);
			session.getEntitySchemaOrThrow(BLOCK).openForWrite()
				.withReflectedReferenceToEntity(PRODUCTS, PRODUCT, BLOCKS).updateVia(session);
			session.goLiveAndClose();
		});
	}

	/** Captures immediate refusal inside updateVia, before session-close validation. */
	private void assertInvalidDeclaration(
		Consumer<ReferenceSchemaEditor.ReferenceSchemaBuilder> editor, String scope
	) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(BLOCK).updateVia(session);
			final InvalidSchemaMutationException error = assertThrows(InvalidSchemaMutationException.class,
				() -> session.defineEntitySchema(PRODUCT)
					.withReferenceToEntity(BLOCKS, BLOCK, Cardinality.ZERO_OR_MORE, editor).updateVia(session));
			assertContext(error, scope);
		});
	}

	/** Messages identify the owner, reference and offending scope. */
	private static void assertContext(InvalidSchemaMutationException error, String scope) {
		assertTrue(error.getMessage().contains(PRODUCT), error.getMessage());
		assertTrue(error.getMessage().contains(BLOCKS), error.getMessage());
		assertTrue(error.getMessage().contains(scope), error.getMessage());
	}

	/** Verifies normal and reflected settings after loading actual storage. */
	private static void assertComposedSettings(Evita engine, float originalWeight, float reflectedWeight) {
		engine.queryCatalog(TEST_CATALOG, session -> {
			final ReferenceSchemaContract original = session.getEntitySchemaOrThrow(PRODUCT)
				.getReference(BLOCKS).orElseThrow();
			assertEquals(Map.of(Scope.LIVE, COMPOSITION, Scope.ARCHIVED, COMPOSITION),
				original.getSearchableThroughInScopes());
			assertEquals(originalWeight, original.getSearchableThroughWeight());
			final ReflectedReferenceSchemaContract reflected = assertInstanceOf(ReflectedReferenceSchemaContract.class,
				session.getEntitySchemaOrThrow(BLOCK).getReference(PRODUCTS).orElseThrow());
			assertEquals(Map.of(Scope.ARCHIVED, COMPOSITION), reflected.getSearchableThroughInScopes());
			assertEquals(reflectedWeight, reflected.getSearchableThroughWeight());
			assertTrue(reflected.isIndexedInherited());
			return null;
		});
	}

	/** Uses a long checkpoint interval so committed WAL bytes are newer than the bootstrap record. */
	private EvitaConfiguration configuration(TestPaths testPaths) {
		final EvitaConfiguration base = newTestEvitaConfigurationBuilder(testPaths).build();
		return EvitaConfiguration.builder(base).transaction(TransactionOptions.builder(base.transaction())
			.checkpointIntervalInMillis(3_600_000L).build()).build();
	}

	/** Reads the active immutable catalog snapshot. */
	private static Catalog catalog(Evita engine) {
		return (Catalog) engine.getCatalogInstanceOrThrowException(TEST_CATALOG);
	}

	/** Reads all index objects of the deliberately tiny collection by their assigned keys. */
	private static Map<Integer, EntityIndex> indexes(Evita engine, String entityType) {
		final EntityCollection collection = catalog(engine).getCollectionForEntityOrThrowException(entityType);
		final Map<Integer, EntityIndex> result = CollectionUtils.createHashMap(collection.getIndexCount());
		for (int id = 1; id <= 100; id++) {
			final EntityIndex index = collection.getIndexByPrimaryKeyIfExists(id);
			if (index != null) {
				result.put(id, index);
			}
		}
		assertEquals(collection.getIndexCount(), result.size(), "Every existing index must be observed");
		return result;
	}

	/** Identity proves that no existing index was rebuilt, replaced or removed. */
	private static void assertIndexesUnchanged(Map<Integer, EntityIndex> expected, Map<Integer, EntityIndex> actual) {
		assertEquals(expected.keySet(), actual.keySet());
		expected.forEach((id, index) -> assertSame(index, actual.get(id), "Index " + id + " changed"));
	}

	/** Reads real fulltext generations retained by the global index. */
	private static FulltextIndex fulltext(Evita engine, String entityType) {
		return ((GlobalEntityIndex) catalog(engine).getCollectionForEntityOrThrowException(entityType)
			.getGlobalIndex()).getFulltextIndex(Locale.ENGLISH);
	}

	/** Checks query results over the original indexed fields after metadata-only schema changes. */
	private void assertMatches(String entityType, String text, int... expected) {
		final int[] actual = this.evita.queryCatalog(TEST_CATALOG, session -> {
			return session.query(
			query(collection(entityType), filterBy(entityLocaleEquals(Locale.ENGLISH), textMatches(text)),
				require(page(1, 100))), EntityReference.class).getRecordData().stream()
			.mapToInt(EntityReference::getPrimaryKey).sorted().toArray();
		});
		assertArrayEquals(expected, actual);
	}
}
