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

package io.evitadb.core.executor;

import graphql.schema.DataFetcher;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.histogram.cache.FlattenedHistogramComputer;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramProducer;
import io.evitadb.core.query.filter.translator.FilterByTranslator;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.translator.OrderByTranslator;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.AsyncDataFetcher;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the per-branch semantics of the matcher returned by `interruptibleMethods()` in both
 * {@link AbstractInterruptionTransformer} implementations — the engine one and the GraphQL one.
 *
 * ## Division of labour — do not "consolidate" this class with the woven test
 *
 * Three tests guard the interruption weaving, and each owns an orthogonal claim:
 *
 * - **This class owns per-branch matcher semantics.** The engine matcher is a union of seven branches; this class
 *   asserts each one individually, plus the negative cases that pin the trailing `not(isAbstract())` clause. It needs
 *   no build artifacts and runs in milliseconds, which is what makes it the right place to catch a branch that stopped
 *   matching — the original `ElementMatchers.anyOf(...)` defect would have failed every positive assertion here
 *   instantly.
 * - **{@link InterruptionAdviceWovenTest} owns the orthogonal claim that the plugin actually ran** against the shipped
 *   class files, with exactly one assertion per module carrying the transformer. A matcher can be perfect and the
 *   build still ship un-woven classes.
 * - Its `Runtime behaviour` nested class owns the claim that the woven poll actually throws.
 *
 * Extending any of the three into another's territory is redundant, not thorough.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(TASK)
@DisplayName("Interruption matcher")
class InterruptionTransformerMatcherTest {

	/**
	 * Resolves the declared methods of the given type through a {@link TypePool} rather than through reflection.
	 *
	 * {@link Interruptible} is `RetentionPolicy.CLASS`, so it is present in the class file but invisible to
	 * {@link java.lang.reflect.Method#getDeclaredAnnotations()}. A {@link MethodDescription} built from
	 * `ForLoadedMethod` would therefore report the `isAnnotatedWith(Interruptible.class)` branch dead — a false red
	 * that reads exactly like a genuinely broken matcher. Parsing the class file preserves the annotation.
	 *
	 * The pool is opened on the **type's own** class loader, never `ofSystemLoader()`: under surefire's manifest-only
	 * classpath jar the system loader resolves nothing, which surfaces as "method not found" — i.e. as the very false
	 * red this helper exists to prevent.
	 *
	 * @param type       the type whose declared methods are resolved
	 * @param methodName the method name to filter by
	 * @return the declared methods of that name, parsed from the class file
	 */
	@Nonnull
	private static MethodList<MethodDescription.InDefinedShape> declaredMethods(
		@Nonnull Class<?> type,
		@Nonnull String methodName
	) {
		final TypeDescription description = TypePool.Default.of(type.getClassLoader())
			.describe(type.getName())
			.resolve();
		final MethodList<MethodDescription.InDefinedShape> declared = description
			.getDeclaredMethods()
			.filter(ElementMatchers.named(methodName));
		// fail as "the method is gone" rather than as "the branch is dead" when someone renames the target
		assertFalse(
			declared.isEmpty(),
			type.getName() + " declares no method named `" + methodName + "` - the assertion target was renamed or " +
				"moved, which is not the same thing as the matcher branch having died"
		);
		return declared;
	}

	/**
	 * Reports whether the given matcher selects at least one method of the given name declared by the given type.
	 *
	 * A generic override (`FilterByTranslator#translate`, `OrderByTranslator#createSorter`,
	 * `FlattenedHistogramComputer#compute`) is accompanied in the class file by a javac-generated bridge carrying the
	 * erased signature, and it is the bridge that matches `isOverriddenFrom(...)`. Weaving the bridge is equally
	 * effective — it polls before delegating — so "at least one declared method of this name matches" is the honest
	 * question to ask.
	 *
	 * @param matcher    the matcher under test
	 * @param type       the declaring type
	 * @param methodName the method name
	 * @return true when at least one declared method of that name matches
	 */
	private static boolean matches(
		@Nonnull ElementMatcher<? super MethodDescription> matcher,
		@Nonnull Class<?> type,
		@Nonnull String methodName
	) {
		for (MethodDescription method : declaredMethods(type, methodName)) {
			if (matcher.matches(method)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Widens the engine transformer's `protected` matcher accessor to this test.
	 *
	 * Subclassing rather than relying on package placement is deliberate: it is what lets the GraphQL half of the
	 * matcher be asserted from this same class instead of forcing a second test class into
	 * `io.evitadb.externalApi.graphql.async`.
	 */
	private static final class EngineTransformerProbe extends InterruptionTransformer {

		@Nonnull
		@Override
		public ElementMatcher.Junction<MethodDescription> interruptibleMethods() {
			return super.interruptibleMethods();
		}

	}

	/**
	 * Widens the GraphQL transformer's `protected` matcher accessor to this test — see {@link EngineTransformerProbe}.
	 */
	private static final class GraphQlTransformerProbe
		extends io.evitadb.externalApi.graphql.async.InterruptionTransformer {

		@Nonnull
		@Override
		public ElementMatcher.Junction<MethodDescription> interruptibleMethods() {
			return super.interruptibleMethods();
		}

	}

	@Nested
	@DisplayName("Engine matcher")
	class EngineMatcher {
		private final ElementMatcher.Junction<MethodDescription> matcher =
			new EngineTransformerProbe().interruptibleMethods();

		@Test
		@DisplayName("selects a method carrying the @Interruptible annotation")
		void shouldSelectAnnotatedMethod() {
			assertTrue(
				matches(this.matcher, EvitaSession.class, "getCatalogSchema"),
				"the isAnnotatedWith(Interruptible.class) branch selects nothing - session-level interruption is off"
			);
		}

		@Test
		@DisplayName("selects a filtering constraint translator")
		void shouldSelectFilteringConstraintTranslator() {
			assertTrue(
				matches(this.matcher, FilterByTranslator.class, "translate"),
				"the FilteringConstraintTranslator#translate branch selects nothing - filter planning is not " +
					"interruptible"
			);
		}

		@Test
		@DisplayName("selects a formula computation")
		void shouldSelectFormulaComputation() {
			assertTrue(
				matches(this.matcher, AbstractFormula.class, "compute"),
				"the Formula#compute branch selects nothing - the hottest checkpoint in the engine is not " +
					"interruptible"
			);
		}

		@Test
		@DisplayName("selects an ordering constraint translator")
		void shouldSelectOrderingConstraintTranslator() {
			assertTrue(
				matches(this.matcher, OrderByTranslator.class, "createSorter"),
				"the OrderingConstraintTranslator#createSorter branch selects nothing - order planning is not " +
					"interruptible"
			);
		}

		@Test
		@DisplayName("selects a sorter application")
		void shouldSelectSorterApplication() {
			assertTrue(
				matches(this.matcher, NoSorter.class, "sortAndSlice"),
				"the Sorter#sortAndSlice branch selects nothing - sorting is not interruptible"
			);
		}

		@Test
		@DisplayName("selects an extra result producer")
		void shouldSelectExtraResultProducer() {
			assertTrue(
				matches(this.matcher, AttributeHistogramProducer.class, "fabricate"),
				"the ExtraResultProducer#fabricate branch selects nothing - require planning is not interruptible"
			);
		}

		@Test
		@DisplayName("selects an extra result computer")
		void shouldSelectExtraResultComputer() {
			assertTrue(
				matches(this.matcher, FlattenedHistogramComputer.class, "compute"),
				"the EvitaResponseExtraResultComputer#compute branch selects nothing - extra result computation is " +
					"not interruptible"
			);
		}

		@Test
		@DisplayName("rejects the abstract declarations of the matched methods")
		void shouldRejectAbstractDeclarations() {
			// the trailing not(isAbstract()) clause - an abstract method has no body to instrument, and weaving one
			// is a hard build failure rather than a silent no-op
			assertFalse(
				matches(this.matcher, Formula.class, "compute"),
				"the abstract Formula#compute declaration must not be selected"
			);
			assertFalse(
				matches(this.matcher, Sorter.class, "sortAndSlice"),
				"the abstract Sorter#sortAndSlice declaration must not be selected"
			);
			assertFalse(
				matches(this.matcher, ExtraResultProducer.class, "fabricate"),
				"the abstract ExtraResultProducer#fabricate declaration must not be selected"
			);
		}

		@Test
		@DisplayName("rejects a method unrelated to query execution")
		void shouldRejectUnrelatedMethod() {
			// the assertion that fails if a future edit widens the union to everything - getEstimatedCost sits on the
			// very class whose compute() the matcher does select, so nothing but the branch conditions separates them
			assertFalse(
				matches(this.matcher, AbstractFormula.class, "getEstimatedCost"),
				"the matcher selects a method that is not a query-execution checkpoint - the union was widened"
			);
		}

		@Test
		@DisplayName("applies to every type in the module")
		void shouldApplyToEveryType() {
			// the plugin filters methods, not types - apply(...) is invoked for each type and the advice visitor
			// decides what gets rewritten
			assertTrue(
				new EngineTransformerProbe().matches(TypeDescription.ForLoadedType.of(AbstractFormula.class)),
				"the transformer must accept every type in the module"
			);
		}
	}

	@Nested
	@DisplayName("GraphQL matcher")
	class GraphQlMatcher {
		private final ElementMatcher.Junction<MethodDescription> matcher =
			new GraphQlTransformerProbe().interruptibleMethods();

		@Test
		@DisplayName("selects a data fetcher invocation")
		void shouldSelectDataFetcherInvocation() {
			assertTrue(
				matches(this.matcher, AsyncDataFetcher.class, "get"),
				"the DataFetcher#get branch selects nothing - GraphQL cancellation is off"
			);
		}

		@Test
		@DisplayName("rejects the abstract declaration of the matched method")
		void shouldRejectAbstractDeclaration() {
			assertFalse(
				matches(this.matcher, DataFetcher.class, "get"),
				"the abstract DataFetcher#get declaration must not be selected"
			);
		}

		@Test
		@DisplayName("applies to every type in the module")
		void shouldApplyToEveryType() {
			assertTrue(
				new GraphQlTransformerProbe().matches(TypeDescription.ForLoadedType.of(AsyncDataFetcher.class)),
				"the transformer must accept every type in the module"
			);
		}
	}

}
