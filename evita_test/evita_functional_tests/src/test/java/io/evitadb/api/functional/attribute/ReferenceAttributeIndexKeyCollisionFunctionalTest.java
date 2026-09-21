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

package io.evitadb.api.functional.attribute;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import io.evitadb.dataType.BigDecimalNumberRange;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceContentAllWithAttributes;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers a `BigDecimal` reference attribute whose declared `indexDecimalPlaces` is coarser than the values
 * actually stored, so that several distinct values share one index key.
 *
 * A reference-type index does not hold one bucket entry per owner: it holds one entry per
 * (value key, reduced index) pair, and keeps an `AttributeCardinalityIndex` beside the value tree that counts
 * how many owners contribute it, so that only the removal which takes the count to zero also takes the entry
 * away. The count and the entry are therefore keyed by two different canonicalizations of the same value —
 * the counter by the normalized `BigDecimal`, the entry by the order-preserving `int` that
 * `NumberUtils#convertToInt` rounds it to at the schema's scale.
 *
 * When the schema's scale is coarser than the values, that mapping is many-to-one: `1.2` and `1.4` round to
 * the same key `1` while remaining two distinct counter keys. The first owner to move away then drains *its*
 * counter to zero and removes the shared entry that the other owner still relies on — silently, because the
 * removal is perfectly well-formed from the counter's point of view. The damage surfaces later, on the
 * surviving owner's next write, as the removal path's precondition failing on an entry that is already gone.
 *
 * Both halves are asserted separately on purpose: the query assertion pins the moment the index stops
 * describing the data, and the write assertion pins the exception that a client actually observes. A fix that
 * only silenced the exception would leave the first assertion failing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference attribute values that round to one index key must not share a single removal")
@Tag(ENGINE)
@Tag(REFERENCE)
@Tag(ATTRIBUTE)
public class ReferenceAttributeIndexKeyCollisionFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_STOCK = "stock";
	private static final String REFERENCE_STOCKS = "stocks";
	private static final String ATTRIBUTE_QUANTITY = "quantityOnStock";
	private static final String ATTRIBUTE_LABEL = "stockLabel";
	/** An ENTITY-level array attribute — no reference involved, so it lands on the global entity index. */
	private static final String ATTRIBUTE_LABELS = "productLabels";
	private static final String ATTRIBUTE_QUANTITIES = "quantitiesOnStock";
	private static final String ATTRIBUTE_STOCKED_AT = "stockedAt";
	private static final String ATTRIBUTE_VALID_RANGE = "stockValidRange";
	private static final String ATTRIBUTE_CURRENCY = "stockCurrency";
	private static final String ENTITY_STOCK_GROUP = "stockGroup";
	private static final int SHARED_STOCK_GROUP_PK = 100;
	private static final int GROUPED_OWNER_PK = 10;
	private static final int FIRST_GROUPED_STOCK_PK = 11;
	private static final int SECOND_GROUPED_STOCK_PK = 12;
	/**
	 * One instant written two ways. The index key is the {@link java.time.Instant} the value anchors to, so the
	 * offset is discarded entirely \u2014 but `OffsetDateTime#equals` compares the offset, so these remain two distinct
	 * counter keys. The offset is the axis on which a temporal attribute collides: nothing canonicalizes it on
	 * the way in, while the index normalizer discards it on the way into the tree.
	 */
	private static final OffsetDateTime SAME_INSTANT_PLUS_TWO =
		OffsetDateTime.of(2026, 9, 21, 12, 0, 0, 0, ZoneOffset.ofHours(2));
	private static final OffsetDateTime SAME_INSTANT_UTC =
		OffsetDateTime.of(2026, 9, 21, 10, 0, 0, 0, ZoneOffset.UTC);
	/**
	 * Two strings that are canonically equivalent but not equal: the first carries a precomposed `e` with an
	 * acute accent, the second the same letter decomposed into `e` plus a combining accent. The index folds
	 * both to one NFD key; `String#equals` keeps them apart.
	 */
	private static final String PRECOMPOSED_LABEL = "caf\u00e9";
	private static final String DECOMPOSED_LABEL = "cafe\u0301";
	/**
	 * The stock every product in these tests references, so that all owners land in one reduced index and
	 * therefore contend for one bucket entry.
	 */
	private static final int SHARED_STOCK_PK = 1;
	/**
	 * Two values that differ, and that `indexDecimalPlaces = 0` rounds to the same index key of `1`.
	 */
	private static final BigDecimal FIRST_OWNER_QUANTITY = new BigDecimal("1.2");
	private static final BigDecimal SECOND_OWNER_QUANTITY = new BigDecimal("1.4");
	/**
	 * The key both of the above round to — the bucket whose single entry they share.
	 */
	private static final BigDecimal SHARED_INDEX_KEY = new BigDecimal("1");

	/**
	 * Declares a product referencing a stock, with a `BigDecimal` reference attribute indexed at a scale of
	 * zero. The reference is indexed for filtering *and* partitioning because the cardinality bookkeeping
	 * under test lives on the reference-type index that pairing creates.
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_STOCK).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReferenceToEntity(
				REFERENCE_STOCKS,
				ENTITY_STOCK,
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.withAttribute(
						ATTRIBUTE_QUANTITY, BigDecimal.class,
						thatIs -> thatIs.filterable().indexDecimalPlaces(0).nullable()
					)
			)
			.updateVia(session);
	}

	/**
	 * Declares the same shape as {@link #defineSchema} but with a filterable `String` reference attribute, whose
	 * index key is the Unicode NFD folding of the stored value — the other normalizer that maps several distinct
	 * stored values onto one key.
	 */
	private static void defineStringSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_STOCK).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReferenceToEntity(
				REFERENCE_STOCKS,
				ENTITY_STOCK,
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.withAttribute(
						ATTRIBUTE_LABEL, String.class,
						thatIs -> thatIs.filterable().nullable()
					)
			)
			.updateVia(session);
	}

	/**
	 * Creates the shared stock and two products whose labels are canonically equivalent but not equal.
	 */
	private static void seedTwoOwnersSharingOneStringKey(@Nonnull EvitaSessionContract session) {
		defineStringSchema(session);
		session.createNewEntity(ENTITY_STOCK, SHARED_STOCK_PK).upsertVia(session);
		upsertLabelOwner(session, 1, PRECOMPOSED_LABEL);
		upsertLabelOwner(session, 2, DECOMPOSED_LABEL);
	}

	/**
	 * String counterpart of {@link #upsertOwner}.
	 */
	private static void upsertLabelOwner(
		@Nonnull EvitaSessionContract session,
		int productPk,
		@Nonnull String label
	) {
		session.getEntity(ENTITY_PRODUCT, productPk, referenceContentAllWithAttributes())
			.map(it -> it.openForWrite())
			.orElseGet(() -> session.createNewEntity(ENTITY_PRODUCT, productPk))
			.setReference(
				REFERENCE_STOCKS, SHARED_STOCK_PK,
				whichIs -> whichIs.setAttribute(ATTRIBUTE_LABEL, label)
			)
			.upsertVia(session);
	}

	/**
	 * Declares a single-valued reference attribute of the supplied type, indexed for filtering, on a reference that
	 * is indexed for filtering and partitioning — the pairing that creates the reference-type index whose cardinality
	 * bookkeeping is under test.
	 */
	private static void defineSchemaWithAttribute(
		@Nonnull EvitaSessionContract session,
		@Nonnull String attributeName,
		@Nonnull Class<? extends java.io.Serializable> attributeType,
		int indexDecimalPlaces
	) {
		session.defineEntitySchema(ENTITY_STOCK).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReferenceToEntity(
				REFERENCE_STOCKS,
				ENTITY_STOCK,
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.withAttribute(
						attributeName, attributeType,
						thatIs -> thatIs.filterable().indexDecimalPlaces(indexDecimalPlaces).nullable()
					)
			)
			.updateVia(session);
	}

	/**
	 * Writes one product referencing {@link #SHARED_STOCK_PK} with an arbitrary reference attribute value.
	 */
	private static void upsertOwnerWith(
		@Nonnull EvitaSessionContract session,
		int productPk,
		@Nonnull String attributeName,
		@Nonnull java.io.Serializable value
	) {
		session.getEntity(ENTITY_PRODUCT, productPk, referenceContentAllWithAttributes())
			.map(it -> it.openForWrite())
			.orElseGet(() -> session.createNewEntity(ENTITY_PRODUCT, productPk))
			.setReference(
				REFERENCE_STOCKS, SHARED_STOCK_PK,
				whichIs -> whichIs.setAttribute(attributeName, value)
			)
			.upsertVia(session);
	}

	/**
	 * Creates the shared stock and two products that both reference it, each carrying its own quantity.
	 */
	private static void seedTwoOwnersSharingOneKey(@Nonnull EvitaSessionContract session) {
		defineSchema(session);
		session.createNewEntity(ENTITY_STOCK, SHARED_STOCK_PK).upsertVia(session);
		upsertOwner(session, 1, FIRST_OWNER_QUANTITY);
		upsertOwner(session, 2, SECOND_OWNER_QUANTITY);
	}

	/**
	 * Writes one product that references {@link #SHARED_STOCK_PK} with the supplied quantity, creating it if
	 * it does not exist yet and replacing the reference outright if it does.
	 */
	private static void upsertOwner(
		@Nonnull EvitaSessionContract session,
		int productPk,
		@Nonnull BigDecimal quantity
	) {
		session.getEntity(ENTITY_PRODUCT, productPk, referenceContentAllWithAttributes())
			.map(it -> it.openForWrite())
			.orElseGet(() -> session.createNewEntity(ENTITY_PRODUCT, productPk))
			.setReference(
				REFERENCE_STOCKS, SHARED_STOCK_PK,
				whichIs -> whichIs.setAttribute(ATTRIBUTE_QUANTITY, quantity)
			)
			.upsertVia(session);
	}

	/**
	 * Returns the primary keys of the products whose stock reference carries the given value for an arbitrary
	 * reference attribute — the type-agnostic form of {@link #ownersMatching}.
	 */
	@Nonnull
	private static Set<Integer> ownersMatchingValue(
		@Nonnull EvitaSessionContract session,
		@Nonnull String attributeName,
		@Nonnull java.io.Serializable value
	) {
		final EvitaResponse<EntityReference> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(
					referenceHaving(
						REFERENCE_STOCKS,
						attributeEquals(attributeName, value)
					)
				),
				require(page(1, Integer.MAX_VALUE))
			),
			EntityReference.class
		);
		final Set<Integer> primaryKeys = new TreeSet<>();
		for (final EntityReference reference : result.getRecordData()) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		return primaryKeys;
	}

	/**
	 * Returns the primary keys of the products whose ENTITY-level label array contains the given value — the
	 * entity-level counterpart of {@link #ownersMatchingValue}, with no `referenceHaving` wrapper.
	 */
	@Nonnull
	private static Set<Integer> entitiesMatchingLabel(
		@Nonnull EvitaSessionContract session,
		@Nonnull String label
	) {
		final EvitaResponse<EntityReference> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(attributeEquals(ATTRIBUTE_LABELS, label)),
				require(page(1, Integer.MAX_VALUE))
			),
			EntityReference.class
		);
		final Set<Integer> primaryKeys = new TreeSet<>();
		for (final EntityReference reference : result.getRecordData()) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		return primaryKeys;
	}

	/**
	 * Returns the primary keys of the products whose stock reference carries the given quantity.
	 */
	@Nonnull
	private static Set<Integer> ownersMatching(
		@Nonnull EvitaSessionContract session,
		@Nonnull BigDecimal quantity
	) {
		final EvitaResponse<EntityReference> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(
					referenceHaving(
						REFERENCE_STOCKS,
						attributeEquals(ATTRIBUTE_QUANTITY, quantity)
					)
				),
				require(page(1, Integer.MAX_VALUE))
			),
			EntityReference.class
		);
		final Set<Integer> primaryKeys = new TreeSet<>();
		for (final EntityReference reference : result.getRecordData()) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		return primaryKeys;
	}

	/**
	 * Builds the standard test configuration with a disabled session inactivity timeout and per-test
	 * storage / export directories.
	 */
	@Nonnull
	private EvitaConfiguration createConfiguration(@Nonnull TestPaths paths) {
		return newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Spins up a fresh Evita instance, seeds it in a writable catalog, takes the catalog live, then hands
	 * the live instance to the body so follow-up write transactions can run against it.
	 */
	private void runWithLiveCatalog(
		@Nonnull String label,
		@Nonnull Consumer<EvitaSessionContract> seeding,
		@Nonnull BiConsumer<Evita, EvitaSessionContract> body
	) {
		final TestPaths paths = createTestPaths(label);
		try (
			final Evita evita = new Evita(createConfiguration(paths))
		) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					seeding.accept(session);
					session.goLiveAndClose();
				}
			);
			evita.queryCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> body.accept(evita, session)
			);
		} finally {
			cleanupTestPaths(paths);
		}
	}

	@Test
	@DisplayName("should keep the surviving owner indexed when a co-located value is moved away")
	@Tag(ENGINE)
	@Tag(REFERENCE)
	@Tag(ATTRIBUTE)
	void shouldKeepSurvivingOwnerIndexedWhenColocatedValueMovesAway() {
		runWithLiveCatalog(
			"referenceAttributeKeyCollision_survivingOwner",
			ReferenceAttributeIndexKeyCollisionFunctionalTest::seedTwoOwnersSharingOneKey,
			(evita, session) -> {
				// both owners round to the same key, so both are found through it
				assertEquals(
					Set.of(1, 2), ownersMatching(session, SHARED_INDEX_KEY),
					"both owners should be reachable through the key their quantities round to"
				);

				// the first owner moves to a quantity that rounds elsewhere; this must not disturb the second
				evita.updateCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) writeSession ->
						upsertOwner(writeSession, 1, new BigDecimal("5"))
				);

				evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) readSession -> {
						assertEquals(
							Set.of(1), ownersMatching(readSession, new BigDecimal("5")),
							"the moved owner should be reachable through its new key"
						);
						assertEquals(
							Set.of(2), ownersMatching(readSession, SHARED_INDEX_KEY),
							"the owner that did not move must still be reachable through the shared key"
						);
					}
				);
			}
		);
	}

	@Test
	@DisplayName("should accept the surviving owner's next write after a co-located value is moved away")
	@Tag(ENGINE)
	@Tag(REFERENCE)
	@Tag(ATTRIBUTE)
	void shouldAcceptSurvivingOwnersNextWriteAfterColocatedValueMovesAway() {
		runWithLiveCatalog(
			"referenceAttributeKeyCollision_survivingWrite",
			ReferenceAttributeIndexKeyCollisionFunctionalTest::seedTwoOwnersSharingOneKey,
			(evita, session) -> {
				evita.updateCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) writeSession ->
						upsertOwner(writeSession, 1, new BigDecimal("5"))
				);

				// this is the write a production client retries forever: the surviving owner restates its own
				// reference, and the removal of its prior value reaches a bucket entry the step above took away
				assertDoesNotThrow(
					() -> evita.updateCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) writeSession ->
							upsertOwner(writeSession, 2, new BigDecimal("6"))
					),
					"the surviving owner's own update must not fail on an index entry it never removed"
				);
			}
		);
	}

	@Test
	@DisplayName("should accept the surviving owner's next write when the shared key is a Unicode folding")
	@Tag(ENGINE)
	@Tag(REFERENCE)
	@Tag(ATTRIBUTE)
	void shouldAcceptSurvivingOwnersNextWriteWhenSharedKeyIsAUnicodeFolding() {
		runWithLiveCatalog(
			"referenceAttributeKeyCollision_unicodeFolding",
			ReferenceAttributeIndexKeyCollisionFunctionalTest::seedTwoOwnersSharingOneStringKey,
			(evita, session) -> {
				evita.updateCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) writeSession ->
						upsertLabelOwner(writeSession, 1, "tea")
				);
				assertDoesNotThrow(
					() -> evita.updateCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) writeSession ->
							upsertLabelOwner(writeSession, 2, "cocoa")
					),
					"the surviving owner's own update must not fail on an index entry it never removed"
				);
			}
		);
	}

	@Test
	@DisplayName("should accept the surviving owner's next write when the colliding values live in an array")
	@Tag(ENGINE)
	@Tag(REFERENCE)
	@Tag(ATTRIBUTE)
	void shouldAcceptSurvivingOwnersNextWriteWhenCollidingValuesLiveInAnArray() {
		runWithLiveCatalog(
			"referenceAttributeKeyCollision_arrayAttribute",
			session -> {
				defineSchemaWithAttribute(session, ATTRIBUTE_QUANTITIES, BigDecimal[].class, 0);
				session.createNewEntity(ENTITY_STOCK, SHARED_STOCK_PK).upsertVia(session);
				upsertOwnerWith(
					session, 1, ATTRIBUTE_QUANTITIES, new BigDecimal[]{FIRST_OWNER_QUANTITY});
				upsertOwnerWith(
					session, 2, ATTRIBUTE_QUANTITIES, new BigDecimal[]{SECOND_OWNER_QUANTITY});
			},
			(evita, session) -> {
				evita.updateCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) writeSession -> upsertOwnerWith(
						writeSession, 1, ATTRIBUTE_QUANTITIES, new BigDecimal[]{new BigDecimal("5")})
				);
				assertDoesNotThrow(
					() -> evita.updateCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) writeSession -> upsertOwnerWith(
							writeSession, 2, ATTRIBUTE_QUANTITIES, new BigDecimal[]{new BigDecimal("6")})
					),
					"the array branch keeps its own delta from the same counter and must not lose the shared entry"
				);
			}
		);
	}

	/**
	 * The sharpest form of the defect: **one** owner, one write, no second party at all.
	 *
	 * Every other reproduction needs two owners contributing to a shared entry. Here a single owner's array holds
	 * both colliding values, and the index this lands on — `ReducedEntityIndex` — has NO cardinality counter at
	 * all, so nothing tracks that the owner reached the one tree entry keyed `1` twice. Replacing the array removes
	 * the whole prior value: the first element takes the owner out of the bucket, and the second finds it already
	 * gone and fails `FilterIndex`'s membership premise. This one THROWS rather than unindexing silently — the
	 * silent variant is #1620's, which needs a counter to get it wrong.
	 */
	@Test
	@DisplayName("should keep an owner indexed when its OWN array drops one of two colliding elements")
	@Tag(ENGINE)
	@Tag(REFERENCE)
	@Tag(ATTRIBUTE)
	void shouldKeepOwnerIndexedWhenItsOwnArrayDropsOneCollidingElement() {
		runWithLiveCatalog(
			"referenceAttributeKeyCollision_intraArray",
			session -> {
				defineSchemaWithAttribute(session, ATTRIBUTE_QUANTITIES, BigDecimal[].class, 0);
				session.createNewEntity(ENTITY_STOCK, SHARED_STOCK_PK).upsertVia(session);
				upsertOwnerWith(
					session, 1, ATTRIBUTE_QUANTITIES,
					new BigDecimal[]{FIRST_OWNER_QUANTITY, SECOND_OWNER_QUANTITY}
				);
			},
			(evita, session) -> {
				// the premise: both elements of the one array round to SHARED_INDEX_KEY and therefore contribute to
				// a single tree entry twice over - without that there is no double contribution and nothing to lose
				evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) readSession -> {
						assertEquals(
							Set.of(1),
							ownersMatchingValue(readSession, ATTRIBUTE_QUANTITIES, FIRST_OWNER_QUANTITY),
							"the owner must be indexed under the key its first element rounds to"
						);
						assertEquals(
							Set.of(1),
							ownersMatchingValue(readSession, ATTRIBUTE_QUANTITIES, SECOND_OWNER_QUANTITY),
							"and its second element must round to that very same entry, not a second one"
						);
					}
				);

				// the owner drops the second element and keeps the first - so it must remain indexed
				evita.updateCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) writeSession -> upsertOwnerWith(
						writeSession, 1, ATTRIBUTE_QUANTITIES, new BigDecimal[]{FIRST_OWNER_QUANTITY})
				);

				evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) readSession -> assertEquals(
						Set.of(1),
						ownersMatchingValue(readSession, ATTRIBUTE_QUANTITIES, FIRST_OWNER_QUANTITY),
						"the owner still holds a value that rounds to " + SHARED_INDEX_KEY + ", so it must still be " +
							"found by it - a counter keyed on the raw element drains one of two keys to zero here " +
							"and removes the entry the surviving element needs"
					)
				);
			}
		);
	}

	/**
	 * The same defect on a plain ENTITY-level array attribute, with references nowhere in the picture.
	 *
	 * It lands on the global entity index, which — like the reduced index the test above uses — keeps no
	 * cardinality counter, so nothing records that one entity reached a single index key through two array
	 * elements. Included because the reference-shaped reproductions above make the defect look like a
	 * reference-indexing problem, and it is not: it is `FilterIndex`'s array handling, reachable by any
	 * collection with an array attribute whose values canonicalize.
	 */
	@Test
	@DisplayName("should keep an entity indexed when its OWN entity-level array drops one colliding element")
	@Tag(ENGINE)
	@Tag(ATTRIBUTE)
	void shouldKeepEntityIndexedWhenItsEntityLevelArrayDropsOneCollidingElement() {
		runWithLiveCatalog(
			"entityAttributeKeyCollision_intraArray",
			session -> {
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withAttribute(
						ATTRIBUTE_LABELS, String[].class,
						thatIs -> thatIs.filterable().nullable()
					)
					.updateVia(session);
				session.createNewEntity(ENTITY_PRODUCT, 1)
					.setAttribute(ATTRIBUTE_LABELS, new String[]{PRECOMPOSED_LABEL, DECOMPOSED_LABEL})
					.upsertVia(session);
			},
			(evita, session) -> {
				// the premise: both spellings are canonically equivalent, so the entity sits in ONE bucket
				evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) readSession -> assertEquals(
						Set.of(1), entitiesMatchingLabel(readSession, PRECOMPOSED_LABEL),
						"the entity must be findable by the label it carries"
					)
				);

				evita.updateCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) writeSession -> writeSession
						.getEntity(ENTITY_PRODUCT, 1, attributeContentAll())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTRIBUTE_LABELS, new String[]{PRECOMPOSED_LABEL})
						.upsertVia(writeSession)
				);

				evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) readSession -> assertEquals(
						Set.of(1), entitiesMatchingLabel(readSession, PRECOMPOSED_LABEL),
						"the entity still carries a label that folds to that key, so it must still be found"
					)
				);
			}
		);
	}

	@Test
	@DisplayName("should accept the surviving owner's next write when two offsets denote one instant")
	@Tag(ENGINE)
	@Tag(REFERENCE)
	@Tag(ATTRIBUTE)
	void shouldAcceptSurvivingOwnersNextWriteWhenTwoOffsetsDenoteOneInstant() {
		runWithLiveCatalog(
			"referenceAttributeKeyCollision_offsetCollapse",
			session -> {
				defineSchemaWithAttribute(session, ATTRIBUTE_STOCKED_AT, OffsetDateTime.class, 0);
				session.createNewEntity(ENTITY_STOCK, SHARED_STOCK_PK).upsertVia(session);
				upsertOwnerWith(session, 1, ATTRIBUTE_STOCKED_AT, SAME_INSTANT_PLUS_TWO);
				upsertOwnerWith(session, 2, ATTRIBUTE_STOCKED_AT, SAME_INSTANT_UTC);
			},
			(evita, session) -> {
				evita.updateCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) writeSession -> upsertOwnerWith(
						writeSession, 1, ATTRIBUTE_STOCKED_AT, SAME_INSTANT_UTC.plusDays(1))
				);

				assertDoesNotThrow(
					() -> evita.updateCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) writeSession -> upsertOwnerWith(
							writeSession, 2, ATTRIBUTE_STOCKED_AT, SAME_INSTANT_UTC.plusDays(2))
					),
					"two offsets denoting one instant share a key and must not share a single removal"
				);
			}
		);
	}

	@Test
	@DisplayName("should accept the surviving owner's next write when the colliding values are number ranges")
	@Tag(ENGINE)
	@Tag(REFERENCE)
	@Tag(ATTRIBUTE)
	void shouldAcceptSurvivingOwnersNextWriteWhenCollidingValuesAreNumberRanges() {
		runWithLiveCatalog(
			"referenceAttributeKeyCollision_numberRange",
			session -> {
				defineSchemaWithAttribute(session, ATTRIBUTE_VALID_RANGE, BigDecimalNumberRange.class, 0);
				session.createNewEntity(ENTITY_STOCK, SHARED_STOCK_PK).upsertVia(session);
				upsertOwnerWith(
					session, 1, ATTRIBUTE_VALID_RANGE,
					BigDecimalNumberRange.between(new BigDecimal("1.2"), new BigDecimal("2.2"))
				);
				upsertOwnerWith(
					session, 2, ATTRIBUTE_VALID_RANGE,
					BigDecimalNumberRange.between(new BigDecimal("1.4"), new BigDecimal("2.4"))
				);
			},
			(evita, session) -> {
				evita.updateCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) writeSession -> upsertOwnerWith(
						writeSession, 1, ATTRIBUTE_VALID_RANGE,
						BigDecimalNumberRange.between(new BigDecimal("8"), new BigDecimal("9"))
					)
				);
				assertDoesNotThrow(
					() -> evita.updateCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) writeSession -> upsertOwnerWith(
							writeSession, 2, ATTRIBUTE_VALID_RANGE,
							BigDecimalNumberRange.between(new BigDecimal("6"), new BigDecimal("7"))
						)
					),
					"a range rescaled to the schema scale must not lose the shared entry either"
				);
			}
		);
	}

	/**
	 * Declares a reference that carries a group type and is indexed for filtering and partitioning, with a
	 * `BigDecimal` reference attribute at a coarse scale.
	 *
	 * A grouped reference builds a `ReducedGroupEntityIndex`, which keeps its own `AttributeCardinalityIndex`
	 * beside the shared value tree exactly as the reference-type index does — the engine's second counter.
	 *
	 * # Why one entity, and not two
	 *
	 * The obvious construction — two entities whose values collide — cannot reach this counter. Records here are
	 * keyed by the OWNER entity, and `AttributeMutationFanOut` states the rule outright: entity-level bookkeeping
	 * is "indexed once per (entity, reduced-index) pair", with `fanOutUniquePerIndex` folding N sibling references
	 * into one invocation. Two entities are therefore two record ids and two independent counter keys, and share
	 * no entry to lose.
	 *
	 * What does collide is ONE entity holding several references into the SAME group: every one of them
	 * contributes under that single entity's record id, so two colliding reference-attribute values become two
	 * counter keys over one shared bucket entry — the same defect, reached through the other index class.
	 */
	private static void defineGroupedReferenceSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_STOCK).updateVia(session);
		session.defineEntitySchema(ENTITY_STOCK_GROUP).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReferenceToEntity(
				REFERENCE_STOCKS,
				ENTITY_STOCK,
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.withGroupTypeRelatedToEntity(ENTITY_STOCK_GROUP)
					// declaring a group type is not enough — the engine only builds the per-group
					// REFERENCED_GROUP_ENTITY index when that component is explicitly enabled for the scope
					.indexedWithComponents(
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.withAttribute(
						ATTRIBUTE_QUANTITY, BigDecimal.class,
						thatIs -> thatIs.filterable().indexDecimalPlaces(0).nullable()
					)
			)
			.updateVia(session);
	}

	/**
	 * Points one of the product's grouped stock references at a quantity, creating the product on first call.
	 * Every reference joins the same group, so all of them land in one `ReducedGroupEntityIndex`.
	 */
	private static void upsertGroupedReference(
		@Nonnull EvitaSessionContract session,
		int stockPk,
		@Nonnull BigDecimal quantity
	) {
		session.getEntity(ENTITY_PRODUCT, GROUPED_OWNER_PK, referenceContentAllWithAttributes())
			.map(it -> it.openForWrite())
			.orElseGet(() -> session.createNewEntity(ENTITY_PRODUCT, GROUPED_OWNER_PK))
			.setReference(
				REFERENCE_STOCKS, stockPk,
				whichIs -> whichIs
					.setGroup(ENTITY_STOCK_GROUP, SHARED_STOCK_GROUP_PK)
					.setAttribute(ATTRIBUTE_QUANTITY, quantity)
			)
			.upsertVia(session);
	}

	/**
	 * Creates the group, two stocks, and one product referencing both of them inside that group with quantities
	 * that round to a single index key.
	 */
	private static void seedOneOwnerWithTwoCollidingGroupedReferences(@Nonnull EvitaSessionContract session) {
		defineGroupedReferenceSchema(session);
		session.createNewEntity(ENTITY_STOCK_GROUP, SHARED_STOCK_GROUP_PK).upsertVia(session);
		session.createNewEntity(ENTITY_STOCK, FIRST_GROUPED_STOCK_PK).upsertVia(session);
		session.createNewEntity(ENTITY_STOCK, SECOND_GROUPED_STOCK_PK).upsertVia(session);
		upsertGroupedReference(session, FIRST_GROUPED_STOCK_PK, FIRST_OWNER_QUANTITY);
		upsertGroupedReference(session, SECOND_GROUPED_STOCK_PK, SECOND_OWNER_QUANTITY);
	}

	@Test
	@DisplayName("should keep a sibling grouped reference indexed when a co-located value is moved away")
	@Tag(ENGINE)
	@Tag(REFERENCE)
	@Tag(ATTRIBUTE)
	void shouldAcceptNextWriteWhenCollidingValuesShareOneGroupedIndex() {
		runWithLiveCatalog(
			"referenceAttributeKeyCollision_groupedReferenceSiblings",
			ReferenceAttributeIndexKeyCollisionFunctionalTest::seedOneOwnerWithTwoCollidingGroupedReferences,
			(evita, session) -> {
				evita.updateCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) writeSession ->
						upsertGroupedReference(writeSession, FIRST_GROUPED_STOCK_PK, new BigDecimal("5"))
				);

				assertDoesNotThrow(
					() -> evita.updateCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) writeSession ->
							upsertGroupedReference(writeSession, SECOND_GROUPED_STOCK_PK, new BigDecimal("6"))
					),
					"a sibling grouped reference must not lose the bucket entry it still contributes to"
				);
			}
		);
	}

	/**
	 * Declares a reference attribute that is `unique()` but deliberately NOT `filterable()`.
	 *
	 * {@link io.evitadb.index.EntityIndex#upsertAttribute} shadows a unique value into the filter index whenever
	 * `unique || filterable`, "because the shared value tree that backs unique reads IS the filter structure", so
	 * such an attribute does reach the counter-gated path. That makes it look like a further way to reach this
	 * defect — and it is not, which is what the test below pins.
	 *
	 * A folded unique is enforced BY the filter write ({@code UniquenessEnforcement.BY_FILTER_WRITE}), on the
	 * shared tree's NORMALIZED key. So the very folding that causes the defect elsewhere is, here, what the
	 * uniqueness check runs on: the second owner's `1.4` is rejected as already present because `1.2` occupies the
	 * key both round to. The colliding pair can therefore never come into existence, and the shared-entry defect
	 * is unreachable for a unique attribute. This BOUNDS the blast radius rather than extending it.
	 *
	 * Worth knowing separately: the rejection reports the value the client sent (`1.4`) as "already present" when
	 * no entity holds it, which reads as a false positive unless one knows the declared scale. That is a message
	 * quality issue, not corruption, and is out of scope here.
	 */
	private static void defineUniqueOnlySchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_STOCK).updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReferenceToEntity(
				REFERENCE_STOCKS,
				ENTITY_STOCK,
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.withAttribute(
						ATTRIBUTE_QUANTITY, BigDecimal.class,
						thatIs -> thatIs.unique().indexDecimalPlaces(0).nullable()
					)
			)
			.updateVia(session);
	}

	@Test
	@DisplayName("blast-radius bound — a unique attribute forecloses the collision instead of suffering it")
	@Tag(ENGINE)
	@Tag(REFERENCE)
	@Tag(ATTRIBUTE)
	void shouldRejectCollidingValueOutrightWhenAttributeIsUniqueOnly() {
		final TestPaths paths = createTestPaths("referenceAttributeKeyCollision_uniqueOnly");
		try (final Evita evita = new Evita(createConfiguration(paths))) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					defineUniqueOnlySchema(session);
					session.createNewEntity(ENTITY_STOCK, SHARED_STOCK_PK).upsertVia(session);
					upsertOwner(session, 1, FIRST_OWNER_QUANTITY);
					// the second owner's value is distinct, but rounds to the key the first owner already holds,
					// and a folded unique is enforced on exactly that key — so it never reaches the counter at all
					assertThrows(
						UniqueValueViolationException.class,
						() -> upsertOwner(session, 2, SECOND_OWNER_QUANTITY),
						"a folded unique is enforced on the normalized key, so a colliding value must be refused"
					);
				}
			);
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Guards the widening this fix made to {@link io.evitadb.index.cardinality.AttributeCardinalityIndex}'s type
	 * check. Normalizing before the counter changed WHICH type the counter stores for several attribute types —
	 * `Currency` is now held as a `ComparableCurrency` — so this asserts that a type whose normalizer is a faithful
	 * bijection still inserts, removes and re-inserts cleanly, and stays queryable.
	 *
	 * It is a guard against over-correction, not a reproduction: distinct currencies must keep their own entries,
	 * because nothing about them collides. A future change that normalized too aggressively would fail here.
	 */
	@Test
	@DisplayName("negative control — a bijective normalizer must keep distinct values in distinct entries")
	@Tag(ENGINE)
	@Tag(REFERENCE)
	@Tag(ATTRIBUTE)
	void shouldKeepDistinctEntriesForValuesWhoseNormalizerIsBijective() {
		runWithLiveCatalog(
			"referenceAttributeKeyCollision_bijectiveControl",
			session -> {
				defineSchemaWithAttribute(session, ATTRIBUTE_CURRENCY, Currency.class, 0);
				session.createNewEntity(ENTITY_STOCK, SHARED_STOCK_PK).upsertVia(session);
				upsertOwnerWith(session, 1, ATTRIBUTE_CURRENCY, Currency.getInstance("USD"));
				upsertOwnerWith(session, 2, ATTRIBUTE_CURRENCY, Currency.getInstance("EUR"));
			},
			(evita, session) -> {
				evita.updateCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) writeSession -> upsertOwnerWith(
						writeSession, 1, ATTRIBUTE_CURRENCY, Currency.getInstance("GBP"))
				);

				evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) readSession -> {
						assertEquals(
							Set.of(2), ownersMatchingValue(readSession, ATTRIBUTE_CURRENCY, Currency.getInstance("EUR")),
							"the owner that did not move must keep its own entry"
						);
						assertEquals(
							Set.of(1), ownersMatchingValue(readSession, ATTRIBUTE_CURRENCY, Currency.getInstance("GBP")),
							"the moved owner must be reachable through its new value"
						);
					}
				);

				assertDoesNotThrow(
					() -> evita.updateCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) writeSession -> upsertOwnerWith(
							writeSession, 2, ATTRIBUTE_CURRENCY, Currency.getInstance("CHF"))
					),
					"a bijectively-normalized value must round-trip through the counter"
				);

				// not throwing is the weaker half of the claim: a normalizer that dropped the entry on the way out
				// would also not throw. The value has to be QUERYABLE at its new key and absent from the old one
				evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) readSession -> {
						assertEquals(
							Set.of(2),
							ownersMatchingValue(readSession, ATTRIBUTE_CURRENCY, Currency.getInstance("CHF")),
							"the re-inserted value must be reachable through its own entry"
						);
						assertEquals(
							Set.of(),
							ownersMatchingValue(readSession, ATTRIBUTE_CURRENCY, Currency.getInstance("EUR")),
							"and the entry it left must no longer list it"
						);
						assertEquals(
							Set.of(1),
							ownersMatchingValue(readSession, ATTRIBUTE_CURRENCY, Currency.getInstance("GBP")),
							"removing one distinct value must not disturb another owner's separate entry"
						);
					}
				);
			}
		);
	}

}
