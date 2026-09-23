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

package io.evitadb.spike;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.data.structure.References;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceDecodeCoverage;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.spi.store.catalog.persistence.EntitySchemaContext;
import io.evitadb.spi.store.catalog.persistence.ReferenceDecodeCoverageContext;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.utils.NamingConvention;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Measures what reference-name narrowing removes from the cost of decoding a {@link ReferencesStoragePart}.
 *
 * A reflected reference makes a shared, low-cardinality entity the owner of one back-reference per entity that
 * points at it, and all of them live in the **same** storage record as the handful of references a projection
 * actually asks for. Narrowing carries the projection's reference-name set into the Kryo deserializer
 * ({@link ReferenceDecodeCoverageContext}), which then materializes only those names and steps the stream past the
 * rest. This benchmark prices exactly that decision, in isolation from a running server.
 *
 * The shape is the one measured on a large e-commerce catalogue, parameterized by `backReferences`: one entity
 * holding a single `parameter` reference plus N `products` back-references that carry an attribute (a reflected
 * reference inherits its source's attributes, so a skipped back-reference is **not** attribute-free). The
 * projection wants only `parameter`. `backReferences=72217` is the worst single record observed in production.
 *
 * Ops - each arm exists twice, narrowed and not, so the pair is the A/B:
 *
 * - {@code decodeAll} / {@code decodeNarrowed} - the deserializer alone.
 * - {@code decodeAndIndexAll} / {@code decodeAndIndexNarrowed} - decode plus the {@link References} index build
 *   the fetch pipeline performs on the result. The index is sized from the decoded array, so it is a cost the
 *   narrowing removes without ever appearing under the serializer's own profiler frame - which is why the two
 *   halves are timed separately.
 *
 * {@code gc.alloc.rate.norm} is the deterministic signal here: the decode's dominant cost is materializing
 * `Reference`, `ReferenceKey`, `AttributeValue` and reference-name `String` instances that are then discarded.
 *
 * Results and the decision they drove: `documentation/performance/individual/ReferenceNarrowingDecodeBenchmark`.
 *
 * Run through JMH's own runner (the benchmarks jar uses a custom main):
 * {@code java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main
 * io\.evitadb\.spike\.ReferenceNarrowingDecodeBenchmark -prof gc}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ReferenceNarrowingDecodeBenchmark {
	private static final String PRODUCTS = "products";
	private static final String PARAMETER = "parameter";
	private static final String ATTRIBUTE_VARIANT = "variant";

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/* =========================================================================================== */

	@Benchmark
	public int decodeAll(RecordState state) {
		return state.decode(null).getReferences().length;
	}

	@Benchmark
	public int decodeNarrowed(RecordState state) {
		return state.decode(state.projectedNames).getReferences().length;
	}

	@Benchmark
	public int decodeKeyNarrowed(RecordState state) {
		return state.decode(state.projectedKeys).getReferences().length;
	}

	@Benchmark
	public int decodeAndIndexAll(RecordState state) {
		return state.decodeAndIndex(null);
	}

	@Benchmark
	public int decodeAndIndexKeyNarrowed(RecordState state) {
		return state.decodeAndIndex(state.projectedKeys);
	}

	@Benchmark
	public int decodeAndIndexNarrowed(RecordState state) {
		return state.decodeAndIndex(state.projectedNames);
	}

	/* =========================================================================================== */

	/**
	 * Holds one pre-serialized reference storage part of the measured shape, plus the Kryo instance and schema
	 * context the decode needs. Thread scoped because {@link Kryo} is not thread safe.
	 */
	@State(Scope.Thread)
	public static class RecordState {
		/**
		 * Number of `products` back-references the record carries besides the single `parameter` reference.
		 * The largest value is the worst single record observed on a large e-commerce catalogue.
		 */
		@Param({"1000", "10000", "72217"})
		public int backReferences;

		/**
		 * How many of the `products` back-references the key-narrowed arms admit. A projection naming exact
		 * referenced keys asks for a page of them, not for the whole run - this is that page.
		 */
		@Param({"20"})
		public int admittedBackReferences;

		private EntitySchema schema;
		private Kryo kryo;
		private byte[] serialized;
		private ReferenceDecodeCoverage projectedNames;
		private ReferenceDecodeCoverage projectedKeys;

		@Setup(Level.Trial)
		public void setUp() {
			this.schema = schema();
			this.kryo = KryoFactory.createKryo(
				SharedClassesConfigurer.INSTANCE
					.andThen(new EntityStoragePartConfigurer(new ReadWriteKeyCompressor(new ConcurrentHashMap<>())))
			);
			this.projectedNames = ReferenceDecodeCoverage.ofNames(Set.of(PARAMETER));
			// the key axis: `parameter` whole, `products` bounded to the keys the projection named. The back
			// references carry primary keys 1..backReferences, so admitting the first N of them is the shape a
			// query naming exact keys at its filter's conjunctive root produces
			final int[] admittedKeys = new int[Math.min(this.admittedBackReferences, this.backReferences)];
			for (int i = 0; i < admittedKeys.length; i++) {
				admittedKeys[i] = i + 1;
			}
			this.projectedKeys = ReferenceDecodeCoverage.of(
				Set.of(PARAMETER), Map.of(PRODUCTS, admittedKeys)
			);
			final ByteArrayOutputStream baos = new ByteArrayOutputStream(4 << 20);
			try (final ByteBufferOutput output = new ByteBufferOutput(baos, 1 << 20)) {
				EntitySchemaContext.executeWithSchemaContext(this.schema, () -> {
					this.kryo.writeObject(output, backReferenceHeavyPart(this.schema, this.backReferences));
					return null;
				});
			}
			this.serialized = baos.toByteArray();
		}

		/**
		 * Decodes the record once under the passed filter.
		 *
		 * @param coverage what the read may materialize, NULL to decode the record in full
		 * @return the decoded storage part
		 */
		@Nonnull
		private ReferencesStoragePart decode(@Nullable ReferenceDecodeCoverage coverage) {
			try (final ByteBufferInput input = new ByteBufferInput(new ByteArrayInputStream(this.serialized))) {
				return EntitySchemaContext.executeWithSchemaContext(
					this.schema,
					() -> ReferenceDecodeCoverageContext.executeWithCoverage(
						coverage,
						() -> this.kryo.readObject(input, ReferencesStoragePart.class)
					)
				);
			}
		}

		/**
		 * Decodes the record and builds the reference index over the result - together, the work one fetched
		 * entity costs the pipeline.
		 *
		 * @param coverage what the read may materialize, NULL to decode the record in full
		 * @return the number of references that ended up visible, so the JIT cannot discard the work
		 */
		private int decodeAndIndex(@Nullable ReferenceDecodeCoverage coverage) {
			final ReferencesStoragePart part = decode(coverage);
			final References references = new References(
				this.schema, part.getReferences(), this.schema.getReferences().keySet(),
				References.DEFAULT_CHUNK_TRANSFORMER
			);
			return references.getReferences().size();
		}
	}

	/* =========================================================================================== */

	/**
	 * Builds a reference schema carrying the single attribute a reflected reference inherits from its source.
	 *
	 * @param name name of the reference
	 * @return the reference schema
	 */
	@Nonnull
	private static ReferenceSchema referenceSchema(@Nonnull String name) {
		return ReferenceSchema._internalBuild(
			name, NamingConvention.generate(name),
			null, null,
			Cardinality.ZERO_OR_MORE,
			name, NamingConvention.generate(name), false,
			null, Collections.emptyMap(), false,
			Collections.emptyMap(), Collections.emptyMap(), Collections.emptySet(),
			Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
			Map.of(
				ATTRIBUTE_VARIANT,
				AttributeSchema._internalBuild(
					ATTRIBUTE_VARIANT, String.class, false, ConflictResolutionOverride.INHERITED
				)
			),
			Collections.emptyMap(),
			ConflictResolutionOverride.INHERITED
		);
	}

	/**
	 * Builds the entity schema of the measured record's owner.
	 *
	 * @return the entity schema
	 */
	@Nonnull
	private static EntitySchema schema() {
		return EntitySchema._internalBuild(
			1, "ParameterValue",
			null, null, null,
			true,
			false, io.evitadb.dataType.Scope.NO_SCOPE,
			false, io.evitadb.dataType.Scope.NO_SCOPE, 0,
			Collections.emptySet(), Collections.emptySet(),
			Collections.emptyMap(), Collections.emptyMap(),
			Map.of(PARAMETER, referenceSchema(PARAMETER), PRODUCTS, referenceSchema(PRODUCTS)),
			Collections.emptySet(), Collections.emptyMap()
		);
	}

	/**
	 * Builds the record in the sorted order the storage guarantees: the `parameter` run first, then the
	 * `products` back-references - so the narrowing has to walk past all of them after finding the one it keeps.
	 *
	 * @param schema         schema of the entity the references belong to
	 * @param backReferences number of `products` back-references to generate
	 * @return the storage part to be serialized
	 */
	@Nonnull
	private static ReferencesStoragePart backReferenceHeavyPart(@Nonnull EntitySchema schema, int backReferences) {
		final Reference[] references = new Reference[backReferences + 1];
		final AttributeKey variantKey = new AttributeKey(ATTRIBUTE_VARIANT);
		final Map<AttributeKey, AttributeValue> parameterAttributes = new LinkedHashMap<>(2);
		parameterAttributes.put(variantKey, new AttributeValue(variantKey, "primary"));
		references[0] = new Reference(
			schema, schema.getReferenceOrThrowException(PARAMETER), 1,
			new ReferenceKey(PARAMETER, 15, 1), null, parameterAttributes, false
		);
		for (int i = 0; i < backReferences; i++) {
			// a reflected reference inherits its source's attributes, so every back-reference carries one too -
			// decoding-then-dropping them is a measurable share of the skip path
			final Map<AttributeKey, AttributeValue> backReferenceAttributes = new LinkedHashMap<>(2);
			backReferenceAttributes.put(variantKey, new AttributeValue(variantKey, "inherited"));
			references[i + 1] = new Reference(
				schema, schema.getReferenceOrThrowException(PRODUCTS), 1,
				new ReferenceKey(PRODUCTS, i + 1, i + 2), null, backReferenceAttributes, false
			);
		}
		return new ReferencesStoragePart(498_295, backReferences + 1, references, -1);
	}

}
