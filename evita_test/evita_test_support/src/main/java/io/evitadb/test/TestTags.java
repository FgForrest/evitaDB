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

package io.evitadb.test;

import java.util.Set;

/**
 * Catalog of JUnit 5 tag identifiers used across the evitaDB test suite.
 *
 * Tags are organised on three orthogonal axes — every test is expected to carry
 * at least one tag from the layer axis and one from the capability axis. Tags
 * from the cost axis are optional and default to "fast".
 *
 * The taxonomy is intentionally flat (no `cap:` / `surface:` prefixes) so that
 * tag-filter expressions stay readable, e.g.
 * `mvn test -Dgroups="(facet | hierarchy) & external_api & !slow"`.
 *
 * The {@link #LAYER_TAGS} and {@link #CAPABILITY_TAGS} sets are consumed by
 * {@code TestTagPolicyFilter} to enforce that every test method has been
 * categorised on both axes.
 */
public interface TestTags {

	// ---------------------------------------------------------------------
	// Cost axis — optional, mutually exclusive
	// ---------------------------------------------------------------------

	/**
	 * Slow / generative test that should be excluded from the default fast loop
	 * and only executed in the dedicated long-running pipeline.
	 */
	String SLOW = "slow";

	/**
	 * Test currently known to be unstable; excluded from required runs and
	 * only executed on demand.
	 */
	String FLAKY = "flaky";

	// ---------------------------------------------------------------------
	// Layer axis — where in the stack the test exercises
	// ---------------------------------------------------------------------

	/** Public-API contracts in {@code evita_api} — schema, mutations, query model. */
	String CONTRACT = "contract";

	/** Query planner / executor in {@code evita_engine} (algebra, filtering, sorting, fetch). */
	String ENGINE = "engine";

	/** In-memory indexes — entity/attribute/facet/hierarchy/price indexes. */
	String INDEXING = "indexing";

	/** Persistence layer — offset index, kryo, on-disk catalog state. */
	String STORAGE = "storage";

	/** Java gRPC client / driver. */
	String DRIVER = "driver";

	/** Server bootstrap, configuration, lifecycle. */
	String SERVER = "server";

	/**
	 * External API perimeter umbrella — combine with one of the protocol-specific
	 * layer tags below ({@link #REST}, {@link #GRAPHQL}, {@link #GRPC}, …).
	 */
	String EXTERNAL_API = "external_api";

	/** REST endpoint surface. */
	String REST = "rest";

	/** GraphQL endpoint surface. */
	String GRAPHQL = "graphql";

	/** gRPC endpoint surface. */
	String GRPC = "grpc";

	/** evitaLab admin web app surface. */
	String LAB = "lab";

	/** System / management endpoints (TLS handshake, health, version). */
	String SYSTEM_API = "system_api";

	/** External observability surface (metrics endpoint, tracing endpoint). */
	String OBSERVABILITY_API = "observability_api";

	/** Command-line tooling. */
	String CLI = "cli";

	/**
	 * The test harness itself rather than the database — the tag-policy gate, the dataset
	 * provisioning extensions, the generators. Dual-axis, like {@link #INDEXING}: such a test has no
	 * meaningful database capability to declare, so this single tag satisfies both requirements.
	 */
	String TEST_HARNESS = "test_harness";

	// ---------------------------------------------------------------------
	// Capability axis — what behaviour is being tested
	// ---------------------------------------------------------------------

	/** Querying overall — parsing, planning, execution. */
	String QUERY = "query";

	/** Filter constraints. */
	String FILTER = "filter";

	/** Order constraints. */
	String ORDER = "order";

	/** Require constraints. */
	String REQUIRE = "require";

	/** Attribute handling — attribute index, sortable attribute compounds. */
	String ATTRIBUTE = "attribute";

	/** Hierarchy queries / index. */
	String HIERARCHY = "hierarchy";

	/** Facet queries / index / summary. */
	String FACET = "facet";

	/** Price queries / price index. */
	String PRICE = "price";

	/** Histogram extraction (attribute & price histograms). */
	String HISTOGRAM = "histogram";

	/** Reference handling — referenced indexes, reference attributes. */
	String REFERENCE = "reference";

	/** Schema management & schema mutations. */
	String SCHEMA = "schema";

	/** Transactions, isolation, conflict detection. */
	String TRANSACTION = "transaction";

	/** Write-ahead log. */
	String WAL = "wal";

	/** Change Data Capture. */
	String CDC = "cdc";

	/** Caching layer. */
	String CACHE = "cache";

	/** Session lifecycle, session-killer, timeouts. */
	String SESSION = "session";

	/** Entity proxying (POJO / interface / editor proxies). */
	String PROXY = "proxy";

	/** Export pipeline (file / S3). */
	String EXPORT = "export";

	/** Streaming APIs. */
	String STREAM = "stream";

	/** Kryo / JSON serialisation round-trips. */
	String SERIALIZATION = "serialization";

	/** Expression engine. */
	String EXPRESSION = "expression";

	/** Comparators. */
	String COMPARATOR = "comparator";

	/** Internal observability — metrics emission, JFR events, tracing semantics. */
	String OBSERVABILITY = "observability";

	/** Async tasks / scheduler / progress reporting. */
	String TASK = "task";

	/** TLS / certificates / authentication / permissions. */
	String SECURITY = "security";

	/** Primitive evita data types ({@code Range}, {@code BigDecimalNumberRange}, …). */
	String DATA_TYPE = "data_type";

	/** Traffic recording & replay — spans the whole stack from API to storage. */
	String TRAFFIC_ENGINE = "traffic_engine";

	/** Catalog & instance management — backups, restore, migration, lifecycle. */
	String MANAGEMENT = "management";

	/** Full-text search — analysis chain, term dictionary, full-text index & queries. */
	String FULLTEXT = "fulltext";

	// ---------------------------------------------------------------------
	// Tag dictionaries consumed by TestTagPolicyFilter
	// ---------------------------------------------------------------------

	/**
	 * Layer / surface tags — every test must carry at least one of these.
	 */
	Set<String> LAYER_TAGS = Set.of(
		CONTRACT, ENGINE, INDEXING, STORAGE, DRIVER, SERVER,
		EXTERNAL_API, REST, GRAPHQL, GRPC, LAB, SYSTEM_API, OBSERVABILITY_API,
		CLI, TEST_HARNESS
	);

	/**
	 * Capability / domain tags — every test must carry at least one of these.
	 *
	 * Note: {@link #INDEXING} and {@link #TEST_HARNESS} appear in both this set and
	 * {@link #LAYER_TAGS}. {@link #INDEXING} is genuinely dual-axis — "indexing" is both *where* in
	 * the stack a test lives (the in-memory index implementation) and *what* it exercises (the
	 * indexing capability of the database). Listing it in both sets lets a path like
	 * {@code io/evitadb/api/functional/indexing/...} satisfy the layer requirement via
	 * {@link #CONTRACT} and the capability requirement via {@link #INDEXING}. {@link #TEST_HARNESS}
	 * is dual-axis for the opposite reason — a test of the harness exercises no database capability
	 * at all, and forcing one on it would be a lie that survives in tag-filter expressions.
	 */
	Set<String> CAPABILITY_TAGS = Set.of(
		QUERY, FILTER, ORDER, REQUIRE,
		ATTRIBUTE, HIERARCHY, FACET, PRICE, HISTOGRAM, REFERENCE,
		SCHEMA, TRANSACTION, WAL, CDC, CACHE, SESSION, PROXY,
		EXPORT, STREAM, SERIALIZATION, EXPRESSION, COMPARATOR,
		OBSERVABILITY, TASK, SECURITY, DATA_TYPE, TRAFFIC_ENGINE, MANAGEMENT,
		FULLTEXT, INDEXING, TEST_HARNESS
	);

	/**
	 * Cost tags — optional, mutually exclusive (a test should carry at most one).
	 */
	Set<String> COST_TAGS = Set.of(SLOW, FLAKY);

}
