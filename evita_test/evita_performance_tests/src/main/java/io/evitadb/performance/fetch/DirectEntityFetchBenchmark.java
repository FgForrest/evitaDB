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

package io.evitadb.performance.fetch;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.query.extraResult.translator.reference.EntityFetchContentVerifier;
import io.evitadb.utils.FileUtils;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;

/**
 * Measures direct content validation and a complete embedded primary-key fetch on the same fixture and build.
 * Use JMH's `-prof gc` to report bytes allocated per operation alongside average nanoseconds per operation.
 * Every operation builds fresh requirements, including `entityFetch`, so memoized constraint accessors cannot hide
 * allocation costs. The validation measurement includes the request construction a session normally performs.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
@State(org.openjdk.jmh.annotations.Scope.Thread)
public class DirectEntityFetchBenchmark {
	/** Explicitly names the deterministic catalog fixture; unknown fixture names fail setup. */
	@Param({"SMALL_EMBEDDED_LOCALIZED"})
	public String fixture;
	/** Fetch shapes whose time and allocation costs are compared. */
	@Param({"EMPTY", "BULK", "NAMED_WITH_LOCALE", "REFERENCE_BODY", "NESTED_REFERENCE_BODY"})
	public String shape;
	/** Embedded engine owned by this benchmark thread. */
	private Evita evita;
	/** Session reused across operations, as an application session would be. */
	private EvitaSessionContract session;
	/** Catalog schema used by the validation-only measurement. */
	private CatalogSchemaContract catalogSchema;
	/** Schema of the three small fixture entities. */
	private EntitySchemaContract schema;
	/** Isolated storage removed after the trial. */
	private Path storage;
	/** Fixed request time keeps clock overhead out of the validation-only operation. */
	private OffsetDateTime now;

	/** Creates a benchmark state whose parameters and trial resources are initialized by JMH. */
	public DirectEntityFetchBenchmark() {
	}

	/**
	 * Creates three localized entities connected by a two-level reference chain.
	 *
	 * @throws IOException if the trial's isolated temporary storage directory cannot be created
	 */
	@Setup(Level.Trial)
	public void setUp() throws IOException {
		if (!"SMALL_EMBEDDED_LOCALIZED".equals(this.fixture)) {
			throw new IllegalArgumentException("Unknown fixture: " + this.fixture);
		}
		this.storage = Files.createTempDirectory("direct-entity-fetch-benchmark-");
		this.evita = new Evita(EvitaConfiguration.builder()
			.storage(StorageOptions.builder().storageDirectory(this.storage).build()).build());
		this.evita.defineCatalog("directFetch");
		this.evita.updateCatalog("directFetch", writer -> {
			writer.defineEntitySchema("Node").withoutGeneratedPrimaryKey()
				.withAttribute("label", String.class, attribute -> attribute.localized())
				.withAttribute("code", String.class)
				.withReferenceToEntity("next", "Node", Cardinality.ZERO_OR_MORE,
					reference -> reference.indexedForFilteringAndPartitioning()).updateVia(writer);
			writer.createNewEntity("Node", 3).setAttribute("label", Locale.ENGLISH, "third")
				.setAttribute("code", "3").upsertVia(writer);
			writer.createNewEntity("Node", 2).setAttribute("label", Locale.ENGLISH, "second")
				.setAttribute("code", "2").setReference("next", 3).upsertVia(writer);
			writer.createNewEntity("Node", 1).setAttribute("label", Locale.ENGLISH, "first")
				.setAttribute("code", "1").setReference("next", 2).upsertVia(writer);
		});
		this.session = this.evita.createReadOnlySession("directFetch");
		this.catalogSchema = this.session.getCatalogSchema();
		this.schema = this.catalogSchema.getEntitySchemaOrThrowException("Node");
		this.now = OffsetDateTime.now();
	}

	/**
	 * Measures the request construction of `checkOnly` without the validation, so that the difference between the
	 * two operations is the time and allocation of the validation alone. The request resolves the locales and scopes
	 * the validation reads: they are memoized, and a `getEntity` call resolves them anyway (the scope lookup of the
	 * primary key, the locale predicates of the fetched entity), so the validation must not be charged for them.
	 *
	 * @param blackhole JMH sink consuming the constructed request and its resolved accessors
	 */
	@Benchmark
	public void requestOnly(@Nonnull Blackhole blackhole) {
		final EvitaRequest request = new EvitaRequest(query(collection("Node"), filterBy(scope()),
			require(entityFetch(requirements()))), this.now, SealedEntity.class, "Node");
		blackhole.consume(request.getRequiredLocales());
		blackhole.consume(request.getImplicitLocale());
		blackhole.consume(request.getScopes());
		blackhole.consume(request);
	}

	/**
	 * Measures `requestOnly` plus the validation, without entity or persistence fetching.
	 *
	 * @param blackhole JMH sink consuming the constructed request and its resolved accessors
	 */
	@Benchmark
	public void checkOnly(@Nonnull Blackhole blackhole) {
		final EvitaRequest request = new EvitaRequest(query(collection("Node"), filterBy(scope()),
			require(entityFetch(requirements()))), this.now, SealedEntity.class, "Node");
		blackhole.consume(request.getRequiredLocales());
		blackhole.consume(request.getImplicitLocale());
		blackhole.consume(request.getScopes());
		EntityFetchContentVerifier.verifyEntityFetchContent(request, this.schema, this.catalogSchema);
		blackhole.consume(request);
	}

	/**
	 * Measures the same shape through the public embedded getEntity entry point.
	 *
	 * @param blackhole JMH sink consuming the fetched entity so the complete lookup remains observable
	 */
	@Benchmark
	public void getEntity(@Nonnull Blackhole blackhole) {
		blackhole.consume(this.session.getEntity("Node", 1, requirements()).orElseThrow());
	}

	/**
	 * Builds fresh content for each operation; unsupported shapes cannot silently select an empty fetch.
	 *
	 * @return fresh fetch requirements for the JMH `shape` parameter; empty for `EMPTY`
	 */
	@Nonnull
	private EntityContentRequire[] requirements() {
		return switch (this.shape) {
			case "EMPTY" -> new EntityContentRequire[0];
			case "BULK" -> new EntityContentRequire[]{attributeContentAll(), associatedDataContentAll()};
			case "NAMED_WITH_LOCALE" -> new EntityContentRequire[]{
				attributeContent("label"), dataInLocales(Locale.ENGLISH)
			};
			case "REFERENCE_BODY" -> new EntityContentRequire[]{referenceContent("next",
				entityFetch(attributeContent("label"))), dataInLocales(Locale.ENGLISH)};
			case "NESTED_REFERENCE_BODY" -> new EntityContentRequire[]{
				referenceContent("next", entityFetch(referenceContent("next", entityFetch(attributeContent("label"))))),
				dataInLocales(Locale.ENGLISH)
			};
			default -> throw new IllegalArgumentException("Unknown shape: " + this.shape);
		};
	}

	/** Closes the session and engine and removes only the storage owned by this trial. */
	@TearDown(Level.Trial)
	public void tearDown() {
		if (this.session != null) {
			this.session.close();
		}
		if (this.evita != null) {
			this.evita.close();
		}
		if (this.storage != null) {
			FileUtils.deleteDirectory(this.storage);
		}
	}
}
