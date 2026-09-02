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

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.AsyncDataFetcher;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.store.catalog.task.RestoreTask;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the build-time ByteBuddy weaving performed by {@link AbstractInterruptionTransformer} actually
 * injected the interrupt poll into the shipped class files.
 *
 * ## Why this test exists
 *
 * A ByteBuddy matcher that matches nothing is indistinguishable from one that works — the plugin logs
 * `Transformed N type(s)` either way. The matcher union had originally been assembled with
 * `ElementMatchers.anyOf(...)`, whose `Object...` overload compares candidates by {@link Object#equals(Object)}
 * against the supplied *values*; handed matcher instances it matched no method anywhere, in any module. Query
 * cancellation and query timeouts were silently disabled for the entire lifetime of the annotation, and every test in
 * the suite still passed, because nothing asserted on the woven output.
 *
 * The check cannot live inside the transformer: `byte-buddy-maven-plugin`'s `transform` goal is incremental (see its
 * `staleMilliseconds` parameter), so a build in which nothing recompiled processes zero types — a plugin-side "I wove
 * nothing" assertion cannot distinguish a dead matcher from an up-to-date module. Asserting on the **built artifact**
 * is immune to that.
 *
 * ## One assertion per module is the intended granularity, not an oversight
 *
 * This class owns exactly one claim — *the plugin ran against the shipped classes of this module* — and one
 * known-woven method per module is enough to carry it. Per-branch matcher semantics are owned by
 * {@link InterruptionTransformerMatcherTest}, which needs no build artifacts and would have caught the `anyOf` defect
 * in milliseconds. Adding more branches here would duplicate that class without strengthening this claim. Should a
 * matcher branch be narrowed deliberately, move the assertion to another method the branch still covers rather than
 * deleting it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(TASK)
@DisplayName("Interruption advice weaving")
class InterruptionAdviceWovenTest {
	/**
	 * Appended to every assertion message in this class. These assertions read the **built** class files, so a stale
	 * artifact produces a red that is indistinguishable from a dead matcher.
	 */
	private static final String STALE_ARTIFACT_HINT =
		" - note this reads the BUILT class file, so a stale ~/.m2 artifact produces the identical failure; rebuild " +
			"with `mvn -o install -DskipTests -pl <module>` before concluding the matcher is broken";

	/**
	 * Reads the class file of the given type from the classpath and reports whether the named method polls the thread
	 * interrupt flag, i.e. whether {@link Thread#isInterrupted()} is invoked anywhere in its body.
	 *
	 * Exactly one method of that name must exist in the class file. Matching by name alone would let an overload that
	 * happens to hand-poll the flag vouch for a sibling that was never woven, so the count is asserted rather than
	 * assumed — a new overload turns into an explicit failure asking for a descriptor-aware assertion.
	 *
	 * @param type       the type whose class file is inspected
	 * @param methodName the method to look for
	 * @return true when that method calls {@link Thread#isInterrupted()}
	 */
	private static boolean pollsInterruptFlag(@Nonnull Class<?> type, @Nonnull String methodName) {
		final String resource = type.getName().replace('.', '/') + ".class";
		try (InputStream is = type.getClassLoader().getResourceAsStream(resource)) {
			assertNotNull(is, "class file not found on classpath: " + resource + STALE_ARTIFACT_HINT);
			final boolean[] found = new boolean[1];
			final int[] inspected = new int[1];
			new ClassReader(is).accept(
				new ClassVisitor(Opcodes.ASM9) {
					@Override
					public MethodVisitor visitMethod(
						int access, String name, String descriptor, String signature, String[] exceptions
					) {
						if (!methodName.equals(name)) {
							return null;
						}
						inspected[0]++;
						return new MethodVisitor(Opcodes.ASM9) {
							@Override
							public void visitMethodInsn(
								int opcode, String owner, String mName, String mDescriptor, boolean isInterface
							) {
								if ("java/lang/Thread".equals(owner) && "isInterrupted".equals(mName)) {
									found[0] = true;
								}
							}
						};
					}
				},
				ClassReader.SKIP_FRAMES
			);
			assertEquals(
				1, inspected[0],
				"expected exactly one method named `" + methodName + "` in " + type.getName() + " - the assertion " +
					"below cannot tell which overload it vouched for" + STALE_ARTIFACT_HINT
			);
			return found[0];
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Nested
	@DisplayName("evita_engine")
	class Engine {

		@Test
		@DisplayName("weaves the interrupt poll into Formula#compute")
		void shouldWeaveInterruptPollIntoFormulaCompute() {
			// the single concrete implementation of Formula#compute - the gate every formula node passes through,
			// and therefore the checkpoint that makes a runaway query interruptible
			assertTrue(
				pollsInterruptFlag(AbstractFormula.class, "compute"),
				"AbstractFormula#compute carries no interrupt poll - query cancellation is silently disabled"
					+ STALE_ARTIFACT_HINT
			);
		}

		@Test
		@DisplayName("weaves the interrupt poll into an @Interruptible method")
		void shouldWeaveInterruptPollIntoAnnotatedMethod() {
			// covers the isAnnotatedWith(Interruptible.class) branch, which is the only branch driven by the
			// annotation rather than by a type hierarchy
			assertTrue(
				pollsInterruptFlag(EvitaSession.class, "getCatalogSchema"),
				"@Interruptible on EvitaSession#getCatalogSchema was not woven" + STALE_ARTIFACT_HINT
			);
		}
	}

	@Nested
	@Tag(STORAGE)
	@DisplayName("evita_store_server")
	class StoreServer {

		@Test
		@DisplayName("weaves the interrupt poll into an @Interruptible task method")
		void shouldWeaveInterruptPollIntoRestoreTask() {
			assertTrue(
				pollsInterruptFlag(RestoreTask.class, "readBlock"),
				"@Interruptible on RestoreTask#readBlock was not woven" + STALE_ARTIFACT_HINT
			);
		}
	}

	@Nested
	@Tag(GRAPHQL)
	@Tag(EXTERNAL_API)
	@DisplayName("evita_external_api_graphql")
	class GraphQl {

		@Test
		@DisplayName("weaves the interrupt poll into DataFetcher#get")
		void shouldWeaveInterruptPollIntoDataFetcherGet() {
			assertTrue(
				pollsInterruptFlag(AsyncDataFetcher.class, "get"),
				"AsyncDataFetcher#get carries no interrupt poll - GraphQL cancellation is silently disabled"
					+ STALE_ARTIFACT_HINT
			);
		}
	}

	@Nested
	@DisplayName("Runtime behaviour")
	class RuntimeBehaviour {

		/**
		 * Clears the thread interrupt flag after every test in this class.
		 *
		 * This is a hard requirement rather than tidiness: `EvitaSession` alone carries 55 `@Interruptible` methods, so
		 * a flag left set on a surefire worker thread aborts every subsequent test in the same fork that touches a
		 * session — with a failure that points nowhere near this class.
		 */
		@AfterEach
		void clearInterruptFlag() {
			//noinspection ResultOfMethodCallIgnored
			Thread.interrupted();
		}

		@Test
		@DisplayName("throws InterruptedException from a woven method when the flag is already set")
		void shouldThrowInterruptedExceptionWhenFlagIsSetOnEntry() {
			final Formula formula = new ConstantFormula(new BaseBitmap(1, 2, 3));
			try {
				Thread.currentThread().interrupt();
				// Formula#compute declares no checked exception - the woven advice throws one anyway, straight from
				// bytecode. That undeclared surfacing IS the contract: it is what unwinds a running query, and it
				// compiles here only because JUnit's Executable declares `throws Throwable`.
				assertThrows(
					InterruptedException.class,
					formula::compute,
					"the woven poll did not fire - the advice is present in the class file but does not throw"
				);
			} finally {
				//noinspection ResultOfMethodCallIgnored
				Thread.interrupted();
			}
		}
	}

}
