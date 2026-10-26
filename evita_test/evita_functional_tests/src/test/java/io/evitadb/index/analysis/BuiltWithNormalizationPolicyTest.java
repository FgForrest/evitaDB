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


package io.evitadb.index.analysis;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaTypeMutation;
import io.evitadb.index.analysis.AttributeNormalizationPolicy.ElementBinding;
import io.evitadb.index.attribute.EntityAttributeIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static org.junit.jupiter.api.Assertions.*;

/** Cheap parallelizable policy/codec cases; embedded-engine tests cover actual ownership and publication. */
@Tag(INDEXING)
@Tag(SERIALIZATION)
@DisplayName("Built-with policy reconciliation and description persistence")
class BuiltWithNormalizationPolicyTest {
	private static final AttributeIndexKey CODE = new AttributeIndexKey(null, "code", null);
	private static final AttributeIndexKey COMPOUND = new AttributeIndexKey(null, "compound", null);

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("An empty attribute adopts its declaration, a populated one preserves its built-with row")
	void shouldReconcileOnlyAtSchemaAdoption(boolean populated) {
		final EntitySchemaContract schema = builder().withAttribute("code", String.class,
			a -> a.filterable().normalized(lowercase())).toInstance();
		final AttributeNormalizationPolicy policy = AttributeNormalizationReconciler.reconcile("unit",
			AttributeNormalizationPolicy.EMPTY, schema, key -> populated, key -> false, Map.of());
		assertEquals(populated ? List.of() : List.of(lowercase()), policy.rows().getOrDefault(CODE, List.of()));
	}

	@Test
	@DisplayName("A resolver over no persisted row keeps the type-level key function whatever today's declaration says")
	void shouldNotBorrowDeclarationWhenPersistedPolicyIsMissing() {
		final EntitySchemaContract schema = builder().withAttribute("code", String.class,
			a -> a.filterable().normalized(lowercase())).toInstance();
		assertEquals("TITLE", new BuiltWithAttributeNormalizationResolver(new AnalysisStepRegistry(),
			AttributeNormalizationPolicy.EMPTY, Map.of()).resolve(null, schema.getAttribute("code").orElseThrow(), null)
			.apply("TITLE"), "Missing persisted metadata must never borrow today's declaration");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Removing or changing the type preserves a row exactly while its String keys survive")
	void shouldKeepAnOrphanedOrRetypedStringRow(boolean populated) {
		final AttributeNormalizationPolicy previous = new AttributeNormalizationPolicy(
			Map.of(CODE, List.of(lowercase())), Map.of(), Set.of());
		for (final EntitySchemaContract schema : List.of(builder().toInstance(),
			builder().withAttribute("code", Integer.class).toInstance())) {
			final AttributeNormalizationPolicy result = AttributeNormalizationReconciler.reconcile("unit", previous,
				schema, key -> populated, key -> false, Map.of());
			assertEquals(populated, result.rows().containsKey(CODE));
			if (populated) {
				assertEquals("title", new BuiltWithAttributeNormalizationResolver(
					new AnalysisStepRegistry(), result, Map.of())
					.resolve(null, "code", null).apply("TITLE"));
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("A partial NULL compound pins only its non-null String elements")
	void shouldReportStringKeysOnlyForNonNullStringCompoundElements(boolean present) {
		final EntitySchemaContract schema = builder()
			.withAttribute("code", String.class, a -> a.nullable())
			.withAttribute("other", Integer.class)
			.withSortableAttributeCompound("compound",
				new AttributeElement("code", OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new AttributeElement("other", OrderDirection.ASC, OrderBehaviour.NULLS_LAST)).toInstance();
		final AttributeNormalizationPolicy policy = new AttributeNormalizationPolicy(Map.of(),
			AttributeNormalizationReconciler.describeCompounds(schema), Set.of());
		final EntityAttributeIndex index =
			new EntityAttributeIndex("Product", AttributeNormalizationResolver.LEGACY_NFD);
		index.insertSortAttributeCompound(schema, null, schema.getSortableAttributeCompound("compound").orElseThrow(),
			name -> schema.getAttribute(name).orElseThrow().getType(), null,
			new Serializable[]{present ? "" : null, 1}, 1);
		assertEquals(present, index.hasIndexedStringKeys(CODE, policy));
		assertTrue(index.hasSortKeys(COMPOUND));
	}

	@Test
	@DisplayName("Stored compound functions follow retained element names and localization after schema changes")
	void shouldBindAnOrphanedCompoundByPersistedIdentity() {
		final AttributeNormalizationPolicy policy = new AttributeNormalizationPolicy(
			Map.of(CODE, List.of(asciiFolding(), lowercase())),
			Map.of(COMPOUND, List.of(new ElementBinding("code", String.class, false, false),
				new ElementBinding("global", String.class, true, true))), Set.of("global"));
		final BuiltWithAttributeNormalizationResolver resolver = new BuiltWithAttributeNormalizationResolver(
			new AnalysisStepRegistry(), policy, Map.of("global", List.of(asciiFolding(), lowercase())));
		final SortIndex.ComparatorSource[] descriptors = {
			new SortIndex.ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
			new SortIndex.ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
		};
		final Serializable[] tuple = {"ÍTITLE", "ÍTITLE"};
		resolver.resolveStoredSortNormalizer(
			new AttributeIndexKey(null, "compound", Locale.forLanguageTag("tr")),
			descriptors, 0).apply(tuple);
		assertArrayEquals(new Serializable[]{"ititle", "ıtıtle"}, tuple);
	}

	@Test
	@DisplayName("Owner budgets include parameter text and arrays without multiplying by physical index count")
	void shouldPriceParameterContentAtTheOwner() {
		final AttributeNormalizationPolicy shortPolicy = new AttributeNormalizationPolicy(
			Map.of(CODE, List.of(removeCharacters("-"))), Map.of(), Set.of());
		final AttributeNormalizationPolicy longPolicy = new AttributeNormalizationPolicy(
			Map.of(CODE, List.of(removeCharacters("-".repeat(1000)))), Map.of(), Set.of());
		assertTrue(AttributeNormalizationPolicyHeapSize.estimate(longPolicy) >
			AttributeNormalizationPolicyHeapSize.estimate(shortPolicy) + 900);
		final AttributeNormalizationPolicy shortAdoption = new AttributeNormalizationPolicy(
			Map.of(), Map.of(), Set.of("global"));
		final AttributeNormalizationPolicy longAdoption = new AttributeNormalizationPolicy(
			Map.of(), Map.of(), Set.of("global".repeat(1000)));
		assertTrue(AttributeNormalizationPolicyHeapSize.estimate(longAdoption) >
			AttributeNormalizationPolicyHeapSize.estimate(shortAdoption) + 900);
	}

	@Test
	@DisplayName("A populated mismatch logs one warning naming the attribute, declaration and effective chain")
	void shouldWarnOnceForTheLogicalAttribute() {
		final AttributeNormalizationPolicy previous = new AttributeNormalizationPolicy(
			Map.of(CODE, List.of(lowercase())), Map.of(), Set.of());
		final EntitySchemaContract schema = builder().withAttribute("code", String.class,
			a -> a.normalized(asciiFolding())).toInstance();

		final List<String> warnings = captureWarnings("warningUnitOwner", () -> AttributeNormalizationReconciler
			.reconcile("warningUnitOwner", previous, schema, key -> true, key -> false, Map.of()));

		assertEquals(1, warnings.size());
		assertTrue(warnings.getFirst().contains("code"));
		assertTrue(warnings.getFirst().contains("ascii-folding"));
		assertTrue(warnings.getFirst().contains("lowercase"));
	}

	@Test
	@DisplayName("A populated attribute whose built-with chain equals its declaration logs nothing")
	void shouldNotWarnWhenEffectiveChainEqualsDeclaration() {
		final AttributeNormalizationPolicy previous = new AttributeNormalizationPolicy(
			Map.of(CODE, List.of(lowercase())), Map.of(), Set.of());
		final EntitySchemaContract schema = builder().withAttribute("code", String.class,
			a -> a.normalized(lowercase())).toInstance();

		final List<String> warnings = captureWarnings("equalChainUnitOwner", () -> AttributeNormalizationReconciler
			.reconcile("equalChainUnitOwner", previous, schema, key -> true, key -> false, Map.of()));

		assertEquals(List.of(), warnings);
	}

	@Test
	@DisplayName("An empty attribute adopts a changed declaration without logging")
	void shouldNotWarnWhenAttributeIsEmpty() {
		final AttributeNormalizationPolicy previous = new AttributeNormalizationPolicy(
			Map.of(CODE, List.of(lowercase())), Map.of(), Set.of());
		final EntitySchemaContract schema = builder().withAttribute("code", String.class,
			a -> a.normalized(asciiFolding())).toInstance();

		final List<String> warnings = captureWarnings("emptyUnitOwner", () -> AttributeNormalizationReconciler
			.reconcile("emptyUnitOwner", previous, schema, key -> false, key -> false, Map.of()));

		assertEquals(List.of(), warnings);
	}

	@ParameterizedTest
	@MethodSource("attributeTypes")
	@DisplayName("Type mutation readers consume the existing ClassSerializer primitive/wrapper flag")
	void shouldReadTheUnchangedTypeMutationPayload(@Nonnull Class<? extends Serializable> type) {
		final Kryo kryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		try (final Output output = new Output(4096, -1)) {
			kryo.writeClassAndObject(output, new ModifyAttributeSchemaTypeMutation("code", type, 2));
			try (final Input input = new Input(output.toBytes())) {
				final ModifyAttributeSchemaTypeMutation mutation =
					(ModifyAttributeSchemaTypeMutation) kryo.readClassAndObject(input);
				assertEquals(type, mutation.getType());
				assertEquals(2, mutation.getIndexedDecimalPlaces());
				assertEquals(output.position(), input.position());
			}
		}
	}

	/**
	 * Includes numeric wrappers whose ClassSerializer payload has a flag, and ordinary reference types without it.
	 *
	 * @return attribute types whose serialized form is checked
	 */
	@Nonnull
	private static List<Class<? extends Serializable>> attributeTypes() {
		return List.of(Integer.class, Long.class, String.class);
	}

	/**
	 * Runs the action with the reconciler's logger captured. The appender sees every thread, so only the messages
	 * naming `owner` are returned, which keeps parallel tests out of each other's results.
	 *
	 * @param owner name of the schema owner that a warning must mention to be returned
	 * @param action code that is expected to log reconciler warnings
	 * @return formatted warning messages mentioning `owner`, in logging order
	 */
	@Nonnull
	private static List<String> captureWarnings(@Nonnull String owner, @Nonnull Runnable action) {
		final Logger logger = (Logger) LoggerFactory.getLogger(AttributeNormalizationReconciler.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.list = new CopyOnWriteArrayList<>();
		appender.start();
		logger.addAppender(appender);
		try {
			action.run();
			return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
				.filter(message -> message.contains(owner)).toList();
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	/**
	 * Produces API schema candidates without booting an engine or creating a WAL.
	 *
	 * @return builder of an empty `Product` schema inside an in-memory catalog schema
	 */
	@Nonnull
	private static InternalEntitySchemaBuilder builder() {
		return new InternalEntitySchemaBuilder(CatalogSchema._internalBuild("unit", NamingConvention.generate("unit"),
			null, EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE),
			EntitySchema._internalBuild("Product"));
	}
}
