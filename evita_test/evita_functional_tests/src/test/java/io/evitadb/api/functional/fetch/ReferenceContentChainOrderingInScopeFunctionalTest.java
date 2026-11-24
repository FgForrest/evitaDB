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

package io.evitadb.api.functional.fetch;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.DefaultPrefetchRequirementCollector;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.exception.ReferenceComponentNotIndexedException;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.session.EvitaInternalSessionContract;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import static io.evitadb.api.SessionTraits.SessionFlags.DRY_RUN;
import static io.evitadb.api.SessionTraits.SessionFlags.READ_WRITE;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.api.requestResponse.schema.Cardinality.ZERO_OR_MORE;
import static io.evitadb.api.requestResponse.schema.Cardinality.ZERO_OR_MORE_WITH_DUPLICATES;
import static io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY;
import static io.evitadb.dataType.Scope.ARCHIVED;
import static io.evitadb.dataType.Scope.LIVE;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Exercises chain ordering over both owner scopes. The plain duplicate rows share a target and have the same
 * representative keys in the two scopes. The live reflected owner has a chain from target 3 to target 2 and a
 * row for target 1 with no chain position. Its nullable plain chain has a value only on target 2.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Owner scopes in reference content chain ordering")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(ORDER)
@Tag(REFERENCE)
class ReferenceContentChainOrderingInScopeFunctionalTest {
	private static final String EMPTY_DATA_SET = "emptyReferenceContentChains";
	private static final String DATA_SET = "referenceContentChains";
	private static final String FORWARD_TO_ARCHIVED_DATA_SET = "referenceContentChainsForwardToArchived";
	private static final String OWNER = "OWNER";
	private static final String TARGET = "TARGET";
	private static final String ITEMS = "items";
	private static final String NULLABLE_ITEMS = "nullableItems";
	private static final String FORWARD = "forward";
	private static final String REFLECTED = "reflected";
	private static final String PREDECESSOR = "predecessor";
	private static final String KIND = "kind";
	private static final String NAME = "name";
	private static final int LIVE_OWNER = 1;
	private static final int ARCHIVED_OWNER = 2;
	private static final int ARCHIVED_OWNER_REFERRER = 9;
	private static final Scope[] BOTH_SCOPES = {LIVE, ARCHIVED};
	private static final OrderBy CHAIN_ORDER = orderBy(attributeNatural(PREDECESSOR, ASC));
	private static final OrderBy LIVE_CHAIN_ORDER = orderBy(inScope(LIVE, attributeNatural(PREDECESSOR, ASC)));
	private static final String MULTIPLE_SCOPES_MESSAGE =
		"Chain sort cannot be executed in multiple scopes (LIVE, ARCHIVED) simultaneously.";

	/**
	 * Creates both chain schemas and leaves the owner collection empty.
	 *
	 * @param evita engine hosting the empty fixture
	 */
	@DataSet(value = EMPTY_DATA_SET, destroyAfterClass = true)
	void setUpEmpty(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(TARGET)
				.withoutGeneratedPrimaryKey()
				.withAttribute(NAME, String.class)
				.withReferenceToEntity(
					FORWARD, OWNER, ZERO_OR_MORE,
					reference -> reference.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES)
						.withAttribute(
							PREDECESSOR, Predecessor.class,
							attribute -> attribute.sortableInScope(BOTH_SCOPES).nullable()
						)
				)
				.updateVia(session);
			session.defineEntitySchema(OWNER)
				.withoutGeneratedPrimaryKey()
				.withReferenceToEntity(
					ITEMS, TARGET, ZERO_OR_MORE_WITH_DUPLICATES,
					reference -> reference.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES)
						.withAttribute(
							KIND, String.class, attribute -> attribute.filterableInScope(BOTH_SCOPES).representative()
						)
						.withAttribute(
							PREDECESSOR, Predecessor.class, attribute -> attribute.sortableInScope(BOTH_SCOPES)
						)
				)
				.withReferenceToEntity(
					NULLABLE_ITEMS, TARGET, ZERO_OR_MORE,
					reference -> reference.indexedForFilteringAndPartitioningInScope(BOTH_SCOPES)
						.withAttribute(
							PREDECESSOR, Predecessor.class,
							attribute -> attribute.sortableInScope(BOTH_SCOPES).nullable()
						)
				)
				.withReflectedReferenceToEntity(
					REFLECTED, TARGET, FORWARD, reference -> reference.withAttributesInherited()
				)
				.updateVia(session);
			session.goLiveAndClose();
		});
	}

	/**
	 * Creates the two owners and writes duplicate rows into each owner's final scope and a nullable plain chain
	 * for the live owner.
	 *
	 * @param evita engine hosting the populated fixture
	 */
	@DataSet(value = DATA_SET, destroyAfterClass = true)
	void setUp(@Nonnull Evita evita) {
		this.setUpEmpty(evita);
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.createNewEntity(OWNER, LIVE_OWNER).upsertVia(session);
			session.createNewEntity(OWNER, ARCHIVED_OWNER).upsertVia(session);
			session.createNewEntity(TARGET, 1)
				.setAttribute(NAME, "Outside the chain")
				.setReference(FORWARD, LIVE_OWNER)
				.upsertVia(session);
			session.createNewEntity(TARGET, 3)
				.setAttribute(NAME, "Head")
				.setReference(FORWARD, LIVE_OWNER, reference -> reference.setAttribute(PREDECESSOR, Predecessor.HEAD))
				.upsertVia(session);
			session.createNewEntity(TARGET, 2)
				.setAttribute(NAME, "Tail")
				.setReference(FORWARD, LIVE_OWNER, reference -> reference.setAttribute(PREDECESSOR, new Predecessor(3)))
				.upsertVia(session);
		});
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.archiveEntity(OWNER, ARCHIVED_OWNER);
			for (final int owner : new int[]{LIVE_OWNER, ARCHIVED_OWNER}) {
				session.getEntity(OWNER, owner, BOTH_SCOPES, referenceContentWithAttributes(ITEMS))
					.orElseThrow().openForWrite()
					.setOrUpdateReference(
						ITEMS, 1, Functions.alwaysFalse(),
						reference -> reference.setAttribute(KIND, "a").setAttribute(PREDECESSOR, Predecessor.HEAD)
					)
					.setOrUpdateReference(
						ITEMS, 1, Functions.alwaysFalse(),
						reference -> reference.setAttribute(KIND, "b").setAttribute(PREDECESSOR, Predecessor.HEAD)
					)
					.setOrUpdateReference(
						ITEMS, 2, Functions.alwaysFalse(),
						reference -> reference.setAttribute(KIND, "c").setAttribute(PREDECESSOR, Predecessor.HEAD)
					)
					.upsertVia(session);
			}
			session.getEntity(OWNER, LIVE_OWNER, referenceContentWithAttributes(NULLABLE_ITEMS))
				.orElseThrow().openForWrite()
				.setReference(NULLABLE_ITEMS, 1)
				.setReference(
					NULLABLE_ITEMS, 2, reference -> reference.setAttribute(PREDECESSOR, Predecessor.HEAD)
				)
				.setReference(NULLABLE_ITEMS, 3)
				.upsertVia(session);
		});
	}

	/**
	 * Adds to the populated fixture a live target referring to the archived owner, kept apart because the reference
	 * adds a reflected row to that owner.
	 *
	 * @param evita engine hosting the populated fixture and the referring target
	 */
	@DataSet(value = FORWARD_TO_ARCHIVED_DATA_SET, destroyAfterClass = true)
	void setUpForwardToArchived(@Nonnull Evita evita) {
		this.setUp(evita);
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.createNewEntity(TARGET, ARCHIVED_OWNER_REFERRER)
				.setAttribute(NAME, "Referrer of the archived owner")
				.setReference(FORWARD, ARCHIVED_OWNER)
				.upsertVia(session);
		});
	}

	/**
	 * Runs the requirement over both scopes.
	 *
	 * @param session query session
	 * @param referenceContent reference requirement under test
	 * @return fetched owners in ascending primary-key order
	 */
	@Nonnull
	private static List<SealedEntity> queryOwners(
		@Nonnull EvitaSessionContract session, @Nonnull ReferenceContent referenceContent
	) {
		return session.queryListOfSealedEntities(
			query(collection(OWNER), filterBy(scope(BOTH_SCOPES)), require(entityFetch(referenceContent)))
		);
	}

	/**
	 * Verifies that the body belongs to the reference and carries the requested name.
	 *
	 * @param reference row whose target to inspect
	 * @param expectedName name of the fetched target
	 */
	private static void assertBody(@Nonnull ReferenceContract reference, @Nonnull String expectedName) {
		final SealedEntity body = reference.getReferencedEntity().orElseThrow();
		assertEquals(reference.getReferencedPrimaryKey(), body.getPrimaryKeyOrThrowException());
		assertEquals(expectedName, body.getAttribute(NAME, String.class));
	}

	/**
	 * Asserts the exact diagnostic from rejecting a multi-scope reflected chain order.
	 *
	 * @param session session serving the query
	 * @param referenceFilter optional filter on reflected rows
	 */
	private static void assertReflectedChainRefused(
		@Nonnull EvitaSessionContract session, @Nullable FilterBy referenceFilter
	) {
		final ReferenceContent requirement = referenceContent(REFLECTED, referenceFilter, CHAIN_ORDER);
		final EvitaInvalidUsageException exception = assertThrows(
			EvitaInvalidUsageException.class, () -> queryOwners(session, requirement)
		);
		assertEquals(MULTIPLE_SCOPES_MESSAGE, exception.getMessage());
	}

	/**
	 * Covers plain chain lookups and schema-only acceptance under both requested scopes.
	 */
	@Nested
	@DisplayName("Plain chains")
	class PlainChains {
		/**
		 * Enriches each owner with the same unwrapped ordering and checks every admitted row and its body.
		 *
		 * @param ownerScope scope in which the owner was fetched
		 * @param admittedRows number of reference rows admitted by the filter
		 * @param expectedKinds comma-separated kinds of the returned rows in their expected order
		 * @param evita engine hosting both owners
		 */
		@ParameterizedTest(name = "{0}, {1} admitted rows")
		@CsvSource({
			"LIVE, 0, ''", "LIVE, 1, a", "LIVE, 3, 'b,a,c'",
			"ARCHIVED, 0, ''", "ARCHIVED, 1, a", "ARCHIVED, 3, 'b,a,c'"
		})
		@UseDataSet(DATA_SET)
		@DisplayName("should enrich each owner using its own scope for the unwrapped chain order")
		void shouldEnrichOwnerUsingItsOwnScope(
			@Nonnull Scope ownerScope, int admittedRows, @Nonnull String expectedKinds, @Nonnull Evita evita
		) {
			final FilterBy referenceFilter = filterBy(attributeInSet(
				KIND, List.of("absent", "a", "b", "c").subList(0, admittedRows + 1).toArray(String[]::new)
			));
			final ReferenceContent requirement = referenceContentWithAttributes(
				ITEMS, referenceFilter, CHAIN_ORDER, attributeContent(KIND), entityFetch(attributeContent(NAME)),
				null, null
			);
			evita.queryCatalog(TEST_CATALOG, session -> {
				final int ownerPrimaryKey = ownerScope == LIVE ? LIVE_OWNER : ARCHIVED_OWNER;
				final SealedEntity owner = session.getEntity(OWNER, ownerPrimaryKey, new Scope[]{ownerScope})
					.orElseThrow();
				assertEquals(ownerScope, owner.getScope());
				final SealedEntity enriched = session.enrichEntity(owner, requirement);
				final List<ReferenceContract> rows = List.copyOf(enriched.getReferences(ITEMS));
				// The two HEAD rows tie, preserving the stored order b, then a.
				assertEquals(
					expectedKinds.isEmpty() ? List.of() : List.of(expectedKinds.split(",")),
					rows.stream().map(row -> row.getAttribute(KIND, String.class)).toList()
				);
				for (final ReferenceContract row : rows) {
					assertBody(row, row.getReferencedPrimaryKey() == 1 ? "Outside the chain" : "Tail");
				}
			});
		}

		/**
		 * Enriches a live target whose archived owner body was fetched before in both scopes. The enrichment selects
		 * no scope and reuses that body, so the owner's chain order must still be read in the owner's own scope.
		 *
		 * @param evita engine hosting the archived owner and its live referrer
		 */
		@Test
		@UseDataSet(FORWARD_TO_ARCHIVED_DATA_SET)
		@DisplayName("should order the chain of a reused archived owner body when enriching its referrer")
		void shouldOrderChainOfReusedArchivedOwnerWhenEnrichingReferrer(@Nonnull Evita evita) {
			final ReferenceContent chainedItems = referenceContent(
				FORWARD, entityFetch(referenceContentWithAttributes(ITEMS, CHAIN_ORDER, attributeContent(KIND)))
			);
			evita.queryCatalog(TEST_CATALOG, session -> {
				final SealedEntity referrer = session.getEntity(
					TARGET, ARCHIVED_OWNER_REFERRER, BOTH_SCOPES, referenceContent(FORWARD, entityFetch())
				).orElseThrow();
				final SealedEntity enriched = session.enrichEntity(referrer, chainedItems);
				final SealedEntity owner = enriched.getReference(FORWARD, ARCHIVED_OWNER)
					.flatMap(ReferenceContract::getReferencedEntity)
					.orElseThrow();
				assertEquals(ARCHIVED, owner.getScope());
				// The two HEAD rows tie, preserving the stored order b, then a.
				assertEquals(
					List.of("b", "a", "c"),
					owner.getReferences(ITEMS).stream().map(row -> row.getAttribute(KIND, String.class)).toList()
				);
			});
		}

		/**
		 * Checks that excluding the representative attribute from the response preserves the ordering of all rows.
		 *
		 * @param evita engine hosting both owners and their duplicate references
		 */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("should order duplicate references with their representative attribute hidden")
		void shouldOrderDuplicateReferencesWithRepresentativeAttributeHidden(@Nonnull Evita evita) {
			final ReferenceContent withKind = referenceContentWithAttributes(
				ITEMS, null, CHAIN_ORDER, attributeContent(KIND), null, null, null
			);
			final ReferenceContent withoutKind = referenceContentWithAttributes(
				ITEMS, null, CHAIN_ORDER, attributeContent(PREDECESSOR), null, null, null
			);
			evita.queryCatalog(TEST_CATALOG, session -> {
				final List<SealedEntity> visible = queryOwners(session, withKind);
				final List<SealedEntity> hidden = queryOwners(session, withoutKind);
				assertEquals(2, visible.size());
				assertEquals(visible.size(), hidden.size());
				for (int i = 0; i < visible.size(); i++) {
					final List<ReferenceContract> expected = List.copyOf(visible.get(i).getReferences(ITEMS));
					final List<ReferenceContract> actual = List.copyOf(hidden.get(i).getReferences(ITEMS));
					assertEquals(3, expected.size());
					assertEquals(
						expected.stream().map(ReferenceContract::getReferenceKey).toList(),
						actual.stream().map(ReferenceContract::getReferenceKey).toList()
					);
					for (final ReferenceContract row : actual) {
						assertFalse(row.getAttributeNames().contains(KIND));
					}
				}
			});
		}

		/**
		 * Checks that rows without a plain predecessor follow the ranked rows and use the next ordering if present.
		 *
		 * @param followingOrder whether to order the remaining rows by descending referenced key
		 * @param paged whether to fetch only the first two rows
		 * @param evita engine hosting the nullable chain
		 */
		@ParameterizedTest(name = "Following ordering = {0}, paged = {1}")
		@CsvSource({"false, false", "false, true", "true, false", "true, true"})
		@UseDataSet(DATA_SET)
		@DisplayName("should pass references without a plain predecessor to the following ordering")
		void shouldPassReferencesWithoutPlainPredecessorToFollowingOrdering(
			boolean followingOrder, boolean paged, @Nonnull Evita evita
		) {
			final OrderBy order = followingOrder ?
				orderBy(attributeNatural(PREDECESSOR, ASC), entityPrimaryKeyNatural(DESC)) : CHAIN_ORDER;
			final ReferenceContent requirement = referenceContent(
				NULLABLE_ITEMS, null, order, entityFetch(attributeContent(NAME)), paged ? page(1, 2) : null
			);
			final List<Integer> keys = followingOrder ? List.of(2, 3, 1) : List.of(2, 1, 3);
			final List<String> names = followingOrder ?
				List.of("Tail", "Head", "Outside the chain") : List.of("Tail", "Outside the chain", "Head");
			evita.queryCatalog(TEST_CATALOG, session -> {
				final SealedEntity owner = queryOwners(session, requirement).getFirst();
				assertEquals(LIVE, owner.getScope());
				final List<ReferenceContract> rows = List.copyOf(owner.getReferences(NULLABLE_ITEMS));
				assertEquals(
					paged ? keys.subList(0, 2) : keys,
					rows.stream().map(ReferenceContract::getReferencedPrimaryKey).toList()
				);
				for (int i = 0; i < rows.size(); i++) {
					assertBody(rows.get(i), names.get(i));
				}
			});
		}

		/**
		 * Orders duplicates of target 1 before target 2 and verifies the page through their tied positions.
		 *
		 * @param paged whether to fetch the second row only
		 * @param evita engine hosting the two owners
		 */
		@ParameterizedTest(name = "Paged = {0}")
		@ValueSource(booleans = {false, true})
		@UseDataSet(DATA_SET)
		@DisplayName("should order duplicate references and fetch their target bodies across both scopes")
		void shouldOrderDuplicateReferencesAndFetchBodiesAcrossBothScopes(boolean paged, @Nonnull Evita evita) {
			final ReferenceContent requirement = referenceContentWithAttributes(
				ITEMS, null, CHAIN_ORDER, entityFetch(attributeContent(NAME)), paged ? page(2, 1) : null
			);
			evita.queryCatalog(TEST_CATALOG, session -> {
				final List<SealedEntity> owners = queryOwners(session, requirement);
				assertEquals(List.of(LIVE, ARCHIVED), owners.stream().map(SealedEntity::getScope).toList());
				for (final SealedEntity owner : owners) {
					final List<ReferenceContract> rows = List.copyOf(owner.getReferences(ITEMS));
					assertEquals(
						paged ? List.of(1) : List.of(1, 1, 2),
						rows.stream().map(ReferenceContract::getReferencedPrimaryKey).toList()
					);
					// The input orders target 1's rows by internal primary key (b, then a).
					// They tie on chain position, so the stable sort keeps that order.
					assertEquals(
						paged ? List.of("a") : List.of("b", "a", "c"),
						rows.stream().map(row -> row.getAttribute(KIND, String.class)).toList()
					);
					for (final ReferenceContract row : rows) {
						assertBody(row, row.getReferencedPrimaryKey() == 1 ? "Outside the chain" : "Tail");
					}
				}
			});
		}

		/**
		 * Runs the translated comparator on each owner's rows. Both scopes have the same partition key, so
		 * switching owners must miss the last lookup cache. Each resolved chain contains only its scope's owner.
		 *
		 * @param evita engine hosting the partitions and their owners
		 */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("should resolve the same partition key in each owner's scope")
		void shouldReadEachOwnersScopedChain(@Nonnull Evita evita) {
			// The planning context needs the concrete session supplied inside the transaction callback.
			final SessionTraits traits = new SessionTraits(TEST_CATALOG, READ_WRITE, DRY_RUN);
			try (final EvitaSessionContract dryRun = evita.createSession(traits)) {
				((EvitaInternalSessionContract) dryRun).execute(session -> {
					final EntityCollection ownerCollection = (EntityCollection) evita
						.getCatalogInstanceOrThrowException(TEST_CATALOG).getCollectionForEntity(OWNER).orElseThrow();
					final ReferenceContent requirement = referenceContentWithAttributes(ITEMS);
					final Query query = query(
						collection(OWNER), filterBy(scope(BOTH_SCOPES)), require(entityFetch(requirement))
					);
					final QueryPlanningContext context = ownerCollection.createQueryContext(
						new EvitaRequest(query, OffsetDateTime.now(), SealedEntity.class, null), session
					);
					final QueryPlanningContext observedContext = spy(context);
					final EntitySchema ownerSchema = ownerCollection.getInternalSchema();
					final ReferenceSchema referenceSchema = ownerSchema.getReferenceOrThrowException(ITEMS);
					final ReferenceComparator comparator = ReferenceOrderByVisitor.getComparator(
						observedContext, new DefaultPrefetchRequirementCollector(), CHAIN_ORDER,
						ownerSchema, referenceSchema, observedContext.getScopes()
					).comparator();
					for (final SealedEntity owner : queryOwners(session, requirement)) {
						final ReferenceContract[] rows = owner.getReferences(ITEMS).toArray(ReferenceContract[]::new);
						ReferenceComparator.sort(
							comparator, owner.getPrimaryKeyOrThrowException(), owner.getScope(), rows, 0, rows.length,
							Arrays.asList(rows)
						);
					}
					final ArgumentCaptor<RepresentativeReferenceKey> partitionKey =
						ArgumentCaptor.forClass(RepresentativeReferenceKey.class);
					verify(observedContext).resolvePartitionForReadIfExists(
						eq(OWNER), eq(LIVE), partitionKey.capture()
					);
					verify(observedContext).resolvePartitionForReadIfExists(OWNER, ARCHIVED, partitionKey.getValue());
					for (final Scope scope : BOTH_SCOPES) {
						final ReducedEntityIndex partition = assertInstanceOf(
							ReducedEntityIndex.class,
							context.resolvePartitionForReadIfExists(OWNER, scope, partitionKey.getValue())
						);
						final SortedRecordsProvider chain = partition.getChainIndex(
							referenceSchema, referenceSchema.getAttribute(PREDECESSOR).orElseThrow(), null
						).getAscendingOrderRecordsSupplier();
						assertEquals(0, chain.positionOf(scope == LIVE ? LIVE_OWNER : ARCHIVED_OWNER));
						assertEquals(
							SortedRecordsProvider.POSITION_NOT_FOUND,
							chain.positionOf(scope == LIVE ? ARCHIVED_OWNER : LIVE_OWNER)
						);
					}
				});
			}
		}

		/**
		 * Changes only the rows admitted by the reference filter. The same chain order remains valid.
		 *
		 * @param admittedRows number of duplicate rows to admit for each owner
		 * @param evita engine hosting both owners
		 */
		@ParameterizedTest(name = "{0} admitted rows")
		@ValueSource(ints = {0, 1, 3})
		@UseDataSet(DATA_SET)
		@DisplayName("should accept the unwrapped chain order regardless of the number of admitted rows")
		void shouldAcceptPlainChainsWithAnyNumberOfRows(int admittedRows, @Nonnull Evita evita) {
			final FilterBy referenceFilter = filterBy(attributeInSet(
				KIND, List.of("absent", "a", "b", "c").subList(0, admittedRows + 1).toArray(String[]::new)
			));
			final ReferenceContent requirement = referenceContentWithAttributes(
				ITEMS, referenceFilter, CHAIN_ORDER, attributeContent(KIND), null, null, null
			);
			evita.queryCatalog(TEST_CATALOG, session -> {
				final List<SealedEntity> owners = queryOwners(session, requirement);
				assertEquals(2, owners.size());
				for (final SealedEntity owner : owners) {
					assertEquals(admittedRows, owner.getReferences(ITEMS).size());
				}
			});
		}

		/**
		 * Plans the same unwrapped chain order on a collection with no owners.
		 *
		 * @param evita engine hosting the empty collection
		 */
		@Test
		@UseDataSet(EMPTY_DATA_SET)
		@DisplayName("should accept the unwrapped chain order on an empty collection")
		void shouldAcceptPlainChainsOnEmptyCollection(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertTrue(queryOwners(session, referenceContent(ITEMS, CHAIN_ORDER)).isEmpty());
			});
		}

		/**
		 * Simulates a stored schema missing an index component in one scope inside a dry-run session.
		 * With no owners, only translation can detect the missing component.
		 *
		 * @param evita engine hosting the empty collection
		 */
		@Test
		@UseDataSet(EMPTY_DATA_SET)
		@DisplayName("should refuse a chain missing the entity component even on an empty collection")
		void shouldRefuseMissingEntityComponentDuringTranslation(@Nonnull Evita evita) {
			// Schema validation at commit rejects this legacy shape. The dry-run session discards it on close.
			final SessionTraits traits = new SessionTraits(TEST_CATALOG, READ_WRITE, DRY_RUN);
			try (final EvitaSessionContract session = evita.createSession(traits)) {
				session.getEntitySchemaOrThrowException(OWNER).openForWrite()
					.withReferenceToEntity(
						ITEMS, TARGET, ZERO_OR_MORE_WITH_DUPLICATES,
						reference -> reference.indexedWithComponentsInScope(ARCHIVED, REFERENCED_GROUP_ENTITY)
					)
					.updateVia(session);
				final ReferenceComponentNotIndexedException exception = assertThrows(
					ReferenceComponentNotIndexedException.class,
					() -> queryOwners(session, referenceContent(ITEMS, CHAIN_ORDER))
				);
				assertTrue(exception.getMessage().contains(
					"Reference `items` of entity `OWNER` is indexed in scope `ARCHIVED` without the `REFERENCED_ENTITY`"
				), exception.getMessage());
			}
		}
	}

	/**
	 * Covers reflected chain validation and placement of rows without a chain position.
	 */
	@Nested
	@DisplayName("Reflected chains")
	class ReflectedChains {
		/**
		 * Confirms that the equivalent wrapped query sees the intended number of rows and that the unwrapped
		 * constraint is refused for each number of admitted rows.
		 *
		 * @param admittedRows number of reflected rows admitted for the live owner
		 * @param evita engine hosting the reflected chain
		 */
		@ParameterizedTest(name = "{0} admitted rows")
		@ValueSource(ints = {0, 1, 3})
		@UseDataSet(DATA_SET)
		@DisplayName("should refuse the unwrapped chain order regardless of the number of admitted rows")
		void shouldRefuseReflectedChainsWithAnyNumberOfRows(int admittedRows, @Nonnull Evita evita) {
			final FilterBy referenceFilter = filterBy(entityHaving(entityPrimaryKeyInSet(
				List.of(99, 3, 2, 1).subList(0, admittedRows + 1).toArray(Integer[]::new)
			)));
			evita.queryCatalog(TEST_CATALOG, session -> {
				final List<SealedEntity> owners = queryOwners(
					session, referenceContent(REFLECTED, referenceFilter, LIVE_CHAIN_ORDER)
				);
				assertEquals(LIVE, owners.getFirst().getScope());
				assertEquals(admittedRows, owners.getFirst().getReferences(REFLECTED).size());
				assertReflectedChainRefused(session, referenceFilter);
			});
		}

		/**
		 * Checks that the same refusal happens even when there are no owners.
		 *
		 * @param evita engine hosting the empty collection
		 */
		@Test
		@UseDataSet(EMPTY_DATA_SET)
		@DisplayName("should refuse the unwrapped chain order on an empty collection")
		void shouldRefuseReflectedChainsOnEmptyCollection(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertReflectedChainRefused(session, null);
				assertTrue(queryOwners(session, referenceContent(REFLECTED, LIVE_CHAIN_ORDER)).isEmpty());
			});
		}

		/**
		 * Pins the ranked prefix of a terminal unwrapped predecessor order and the body fetched for its first page.
		 *
		 * @param paged whether to fetch only the first reference and its body
		 * @param evita engine hosting the live reflected owner and its targets
		 */
		@ParameterizedTest(name = "Paged = {0}")
		@ValueSource(booleans = {false, true})
		@UseDataSet(DATA_SET)
		@DisplayName("should place references without a chain position after ranked references")
		void shouldPlaceReferencesWithoutChainPositionAfterRankedReferences(boolean paged, @Nonnull Evita evita) {
			final ReferenceContent requirement = referenceContent(
				REFLECTED, CHAIN_ORDER, entityFetch(attributeContent(NAME)), paged ? page(1, 1) : null
			);
			evita.queryCatalog(TEST_CATALOG, session -> {
				final SealedEntity owner = session.queryListOfSealedEntities(
					query(
						collection(OWNER), filterBy(scope(LIVE), entityPrimaryKeyInSet(LIVE_OWNER)),
						require(entityFetch(requirement))
					)
				).getFirst();
				final List<ReferenceContract> rows = List.copyOf(owner.getReferences(REFLECTED));
				final List<Integer> keys = paged ? List.of(3) : List.of(3, 2, 1);
				final List<String> names = paged ? List.of("Head") : List.of("Head", "Tail", "Outside the chain");
				assertEquals(keys, rows.stream().map(ReferenceContract::getReferencedPrimaryKey).toList());
				for (int i = 0; i < rows.size(); i++) {
					assertBody(rows.get(i), names.get(i));
				}
			});
		}
	}
}
