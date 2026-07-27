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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.RemoveReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaBucketedMutation;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.NamingConvention;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Cross-cutting tests verifying how the per-histogram `assignedWhen` partition selector
 * propagates through the various DTO/mutation conversion sites. Each test exercises one
 * conversion site end-to-end and asserts that a non-null partition selector supplied at the
 * input survives the conversion and reaches the resulting DTO/mutation unchanged.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Assigned-when propagation through DTO conversions")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(HISTOGRAM)
class AssignedWhenPropagationTest {

	private static final String REFERENCE_NAME = "categories";
	private static final String REFERENCE_TYPE = "category";
	private static final String HISTOGRAM_NAME = "priceHistogram";

	/**
	 * Reusable expression fixture for the per-histogram condition slot. Parsed once because
	 * parsing is allocation-heavy and the expression is immutable.
	 */
	@Nonnull
	private static final Expression PER_HISTOGRAM_CONDITION =
		ExpressionFactory.parse("$entity.attributes['x'] > 0");

	@Test
	@DisplayName("should preserve assignedWhen through ReferenceSchema.toBucketedHistogramMap")
	void shouldPreserveAssignedWhenThroughReferenceSchemaToBucketedHistogramMap() {
		final ScopedHistogramIndexDefinition scoped = new ScopedHistogramIndexDefinition(
			Scope.LIVE, HISTOGRAM_NAME, null, PER_HISTOGRAM_CONDITION
		);

		final Map<Scope, Map<String, HistogramIndexDefinition>> result =
			ReferenceSchema.toBucketedHistogramMap(new ScopedHistogramIndexDefinition[]{scoped});

		final HistogramIndexDefinition produced = result.get(Scope.LIVE).get(HISTOGRAM_NAME);
		assertNotNull(produced);
		assertNotNull(
			produced.assignedWhen(),
			"scoped->map conversion must propagate the per-histogram condition"
		);
		assertEquals(
			PER_HISTOGRAM_CONDITION.toExpressionString(),
			produced.assignedWhen().toExpressionString()
		);
	}

	@Test
	@DisplayName("should preserve assignedWhen through builder map->array conversion")
	void shouldPreserveAssignedWhenThroughBuilderToScopedHistogramIndexDefinitionArray()
		throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, ClassNotFoundException {
		// Build a map analogous to ReferenceSchema.getAllHistogramIndexDefinitions() with a
		// non-null assignedWhen on the inner definition.
		final HistogramIndexDefinition def = new HistogramIndexDefinition(
			HISTOGRAM_NAME,
			Collections.emptyMap(),
			null,
			PER_HISTOGRAM_CONDITION
		);
		final Map<String, HistogramIndexDefinition> innerMap = new LinkedHashMap<>();
		innerMap.put(HISTOGRAM_NAME, def);
		final Map<Scope, Map<String, HistogramIndexDefinition>> bucketedMap =
			new EnumMap<>(Scope.class);
		bucketedMap.put(Scope.LIVE, innerMap);

		// The conversion method is protected static — invoke via reflection because the
		// owning class is sealed and cannot be subclassed from tests. The intent is to
		// exercise the conversion logic in isolation, not to test the builder lifecycle.
		final Class<?> builderClass = Class.forName(
			"io.evitadb.api.requestResponse.schema.builder.AbstractReferenceSchemaBuilder"
		);
		final Method method = builderClass.getDeclaredMethod(
			"toScopedHistogramIndexDefinitionArray", Map.class
		);
		method.setAccessible(true);
		final ScopedHistogramIndexDefinition[] result =
			(ScopedHistogramIndexDefinition[]) method.invoke(null, bucketedMap);

		assertNotNull(result);
		assertEquals(1, result.length);
		assertNotNull(
			result[0].assignedWhen(),
			"map->array conversion must propagate the per-histogram condition"
		);
		assertEquals(
			PER_HISTOGRAM_CONDITION.toExpressionString(),
			result[0].assignedWhen().toExpressionString()
		);
	}

	@Test
	@DisplayName("should preserve assignedWhen through CreateReferenceSchemaMutation.combineWith")
	void shouldPreserveAssignedWhenThroughCreateReferenceSchemaMutationCombineWith() {
		// The create mutation declares a histogram with the per-histogram condition populated.
		final CreateReferenceSchemaMutation createMutation = new CreateReferenceSchemaMutation(
			REFERENCE_NAME,
			"description", "deprecationNotice",
			Cardinality.ZERO_OR_MORE, REFERENCE_TYPE, false,
			null, false,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
			},
			null,
			new Scope[]{Scope.LIVE},
			null,
			new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(
					Scope.LIVE, HISTOGRAM_NAME, null, PER_HISTOGRAM_CONDITION
				)
			},
			new ScopedBucketedPartially[]{
				new ScopedBucketedPartially(Scope.LIVE, PER_HISTOGRAM_CONDITION)
			}
		);
		// An existing, non-bucketed reference schema is what triggers the combine path
		// that emits a SetReferenceSchemaBucketedMutation.
		final ReferenceSchema existingSchema = ReferenceSchema._internalBuild(
			REFERENCE_NAME, REFERENCE_TYPE, false,
			Cardinality.ZERO_OR_MORE, null, false,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
			},
			new Scope[]{Scope.LIVE}
		);
		final EntitySchema entitySchema = realEntitySchemaWithReference(existingSchema);

		final MutationCombinationResult<LocalEntitySchemaMutation> result = createMutation.combineWith(
			realCatalogSchema(),
			entitySchema,
			new RemoveReferenceSchemaMutation(REFERENCE_NAME)
		);

		assertNotNull(result);
		final SetReferenceSchemaBucketedMutation bucketedMutation = Arrays.stream(result.current())
			.filter(SetReferenceSchemaBucketedMutation.class::isInstance)
			.map(SetReferenceSchemaBucketedMutation.class::cast)
			.findFirst()
			.orElse(null);
		assertNotNull(
			bucketedMutation,
			"combineWith must emit a SetReferenceSchemaBucketedMutation when bucketed config differs"
		);
		final ScopedHistogramIndexDefinition[] emitted = bucketedMutation.getBucketedInScopes();
		assertNotNull(emitted);
		assertEquals(1, emitted.length);
		assertNotNull(
			emitted[0].assignedWhen(),
			"combineWith must propagate the per-histogram condition into the emitted mutation"
		);
		assertEquals(
			PER_HISTOGRAM_CONDITION.toExpressionString(),
			emitted[0].assignedWhen().toExpressionString()
		);
	}

	@Test
	@DisplayName("should preserve assignedWhen through CreateReflectedReferenceSchemaMutation.combineWith")
	void shouldPreserveAssignedWhenThroughCreateReflectedReferenceSchemaMutationCombineWith() {
		final CreateReflectedReferenceSchemaMutation createMutation =
			new CreateReflectedReferenceSchemaMutation(
				REFERENCE_NAME,
				"description", "deprecationNotice",
				Cardinality.ZERO_OR_MORE, REFERENCE_TYPE,
				"originalRef",
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				new Scope[]{Scope.LIVE},
				null,
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(
						Scope.LIVE, HISTOGRAM_NAME, null, PER_HISTOGRAM_CONDITION
					)
				},
				new ScopedBucketedPartially[]{
					new ScopedBucketedPartially(Scope.LIVE, PER_HISTOGRAM_CONDITION)
				},
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);
		// Existing reflected schema with NO bucketed configuration — guarantees the combine
		// path detects a difference and emits a SetReferenceSchemaBucketedMutation.
		final ReflectedReferenceSchema existingSchema = ReflectedReferenceSchema._internalBuild(
			REFERENCE_NAME,
			"oldDescription", "oldDeprecationNotice",
			REFERENCE_TYPE, "originalRef",
			Cardinality.ZERO_OR_MORE,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
			},
			null,
			new Scope[]{Scope.LIVE},
			null,
			null, null,
			Collections.emptyMap(),
			Collections.emptyMap(),
			AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
			null
		);
		final EntitySchema entitySchema = realEntitySchemaWithReference(existingSchema);

		final MutationCombinationResult<LocalEntitySchemaMutation> result = createMutation.combineWith(
			realCatalogSchema(),
			entitySchema,
			new RemoveReferenceSchemaMutation(REFERENCE_NAME)
		);

		assertNotNull(result);
		final SetReferenceSchemaBucketedMutation bucketedMutation = Arrays.stream(result.current())
			.filter(SetReferenceSchemaBucketedMutation.class::isInstance)
			.map(SetReferenceSchemaBucketedMutation.class::cast)
			.findFirst()
			.orElse(null);
		assertNotNull(
			bucketedMutation,
			"combineWith must emit a SetReferenceSchemaBucketedMutation when bucketed config differs"
		);
		final ScopedHistogramIndexDefinition[] emitted = bucketedMutation.getBucketedInScopes();
		assertNotNull(emitted);
		assertEquals(1, emitted.length);
		assertNotNull(
			emitted[0].assignedWhen(),
			"createCombinedBucketedMutation must propagate the per-histogram condition"
		);
		assertEquals(
			PER_HISTOGRAM_CONDITION.toExpressionString(),
			emitted[0].assignedWhen().toExpressionString()
		);
	}

	@Test
	@DisplayName("should preserve assignedWhen through ReferenceSchemaBuilder createNew path when base schema carries histogram definitions")
	void shouldPreserveAssignedWhenThroughReferenceSchemaBuilderCreateNew()
		throws ReflectiveOperationException {
		// The createNew=true branch with a non-null existingSchema hint fires when the previously
		// persisted reference is being replaced under the same name (e.g. converting a reflected
		// reference to a standard one). The hint's histogram definitions — including their
		// assignedWhen partition selectors — must survive the synthesized CreateReferenceSchemaMutation.
		final ReferenceSchema existingWithCondition = ReferenceSchema._internalBuild(
			REFERENCE_NAME,
			NamingConvention.generate(REFERENCE_NAME),
			null, null,
			REFERENCE_TYPE,
			NamingConvention.generate(REFERENCE_TYPE),
			false,
			Cardinality.ZERO_OR_MORE,
			null, null, false,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
			},
			null,
			new Scope[]{Scope.LIVE},
			null,
			new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(
					Scope.LIVE, HISTOGRAM_NAME, null, PER_HISTOGRAM_CONDITION
				)
			},
			null,
			Collections.emptyMap(),
			Collections.emptyMap(),
			ConflictResolutionOverride.INHERITED
		);

		final List<LocalEntitySchemaMutation> mutations = invokeReferenceSchemaBuilderCreateNew(
			existingWithCondition
		);

		final CreateReferenceSchemaMutation createMutation = mutations.stream()
			.filter(CreateReferenceSchemaMutation.class::isInstance)
			.map(CreateReferenceSchemaMutation.class::cast)
			.findFirst()
			.orElse(null);
		assertNotNull(
			createMutation,
			"ReferenceSchemaBuilder createNew constructor must emit a CreateReferenceSchemaMutation"
		);
		final ScopedHistogramIndexDefinition[] forwarded = createMutation.getBucketedInScopes();
		assertNotNull(forwarded);
		assertEquals(1, forwarded.length);
		assertNotNull(
			forwarded[0].assignedWhen(),
			"ReferenceSchemaBuilder createNew path must forward the per-histogram condition from the base schema"
		);
		assertEquals(
			PER_HISTOGRAM_CONDITION.toExpressionString(),
			forwarded[0].assignedWhen().toExpressionString()
		);
	}

	@Test
	@DisplayName("should preserve assignedWhen through ReflectedReferenceSchemaBuilder createNew path when base schema carries histogram definitions")
	void shouldPreserveAssignedWhenThroughReflectedReferenceSchemaBuilderCreateNew()
		throws ReflectiveOperationException {
		// Symmetric coverage for the reflected reference conversion path — the hint is a reflected
		// reference that already carries assignedWhen on its histogram definitions, and the
		// CreateReflectedReferenceSchemaMutation synthesized during construction must propagate it.
		final ReflectedReferenceSchema existingWithCondition = ReflectedReferenceSchema._internalBuild(
			REFERENCE_NAME,
			null, null,
			REFERENCE_TYPE, "originalRef",
			Cardinality.ZERO_OR_MORE,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
			},
			null,
			new Scope[]{Scope.LIVE},
			null,
			new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(
					Scope.LIVE, HISTOGRAM_NAME, null, PER_HISTOGRAM_CONDITION
				)
			},
			null,
			Collections.emptyMap(),
			Collections.emptyMap(),
			AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
			null
		);

		final List<LocalEntitySchemaMutation> mutations = invokeReflectedReferenceSchemaBuilderCreateNew(
			existingWithCondition
		);

		final CreateReflectedReferenceSchemaMutation createMutation = mutations.stream()
			.filter(CreateReflectedReferenceSchemaMutation.class::isInstance)
			.map(CreateReflectedReferenceSchemaMutation.class::cast)
			.findFirst()
			.orElse(null);
		assertNotNull(
			createMutation,
			"ReflectedReferenceSchemaBuilder createNew constructor must emit a CreateReflectedReferenceSchemaMutation"
		);
		final ScopedHistogramIndexDefinition[] forwarded = createMutation.getBucketedInScopes();
		assertNotNull(forwarded);
		assertEquals(1, forwarded.length);
		assertNotNull(
			forwarded[0].assignedWhen(),
			"ReflectedReferenceSchemaBuilder createNew path must forward the per-histogram condition from the base schema"
		);
		assertEquals(
			PER_HISTOGRAM_CONDITION.toExpressionString(),
			forwarded[0].assignedWhen().toExpressionString()
		);
	}

	/**
	 * Reflectively invokes the package-private {@code ReferenceSchemaBuilder} constructor with
	 * {@code createNew=true} and the supplied existing schema as the base hint, then returns the
	 * mutations the constructor added to its internal list. The builder lives in a different
	 * package, so reflection is the only way to drive its constructor from this test.
	 */
	@Nonnull
	private static List<LocalEntitySchemaMutation> invokeReferenceSchemaBuilderCreateNew(
		@Nonnull ReferenceSchemaContract existingSchema
	) throws ReflectiveOperationException {
		final Class<?> builderClass = Class.forName(
			"io.evitadb.api.requestResponse.schema.builder.ReferenceSchemaBuilder"
		);
		final Constructor<?> ctor = builderClass.getDeclaredConstructor(
			CatalogSchemaContract.class,
			EntitySchemaContract.class,
			ReferenceSchemaContract.class,
			String.class,
			String.class,
			boolean.class,
			Cardinality.class,
			List.class,
			boolean.class
		);
		ctor.setAccessible(true);
		final Object builder = ctor.newInstance(
			realCatalogSchema(),
			realEntitySchemaWithReference(existingSchema),
			existingSchema,
			REFERENCE_NAME,
			REFERENCE_TYPE,
			false,
			Cardinality.ZERO_OR_MORE,
			new LinkedList<LocalEntitySchemaMutation>(),
			true
		);
		return readBuilderMutations(builderClass, builder);
	}

	/**
	 * Reflectively invokes the package-private {@code ReflectedReferenceSchemaBuilder} constructor
	 * with {@code createNew=true} and the supplied existing reflected schema as the base hint,
	 * then returns the mutations the constructor added to its internal list.
	 */
	@Nonnull
	private static List<LocalEntitySchemaMutation> invokeReflectedReferenceSchemaBuilderCreateNew(
		@Nonnull ReflectedReferenceSchema existingSchema
	) throws ReflectiveOperationException {
		final Class<?> builderClass = Class.forName(
			"io.evitadb.api.requestResponse.schema.builder.ReflectedReferenceSchemaBuilder"
		);
		final Class<?> contractClass = Class.forName(
			"io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract"
		);
		final Constructor<?> ctor = builderClass.getDeclaredConstructor(
			CatalogSchemaContract.class,
			EntitySchemaContract.class,
			contractClass,
			String.class,
			String.class,
			String.class,
			List.class,
			boolean.class
		);
		ctor.setAccessible(true);
		final Object builder = ctor.newInstance(
			realCatalogSchema(),
			realEntitySchemaWithReference(existingSchema),
			existingSchema,
			REFERENCE_NAME,
			REFERENCE_TYPE,
			"originalRef",
			new LinkedList<LocalEntitySchemaMutation>(),
			true
		);
		return readBuilderMutations(builderClass, builder);
	}

	/**
	 * Reflectively reads the {@code mutations} field declared on
	 * {@code AbstractReferenceSchemaBuilder} so the test can inspect what the builder's
	 * constructor emitted without driving it through the full {@code toResult()} replay.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static List<LocalEntitySchemaMutation> readBuilderMutations(
		@Nonnull Class<?> builderClass,
		@Nonnull Object builder
	) throws ReflectiveOperationException {
		Class<?> cls = builderClass;
		while (cls != null) {
			try {
				final Field field = cls.getDeclaredField("mutations");
				field.setAccessible(true);
				return (List<LocalEntitySchemaMutation>) field.get(builder);
			} catch (final NoSuchFieldException ignored) {
				cls = cls.getSuperclass();
			}
		}
		throw new NoSuchFieldException("mutations field not found on builder hierarchy");
	}

	/**
	 * Builds a minimal real {@link CatalogSchema} for tests that need a non-null
	 * {@link CatalogSchemaContract} but do not exercise any of its surface beyond
	 * what {@code combineWith} or builder constructors call internally.
	 */
	@Nonnull
	private static CatalogSchema realCatalogSchema() {
		return CatalogSchema._internalBuild(
			"testCatalog",
			NamingConvention.generate("testCatalog"),
			null,
			EnumSet.noneOf(CatalogEvolutionMode.class),
			new EntitySchemaProvider() {
				@Nonnull
				@Override
				public Collection<EntitySchemaContract> getEntitySchemas() {
					return Collections.emptyList();
				}

				@Nonnull
				@Override
				public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
					return Optional.empty();
				}
			}
		);
	}

	/**
	 * Builds a minimal real {@link EntitySchema} whose single reference is the supplied schema —
	 * sufficient for {@code combineWith} or builder constructors to resolve {@code getReference}
	 * without resorting to a mocked {@link EntitySchemaContract}.
	 */
	@Nonnull
	private static EntitySchema realEntitySchemaWithReference(@Nonnull ReferenceSchemaContract reference) {
		return EntitySchema._internalBuild(
			1,
			"product",
			null, null,
			null,
			false, false, null,
			false, null, 2,
			Collections.emptySet(),
			Collections.emptySet(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Map.of(reference.getName(), reference),
			Collections.emptySet(),
			Collections.emptyMap()
		);
	}

}
