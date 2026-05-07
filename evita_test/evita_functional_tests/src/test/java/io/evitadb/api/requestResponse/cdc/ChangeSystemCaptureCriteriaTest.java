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

package io.evitadb.api.requestResponse.cdc;

import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.CONTRACT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ChangeSystemCaptureCriteria} covering construction, builder
 * convenience methods, the natural ordering documented on the record, and the
 * standard `equals` / `hashCode` contract that the system stream's `OR-of-criteria`
 * semantics rely on.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ChangeSystemCaptureCriteria")
@Tag(CONTRACT)
@Tag(CDC)
class ChangeSystemCaptureCriteriaTest implements EvitaTestSupport {

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("should construct with engine area")
		void shouldConstructWithEngineArea() {
			final ChangeSystemCaptureCriteria criteria =
				new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE);

			assertEquals(SystemCaptureArea.ENGINE, criteria.area());
		}

		@Test
		@DisplayName("should construct with host area")
		void shouldConstructWithHostArea() {
			final ChangeSystemCaptureCriteria criteria =
				new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST);

			assertEquals(SystemCaptureArea.HOST, criteria.area());
		}

		@Test
		@DisplayName("should allow null area as match-any-area sentinel")
		void shouldAllowNullArea() {
			// `null` area is a valid match-any-area sentinel inside an explicit criterion;
			// the divergence note on the record's javadoc explains that this is NOT the same
			// as supplying a `null` criteria array on the request
			final ChangeSystemCaptureCriteria criteria = new ChangeSystemCaptureCriteria(null);

			assertNull(criteria.area());
		}
	}

	@Nested
	@DisplayName("Builder")
	class Builder {

		@Test
		@DisplayName("should build with engine area convenience method")
		void shouldBuildWithEngineAreaConvenience() {
			final ChangeSystemCaptureCriteria criteria = ChangeSystemCaptureCriteria.builder()
				.engineArea()
				.build();

			assertEquals(SystemCaptureArea.ENGINE, criteria.area());
		}

		@Test
		@DisplayName("should build with host area convenience method")
		void shouldBuildWithHostAreaConvenience() {
			final ChangeSystemCaptureCriteria criteria = ChangeSystemCaptureCriteria.builder()
				.hostArea()
				.build();

			assertEquals(SystemCaptureArea.HOST, criteria.area());
		}

		@Test
		@DisplayName("should build with explicit null area")
		void shouldBuildWithExplicitNullArea() {
			final ChangeSystemCaptureCriteria criteria = ChangeSystemCaptureCriteria.builder()
				.area(null)
				.build();

			assertNull(criteria.area());
		}

		@Test
		@DisplayName("should build with explicit area setter")
		void shouldBuildWithExplicitArea() {
			final ChangeSystemCaptureCriteria criteria = ChangeSystemCaptureCriteria.builder()
				.area(SystemCaptureArea.HOST)
				.build();

			assertEquals(SystemCaptureArea.HOST, criteria.area());
		}
	}

	@Nested
	@DisplayName("compareTo")
	class CompareTo {

		@Test
		@DisplayName("should return zero when both areas are null")
		void shouldReturnZeroWhenBothAreasNull() {
			final ChangeSystemCaptureCriteria left = new ChangeSystemCaptureCriteria(null);
			final ChangeSystemCaptureCriteria right = new ChangeSystemCaptureCriteria(null);

			assertEquals(0, left.compareTo(right));
			assertEquals(0, right.compareTo(left));
		}

		@Test
		@DisplayName("should order null area before non-null area")
		void shouldOrderNullAreaFirst() {
			final ChangeSystemCaptureCriteria nullArea = new ChangeSystemCaptureCriteria(null);
			final ChangeSystemCaptureCriteria engine =
				new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE);

			assertTrue(nullArea.compareTo(engine) < 0);
			assertTrue(engine.compareTo(nullArea) > 0);
		}

		@Test
		@DisplayName("should order ENGINE before HOST following enum declaration order")
		void shouldOrderEngineBeforeInfrastructure() {
			final ChangeSystemCaptureCriteria engine =
				new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE);
			final ChangeSystemCaptureCriteria infra =
				new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST);

			assertTrue(engine.compareTo(infra) < 0);
			assertTrue(infra.compareTo(engine) > 0);
		}

		@Test
		@DisplayName("should be reflexive")
		void shouldBeReflexive() {
			final ChangeSystemCaptureCriteria criteria =
				new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE);

			assertEquals(0, criteria.compareTo(criteria));
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityAndHashCode {

		@Test
		@DisplayName("should be equal when both areas are the same")
		void shouldBeEqualForSameArea() {
			final ChangeSystemCaptureCriteria left =
				new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE);
			final ChangeSystemCaptureCriteria right =
				new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE);

			assertEquals(left, right);
			assertEquals(left.hashCode(), right.hashCode());
		}

		@Test
		@DisplayName("should be equal when both areas are null")
		void shouldBeEqualForBothNull() {
			final ChangeSystemCaptureCriteria left = new ChangeSystemCaptureCriteria(null);
			final ChangeSystemCaptureCriteria right = new ChangeSystemCaptureCriteria(null);

			assertEquals(left, right);
			assertEquals(left.hashCode(), right.hashCode());
		}

		@Test
		@DisplayName("should not be equal when areas differ")
		void shouldNotBeEqualForDifferentArea() {
			final ChangeSystemCaptureCriteria left =
				new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE);
			final ChangeSystemCaptureCriteria right =
				new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST);

			assertNotEquals(left, right);
		}

		@Test
		@DisplayName("should not equal null-area criterion to engine-area criterion")
		void shouldNotEqualNullToEngine() {
			final ChangeSystemCaptureCriteria nullArea = new ChangeSystemCaptureCriteria(null);
			final ChangeSystemCaptureCriteria engine =
				new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE);

			assertNotEquals(nullArea, engine);
		}
	}
}
