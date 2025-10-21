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

package io.evitadb.api;

import io.evitadb.api.exception.EntityAlreadyRemovedException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.DeletedHierarchy;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.ReflectionLookup;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.Assertions.assertExactlyEquals;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Base work with Evita API integration test:
 *
 * - catalog creation
 * - catalog updating
 * - collection creation
 * - new entity insertion
 * - existing entity updating
 * - getting entity by id
 * - schema creation
 * - verification of the inserted entities by schema
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@SuppressWarnings({"Convert2MethodRef"})
@DisplayName("Evita base API")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
class EvitaApiFunctionalTest {
	public static final String BRAND = "brand";
	public static final String PRODUCT = "product";
	public static final String CATEGORY = "category";
	public static final Locale LOCALE_CZECH = new Locale("cs");
	public static final String LOGO = "https://www.siemens.com/logo.png";
	public static final String SIEMENS_CODE = "siemens";
	public static final String SIEMENS_TITLE = "Siemens";
	public static final Currency EUR = Currency.getInstance("EUR");
	public static final Currency CZK = Currency.getInstance("CZK");
	private static final ReflectionLookup REFLECTION_LOOKUP = new ReflectionLookup(ReflectionCachingBehaviour.NO_CACHE);

	@DisplayName("Create entity with primary key already provided")
	@Test
	void shouldCreateEntityWithExistingPrimaryKey(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// create new entity
				final EntityBuilder newBrand = createBrand(session, 1);
				// store it to the catalog
				session.upsertEntity(newBrand);
			}
		);

		// get entity by primary key and verify its contents
		assertBrand(evita, 1, Locale.ENGLISH, SIEMENS_CODE, SIEMENS_TITLE, LOGO, 1);
	}

	@DisplayName("Entity can be fetched asynchronously")
	@Test
	void shouldQueryCreatedEntityInAsynchronousFashion(Evita evita) {
		final EntityContract entityReference = createFullFeaturedEntity(evita);

		final CompletableFuture<SealedEntity> future = evita.queryCatalogAsync(TEST_CATALOG, session -> {
			// get entity by primary key and verify its contents
			return getProductById(session, 1).orElseThrow();
		});

		while (!future.isDone()) {
			Thread.onSpinWait();
		}

		final SealedEntity loadedProduct = future.getNow(null);
		assertNotNull(loadedProduct);
		assertExactlyEquals(entityReference, loadedProduct);
	}

	@DisplayName("Don't allow creating entity with same primary key twice")
	@Test
	void shouldFailToCreateEntityWithExistingPrimaryKeyTwice(Evita evita) {
		shouldCreateEntityWithExistingPrimaryKey(evita);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// create new entity
				final EntityBuilder newBrand = createBrand(session, 1);
				// store it to the catalog
				assertThrows(InvalidMutationException.class, () -> session.upsertEntity(newBrand));
			}
		);
	}

	@DisplayName("Create entity without PK and verify PK has been assigned")
	@Test
	void shouldAutomaticallyGeneratePrimaryKey(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// primary keys should be automatically generated in monotonic fashion
				assertEquals(1, session.upsertEntity(createBrand(session, null)).getPrimaryKey());
				assertEquals(2, session.upsertEntity(createBrand(session, null)).getPrimaryKey());
				assertEquals(3, session.upsertEntity(createBrand(session, null)).getPrimaryKey());
				final EntitySchemaContract brandSchema = session.getEntitySchema(BRAND).orElseThrow();
				assertTrue(brandSchema.isWithGeneratedPrimaryKey());
			}
		);
	}

	@DisplayName("Create entity outside Evita engine and insert it")
	@Test
	void shouldCreateDetachedEntity(Evita evita) {
		final EntityBuilder detachedBuilder = new InitialEntityBuilder(BRAND, 1)
			.setAttribute("code", "siemens")
			.setAttribute("name", Locale.ENGLISH, SIEMENS_TITLE)
			.setAttribute("logo", LOGO)
			.setAttribute("productCount", 1);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// store it to the catalog
				session.upsertEntity(detachedBuilder);
			}
		);

		// get entity by primary key and verify its contents
		assertBrand(evita, 1, Locale.ENGLISH, "siemens", SIEMENS_TITLE, LOGO, 1);
	}

	@DisplayName("Entity created outside Evita with incompatible schema should be rejected")
	@Test
	void shouldRefuseUpsertOfSchemaIncompatibleDetachedEntity(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT)
					.verifySchemaStrictly()
					.withAttribute("code", String.class, whichIs -> whichIs.unique())
					.withAttribute("url", String.class, whichIs -> whichIs.unique().localized())
					/* finally apply schema changes */
					.updateVia(session);
			}
		);

		final EntityBuilder detachedBuilder = new InitialEntityBuilder(PRODUCT, 1)
			.setAttribute("code", "siemens")
			.setAttribute("name", Locale.ENGLISH, SIEMENS_TITLE)
			.setAttribute("logo", LOGO)
			.setAttribute("productCount", 1);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// store it to the catalog
				assertThrows(InvalidMutationException.class, () -> session.upsertEntity(detachedBuilder));
			}
		);
	}

	@DisplayName("Entity created outside Evita with incompatible schema should be rejected and propagated outside update call")
	@Test
	void shouldRefuseUpsertOfSchemaIncompatibleDetachedEntityAndPropagateItOutsideUpdateCall(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT)
					.verifySchemaStrictly()
					.withAttribute("code", String.class, whichIs -> whichIs.unique())
					.withAttribute("url", String.class, whichIs -> whichIs.unique().localized())
					/* finally apply schema changes */
					.updateVia(session);
			}
		);

		final EntityBuilder detachedBuilder = new InitialEntityBuilder(PRODUCT, 1)
			.setAttribute("code", "siemens")
			.setAttribute("name", Locale.ENGLISH, SIEMENS_TITLE)
			.setAttribute("logo", LOGO)
			.setAttribute("productCount", 1);

		assertThrows(
			InvalidMutationException.class,
			() ->
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						// store it to the catalog
						session.upsertEntity(detachedBuilder);
					}
				)
		);
	}

	@DisplayName("All resources should be safely terminated in try block")
	@Test
	void shouldCreateEntityAndUseAutoCloseableForSessionTermination(Evita evita) {
		// first prepare data in R/W session
		evita.updateCatalog(TEST_CATALOG, session -> {
			//create new entity
			final EntityBuilder newBrand = createBrand(session, 1);
			// store it to the catalog
			session.upsertEntity(newBrand);
		});
		// now test the non-lambda approach to query the session
		try (final EvitaSessionContract session = evita.createReadOnlySession(TEST_CATALOG)) {
			// get entity by primary key and verify its contents
			assertBrandInSession(session, 1, Locale.ENGLISH, "siemens", SIEMENS_TITLE, LOGO, 1);
		}

		// get entity by primary key and verify its contents
		assertBrand(evita, 1, Locale.ENGLISH, "siemens", SIEMENS_TITLE, LOGO, 1);
	}

	@DisplayName("Existing entity can be altered and updated back to evitaDB")
	@Test
	void shouldUpdateExistingEntity(Evita evita) {
		// create new entity
		shouldCreateEntityWithExistingPrimaryKey(evita);
		// open new session
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// select existing entity
				final SealedEntity siemensBrand = getBrandById(session, 1).orElseThrow();
				// modify the entity contents
				final EntityBuilder updatedBrand = siemensBrand.openForWrite()
					.setAttribute("name", LOCALE_CZECH, "Siemens Česko")
					.setAttribute("logo", "https://www.siemens.cz/logo.png");
				// store entity back to the database
				session.upsertEntity(updatedBrand);
				// get entity by primary key and verify its contents
				assertBrandInSession(session, 1, LOCALE_CZECH, "siemens", "Siemens Česko", "https://www.siemens.cz/logo.png", 1);
			}
		);
		// verify data was changed
		assertBrand(evita, 1, LOCALE_CZECH, "siemens", "Siemens Česko", "https://www.siemens.cz/logo.png", 1);
	}

	@DisplayName("Existing entity can be altered and updated back to evitaDB, returning the new contents")
	@Test
	void shouldUpdateExistingEntityAndReturnIt(Evita evita) {
		// create new entity
		shouldCreateEntityWithExistingPrimaryKey(evita);
		// open new session
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// select existing entity
				final SealedEntity siemensBrand = getBrandById(session, 1).orElseThrow();
				// modify the entity contents
				final EntityBuilder updatedBrand = siemensBrand.openForWrite()
					.setAttribute("name", LOCALE_CZECH, "Siemens Česko")
					.setAttribute("logo", "https://www.siemens.cz/logo.png");
				// store entity back to the database
				final SealedEntity updatedEntity = session.upsertAndFetchEntity(updatedBrand, entityFetchAllContent());
				assertNotNull(updatedEntity);

				assertExactlyEquals(updatedBrand, updatedEntity);
			}
		);
	}

	@DisplayName("Existing entity can be altered and updated back to evitaDB, returning the new contents with filtered prices")
	@Test
	void shouldUpdateExistingEntityAndReturnItWithFilteredPrices(Evita evita) {
		// open new session
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityReferenceContract brand = createBrand(session, 1).upsertVia(session);
				final EntityReferenceContract category = createCategory(session, 1).upsertVia(session);
				final EntityReferenceContract productRef = createProduct(session, 1, brand, category).upsertVia(session);
				// select existing entity
				final SealedEntity product = getProductById(session, 1).orElseThrow();
				// modify the entity contents
				final EntityBuilder updatedProduct = product.openForWrite()
					.setPrice(2, "basic", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, createDateRange(2055, 2072), true);
				// store entity back to the database
				final SealedEntity updatedEntity = session.upsertAndFetchEntity(updatedProduct, priceContentRespectingFilter());
				assertNotNull(updatedEntity);

				assertFalse(updatedEntity.getPrices().isEmpty());
				assertTrue(updatedEntity.getPrices().stream().allMatch(it -> it.currency() == EUR && it.priceList().equals("basic")));
				assertTrue(updatedEntity.getPriceForSaleIfAvailable().isEmpty(), "Price for sale has no sense in the response!");
			}
		);
	}

	@DisplayName("Existing entity can be altered and updated back to evitaDB, returning the new contents respecting removed prices")
	@Test
	void shouldUpdateExistingEntityAndReturnItWithoutRemovedPrice(Evita evita) {
		// open new session
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityReferenceContract brand = createBrand(session, 1).upsertVia(session);
				final EntityReferenceContract category = createCategory(session, 1).upsertVia(session);
				final EntityReferenceContract productRef = createProduct(session, 1, brand, category).upsertVia(session);
				// select existing entity
				final SealedEntity product = getProductById(session, 1).orElseThrow();
				// modify the entity contents
				final EntityBuilder updatedProduct = product.openForWrite()
					.removePrice(2, "basic", EUR);
				// store entity back to the database
				final SealedEntity updatedEntity = session.upsertAndFetchEntity(updatedProduct, priceContentAll());
				assertNotNull(updatedEntity);

				assertFalse(updatedEntity.getPrices().isEmpty());
				assertTrue(updatedEntity.getPrices().stream().noneMatch(it -> it.currency() == EUR && it.priceList().equals("basic")));
			}
		);
	}

	@DisplayName("Existing entity should accept price handling strategy change")
	@Test
	void shouldUpdateEntityPriceInnerHandlingStrategy(Evita evita) {
		final EntityContract productRef = createFullFeaturedEntity(evita);

		// open new session
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// select existing entity
				final SealedEntity product = getProductById(session, productRef.getPrimaryKey()).orElseThrow();
				// modify the entity contents
				final EntityBuilder updatedProduct = product.openForWrite()
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.SUM);
				// store entity back to the database
				final SealedEntity updatedEntity = session.upsertAndFetchEntity(updatedProduct, priceContentAll());

				assertNotNull(updatedEntity);
				assertEquals(PriceInnerRecordHandling.SUM, updatedEntity.getPriceInnerRecordHandling());

				final SealedEntity productAgain = getProductById(session, productRef.getPrimaryKey()).orElseThrow();
				assertEquals(PriceInnerRecordHandling.SUM, productAgain.getPriceInnerRecordHandling());
			}
		);
	}

	@DisplayName("Entity with all possible data is created")
	@Test
	void shouldCreateFullFeaturedEntity(Evita evita) {
		final EntityContract entityReference = createFullFeaturedEntity(evita);

		evita.queryCatalog(TEST_CATALOG, session -> {
			// get entity by primary key and verify its contents
			final SealedEntity loadedProduct = getProductById(session, 1).orElseThrow();
			assertNotNull(loadedProduct);
			assertExactlyEquals(entityReference, loadedProduct);
			return loadedProduct;
		});
	}

	@DisplayName("Entity with all possible data is altered")
	@Test
	void shouldUpdateFullFeaturedEntity(Evita evita) {
		final EntityContract productReference = createFullFeaturedEntity(evita);

		final AtomicReference<EntityContract> product = new AtomicReference<>();
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity createdEntity = session.getEntity(
					productReference.getType(), Objects.requireNonNull(productReference.getPrimaryKey()), entityFetchAllContent()
				).orElseThrow();
				final EntityReferenceContract categoryTwo = session.upsertEntity(createCategory(session, 2));
				// alter full-featured entity
				final EntityReferenceContract productRef = session.upsertEntity(updateProduct(createdEntity, categoryTwo));
				product.set(
					session.getEntity(productRef.getType(), productRef.getPrimaryKey(), entityFetchAllContent()).orElseThrow()
				);
			}
		);

		evita.queryCatalog(TEST_CATALOG, session -> {
			// get entity by primary key and verify its contents
			final EntityContract loadedProduct = getProductById(session, 1).orElseThrow();
			assertNotNull(loadedProduct);
			assertExactlyEquals(product.get(), loadedProduct);
			assertEquals(Boolean.TRUE, loadedProduct.getAttribute("aquaStop"));
			assertEquals(Byte.valueOf((byte) 60), loadedProduct.getAttribute("width"));
			assertNull(loadedProduct.getAttributeValue("waterConsumption").orElse(null));
			assertEquals(new LocalizedLabels().withLabel("name", "iQ700完全內置式洗碗機60厘米XXL"), loadedProduct.getAssociatedData("localizedLabels", Locale.CHINA, LocalizedLabels.class, REFLECTION_LOOKUP));
			assertEquals(new LocalizedLabels().withLabel("name", "iQ700 Plně vestavná myčka nádobí 60 cm XXL (ve slevě)"), loadedProduct.getAssociatedData("localizedLabels", LOCALE_CZECH, LocalizedLabels.class, REFLECTION_LOOKUP));
			assertNull(loadedProduct.getAssociatedData("localizedLabels", Locale.ENGLISH));
			assertEquals(new BigDecimal("499"), Objects.requireNonNull(loadedProduct.getPrice(3, "action", EUR)).orElseThrow().priceWithoutTax());
			assertEquals(new BigDecimal("899"), Objects.requireNonNull(loadedProduct.getPrice(2, "reference", EUR)).orElseThrow().priceWithoutTax());
			assertNull(loadedProduct.getPrice(1, "reference", CZK).orElse(null));
			final Collection<ReferenceContract> references = loadedProduct.getReferences(CATEGORY);
			assertEquals(2, references.size());
			for (ReferenceContract reference : references) {
				if (reference.getReferencedPrimaryKey() == 1) {
					final GroupEntityReference group = reference.getGroup().orElseThrow();

					assertEquals("CATEGORY_GROUP", reference.getReferenceSchemaOrThrow().getReferencedGroupType());
					assertEquals(Integer.valueOf(45), group.getPrimaryKey());
					assertEquals(Long.valueOf(20L), reference.getAttribute("priority"));
					assertNull(reference.getAttribute("visibleToCustomerTypes"));
				} else if (reference.getReferencedPrimaryKey() == 2) {
					// ok
				} else {
					fail("Unexpected referenced entity " + reference.getReferenceKey());
				}
			}
			assertTrue(loadedProduct.getReferences(BRAND).isEmpty());
			return null;
		});
	}

	@DisplayName("Entity can be altered by individual mutations avoiding builder entirely")
	@Test
	void shouldUpdateExistingEntityByHandPickedMutations(Evita evita) {
		// create new entity
		shouldCreateEntityWithExistingPrimaryKey(evita);
		// open new session
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// select existing entity
				final SealedEntity siemensBrand = getBrandById(session, 1).orElseThrow();
				// modify contents by delta mutation
				session.upsertEntity(
					siemensBrand.withMutations(
						new ApplyDeltaAttributeMutation<>("productCount", 5)
					)
				);
				// get entity by primary key and verify its contents
				assertBrandInSession(session, 1, Locale.ENGLISH, "siemens", SIEMENS_TITLE, LOGO, 6);
			}
		);

		assertBrand(evita, 1, Locale.ENGLISH, "siemens", SIEMENS_TITLE, LOGO, 6);
	}

	@DisplayName("When entity is enriched it should be refreshed with current data")
	@Test
	void shouldRefreshModifiedEntityOnEnrichment(Evita evita) {
		// create new entity
		final EntityContract productReference = createFullFeaturedEntity(evita);
		assertNotNull(productReference.getPrimaryKey());

		// open new session
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// select existing entity and fetch all data
				final SealedEntity createdEntity = session.getEntity(
					productReference.getType(), productReference.getPrimaryKey(), entityFetchAllContent()
				).orElseThrow();

				// now remove some associated data of this entity
				session.upsertEntity(
					createdEntity.withMutations(
						new RemoveAttributeMutation("width"),
						new RemoveAssociatedDataMutation("stockQuantity"),
						new RemoveAssociatedDataMutation("localizedLabels", LOCALE_CZECH),
						new RemoveReferenceMutation(BRAND, 1),
						new RemovePriceMutation(1, "basic", CZK)
					)
				);

				// select the entity again with all the data
				final SealedEntity updatedEntity = session.getEntity(
					productReference.getType(), productReference.getPrimaryKey(), entityFetchAllContent()
				).orElseThrow();

				// entity loaded after the mutations must be stripped of the removed data
				assertNull(updatedEntity.getAttribute("width"));
				assertFalse(updatedEntity.getAssociatedDataValue("stockQuantity").isPresent());
				assertFalse(updatedEntity.getAssociatedDataValue("localizedLabels", LOCALE_CZECH).isPresent());
				assertTrue(updatedEntity.getAssociatedDataValue("localizedLabels", Locale.ENGLISH).isPresent());
				assertFalse(updatedEntity.getReference(BRAND, 1).isPresent());
				assertFalse(updatedEntity.getPrice(1, "basic", CZK).isPresent());

				// enrich original entity that was partially fetched BEFORE the modifications
				final SealedEntity enrichedOriginalEntity = session.enrichEntity(
					createdEntity, associatedDataContentAll(), dataInLocalesAll()
				);

				// the enriched result reflects all the actual data even if it had all already fetched
				assertNull(enrichedOriginalEntity.getAttribute("width"));
				assertFalse(enrichedOriginalEntity.getAssociatedDataValue("stockQuantity").isPresent());
				assertFalse(enrichedOriginalEntity.getAssociatedDataValue("localizedLabels", LOCALE_CZECH).isPresent());
				assertTrue(enrichedOriginalEntity.getAssociatedDataValue("localizedLabels", Locale.ENGLISH).isPresent());
				assertFalse(enrichedOriginalEntity.getReference(BRAND, 1).isPresent());
				assertFalse(enrichedOriginalEntity.getPrice(1, "basic", CZK).isPresent());

				// the original reference of course does not
				assertNotNull(createdEntity.getAttribute("width"));
				assertTrue(createdEntity.getAssociatedDataValue("stockQuantity").isPresent());
				assertTrue(createdEntity.getAssociatedDataValue("localizedLabels", LOCALE_CZECH).isPresent());
				assertTrue(createdEntity.getAssociatedDataValue("localizedLabels", Locale.ENGLISH).isPresent());
				assertTrue(createdEntity.getReference(BRAND, 1).isPresent());
				assertTrue(createdEntity.getPrice(1, "basic", CZK).isPresent());
			}
		);
	}

	@DisplayName("When entity is enriched it should be refreshed with current data even in multiple steps")
	@Test
	void shouldRefreshModifiedEntityOnEnrichmentGradualScenario(Evita evita) {
		// create new entity
		final EntityContract productReference = createFullFeaturedEntity(evita);
		assertNotNull(productReference.getPrimaryKey());

		// open new session
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// select existing entity and fetch all data
				final SealedEntity createdEntity = session.getEntity(
					productReference.getType(), productReference.getPrimaryKey(), entityFetchAllContent()
				).orElseThrow();

				// now remove some associated data of this entity
				session.upsertEntity(
					createdEntity.withMutations(
						new RemoveAttributeMutation("width"),
						new RemoveReferenceMutation(BRAND, 1),
						new RemovePriceMutation(1, "basic", CZK)
					)
				);

				// select the entity again with all the data
				final SealedEntity updatedEntity = session.getEntity(
					productReference.getType(), productReference.getPrimaryKey(), entityFetchAllContent()
				).orElseThrow();

				// entity loaded after the mutations must be stripped of the removed data
				assertNull(updatedEntity.getAttribute("width"));
				assertNotNull(updatedEntity.getAssociatedDataValue("stockQuantity"));
				assertNotNull(updatedEntity.getAssociatedDataValue("localizedLabels", LOCALE_CZECH));
				assertNotNull(updatedEntity.getAssociatedDataValue("localizedLabels", Locale.ENGLISH));
				assertFalse(updatedEntity.getReference(BRAND, 1).isPresent());
				assertFalse(updatedEntity.getPrice(1, "basic", CZK).isPresent());

				// enrich original entity that was partially fetched BEFORE the modifications
				final SealedEntity enrichedOriginalEntity = session.enrichEntity(
					createdEntity, associatedDataContentAll(), dataInLocalesAll()
				);

				// the enriched result reflects all the actual data even if it had all already fetched
				assertNull(enrichedOriginalEntity.getAttribute("width"));
				assertNotNull(enrichedOriginalEntity.getAssociatedDataValue("stockQuantity"));
				assertNotNull(enrichedOriginalEntity.getAssociatedDataValue("localizedLabels", LOCALE_CZECH));
				assertNotNull(enrichedOriginalEntity.getAssociatedDataValue("localizedLabels", Locale.ENGLISH));
				assertFalse(enrichedOriginalEntity.getReference(BRAND, 1).isPresent());
				assertFalse(enrichedOriginalEntity.getPrice(1, "basic", CZK).isPresent());

				// the original reference of course does not
				assertNotNull(createdEntity.getAttribute("width"));
				assertNotNull(createdEntity.getAssociatedDataValue("stockQuantity"));
				assertNotNull(createdEntity.getAssociatedDataValue("localizedLabels", LOCALE_CZECH));
				assertNotNull(createdEntity.getAssociatedDataValue("localizedLabels", Locale.ENGLISH));
				assertNotNull(createdEntity.getReference(BRAND, 1));
				assertNotNull(createdEntity.getPrice(1, "basic", CZK));

				// now remove some associated data of this entity
				session.upsertEntity(
					createdEntity.withMutations(
						new RemoveAssociatedDataMutation("stockQuantity"),
						new RemoveAssociatedDataMutation("localizedLabels", LOCALE_CZECH)
					)
				);

				// select the entity again with all the data
				final SealedEntity updatedEntityAgain = session.getEntity(
					productReference.getType(), productReference.getPrimaryKey(), entityFetchAllContent()
				).orElseThrow();

				// entity loaded after the mutations must be stripped of the removed data
				assertNull(updatedEntityAgain.getAttribute("width"));
				assertFalse(updatedEntityAgain.getAssociatedDataValue("stockQuantity").isPresent());
				assertFalse(updatedEntityAgain.getAssociatedDataValue("localizedLabels", LOCALE_CZECH).isPresent());
				assertTrue(updatedEntityAgain.getAssociatedDataValue("localizedLabels", Locale.ENGLISH).isPresent());
				assertFalse(updatedEntityAgain.getReference(BRAND, 1).isPresent());
				assertFalse(updatedEntityAgain.getPrice(1, "basic", CZK).isPresent());

				// enrich original entity that was partially fetched BEFORE the modifications
				final SealedEntity enrichedOriginalEntityAgain = session.enrichEntity(
					createdEntity, associatedDataContentAll(), dataInLocalesAll()
				);

				// the enriched result reflects all the actual data even if it had all already fetched
				assertNull(enrichedOriginalEntityAgain.getAttribute("width"));
				assertFalse(enrichedOriginalEntityAgain.getAssociatedDataValue("stockQuantity").isPresent());
				assertFalse(enrichedOriginalEntityAgain.getAssociatedDataValue("localizedLabels", LOCALE_CZECH).isPresent());
				assertTrue(enrichedOriginalEntityAgain.getAssociatedDataValue("localizedLabels", Locale.ENGLISH).isPresent());
				assertFalse(enrichedOriginalEntityAgain.getReference(BRAND, 1).isPresent());
				assertFalse(enrichedOriginalEntityAgain.getPrice(1, "basic", CZK).isPresent());

				// the original reference of course does not
				assertNotNull(createdEntity.getAttribute("width"));
				assertTrue(createdEntity.getAssociatedDataValue("stockQuantity").isPresent());
				assertTrue(createdEntity.getAssociatedDataValue("localizedLabels", LOCALE_CZECH).isPresent());
				assertTrue(createdEntity.getAssociatedDataValue("localizedLabels", Locale.ENGLISH).isPresent());
				assertTrue(createdEntity.getReference(BRAND, 1).isPresent());
				assertTrue(createdEntity.getPrice(1, "basic", CZK).isPresent());
			}
		);
	}

	@DisplayName("When entity is enriched, and it's removed in the meanwhile, exception should be thrown")
	@Test
	void shouldFailToEnrichAlreadyRemovedEntity(Evita evita) {
		// create new entity
		final EntityContract productReference = createFullFeaturedEntity(evita);
		assertNotNull(productReference.getPrimaryKey());

		// open new session
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// select existing entity and fetch all data
				final SealedEntity createdEntity = session.getEntity(
					productReference.getType(), productReference.getPrimaryKey(), entityFetchAllContent()
				).orElseThrow();

				// now blindly remove some associated data of this entity
				session.deleteEntity(
					productReference.getType(), productReference.getPrimaryKey()
				);

				// selection of the entity must return null
				assertFalse(
					session.getEntity(
						productReference.getType(), productReference.getPrimaryKey(), entityFetchAllContent()
					).isPresent()
				);

				// and enriching it must throw an exception
				assertThrows(
					EntityAlreadyRemovedException.class,
					() -> session.enrichEntity(
						createdEntity, associatedDataContentAll(), dataInLocalesAll()
					)
				);

			}
		);
	}

	@DisplayName("Example schema definition can be inserted into evitaDB")
	@Test
	void shouldDefineStrictSchema(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(CATEGORY);
				session.defineEntitySchema(BRAND);

				session.defineEntitySchema(PRODUCT)
					/* all is strictly verified but associated data and references can be added on the fly */
					.verifySchemaButAllow(EvolutionMode.ADDING_ASSOCIATED_DATA, EvolutionMode.ADDING_REFERENCES)
					/* product are not organized in the tree */
					.withoutHierarchy()
					/* prices are referencing another entity stored in Evita */
					.withPrice()
					/* en + cs localized attributes and associated data are allowed only */
					.withLocale(Locale.ENGLISH, new Locale("cs", "CZ"))
					/* here we define list of attributes with indexes for search / sort */
					.withAttribute("code", String.class, whichIs -> whichIs.unique())
					.withAttribute("url", String.class, whichIs -> whichIs.unique().localized())
					.withAttribute("oldEntityUrls", String[].class, whichIs -> whichIs.filterable().localized())
					.withAttribute("name", String.class, whichIs -> whichIs.filterable().sortable())
					.withAttribute("ean", String.class, whichIs -> whichIs.filterable())
					.withAttribute("priority", Long.class, whichIs -> whichIs.sortable())
					.withAttribute("validity", DateTimeRange.class, whichIs -> whichIs.filterable())
					.withAttribute("quantity", BigDecimal.class, whichIs -> whichIs.filterable().indexDecimalPlaces(2))
					.withAttribute("alias", Boolean.class, whichIs -> whichIs.filterable())
					/* here we define set of associated data, that can be stored along with entity */
					//.withAssociatedData("referencedFiles", ReferencedFileSet.class)
					//.withAssociatedData("labels", Labels.class, whichIs -> whichIs.localized())
					/* here we define references that relate to another entities stored in Evita */
					.withReferenceToEntity(
						CATEGORY,
						CATEGORY,
						Cardinality.ZERO_OR_MORE,
						whichIs ->
							/* we can specify special attributes on relation */
							whichIs.indexedForFilteringAndPartitioning()
								.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
					)
					/* for faceted references we can compute "counts" */
					.withReferenceToEntity(
						BRAND,
						BRAND,
						Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs.faceted())
					/* references may be also represented be entities unknown to Evita */
					.withReferenceTo(
						"stock",
						"stock",
						Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.faceted()
					)
					/* finally apply schema changes */
					.updateVia(session);
			}
		);
	}

	@DisplayName("evitaDB tracks open sessions so that they can be closed on evitaDB close")
	@Test
	void shouldTrackAndFreeOpenSessions(Evita evita) throws Exception {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(createBrand(session, null));
			}
		);

		final int numberOfThreads = 4;
		final int iterations = 10;
		final ExecutorService service = Executors.newFixedThreadPool(numberOfThreads);
		final CountDownLatch latch = new CountDownLatch(numberOfThreads);
		final AtomicInteger peek = new AtomicInteger();

		final AtomicReference<Exception> terminatingException = new AtomicReference<>();
		for (int i = 0; i < numberOfThreads; i++) {
			service.execute(() -> {
				try {
					for (int j = 0; j < iterations; j++) {
						evita.updateCatalog(
							TEST_CATALOG,
							session -> {
								// this is kind of unsafe, but it should work
								final int activeSessions = Math.toIntExact(evita.getActiveSessions().count());
								if (peek.get() < activeSessions) {
									peek.set(activeSessions);
								}
								session.upsertEntity(createBrand(session, null));
							}
						);
					}
					latch.countDown();
				} catch (Exception ex) {
					terminatingException.set(ex);
					latch.countDown();
				}
			});
		}

		assertTrue(latch.await(450, TimeUnit.SECONDS), "Timeouted!");

		if (terminatingException.get() != null) {
			throw terminatingException.get();
		}

		assertTrue(peek.get() > 1, "There should be multiple session in parallel!");
		assertEquals(0L, evita.getActiveSessions().count(), "There should be no active session now!");
	}

	@DisplayName("Delete entity with all possible data")
	@Test
	void shouldDeleteFullFeaturedEntity(Evita evita) {
		final EntityContract productReference = createFullFeaturedEntity(evita);

		final int primaryKey = Objects.requireNonNull(productReference.getPrimaryKey());
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// verify entity existence
				assertNotNull(getProductById(session, primaryKey).orElse(null));

				// delete the entity
				final boolean wasDeleted = session.deleteEntity(productReference.getType(), productReference.getPrimaryKey());
				assertTrue(wasDeleted);

				// verify entity is deleted
				assertNull(getProductById(session, productReference.getPrimaryKey()).orElse(null));
			}
		);

		// verify entity is deleted in another sessions
		evita.queryCatalog(TEST_CATALOG, session -> {
			assertNull(getProductById(session, primaryKey).orElse(null));
		});
	}

	@DisplayName("Delete entity with all possible data and returning it")
	@Test
	void shouldDeleteFullFeaturedEntityAndRetrieveItsBody(Evita evita) {
		final EntityContract productReference = createFullFeaturedEntity(evita);

		final int primaryKey = Objects.requireNonNull(productReference.getPrimaryKey());
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// verify entity existence
				final SealedEntity productToDelete = getProductById(session, primaryKey)
					.orElseThrow();

				// delete the entity
				final SealedEntity deletedProduct = session.deleteEntity(productReference.getType(), productReference.getPrimaryKey(), entityFetchAllContent())
					.orElseThrow();

				// verify entity is deleted
				assertTrue(getProductById(session, productReference.getPrimaryKey()).isEmpty());
				assertExactlyEquals(productToDelete, deletedProduct);
			}
		);

		// verify entity is deleted in another sessions
		evita.queryCatalog(TEST_CATALOG, session -> {
			assertNull(getProductById(session, primaryKey).orElse(null));
		});
	}

	@DisplayName("Delete entity containing indexes")
	@Test
	void shouldDeleteEntityWithIndexes(Evita evita) {
		// define schema with indexes
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT)
					.withAttribute("code", String.class, whichIs -> whichIs.unique())
					.updateVia(session);
			}
		);

		// create new entity with indexes
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(
					new InitialEntityBuilder(PRODUCT, 1)
						.setAttribute("code", "phone")
				);
			}
		);

		// delete entity
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// verify entity existence
				assertNotNull(getProductById(session, 1));

				// delete the entity
				final boolean wasDeleted = session.deleteEntity(PRODUCT, 1);
				assertTrue(wasDeleted);

				// verify entity is deleted
				assertNull(getProductById(session, 1).orElse(null));
			}
		);

		// verify entity is deleted in another sessions
		evita.queryCatalog(TEST_CATALOG, session -> {
			assertNull(getProductById(session, 1).orElse(null));
		});
	}

	@DisplayName("Don't delete non-existent entity")
	@Test
	void shouldNotDeleteNonExistentEntity(Evita evita) {
		// define schema with indexes
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT)
					.withAttribute("code", String.class, whichIs -> whichIs.unique())
					.updateVia(session);
			}
		);

		// create other entity
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(
					new InitialEntityBuilder(PRODUCT, 1)
						.setAttribute("code", "phone")
				);
			}
		);

		// delete entity
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// verify entity doesn't exist
				assertNull(getProductById(session, 10).orElse(null));

				// delete the entity
				final boolean wasDeleted = session.deleteEntity(PRODUCT, 10);
				assertFalse(wasDeleted);
			}
		);
	}

	@DisplayName("Delete hierarchical entity with its sub entities")
	@Test
	void shouldDeleteHierarchicalEntityWithItsSubEntities(Evita evita) {
		// create small entity tree
		createSmallHierarchy(evita);

		// delete whole entity tree
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// verify root entity existence
				assertNotNull(getCategoryById(session, 1));

				// delete the root entity
				final int deletedEntitiesCount = session.deleteEntityAndItsHierarchy(CATEGORY, 1);
				assertEquals(3, deletedEntitiesCount);

				// verify all entities are deleted
				assertNull(getCategoryById(session, 1).orElse(null));
				assertNull(getCategoryById(session, 2).orElse(null));
				assertNull(getCategoryById(session, 3).orElse(null));
			}
		);

		// verify whole entity tree is deleted in another sessions
		evita.queryCatalog(TEST_CATALOG, session -> {
			assertNull(getCategoryById(session, 1).orElse(null));
			assertNull(getCategoryById(session, 2).orElse(null));
			assertNull(getCategoryById(session, 3).orElse(null));
		});
	}

	@DisplayName("Delete hierarchical entity with its sub entities and return body of root entity")
	@Test
	void shouldDeleteHierarchicalEntityWithItsSubEntitiesAndReturnRootEntityBody(Evita evita) {
		// create small entity tree
		createSmallHierarchy(evita);

		// delete whole entity tree
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// verify root entity existence
				final SealedEntity removedCategory = getCategoryById(session, 1).orElseThrow();

				// delete the root entity
				final DeletedHierarchy<SealedEntity> deletionResult = session.deleteEntityAndItsHierarchy(CATEGORY, 1, entityFetchAllContent());
				assertEquals(3, deletionResult.deletedEntities());
				assertExactlyEquals(removedCategory, deletionResult.deletedRootEntity());

				// verify all entities are deleted
				assertNull(getCategoryById(session, 1).orElse(null));
				assertNull(getCategoryById(session, 2).orElse(null));
				assertNull(getCategoryById(session, 3).orElse(null));
			}
		);
	}

	@DisplayName("Delete hierarchical entity with its sub entities with indexes")
	@Test
	void shouldDeleteHierarchicalEntityWithItsSubEntitiesWithIndexes(Evita evita) {
		// define schema with indexes
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(CATEGORY)
					.withHierarchy()
					.withAttribute("code", String.class, whichIs -> whichIs.unique())
					.verifySchemaButAllow(EvolutionMode.ADDING_ATTRIBUTES, EvolutionMode.ADDING_LOCALES)
					.updateVia(session);
			}
		);

		// create small entity tree
		createSmallHierarchy(evita);

		// delete whole entity tree
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// verify root entity existence
				assertNotNull(getCategoryById(session, 1));

				// delete the root entity
				final int deletedEntitiesCount = session.deleteEntityAndItsHierarchy(CATEGORY, 1);
				assertEquals(3, deletedEntitiesCount);

				// verify all entities are deleted
				assertNull(getCategoryById(session, 1).orElse(null));
				assertNull(getCategoryById(session, 2).orElse(null));
				assertNull(getCategoryById(session, 3).orElse(null));
			}
		);

		// verify whole entity tree is deleted in another sessions
		evita.queryCatalog(TEST_CATALOG, session -> {
			assertNull(getCategoryById(session, 1).orElse(null));
			assertNull(getCategoryById(session, 2).orElse(null));
			assertNull(getCategoryById(session, 3).orElse(null));
		});
	}

	@DisplayName("Don't delete non-existent hierarchical entity")
	@Test
	void shouldNotDeleteNonExistentHierarchicalEntity(Evita evita) {
		// define schema with indexes
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(CATEGORY)
					.withHierarchy()
					.withAttribute("code", String.class, whichIs -> whichIs.unique())
					.verifySchemaButAllow(EvolutionMode.ADDING_ATTRIBUTES, EvolutionMode.ADDING_LOCALES)
					.updateVia(session);
			}
		);

		// create other entity
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityBuilder category = createCategory(session, 1);
				session.upsertEntity(category);
				assertNotNull(getCategoryById(session, 1));
			}
		);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// verify entity doesn't exist
				assertNull(getCategoryById(session, 10).orElse(null));

				// delete non-existent entities
				final int deletedEntitiesCount = session.deleteEntityAndItsHierarchy(CATEGORY, 10);
				assertEquals(0, deletedEntitiesCount);
			}
		);
	}

	@DisplayName("Delete entity by query")
	@Test
	void shouldDeleteEntityByQuery(Evita evita) {
		// create small entity tree
		createSmallHierarchy(evita);

		// delete whole entity tree
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// verify root entity existence
				assertNotNull(getCategoryById(session, 1));

				// delete the root entity
				final int deletedEntitiesCount = session.deleteEntities(
					query(
						collection(CATEGORY),
						filterBy(
							hierarchyWithinRootSelf()
						)
					)
				);
				assertEquals(3, deletedEntitiesCount);

				// verify all entities are deleted
				assertNull(getCategoryById(session, 1).orElse(null));
				assertNull(getCategoryById(session, 2).orElse(null));
				assertNull(getCategoryById(session, 3).orElse(null));
			}
		);

		// verify whole entity tree is deleted in another sessions
		evita.queryCatalog(TEST_CATALOG, session -> {
			assertNull(getCategoryById(session, 1).orElse(null));
			assertNull(getCategoryById(session, 2).orElse(null));
			assertNull(getCategoryById(session, 3).orElse(null));
		});
	}

	@DisplayName("Delete entity by query and return bodies")
	@Test
	void shouldDeleteEntityByQueryAndReturnBodies(Evita evita) {
		// create small entity tree
		createSmallHierarchy(evita);

		// delete whole entity tree
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// verify root entity existence
				final Map<Integer, SealedEntity> existingEntities = Stream.of(
						getCategoryById(session, 1).orElseThrow(),
						getCategoryById(session, 2).orElseThrow(),
						getCategoryById(session, 3).orElseThrow()
					)
					.collect(
						Collectors.toMap(
							EntityContract::getPrimaryKey,
							Function.identity()
						)
					);

				assertEquals(3, existingEntities.size());

				// delete the root entity
				final SealedEntity[] deletedEntities = session.deleteSealedEntitiesAndReturnBodies(
					query(
						collection(CATEGORY),
						filterBy(
							hierarchyWithinRootSelf()
						),
						require(entityFetchAll())
					)
				);
				assertEquals(3, deletedEntities.length);
				for (SealedEntity deletedEntity : deletedEntities) {
					final SealedEntity originalEntity = existingEntities.get(deletedEntity.getPrimaryKey());
					assertExactlyEquals(originalEntity, deletedEntity);
				}

				// verify all entities are deleted
				assertNull(getCategoryById(session, 1).orElse(null));
				assertNull(getCategoryById(session, 2).orElse(null));
				assertNull(getCategoryById(session, 3).orElse(null));
			}
		);

		// verify whole entity tree is deleted in another sessions
		evita.queryCatalog(TEST_CATALOG, session -> {
			assertNull(getCategoryById(session, 1).orElse(null));
			assertNull(getCategoryById(session, 2).orElse(null));
			assertNull(getCategoryById(session, 3).orElse(null));
		});
	}

	@DisplayName("Delete entity by query with indexes")
	@Test
	void shouldDeleteEntityByQueryWithIndexes(Evita evita) {
		// define schema with indexes
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(CATEGORY)
					.withHierarchy()
					.withAttribute("code", String.class, whichIs -> whichIs.unique())
					.verifySchemaButAllow(EvolutionMode.ADDING_ATTRIBUTES, EvolutionMode.ADDING_LOCALES)
					.updateVia(session);
			}
		);

		// create small entity tree
		createSmallHierarchy(evita);

		// delete whole entity tree
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// verify root entity existence
				assertNotNull(getCategoryById(session, 1));

				// delete the root entity
				final int deletedEntitiesCount = session.deleteEntities(
					query(
						collection(CATEGORY),
						filterBy(
							hierarchyWithinRootSelf()
						)
					)
				);
				assertEquals(3, deletedEntitiesCount);

				// verify all entities are deleted
				assertNull(getCategoryById(session, 1).orElse(null));
				assertNull(getCategoryById(session, 2).orElse(null));
				assertNull(getCategoryById(session, 3).orElse(null));
			}
		);

		// verify whole entity tree is deleted in another sessions
		evita.queryCatalog(TEST_CATALOG, session -> {
			assertNull(getCategoryById(session, 1).orElse(null));
			assertNull(getCategoryById(session, 2).orElse(null));
			assertNull(getCategoryById(session, 3).orElse(null));
		});
	}

	@DisplayName("Don't delete non-existent entity by query")
	@Test
	void shouldNotDeleteNonExistentEntityByQuery(Evita evita) {
		// define schema with indexes
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(CATEGORY)
					.withHierarchy()
					.withAttribute("code", String.class, whichIs -> whichIs.unique())
					.verifySchemaButAllow(EvolutionMode.ADDING_ATTRIBUTES, EvolutionMode.ADDING_LOCALES)
					.updateVia(session);
			}
		);

		// create other entity
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityBuilder category = createCategory(session, 1);
				session.upsertEntity(category);
				assertNotNull(getCategoryById(session, 1));
			}
		);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// verify entity does not exist
				assertNull(getCategoryById(session, 10).orElse(null));

				// delete non-existent entities
				final int deletedEntitiesCount = session.deleteEntities(
					query(
						collection(CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(10)
						)
					)
				);
				assertEquals(0, deletedEntitiesCount);
			}
		);
	}

	@DisplayName("Delete multiple entities in different sessions")
	@Test
	void shouldDeleteMutlipleEntitiesInDifferentSessions(Evita evita) {
		// define schema with indexes
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(CATEGORY)
					.withHierarchy()
					.withAttribute("code", String.class, whichIs -> whichIs.unique())
					.verifySchemaButAllow(EvolutionMode.ADDING_ATTRIBUTES, EvolutionMode.ADDING_LOCALES)
					.updateVia(session);
			}
		);

		// create small entity tree
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityBuilder category = createCategory(session, 1);
				session.upsertEntity(category);
				assertNotNull(getCategoryById(session, 1));

				final EntityBuilder subCategory1 = createSubCategory(session, 2, 1);
				session.upsertEntity(subCategory1);
				assertNotNull(getCategoryById(session, 2));

				final EntityBuilder subCategory2 = createSubCategory(session, 3, 2);
				session.upsertEntity(subCategory2);
				assertNotNull(getCategoryById(session, 3));
			}
		);

		// delete one entity
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// verify entity existence
				assertNotNull(getCategoryById(session, 3));

				// delete entity
				final boolean wasDeleted = session.deleteEntity(CATEGORY, 3);
				assertTrue(wasDeleted);

				// verify entity is deleted
				assertNull(getCategoryById(session, 3).orElse(null));
			}
		);

		// delete second entity
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// verify entity existence
				assertNotNull(getCategoryById(session, 2));

				// delete entity
				final int deletedEntitiesCount = session.deleteEntityAndItsHierarchy(CATEGORY, 2);
				assertEquals(1, deletedEntitiesCount);

				// verify entity is deleted
				assertNull(getCategoryById(session, 2).orElse(null));
			}
		);

		// delete last entity
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// verify root entity existence
				assertNotNull(getCategoryById(session, 1));

				// delete the root entity
				final int deletedEntitiesCount = session.deleteEntities(
					query(
						collection(CATEGORY),
						filterBy(
							hierarchyWithinRootSelf()
						)
					)
				);
				assertEquals(1, deletedEntitiesCount);

				// verify all entities are deleted
				assertNull(getCategoryById(session, 1).orElse(null));
			}
		);

		// verify whole entity tree is deleted in another sessions
		evita.queryCatalog(TEST_CATALOG, session -> {
			assertNull(getCategoryById(session, 1).orElse(null));
			assertNull(getCategoryById(session, 2).orElse(null));
			assertNull(getCategoryById(session, 3).orElse(null));
		});
	}

	/*
		HELPER METHODS
	 */

	private EntityBuilder createBrand(@Nonnull EvitaSessionContract session, @Nullable Integer primaryKey) {
		final EntityBuilder newBrand = primaryKey == null ?
			session.createNewEntity(BRAND) : session.createNewEntity(BRAND, primaryKey);
		newBrand.setAttribute("code", "siemens");
		newBrand.setAttribute("name", Locale.ENGLISH, SIEMENS_TITLE);
		newBrand.setAttribute("logo", LOGO);
		newBrand.setAttribute("productCount", 1);
		return newBrand;
	}

	private EntityBuilder createCategory(EvitaSessionContract session, Integer primaryKey) {
		final EntityBuilder newCategory = session.createNewEntity(CATEGORY, primaryKey);
		newCategory.setAttribute("code", "builtin-dishwashers");
		newCategory.setAttribute("name", LOCALE_CZECH, "Vestavné myčky nádobí");
		newCategory.setAttribute("name", Locale.ENGLISH, "Built-in dishwashers");
		newCategory.setAttribute("active", createDateRange(2020, 2021));
		newCategory.setAttribute("priority", 456L);
		newCategory.setAttribute("visible", true);
		return newCategory;
	}

	private EntityBuilder createSubCategory(EvitaSessionContract session, Integer primaryKey, Integer parentPrimaryKey) {
		final EntityBuilder newCategory = session.createNewEntity(CATEGORY, primaryKey);
		newCategory.setAttribute("code", "builtin-dishwashers" + primaryKey);
		newCategory.setAttribute("name", LOCALE_CZECH, "Vestavné myčky nádobí");
		newCategory.setAttribute("name", Locale.ENGLISH, "Built-in dishwashers");
		newCategory.setAttribute("active", createDateRange(2020, 2021));
		newCategory.setAttribute("priority", 456L);
		newCategory.setAttribute("visible", true);
		newCategory.setParent(parentPrimaryKey);
		return newCategory;
	}

	private EntityBuilder createProduct(EvitaSessionContract session, int primaryKey, EntityReferenceContract brand, EntityReferenceContract category) {
		final EntityBuilder newProduct = session.createNewEntity(PRODUCT, primaryKey);
		newProduct.setAttribute("code", "SX87Y800BE");
		newProduct.setAttribute("name", LOCALE_CZECH, "iQ700 Plně vestavná myčka nádobí 60 cm XXL");
		newProduct.setAttribute("name", Locale.ENGLISH, "iQ700 Fully built-in dishwasher 60 cm XXL");
		newProduct.setAttribute("temperatures", IntegerNumberRange.between(45, 70));
		newProduct.setAttribute("visible", true);
		newProduct.setAttribute("type", new String[]{ProductType.HOME, ProductType.INDUSTRIAL});
		newProduct.setAttribute("noise", 'B');
		newProduct.setAttribute("width", (byte) 59);
		newProduct.setAttribute("height", (short) 86);
		newProduct.setAttribute("waterConsumption", new BigDecimal("9.5"));
		newProduct.setAttribute("energyConsumption", 64L);
		newProduct.setAttribute("producedTime", LocalTime.of(17, 45, 11));
		newProduct.setAttribute("producedDate", LocalDate.of(2021, 2, 1));
		newProduct.setAttribute("qaPassed", LocalDateTime.of(2021, 2, 2, 0, 15, 45));
		newProduct.setAttribute("arrivedAtCZ", OffsetDateTime.of(2021, 2, 2, 0, 15, 45, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now())));

		newProduct.setReference(
			BRAND,
			BRAND,
			Cardinality.ZERO_OR_ONE,
			Objects.requireNonNull(brand.getPrimaryKey()),
			with -> {
				with.setGroup("BRAND_GROUP", 89);
				with.setAttribute("distributor", "Siemens GmBH");
			}
		);
		newProduct.setReference(
			CATEGORY,
			CATEGORY,
			Cardinality.ZERO_OR_MORE,
			Objects.requireNonNull(category.getPrimaryKey()),
			whichIs -> {
				whichIs.setAttribute("priority", 15L);
				whichIs.setAttribute("visibleToCustomerTypes", new Integer[]{1, 8, 9});
			}
		);

		newProduct.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
		newProduct.setPrice(1, "basic", CZK, new BigDecimal("15000"), new BigDecimal("21"), new BigDecimal("18150"), true);
		newProduct.setPrice(2, "basic", EUR, new BigDecimal("555.5"), new BigDecimal("21"), new BigDecimal("672.16"), true);
		newProduct.setPrice(1, "reference", CZK, new BigDecimal("20000"), new BigDecimal("21"), new BigDecimal("24200"), false);
		newProduct.setPrice(2, "reference", EUR, new BigDecimal("799"), new BigDecimal("21"), new BigDecimal("966.79"), false);

		newProduct.setAssociatedData("localizedLabels", LOCALE_CZECH, new LocalizedLabels().withLabel("name", "iQ700 Plně vestavná myčka nádobí 60 cm XXL"));
		newProduct.setAssociatedData("localizedLabels", Locale.ENGLISH, new LocalizedLabels().withLabel("name", "iQ700 Fully built-in dishwasher 60 cm XXL"));
		newProduct.setAssociatedData("stockQuantity", new StockQuantity().withQuantity(1, new BigDecimal("124")).withQuantity(2, new BigDecimal("783")));

		return newProduct;
	}

	private EntityBuilder updateProduct(SealedEntity createdEntity, EntityReferenceContract newCategory) {
		return createdEntity.openForWrite()
			// add new
			.setAttribute("aquaStop", true)
			// update existing
			.setAttribute("width", (byte) 60)
			// remove existing
			.removeAttribute("waterConsumption")
			// add new
			.setAssociatedData("localizedLabels", Locale.CHINA, new LocalizedLabels().withLabel("name", "iQ700完全內置式洗碗機60厘米XXL"))
			// update existing
			.setAssociatedData("localizedLabels", LOCALE_CZECH, new LocalizedLabels().withLabel("name", "iQ700 Plně vestavná myčka nádobí 60 cm XXL (ve slevě)"))
			// remove existing
			.removeAssociatedData("localizedLabels", Locale.ENGLISH)
			// add new
			.setPrice(3, "action", EUR, new BigDecimal("499"), new BigDecimal("21"), new BigDecimal("603.79"), true)
			// update existing
			.setPrice(2, "reference", EUR, new BigDecimal("899"), new BigDecimal("21"), new BigDecimal("1087.79"), false)
			// remove existing
			.removePrice(1, "reference", CZK)
			// add new
			.setReference(newCategory.getType(), newCategory.getPrimaryKey())
			// update existing
			.setReference(
				CATEGORY, 1,
				thatIs -> {
					thatIs.setGroup("CATEGORY_GROUP", 45);
					thatIs.setAttribute("priority", 20L);
					thatIs.removeAttribute("visibleToCustomerTypes");
				})
			// remove existing
			.removeReference(
				BRAND, 1
			);
	}

	@Nonnull
	private EntityContract createFullFeaturedEntity(@Nonnull Evita evita) {
		final AtomicReference<EntityContract> product = new AtomicReference<>();
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// create referenced entities
				final EntityReferenceContract brand = session.upsertEntity(createBrand(session, 1));
				final EntityReferenceContract category = session.upsertEntity(createCategory(session, 1));
				// create full-featured entity
				final EntityReferenceContract productRef = session.upsertEntity(createProduct(session, 1, brand, category));
				product.set(
					session.getEntity(productRef.getType(), productRef.getPrimaryKey(), entityFetchAllContent())
						.orElseThrow()
				);
			}
		);
		return product.get();
	}

	@Nonnull
	private DateTimeRange createDateRange(int yearFrom, int yearTo) {
		return DateTimeRange.between(
			LocalDateTime.of(yearFrom, 1, 1, 0, 0, 0),
			LocalDateTime.of(yearTo, 1, 1, 0, 0, 0),
			ZoneOffset.UTC
		);
	}

	private Optional<SealedEntity> getBrandById(EvitaSessionContract session, int primaryKey) {
		return session.queryOneSealedEntity(
			query(
				collection(BRAND),
				filterBy(entityPrimaryKeyInSet(primaryKey)),
				require(entityFetch(attributeContentAll(), dataInLocalesAll()))
			)
		);
	}

	private Optional<SealedEntity> getProductById(EvitaSessionContract session, int primaryKey) {
		return session.queryOneSealedEntity(
			query(
				collection(PRODUCT),
				filterBy(entityPrimaryKeyInSet(primaryKey)),
				require(entityFetchAll())
			)
		);
	}

	private Optional<SealedEntity> getCategoryById(EvitaSessionContract session, int primaryKey) {
		return session.queryOneSealedEntity(
			query(
				collection(CATEGORY),
				filterBy(entityPrimaryKeyInSet(primaryKey)),
				require(entityFetchAll())
			)
		);
	}

	private void createSmallHierarchy(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityBuilder category = createCategory(session, 1);
				session.upsertEntity(category);
				assertNotNull(getCategoryById(session, 1));

				final EntityBuilder subCategory1 = createSubCategory(session, 2, 1);
				session.upsertEntity(subCategory1);
				assertNotNull(getCategoryById(session, 2));

				final EntityBuilder subCategory2 = createSubCategory(session, 3, 2);
				session.upsertEntity(subCategory2);
				assertNotNull(getCategoryById(session, 3));
			}
		);
	}

	private void assertBrand(Evita evita, int primaryKey, Locale locale, String code, String name, String logo, int productCount) {
		evita.queryCatalog(TEST_CATALOG, session -> {
			assertBrandInSession(session, primaryKey, locale, code, name, logo, productCount);
			return null;
		});
	}

	private void assertBrandInSession(EvitaSessionContract session, int primaryKey, Locale locale, String code, String name, String logo, int productCount) {
		final EvitaResponse<SealedEntity> response = session.query(
			query(
				collection(BRAND),
				filterBy(
					and(
						entityPrimaryKeyInSet(primaryKey),
						entityLocaleEquals(locale)
					)
				),
				require(
					entityFetch(
						attributeContentAll()
					)
				)
			),
			SealedEntity.class
		);
		assertEquals(1, response.getRecordData().size());
		final SealedEntity brand = response.getRecordData().get(0);

		assertEquals(code, brand.getAttribute("code", locale));
		assertEquals(name, brand.getAttribute("name", locale));
		assertEquals(logo, brand.getAttribute("logo", locale));
		assertEquals(productCount, (Integer) brand.getAttribute("productCount", locale));
	}

	private interface ProductType {

		String INDUSTRIAL = "Industrial";
		String HOME = "Home";

	}

	@RequiredArgsConstructor
	@EqualsAndHashCode
	@ToString
	public static class LocalizedLabels implements Serializable {
		@Serial private static final long serialVersionUID = -608008423373881676L;
		@Getter private final Map<String, String> labels;

		public LocalizedLabels() {
			this.labels = new HashMap<>();
		}

		public void addLabel(String code, String label) {
			this.labels.put(code, label);
		}

		public String getLabel(String code) {
			return this.labels.get(code);
		}

		public LocalizedLabels withLabel(String code, String label) {
			addLabel(code, label);
			return this;
		}
	}

	@RequiredArgsConstructor
	@EqualsAndHashCode
	@ToString
	public static class StockQuantity implements Serializable {
		@Serial private static final long serialVersionUID = 3168900957425721663L;
		@Getter private final Map<Integer, BigDecimal> stockQuantity;

		public StockQuantity() {
			this.stockQuantity = new HashMap<>();
		}

		public void setStockQuantity(int stockId, BigDecimal quantity) {
			this.stockQuantity.put(stockId, quantity);
		}

		public BigDecimal getStockQuantity(int stockId) {
			return this.stockQuantity.get(stockId);
		}

		public StockQuantity withQuantity(int stockId, BigDecimal quantity) {
			setStockQuantity(stockId, quantity);
			return this;
		}
	}

}
