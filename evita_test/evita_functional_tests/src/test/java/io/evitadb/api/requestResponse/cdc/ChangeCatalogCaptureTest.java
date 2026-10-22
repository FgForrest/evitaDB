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

package io.evitadb.api.requestResponse.cdc;

import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.mutation.StreamDirection;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.CDC;

/**
 * Tests for {@link ChangeCatalogCapture} verifying factory methods, the `as()` method behavior,
 * and equality/hashCode semantics.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ChangeCatalogCapture")
@Tag(CONTRACT)
@Tag(CDC)
class ChangeCatalogCaptureTest implements EvitaTestSupport {

	private static final OffsetDateTime TIMESTAMP = OffsetDateTime.now();
	/**
	 * Identity of the catalog incarnation the captures of these tests belong to.
	 */
	private static final UUID CATALOG_ID = UUID.fromString("6f0d3e8a-6c1b-4a7e-9a52-0d1f8b4c2e11");
	/**
	 * Identity of another incarnation of the same catalog.
	 */
	private static final UUID OTHER_CATALOG_ID = UUID.fromString("b3a9c2d4-1e7f-4f60-8d2b-7c5e9a1f3d22");

	@Nested
	@DisplayName("Factory methods")
	class FactoryMethods {

		@Test
		@DisplayName("should create data capture with entity primary key")
		void shouldCreateDataCaptureWithEntityPrimaryKey() {
			final MutationPredicateContext context = createContext(1L, 0, "product", 42);
			final ChangeCatalogCapture capture = ChangeCatalogCapture.dataCapture(
				context, Operation.UPSERT, null
			);

			assertNotNull(capture);
			assertEquals(CATALOG_ID, capture.catalogId());
			assertEquals(1L, capture.version());
			assertEquals(0, capture.index());
			assertEquals(CaptureArea.DATA, capture.area());
			assertEquals("product", capture.entityType());
			assertEquals(42, capture.entityPrimaryKey());
			assertEquals(Operation.UPSERT, capture.operation());
			assertNull(capture.body());
		}

		@Test
		@DisplayName("should create data capture without entity primary key")
		void shouldCreateDataCaptureWithoutEntityPrimaryKey() {
			final MutationPredicateContext context = createContext(1L, 0, "product", null);
			final ChangeCatalogCapture capture = ChangeCatalogCapture.dataCapture(
				context, Operation.REMOVE, null
			);

			assertNull(capture.entityPrimaryKey());
			assertEquals(CaptureArea.DATA, capture.area());
		}

		@Test
		@DisplayName("should create schema capture with null entity primary key")
		void shouldCreateSchemaCapture() {
			final MutationPredicateContext context = createContext(2L, 1, "product", 42);
			final ChangeCatalogCapture capture = ChangeCatalogCapture.schemaCapture(
				context, Operation.UPSERT, null
			);

			assertNotNull(capture);
			assertEquals(CATALOG_ID, capture.catalogId());
			assertEquals(2L, capture.version());
			assertEquals(1, capture.index());
			assertEquals(CaptureArea.SCHEMA, capture.area());
			assertEquals("product", capture.entityType());
			assertNull(capture.entityPrimaryKey());
			assertEquals(Operation.UPSERT, capture.operation());
		}

		@Test
		@DisplayName("should create infrastructure capture")
		void shouldCreateInfrastructureCapture() {
			final MutationPredicateContext context = createContext(3L, 0, null, null);
			final ChangeCatalogCapture capture = ChangeCatalogCapture.infrastructureCapture(
				context, Operation.TRANSACTION, null
			);

			assertNotNull(capture);
			assertEquals(CATALOG_ID, capture.catalogId());
			assertEquals(CaptureArea.INFRASTRUCTURE, capture.area());
			assertNull(capture.entityPrimaryKey());
			assertEquals(Operation.TRANSACTION, capture.operation());
		}

		@Test
		@DisplayName("should leave the catalog identity unknown when the context does not know it")
		void shouldLeaveCatalogIdUnknownWhenContextDoesNotKnowIt() {
			final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
			context.setVersion(1L, 10, TIMESTAMP);
			final ChangeCatalogCapture capture = ChangeCatalogCapture.infrastructureCapture(
				context, Operation.TRANSACTION, null
			);

			assertNull(capture.catalogId());
		}

		@Test
		@SuppressWarnings("removal")
		@DisplayName("should leave the catalog identity unknown when created by the compatibility constructor")
		void shouldLeaveCatalogIdUnknownWhenCreatedByCompatibilityConstructor() {
			final ChangeCatalogCapture capture = new ChangeCatalogCapture(
				1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);

			assertNull(capture.catalogId());
			assertEquals(1L, capture.version());
			assertEquals(
				new ChangeCatalogCapture(
					null, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
				),
				capture
			);
		}
	}

	@Nested
	@DisplayName("as() method")
	class AsMethod {

		@Test
		@DisplayName("should return self when requesting BODY with body present")
		void shouldReturnSelfWhenRequestingBodyWithBodyPresent() {
			final ChangeCatalogCapture capture = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);
			// body is null, so requesting BODY should throw
			assertThrows(Exception.class, () -> capture.as(ChangeCaptureContent.BODY));
		}

		@Test
		@DisplayName("should throw when requesting BODY with null body")
		void shouldThrowWhenRequestingBodyWithNullBody() {
			final ChangeCatalogCapture capture = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);

			assertThrows(Exception.class, () -> capture.as(ChangeCaptureContent.BODY));
		}

		@Test
		@DisplayName("should strip body when requesting HEADER with body present")
		void shouldStripBodyWhenRequestingHeaderWithBodyPresent() {
			// we can't easily create a real CatalogBoundMutation, so we test the null-body path
			final ChangeCatalogCapture capture = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);
			final ChangeCatalogCapture result = capture.as(ChangeCaptureContent.HEADER);

			assertSame(capture, result);
		}

		@Test
		@DisplayName("should keep the catalog identity when stripping the body for HEADER content")
		void shouldKeepCatalogIdWhenStrippingBody() {
			final ChangeCatalogCapture capture = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.REMOVE,
				new EntityRemoveMutation("product", 1)
			);
			final ChangeCatalogCapture header = capture.as(ChangeCaptureContent.HEADER);

			assertNull(header.body());
			assertEquals(
				CATALOG_ID, header.catalogId(),
				"A capture delivered without its body is still a position the consumer stores - stripping the " +
					"body must not strip the incarnation it belongs to."
			);
			assertEquals(capture.version(), header.version());
			assertEquals(capture.index(), header.index());
		}

		@Test
		@DisplayName("should return self when requesting HEADER without body")
		void shouldReturnSelfWhenRequestingHeaderWithoutBody() {
			final ChangeCatalogCapture capture = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);
			final ChangeCatalogCapture result = capture.as(ChangeCaptureContent.HEADER);

			assertSame(capture, result);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityAndHashCode {

		@Test
		@DisplayName("should be equal for same version and index")
		void shouldBeEqualForSameVersionAndIndex() {
			final ChangeCatalogCapture capture1 = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);
			final ChangeCatalogCapture capture2 = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);

			assertEquals(capture1, capture2);
			assertEquals(capture1.hashCode(), capture2.hashCode());
		}

		@Test
		@DisplayName("should exclude timestamp from equality (version+index identify position)")
		void shouldExcludeTimestampFromEquality() {
			final OffsetDateTime otherTimestamp = TIMESTAMP.plusHours(1);
			final ChangeCatalogCapture capture1 = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);
			final ChangeCatalogCapture capture2 = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, otherTimestamp, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);

			assertEquals(capture1, capture2);
			assertEquals(capture1.hashCode(), capture2.hashCode());
		}

		@Test
		@DisplayName("should be reflexive")
		void shouldBeReflexive() {
			final ChangeCatalogCapture capture = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);

			assertEquals(capture, capture);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final ChangeCatalogCapture capture = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);

			assertNotEquals(null, capture);
		}

		@Test
		@DisplayName("should not be equal to different type")
		void shouldNotBeEqualToDifferentType() {
			final ChangeCatalogCapture capture = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);

			assertNotEquals("not a capture", capture);
		}

		@Test
		@DisplayName("should be not equal when version differs")
		void shouldNotBeEqualWhenVersionDiffers() {
			final ChangeCatalogCapture capture1 = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);
			final ChangeCatalogCapture capture2 = new ChangeCatalogCapture(
				CATALOG_ID, 2L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);

			assertNotEquals(capture1, capture2);
		}

		@Test
		@DisplayName("should be not equal when index differs")
		void shouldNotBeEqualWhenIndexDiffers() {
			final ChangeCatalogCapture capture1 = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);
			final ChangeCatalogCapture capture2 = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 1, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);

			assertNotEquals(capture1, capture2);
		}

		@Test
		@DisplayName("should not be equal when only the catalog identity differs")
		void shouldNotBeEqualWhenCatalogIdDiffers() {
			final ChangeCatalogCapture capture1 = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);
			final ChangeCatalogCapture capture2 = new ChangeCatalogCapture(
				OTHER_CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);
			final ChangeCatalogCapture capture3 = new ChangeCatalogCapture(
				null, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);

			// version 1 of one incarnation is a different position from version 1 of another
			assertNotEquals(capture1, capture2);
			assertNotEquals(capture1.hashCode(), capture2.hashCode());
			assertNotEquals(capture1, capture3);
			assertNotEquals(capture3, capture1);
		}

		@Test
		@DisplayName("should be not equal when area differs")
		void shouldNotBeEqualWhenAreaDiffers() {
			final ChangeCatalogCapture capture1 = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
			);
			final ChangeCatalogCapture capture2 = new ChangeCatalogCapture(
				CATALOG_ID, 1L, 0, TIMESTAMP, CaptureArea.SCHEMA, "product", 1, Operation.UPSERT, null
			);

			assertNotEquals(capture1, capture2);
		}
	}

	/**
	 * Helper method to create a {@link MutationPredicateContext} for testing.
	 */
	private static MutationPredicateContext createContext(
		long version,
		int index,
		String entityType,
		Integer entityPrimaryKey
	) {
		final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD, CATALOG_ID);
		context.setVersion(version, 10, TIMESTAMP);
		if (entityType != null) {
			context.setEntityType(entityType);
		}
		if (entityPrimaryKey != null) {
			context.setEntityPrimaryKey(entityPrimaryKey);
		}
		// advance index times to reach the desired index
		for (int i = 0; i < index; i++) {
			context.advance();
		}
		return context;
	}
}
