/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.api.requestResponse.transaction;

import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.mutation.StreamDirection;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.store.wal.supplier.TransactionMutationWithLocation;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TransactionMutation} verifying construction, getter
 * access, CDC capture generation, conflict key collection, equality,
 * and string representation.
 *
 * @author evitaDB
 */
@DisplayName("TransactionMutation")
class TransactionMutationTest implements EvitaTestSupport {

	private static final UUID TX_ID =
		UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
	private static final long VERSION = 42L;
	private static final int MUTATION_COUNT = 10;
	private static final long WAL_SIZE = 2048L;
	private static final OffsetDateTime TIMESTAMP =
		OffsetDateTime.of(2025, 6, 15, 12, 30, 0, 0, ZoneOffset.UTC);

	/**
	 * Creates a {@link TransactionMutation} with the shared test constants.
	 *
	 * @return a new default test instance
	 */
	@Nonnull
	private static TransactionMutation createDefault() {
		return new TransactionMutation(
			TX_ID, VERSION, MUTATION_COUNT, WAL_SIZE, TIMESTAMP
		);
	}

	/**
	 * Creates a simple {@link MutationPredicate} that always returns
	 * the given result.
	 *
	 * @param result the boolean value the predicate should return
	 * @return a new constant predicate backed by a FORWARD context
	 */
	@Nonnull
	private static MutationPredicate constantPredicate(boolean result) {
		final MutationPredicateContext context =
			new MutationPredicateContext(StreamDirection.FORWARD);
		return new MutationPredicate(context) {
			@Override
			public boolean test(@Nonnull Mutation mutation) {
				return result;
			}
		};
	}

	// -- Nested test groups -------------------------------------------

	@Nested
	@DisplayName("Construction and getters")
	class ConstructionAndGettersTest {

		@Test
		@DisplayName("should store all fields correctly")
		void shouldStoreAllFieldsCorrectly() {
			final TransactionMutation mutation = createDefault();

			assertAll(
				() -> assertEquals(TX_ID, mutation.getTransactionId()),
				() -> assertEquals(VERSION, mutation.getVersion()),
				() -> assertEquals(
					MUTATION_COUNT, mutation.getMutationCount()
				),
				() -> assertEquals(
					WAL_SIZE, mutation.getWalSizeInBytes()
				),
				() -> assertEquals(TIMESTAMP, mutation.getCommitTimestamp())
			);
		}

		@Test
		@DisplayName("should accept zero version")
		void shouldAcceptZeroVersion() {
			final TransactionMutation mutation =
				new TransactionMutation(
					TX_ID, 0L, MUTATION_COUNT, WAL_SIZE, TIMESTAMP
				);

			assertEquals(0L, mutation.getVersion());
		}

		@Test
		@DisplayName("should accept zero mutation count")
		void shouldAcceptZeroMutationCount() {
			final TransactionMutation mutation =
				new TransactionMutation(
					TX_ID, VERSION, 0, WAL_SIZE, TIMESTAMP
				);

			assertEquals(0, mutation.getMutationCount());
		}

		@Test
		@DisplayName("should accept zero WAL size")
		void shouldAcceptZeroWalSize() {
			final TransactionMutation mutation =
				new TransactionMutation(
					TX_ID, VERSION, MUTATION_COUNT, 0L, TIMESTAMP
				);

			assertEquals(0L, mutation.getWalSizeInBytes());
		}
	}

	@Nested
	@DisplayName("Operation and progress type")
	class OperationAndProgressTypeTest {

		@Test
		@DisplayName("should return TRANSACTION operation")
		void shouldReturnTransactionOperation() {
			final TransactionMutation mutation = createDefault();

			assertEquals(Operation.TRANSACTION, mutation.operation());
		}

		@Test
		@DisplayName("should return Void progress result type")
		void shouldReturnVoidProgressResultType() {
			final TransactionMutation mutation = createDefault();

			assertEquals(Void.class, mutation.getProgressResultType());
		}
	}

	@Nested
	@DisplayName("Context preparation")
	class ContextPreparationTest {

		@Test
		@DisplayName("should set version, mutation count, and timestamp")
		void shouldSetVersionOnContext() {
			final TransactionMutation mutation = createDefault();
			final MutationPredicateContext context =
				new MutationPredicateContext(StreamDirection.FORWARD);

			mutation.prepareContext(context);

			assertAll(
				() -> assertEquals(
					VERSION, context.getVersion()
				),
				() -> assertEquals(
					TIMESTAMP, context.getTimestamp()
				),
				// setVersion resets index to 0
				() -> assertEquals(0, context.getIndex())
			);
		}

		@Test
		@DisplayName(
			"should not call advance on context"
		)
		void shouldNotCallAdvanceOnContext() {
			// The default EngineMutation.prepareContext calls
			// context.advance(), but TransactionMutation overrides
			// it to call context.setVersion() instead.
			final TransactionMutation mutation = createDefault();
			final MutationPredicateContext context =
				new MutationPredicateContext(StreamDirection.FORWARD);
			// pre-set a version so advance() would increment from 0
			context.setVersion(1L, 5, TIMESTAMP);

			mutation.prepareContext(context);

			// If advance() had been called, index would be 1.
			// setVersion() resets index to 0.
			assertEquals(0, context.getIndex());
			// version should be overwritten by the mutation's version
			assertEquals(VERSION, context.getVersion());
		}
	}

	@Nested
	@DisplayName("Change catalog capture")
	class ChangeCatalogCaptureTest {

		@Test
		@DisplayName(
			"should return capture with body when predicate matches"
		)
		void shouldReturnCaptureWhenPredicateMatches() {
			final TransactionMutation mutation = createDefault();
			final MutationPredicate predicate = constantPredicate(true);

			final List<ChangeCatalogCapture> captures =
				mutation.toChangeCatalogCapture(
					predicate, ChangeCaptureContent.BODY
				).toList();

			assertEquals(1, captures.size());
			final ChangeCatalogCapture capture = captures.get(0);
			assertAll(
				() -> assertEquals(VERSION, capture.version()),
				() -> assertEquals(0, capture.index()),
				() -> assertEquals(
					TIMESTAMP, capture.timestamp()
				),
				() -> assertEquals(
					CaptureArea.INFRASTRUCTURE, capture.area()
				),
				() -> assertEquals(
					Operation.TRANSACTION, capture.operation()
				),
				() -> assertSame(mutation, capture.body())
			);
		}

		@Test
		@DisplayName(
			"should return capture without body when HEADER requested"
		)
		void shouldReturnCaptureWithoutBodyWhenHeaderRequested() {
			final TransactionMutation mutation = createDefault();
			final MutationPredicate predicate = constantPredicate(true);

			final List<ChangeCatalogCapture> captures =
				mutation.toChangeCatalogCapture(
					predicate, ChangeCaptureContent.HEADER
				).toList();

			assertEquals(1, captures.size());
			assertNull(captures.get(0).body());
		}

		@Test
		@DisplayName(
			"should return empty stream when predicate rejects"
		)
		void shouldReturnEmptyStreamWhenPredicateRejects() {
			final TransactionMutation mutation = createDefault();
			final MutationPredicate predicate = constantPredicate(false);

			final List<ChangeCatalogCapture> captures =
				mutation.toChangeCatalogCapture(
					predicate, ChangeCaptureContent.BODY
				).toList();

			assertTrue(captures.isEmpty());
		}

		@Test
		@DisplayName(
			"should set version on context even when predicate rejects"
		)
		void shouldSetVersionOnContextEvenWhenPredicateRejects() {
			final TransactionMutation mutation = createDefault();
			final MutationPredicate predicate = constantPredicate(false);

			mutation.toChangeCatalogCapture(
				predicate, ChangeCaptureContent.BODY
			).toList();

			// context side-effect happens before predicate test
			final MutationPredicateContext context =
				predicate.getContext();
			assertEquals(VERSION, context.getVersion());
			assertEquals(TIMESTAMP, context.getTimestamp());
		}

		@Test
		@DisplayName(
			"should use INFRASTRUCTURE area, not DATA or SCHEMA"
		)
		void shouldUseInfrastructureArea() {
			final TransactionMutation mutation = createDefault();
			final MutationPredicate predicate = constantPredicate(true);

			final List<ChangeCatalogCapture> captures =
				mutation.toChangeCatalogCapture(
					predicate, ChangeCaptureContent.BODY
				).toList();

			assertEquals(
				CaptureArea.INFRASTRUCTURE,
				captures.get(0).area()
			);
		}
	}

	@Nested
	@DisplayName("Change system capture")
	class ChangeSystemCaptureTest {

		@Test
		@DisplayName(
			"should return system capture when predicate matches"
		)
		void shouldReturnSystemCaptureWhenPredicateMatches() {
			final TransactionMutation mutation = createDefault();
			final MutationPredicate predicate = constantPredicate(true);

			final List<ChangeSystemCapture> captures =
				mutation.toChangeSystemCapture(
					predicate, ChangeCaptureContent.BODY
				).toList();

			assertEquals(1, captures.size());
			final ChangeSystemCapture capture = captures.get(0);
			assertAll(
				() -> assertEquals(VERSION, capture.version()),
				() -> assertEquals(0, capture.index()),
				() -> assertEquals(
					TIMESTAMP, capture.timestamp()
				),
				() -> assertEquals(
					Operation.TRANSACTION, capture.operation()
				),
				() -> assertSame(mutation, capture.body())
			);
		}

		@Test
		@DisplayName(
			"should return system capture without body for HEADER"
		)
		void shouldReturnSystemCaptureWithoutBodyForHeader() {
			final TransactionMutation mutation = createDefault();
			final MutationPredicate predicate = constantPredicate(true);

			final List<ChangeSystemCapture> captures =
				mutation.toChangeSystemCapture(
					predicate, ChangeCaptureContent.HEADER
				).toList();

			assertEquals(1, captures.size());
			assertNull(captures.get(0).body());
		}

		@Test
		@DisplayName(
			"should return empty stream when predicate rejects"
		)
		void shouldReturnEmptyStreamWhenPredicateRejects() {
			final TransactionMutation mutation = createDefault();
			final MutationPredicate predicate = constantPredicate(false);

			final List<ChangeSystemCapture> captures =
				mutation.toChangeSystemCapture(
					predicate, ChangeCaptureContent.BODY
				).toList();

			assertTrue(captures.isEmpty());
		}
	}

	@Nested
	@DisplayName("Conflict keys")
	class ConflictKeysTest {

		@Test
		@DisplayName("should return empty conflict keys")
		void shouldReturnEmptyConflictKeys() {
			final TransactionMutation mutation = createDefault();
			final ConflictGenerationContext context =
				new ConflictGenerationContext();

			final Stream<ConflictKey> keys =
				mutation.collectConflictKeys(
					context, Set.of(ConflictPolicy.CATALOG)
				);

			assertEquals(0L, keys.count());
		}

		@Test
		@DisplayName(
			"should return empty conflict keys with multiple policies"
		)
		void shouldReturnEmptyConflictKeysWithMultiplePolicies() {
			final TransactionMutation mutation = createDefault();
			final ConflictGenerationContext context =
				new ConflictGenerationContext();
			final Set<ConflictPolicy> allPolicies =
				EnumSet.allOf(ConflictPolicy.class);

			final Stream<ConflictKey> keys =
				mutation.collectConflictKeys(context, allPolicies);

			assertEquals(0L, keys.count());
		}
	}

	@Nested
	@DisplayName("Applicability verification")
	class ApplicabilityTest {

		@Test
		@DisplayName("should not throw on verifyApplicability")
		void shouldNotThrowOnVerifyApplicability() {
			final TransactionMutation mutation = createDefault();

			// verifyApplicability is a no-op; passing null should
			// not cause issues since the body is empty
			assertDoesNotThrow(
				() -> mutation.verifyApplicability(null)
			);
		}
	}

	@Nested
	@DisplayName("Equality and hash code")
	class EqualityAndHashCodeTest {

		@Test
		@DisplayName("should be equal to itself (reflexive)")
		void shouldBeEqualToItself() {
			final TransactionMutation mutation = createDefault();

			assertEquals(mutation, mutation);
		}

		@Test
		@DisplayName(
			"should be equal to identical instance (symmetric)"
		)
		void shouldBeEqualToIdenticalInstance() {
			final TransactionMutation a = createDefault();
			final TransactionMutation b = createDefault();

			assertAll(
				() -> assertEquals(a, b),
				() -> assertEquals(b, a)
			);
		}

		@Test
		@DisplayName(
			"should not be equal when transaction ID differs"
		)
		void shouldNotBeEqualWhenTransactionIdDiffers() {
			final TransactionMutation a = createDefault();
			final TransactionMutation b = new TransactionMutation(
				UUID.randomUUID(),
				VERSION, MUTATION_COUNT, WAL_SIZE, TIMESTAMP
			);

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should not be equal when version differs")
		void shouldNotBeEqualWhenVersionDiffers() {
			final TransactionMutation a = createDefault();
			final TransactionMutation b = new TransactionMutation(
				TX_ID, VERSION + 1,
				MUTATION_COUNT, WAL_SIZE, TIMESTAMP
			);

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final TransactionMutation mutation = createDefault();

			assertNotEquals(null, mutation);
		}

		@Test
		@DisplayName(
			"should have consistent hash code for equal instances"
		)
		void shouldHaveConsistentHashCodeForEqualInstances() {
			final TransactionMutation a = createDefault();
			final TransactionMutation b = createDefault();

			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName(
			"should not be equal when mutation count differs"
		)
		void shouldNotBeEqualWhenMutationCountDiffers() {
			final TransactionMutation a = createDefault();
			final TransactionMutation b = new TransactionMutation(
				TX_ID, VERSION,
				MUTATION_COUNT + 1, WAL_SIZE, TIMESTAMP
			);

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName(
			"should not be equal when WAL size differs"
		)
		void shouldNotBeEqualWhenWalSizeDiffers() {
			final TransactionMutation a = createDefault();
			final TransactionMutation b = new TransactionMutation(
				TX_ID, VERSION,
				MUTATION_COUNT, WAL_SIZE + 1, TIMESTAMP
			);

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName(
			"should not be equal when commit timestamp differs"
		)
		void shouldNotBeEqualWhenCommitTimestampDiffers() {
			final TransactionMutation a = createDefault();
			final TransactionMutation b = new TransactionMutation(
				TX_ID, VERSION, MUTATION_COUNT, WAL_SIZE,
				TIMESTAMP.plusHours(1)
			);

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName(
			"should not be equal when location fields differ"
		)
		void shouldNotBeEqualWhenLocationFieldsDiffer() {
			final TransactionMutation base = createDefault();
			final TransactionMutationWithLocation withLoc =
				new TransactionMutationWithLocation(
					base,
					new FileLocation(0L, 100),
					1
				);
			final TransactionMutationWithLocation withDiffLoc =
				new TransactionMutationWithLocation(
					base,
					new FileLocation(999L, 500),
					7
				);

			// Two subclass instances with different location
			// fields must NOT be equal
			assertNotEquals(
				withLoc, withDiffLoc,
				"Subclass instances with different "
					+ "transactionSpan/walFileIndex "
					+ "must not be equal"
			);
		}

		@Test
		@DisplayName(
			"should not be equal to parent with same base fields"
		)
		void shouldNotBeEqualToParentWithSameBaseFields() {
			final TransactionMutation base = createDefault();
			final TransactionMutationWithLocation withLoc =
				new TransactionMutationWithLocation(
					base,
					new FileLocation(0L, 100),
					1
				);

			// Parent and subclass must NOT be equal even when
			// all base fields match
			assertNotEquals(
				base, withLoc,
				"Parent and subclass must not be equal"
			);
			assertNotEquals(
				withLoc, base,
				"Subclass and parent must not be equal"
			);
		}

		@Test
		@DisplayName(
			"should be equal when all fields including "
				+ "location match"
		)
		void shouldBeEqualWhenAllFieldsMatch() {
			final TransactionMutation base = createDefault();
			final TransactionMutationWithLocation a =
				new TransactionMutationWithLocation(
					base,
					new FileLocation(0L, 100),
					1
				);
			final TransactionMutationWithLocation b =
				new TransactionMutationWithLocation(
					base,
					new FileLocation(0L, 100),
					1
				);

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}
	}

	@Nested
	@DisplayName("String representation")
	class StringRepresentationTest {

		@Test
		@DisplayName("should contain transaction ID in toString")
		void shouldContainTransactionIdInToString() {
			final TransactionMutation mutation = createDefault();

			final String result = mutation.toString();

			assertTrue(
				result.contains(TX_ID.toString()),
				"toString should contain the transaction UUID"
			);
		}

		@Test
		@DisplayName("should contain version in toString")
		void shouldContainVersionInToString() {
			final TransactionMutation mutation = createDefault();

			final String result = mutation.toString();

			assertTrue(
				result.contains(String.valueOf(VERSION)),
				"toString should contain the version number"
			);
		}

		@Test
		@DisplayName("should match expected format")
		void shouldMatchExpectedFormat() {
			final TransactionMutation mutation = createDefault();

			final String expected =
				"transaction commit `" + TX_ID
					+ "` (moves persistent state to version `"
					+ VERSION + "`)";

			assertEquals(expected, mutation.toString());
		}
	}
}
