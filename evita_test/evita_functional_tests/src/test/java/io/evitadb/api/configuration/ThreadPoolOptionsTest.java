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

package io.evitadb.api.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ThreadPoolOptions} record and its builders.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ThreadPoolOptions")
class ThreadPoolOptionsTest {

	@Nested
	@DisplayName("Request thread pool builder")
	class RequestThreadPoolBuilderTest {

		@Test
		@DisplayName("should create with request defaults")
		void shouldCreateWithRequestDefaults() {
			final ThreadPoolOptions options =
				ThreadPoolOptions.requestThreadPoolBuilder().build();

			assertEquals(
				ThreadPoolOptions.DEFAULT_REQUEST_MIN_THREAD_COUNT,
				options.minThreadCount()
			);
			assertEquals(
				ThreadPoolOptions.DEFAULT_REQUEST_MAX_THREAD_COUNT,
				options.maxThreadCount()
			);
			assertEquals(
				ThreadPoolOptions.DEFAULT_REQUEST_THREAD_PRIORITY,
				options.threadPriority()
			);
			assertEquals(
				ThreadPoolOptions.DEFAULT_REQUEST_QUEUE_SIZE,
				options.queueSize()
			);
		}

		@Test
		@DisplayName("should have min thread count > 0")
		void shouldHavePositiveMinThreadCount() {
			final ThreadPoolOptions options =
				ThreadPoolOptions.requestThreadPoolBuilder().build();

			assertTrue(options.minThreadCount() > 0);
		}

		@Test
		@DisplayName(
			"should have max thread count >= min thread count"
		)
		void shouldHaveMaxGreaterOrEqualToMin() {
			final ThreadPoolOptions options =
				ThreadPoolOptions.requestThreadPoolBuilder().build();

			assertTrue(
				options.maxThreadCount() >= options.minThreadCount()
			);
		}
	}

	@Nested
	@DisplayName("Transaction thread pool builder")
	class TransactionThreadPoolBuilderTest {

		@Test
		@DisplayName("should create with transaction defaults")
		void shouldCreateWithTransactionDefaults() {
			final ThreadPoolOptions options =
				ThreadPoolOptions.transactionThreadPoolBuilder()
					.build();

			assertEquals(
				ThreadPoolOptions
					.DEFAULT_TRANSACTION_MIN_THREAD_COUNT,
				options.minThreadCount()
			);
			assertEquals(
				ThreadPoolOptions
					.DEFAULT_TRANSACTION_MAX_THREAD_COUNT,
				options.maxThreadCount()
			);
			assertEquals(
				ThreadPoolOptions
					.DEFAULT_TRANSACTION_THREAD_PRIORITY,
				options.threadPriority()
			);
			assertEquals(
				ThreadPoolOptions
					.DEFAULT_TRANSACTION_QUEUE_SIZE,
				options.queueSize()
			);
		}
	}

	@Nested
	@DisplayName("Service thread pool builder")
	class ServiceThreadPoolBuilderTest {

		@Test
		@DisplayName(
			"should have min thread count at least 1"
		)
		void shouldHaveServicePoolMinThreadCountAtLeastOne() {
			final ThreadPoolOptions options =
				ThreadPoolOptions.serviceThreadPoolBuilder()
					.build();

			assertTrue(
				options.minThreadCount() >= 1,
				"Service pool min thread count should be >= 1"
			);
		}

		@Test
		@DisplayName(
			"should have max thread count at least 2 on " +
				"multi-core"
		)
		void shouldHaveServicePoolMaxThreadCountAtLeastTwo() {
			final ThreadPoolOptions options =
				ThreadPoolOptions.serviceThreadPoolBuilder()
					.build();

			final int processors =
				Runtime.getRuntime().availableProcessors();
			if (processors > 1) {
				assertTrue(
					options.maxThreadCount() >= 2,
					"Service pool max thread count should " +
						"be >= 2 on multi-core systems"
				);
			} else {
				assertTrue(
					options.maxThreadCount() >= 1,
					"Service pool max thread count should " +
						"be >= 1"
				);
			}
		}

		@Test
		@DisplayName("should create with service defaults")
		void shouldCreateWithServiceDefaults() {
			final ThreadPoolOptions options =
				ThreadPoolOptions.serviceThreadPoolBuilder()
					.build();

			assertEquals(
				ThreadPoolOptions.DEFAULT_MIN_SERVICE_THREAD_COUNT,
				options.minThreadCount()
			);
			assertEquals(
				ThreadPoolOptions.DEFAULT_MAX_SERVICE_THREAD_COUNT,
				options.maxThreadCount()
			);
			assertEquals(
				ThreadPoolOptions.DEFAULT_SERVICE_THREAD_PRIORITY,
				options.threadPriority()
			);
			assertEquals(
				ThreadPoolOptions.DEFAULT_SERVICE_QUEUE_SIZE,
				options.queueSize()
			);
		}
	}

	@Nested
	@DisplayName("Builder setters")
	class BuilderSettersTest {

		@Test
		@DisplayName("should override min thread count")
		void shouldOverrideMinThreadCount() {
			final ThreadPoolOptions options =
				ThreadPoolOptions.requestThreadPoolBuilder()
					.minThreadCount(42)
					.build();

			assertEquals(42, options.minThreadCount());
		}

		@Test
		@DisplayName("should override max thread count")
		void shouldOverrideMaxThreadCount() {
			final ThreadPoolOptions options =
				ThreadPoolOptions.requestThreadPoolBuilder()
					.maxThreadCount(84)
					.build();

			assertEquals(84, options.maxThreadCount());
		}

		@Test
		@DisplayName("should override thread priority")
		void shouldOverrideThreadPriority() {
			final ThreadPoolOptions options =
				ThreadPoolOptions.requestThreadPoolBuilder()
					.threadPriority(3)
					.build();

			assertEquals(3, options.threadPriority());
		}

		@Test
		@DisplayName("should override queue size")
		void shouldOverrideQueueSize() {
			final ThreadPoolOptions options =
				ThreadPoolOptions.requestThreadPoolBuilder()
					.queueSize(500)
					.build();

			assertEquals(500, options.queueSize());
		}
	}

	@Nested
	@DisplayName("Queue size validation")
	class QueueSizeValidationTest {

		@Test
		@DisplayName(
			"should reject queue size >= 100000 in constructor"
		)
		void shouldRejectLargeQueueSizeInConstructor() {
			assertThrows(
				Exception.class,
				() -> new ThreadPoolOptions.Builder(
					4, 8, 5, 200_000
				)
			);
		}

		@Test
		@DisplayName(
			"should accept queue size < 100000 in constructor"
		)
		void shouldAcceptValidQueueSizeInConstructor() {
			final ThreadPoolOptions.Builder builder =
				new ThreadPoolOptions.Builder(4, 8, 5, 99_999);
			final ThreadPoolOptions options = builder.build();

			assertEquals(99_999, options.queueSize());
		}

		@Test
		@DisplayName(
			"should reject large queue size set via setter " +
				"on build"
		)
		void shouldRejectLargeQueueSizeViaSetterOnBuild() {
			final ThreadPoolOptions.Builder builder =
				ThreadPoolOptions.requestThreadPoolBuilder()
					.queueSize(200_000);

			assertThrows(
				Exception.class,
				builder::build,
				"build() should validate queueSize even " +
					"when set via setter"
			);
		}
	}

	@Nested
	@DisplayName("Copy constructor")
	class CopyConstructorTest {

		@Test
		@DisplayName(
			"should copy all fields from source options"
		)
		void shouldCopyAllFieldsFromSource() {
			final ThreadPoolOptions source =
				ThreadPoolOptions.requestThreadPoolBuilder()
					.minThreadCount(10)
					.maxThreadCount(20)
					.threadPriority(7)
					.queueSize(50)
					.build();

			final ThreadPoolOptions copy =
				ThreadPoolOptions.builder(source).build();

			assertEquals(
				source.minThreadCount(), copy.minThreadCount()
			);
			assertEquals(
				source.maxThreadCount(), copy.maxThreadCount()
			);
			assertEquals(
				source.threadPriority(), copy.threadPriority()
			);
			assertEquals(
				source.queueSize(), copy.queueSize()
			);
		}
	}
}
