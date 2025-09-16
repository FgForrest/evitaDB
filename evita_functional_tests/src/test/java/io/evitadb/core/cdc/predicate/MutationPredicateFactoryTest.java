/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.cdc.predicate;

import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.cdc.SchemaSite;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.Mutation.StreamDirection;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.dataType.ContainerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test class verifies the functionality of the {@link MutationPredicateFactory} which is responsible for creating
 * predicate chains that filter mutations based on various criteria.
 *
 * The tests cover the following scenarios:
 * - Creating predicates for forward and reverse change catalog capture
 * - Creating predicates based on specific criteria (area, site)
 * - Creating predicates with version and index constraints
 * - Testing the behavior of combined predicates (AND, OR)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("MutationPredicateFactory should")
class MutationPredicateFactoryTest {

    /**
     * Tests the {@link MutationPredicateFactory#createChangeCatalogCapturePredicate(ChangeCatalogCaptureRequest)} method
     * with a request that specifies a version.
     *
     * Verifies that the created predicate correctly filters mutations based on the specified version.
     */
    @Test
    @DisplayName("create predicate that filters mutations by version")
    void shouldCreateChangeCatalogCapturePredicateWithVersion() {
        // Create a request with a specific version
        ChangeCatalogCaptureRequest request = ChangeCatalogCaptureRequest.builder()
            .sinceVersion(100L)
            .content(ChangeCaptureContent.HEADER)
            .build();

        // Create the predicate
        MutationPredicate predicate = MutationPredicateFactory.createChangeCatalogCapturePredicate(request);

        // Verify the predicate is not null
        assertNotNull(predicate);

        // Create a transaction mutation with version 101
        TransactionMutation mutation = new TransactionMutation(
            UUID.randomUUID(), 101L, 10, 100L, OffsetDateTime.now()
        );

        // The predicate should accept mutations with version > 100
	    predicate.getContext().setVersion(mutation.getVersion(), mutation.getMutationCount());
        assertTrue(predicate.test(mutation));

        // Create a transaction mutation with version 99
        TransactionMutation olderMutation = new TransactionMutation(
            UUID.randomUUID(), 99L, 10, 100L, OffsetDateTime.now()
        );

        // The predicate should reject mutations with version < 100
	    predicate.getContext().setVersion(olderMutation.getVersion(), olderMutation.getMutationCount());
        assertFalse(predicate.test(olderMutation));
    }

    /**
     * Tests the {@link MutationPredicateFactory#createChangeCatalogCapturePredicate(ChangeCatalogCaptureRequest)} method
     * with a request that specifies both version and index.
     *
     * Verifies that the created predicate correctly filters mutations based on both version and index.
     */
    @Test
    @DisplayName("create predicate that filters mutations by version and index")
    void shouldCreateChangeCatalogCapturePredicateWithVersionAndIndex() {
        // Create a request with a specific version and index
        ChangeCatalogCaptureRequest request = ChangeCatalogCaptureRequest.builder()
            .sinceVersion(100L)
            .sinceIndex(5)
            .content(ChangeCaptureContent.HEADER)
            .build();

        // Create the predicate
        MutationPredicate predicate = MutationPredicateFactory.createChangeCatalogCapturePredicate(request);

        // Verify the predicate is not null
        assertNotNull(predicate);

		// create work mutation
	    EntityUpsertMutation entityMutation = new EntityUpsertMutation("product", 1, EntityExistence.MAY_EXIST);

	    // Create a transaction mutation with version 99
	    // The context will track the index as mutations are processed
	    TransactionMutation olderMutation = new TransactionMutation(
		    UUID.randomUUID(), 99L, 5, 100L, OffsetDateTime.now()
	    );

	    // The predicate should reject mutations with version = 100 and index < 5
	    assertFalse(predicate.test(olderMutation));

	    for (int i = 0; i < 5; i++) {
	        // The predicate should reject all mutations in this transaction
		    assertFalse(predicate.test(entityMutation));
	    }

        // Create a transaction mutation with version 100
        // The context will track the index as mutations are processed
        TransactionMutation correctTransaction = new TransactionMutation(
            UUID.randomUUID(), 100L, 10, 100L, OffsetDateTime.now()
        );

	    // The predicate should reject transaction mutation with correct version (bad index)
	    predicate.getContext().setVersion(correctTransaction.getVersion(), correctTransaction.getMutationCount());
	    assertFalse(predicate.test(correctTransaction));
        for (int i = 0; i < 4; i++) {
	        // The predicate should reject mutations with version = 100 and index < 5
	        predicate.getContext().advance();
	        assertFalse(predicate.test(entityMutation));
        }

		// but the fifth and subsequent mutations should be accepted
	    for (int i = 0; i < 5; i++) {
		    predicate.getContext().advance();
		    assertTrue(predicate.test(entityMutation));
	    }

        // Create a transaction mutation with version 101
        TransactionMutation newerMutation = new TransactionMutation(
            UUID.randomUUID(), 101L, 10, 100L, OffsetDateTime.now()
        );

        // The predicate should accept mutations with version > 100 regardless of index
	    predicate.getContext().setVersion(newerMutation.getVersion(), newerMutation.getMutationCount());
        assertTrue(predicate.test(newerMutation));
    }

	/**
	 * Tests the {@link MutationPredicateFactory#createReversedChangeCatalogCapturePredicate(ChangeCatalogCaptureRequest)} method
	 * with a request that specifies both version and index.
	 *
	 * Verifies that the created predicate correctly filters mutations in reverse order based on both version and index.
	 */
	@Test
	@DisplayName("create reversed predicate that filters mutations by version and index")
	void shouldCreateReversedChangeCatalogCapturePredicateWithVersionAndIndex() {
		// Create a request with a specific version and index
		ChangeCatalogCaptureRequest request = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(100L)
			.sinceIndex(5)
			.content(ChangeCaptureContent.HEADER)
			.build();

		// Create the predicate
		MutationPredicate predicate = MutationPredicateFactory.createReversedChangeCatalogCapturePredicate(request);

		// Verify the predicate is not null
		assertNotNull(predicate);

		// create work mutation
		EntityUpsertMutation entityMutation = new EntityUpsertMutation("product", 1, EntityExistence.MAY_EXIST);

		// Create a transaction mutation with version 101
		TransactionMutation newerMutation = new TransactionMutation(
			UUID.randomUUID(), 101L, 10, 100L, OffsetDateTime.now()
		);

		// The predicate should reject mutations with version > 100 regardless of index
		predicate.getContext().setVersion(newerMutation.getVersion(), newerMutation.getMutationCount());
		assertFalse(predicate.test(newerMutation));
		for (int i = 0; i < 10; i++) {
			predicate.getContext().advance();
			// The predicate should reject all mutations in this transaction
			assertFalse(predicate.test(entityMutation));
		}

		// Create a transaction mutation with version 100
		TransactionMutation correctTransaction = new TransactionMutation(
			UUID.randomUUID(), 100L, 10, 100L, OffsetDateTime.now()
		);

		// The predicate should accept transaction mutation with correct version
		predicate.getContext().setVersion(correctTransaction.getVersion(), correctTransaction.getMutationCount());
		assertTrue(predicate.test(correctTransaction));
		for (int i = 0; i < 5; i++) {
			predicate.getContext().advance();
			// The predicate should reject mutations with version = 100 and index < 5
			assertFalse(predicate.test(entityMutation));
		}
		// but the fifth and subsequent mutations should be rejected
		for (int i = 0; i < 5; i++) {
			predicate.getContext().advance();
			assertTrue(predicate.test(entityMutation));
		}

		// Create a transaction mutation with version 99
		// The context will track the index as mutations are processed
		TransactionMutation olderMutation = new TransactionMutation(
			UUID.randomUUID(), 99L, 10, 100L, OffsetDateTime.now()
		);

		// The predicate should accept mutations with version < 100 regardless of index
		predicate.getContext().setVersion(olderMutation.getVersion(), olderMutation.getMutationCount());
		assertTrue(predicate.test(olderMutation));
		for (int i = 0; i < 10; i++) {
			predicate.getContext().advance();
			// The predicate should accept all mutations in this transaction
			assertTrue(predicate.test(entityMutation));
		}
	}

    /**
     * Tests the {@link MutationPredicateFactory#createReversedChangeCatalogCapturePredicate(ChangeCatalogCaptureRequest)} method
     * with a request that specifies a version.
     *
     * Verifies that the created predicate correctly filters mutations in reverse order based on the specified version.
     */
   	@Test
   	@DisplayName("create reversed predicate for catalog capture")
   	void shouldCreateReversedChangeCatalogCapturePredicate() {
        // Create a request with a specific version
        ChangeCatalogCaptureRequest request = ChangeCatalogCaptureRequest.builder()
            .sinceVersion(100L)
            .content(ChangeCaptureContent.HEADER)
            .build();

        // Create the predicate
        MutationPredicate predicate = MutationPredicateFactory.createReversedChangeCatalogCapturePredicate(request);

        // Verify the predicate is not null
        assertNotNull(predicate);

        // Create a transaction mutation with version 99
        TransactionMutation mutation = new TransactionMutation(
            UUID.randomUUID(), 99L, 10, 100L, OffsetDateTime.now()
        );

        // The predicate should accept mutations with version < 100 in reverse order
        assertTrue(predicate.test(mutation));

        // Create a transaction mutation with version 101
        TransactionMutation newerMutation = new TransactionMutation(
            UUID.randomUUID(), 101L, 10, 100L, OffsetDateTime.now()
        );

        // The predicate should reject mutations with version > 100 in reverse order
	    predicate.getContext().setVersion(newerMutation.getVersion(), newerMutation.getMutationCount());
        assertFalse(predicate.test(newerMutation));
    }

	/**
     * Tests the {@link MutationPredicateFactory#createCriteriaPredicate(ChangeCatalogCaptureCriteria, MutationPredicateContext)} method
     * with criteria that specifies a schema area.
     *
     * Verifies that the created predicate correctly filters mutations based on the schema area.
     */
    @Test
    @DisplayName("create criteria predicate for schema area")
    void shouldCreateCriteriaPredicateForSchemaArea() {
        // Create a context
        MutationPredicateContext context = new MutationPredicateContext(Mutation.StreamDirection.FORWARD);

        // Create criteria for schema area
        ChangeCatalogCaptureCriteria criteria = ChangeCatalogCaptureCriteria.builder()
            .area(CaptureArea.SCHEMA)
            .site(SchemaSite.builder()
                .entityType("product")
                .operation(Operation.UPSERT)
                .build())
            .build();

        // Create the predicate
        MutationPredicate predicate = MutationPredicateFactory.createCriteriaPredicate(criteria, context);

        // Verify the predicate is not null
        assertNotNull(predicate);

	    final CreateAttributeSchemaMutation attributeCreation = new CreateAttributeSchemaMutation(
			"name", null, null, null,
		    true, false, false, false, false,
		    String.class, null, 0
	    );

	    // Create a matching schema mutation for product entity type
	    ModifyEntitySchemaMutation matchingMutation = new ModifyEntitySchemaMutation(
		    "product",
		    attributeCreation
	    );

	    // Create a non-matching schema mutation for different entity type
	    ModifyEntitySchemaMutation wrongTypeMutation = new ModifyEntitySchemaMutation(
		    "category",
		    attributeCreation
	    );

	    // The predicate should accept mutations matching the criteria
	    assertTrue(predicate.test(matchingMutation));
	    // The predicate should reject mutations with wrong entity type
	    assertFalse(predicate.test(wrongTypeMutation));
    }

    /**
     * Tests the {@link MutationPredicateFactory#createCriteriaPredicate(ChangeCatalogCaptureCriteria, MutationPredicateContext)} method
     * with criteria that specifies a data area.
     *
     * Verifies that the created predicate correctly filters mutations based on the data area.
     */
    @Test
    @DisplayName("create criteria predicate for data area")
    void shouldCreateCriteriaPredicateForDataArea() {
        // Create a context
        MutationPredicateContext context = new MutationPredicateContext(Mutation.StreamDirection.FORWARD);

        // Create criteria for data area
        ChangeCatalogCaptureCriteria criteria = ChangeCatalogCaptureCriteria.builder()
            .area(CaptureArea.DATA)
            .site(DataSite.builder()
                .entityType("product")
                .entityPrimaryKey(1)
                .operation(Operation.UPSERT)
                .build())
            .build();

        // Create the predicate
        MutationPredicate predicate = MutationPredicateFactory.createCriteriaPredicate(criteria, context);

        // Verify the predicate is not null
        assertNotNull(predicate);

	    // Create a matching mutation for product entity type with ID 1
	    final EntityUpsertMutation matchingMutation = new EntityUpsertMutation("product", 1, EntityExistence.MAY_EXIST);

	    // Create a non-matching mutation with different entity type
	    final EntityUpsertMutation wrongTypeMutation = new EntityUpsertMutation("category", 1, EntityExistence.MAY_EXIST);

	    // Create a non-matching mutation with different primary key
	    final EntityUpsertMutation wrongIdMutation = new EntityUpsertMutation("product", 2, EntityExistence.MAY_EXIST);

	    // The predicate should accept mutations matching the criteria
	    assertTrue(predicate.test(matchingMutation));
	    // The predicate should reject mutations with wrong entity type
	    assertFalse(predicate.test(wrongTypeMutation));
	    // The predicate should reject mutations with wrong primary key
	    assertFalse(predicate.test(wrongIdMutation));
    }

    /**
     * Tests the {@link MutationPredicateFactory#createPredicateUsingComparator(Long, Integer, ChangeCatalogCaptureCriteria[], Comparator, Comparator, StreamDirection)}
     * method with an array of criteria.
     *
     * Verifies that the created predicate correctly combines multiple criteria with OR logic.
     */
    @Test
    @DisplayName("create predicate combining multiple criteria with OR logic")
    void shouldCreatePredicateUsingComparatorWithCriteriaArray() {
        // Create criteria for data area with PK 1
	    ChangeCatalogCaptureCriteria dataCriteria1 = ChangeCatalogCaptureCriteria.builder()
		    .area(CaptureArea.DATA)
		    .site(DataSite.builder()
			    .entityType("product")
			    .entityPrimaryKey(1)
			    .operation(Operation.UPSERT)
			    .build())
		    .build();

	    // Create criteria for data area with PK 2
	    ChangeCatalogCaptureCriteria dataCriteria2 = ChangeCatalogCaptureCriteria.builder()
		    .area(CaptureArea.DATA)
		    .site(DataSite.builder()
			    .entityType("product")
			    .entityPrimaryKey(2)
			    .operation(Operation.UPSERT)
			    .build())
		    .build();

        // Create the predicate with both criteria
        MutationPredicate predicate = MutationPredicateFactory.createPredicateUsingComparator(
			null, null,
            new ChangeCatalogCaptureCriteria[]{dataCriteria2, dataCriteria1},
            Comparator.naturalOrder(), Comparator.naturalOrder(),
	        StreamDirection.FORWARD
        );

	    // Create a matching data mutation 1
	    final EntityUpsertMutation dataMutation1 = new EntityUpsertMutation("product", 1, EntityExistence.MAY_EXIST);
	    // Create a matching data mutation 2
	    final EntityUpsertMutation dataMutation2 = new EntityUpsertMutation("product", 2, EntityExistence.MAY_EXIST);
	    // Create a non-matching data mutation 3
	    final EntityUpsertMutation dataMutation3 = new EntityUpsertMutation("product", 3, EntityExistence.MAY_EXIST);
	    // Create a non-matching mutation
	    final EntityUpsertMutation wrongMutation = new EntityUpsertMutation("category", 2, EntityExistence.MAY_EXIST);

	    // The predicate should accept mutations matching data criteria with PK 1 or 2
	    assertTrue(predicate.test(dataMutation1));
	    assertTrue(predicate.test(dataMutation2));
	    // The predicate should reject mutations not matching data criteria
	    assertFalse(predicate.test(dataMutation3));
	    // The predicate should reject mutations not matching any criteria
	    assertFalse(predicate.test(wrongMutation));

    }

    /**
     * Tests the {@link MutationPredicateFactory#createPredicateUsingComparator(Long, Integer, ChangeCatalogCaptureCriteria[], Comparator, Comparator, StreamDirection)}
     * method with null criteria.
     *
     * Verifies that the created predicate is a TruePredicate that accepts all mutations.
     */
    @Test
    @DisplayName("create true predicate when criteria is null")
    void shouldCreatePredicateUsingComparatorWithNullCriteria() {
        // Create the predicate with null criteria
        MutationPredicate predicate = MutationPredicateFactory.createPredicateUsingComparator(
			null, null, null,
	        Comparator.naturalOrder(), Comparator.naturalOrder(),
	        StreamDirection.FORWARD
        );

        // Verify the predicate is not null
        assertNotNull(predicate);

        // Create a transaction mutation
        TransactionMutation mutation = new TransactionMutation(
            UUID.randomUUID(), 100L, 10, 100L, OffsetDateTime.now()
        );

        // The predicate should accept all mutations (TruePredicate)
        assertTrue(predicate.test(mutation));
    }

    /**
     * Tests the {@link MutationPredicateFactory#createChangeCatalogCapturePredicate(ChangeCatalogCaptureRequest)} method
     * with a request that specifies criteria.
     *
     * Verifies that the created predicate correctly filters mutations based on the specified criteria.
     */
    @Test
    @DisplayName("create predicate that filters mutations by criteria")
    void shouldCreateChangeCatalogCapturePredicateWithCriteria() {
        // Create criteria for schema area
        ChangeCatalogCaptureCriteria dataCriteria = ChangeCatalogCaptureCriteria.builder()
            .area(CaptureArea.DATA)
            .site(DataSite.builder()
                .entityType("product")
                .operation(Operation.UPSERT)
	            .entityPrimaryKey(1)
                .containerType(ContainerType.ATTRIBUTE)
                .build())
            .build();

        // Create a request with criteria
        ChangeCatalogCaptureRequest request = ChangeCatalogCaptureRequest.builder()
            .sinceVersion(100L)
            .content(ChangeCaptureContent.HEADER)
            .criteria(dataCriteria)
            .build();

        // Create the predicate
        MutationPredicate predicate = MutationPredicateFactory.createChangeCatalogCapturePredicate(request);

        // Verify the predicate is not null
        assertNotNull(predicate);
    }
}
