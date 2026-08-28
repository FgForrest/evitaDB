/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.observability.agent;

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.NotMonitored;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.matcher.ElementMatcher;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Agent that intercepts all Error constructors and sends a metric to the MetricHandler.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ErrorMonitoringAgent {

	public static void premain(String agentArgs, Instrumentation inst) {
		ClassInjector.UsingUnsafe.Factory factory = ClassInjector.UsingUnsafe.Factory.resolve(inst);
		AgentBuilder agentBuilder = new AgentBuilder.Default();
		agentBuilder = agentBuilder.with(new AgentBuilder.InjectionStrategy.UsingUnsafe.OfFactory(factory));

		agentBuilder
			.disableClassFormatChanges()
			.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
			.ignore(none())
			.ignore(nameStartsWith("net.bytebuddy."))
			.type(javaErrorTypes())
			.transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
				.visit(
					Advice
						.to(JavaErrorConstructorInterceptAdvice.class)
						.on(isConstructor())
				))
			.type(evitaInternalErrorRoot())
			.transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
				.visit(
					Advice
						.to(EvitaDbErrorConstructorInterceptAdvice.class)
						.on(isConstructor())
				))
			.type(clientErrorRoot())
			.transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
				.visit(
					Advice
						.to(ClientErrorConstructorInterceptAdvice.class)
						.on(isConstructor())
				))
			.installOn(inst);

		// Inject ErrorMonitoring class into the bootstrap classloader
		Map<TypeDescription, byte[]> types = new HashMap<>(8);
		types.put(
			new TypeDescription.ForLoadedType(ErrorMonitor.class),
			getClassBytes(ErrorMonitor.class)
		);
		ClassInjector.UsingUnsafe.ofBootLoader().inject(types);
	}

	/**
	 * JVM error types whose construction is counted. Unlike the two evitaDB hierarchies below, these are matched per
	 * concrete subtype: `VirtualMachineError` is a JDK class, its subtypes neither extend one another nor delegate
	 * between their own constructors, so per-subtype matching counts each instance exactly once - and retransforming
	 * `java.lang.VirtualMachineError` itself would buy nothing for the added risk.
	 *
	 * A type opts out of monitoring by carrying the `NotMonitored` marker; here that can still be decided while
	 * instrumenting, because the marker sits on the very type being matched.
	 *
	 * @return matcher selecting the JVM error types to instrument
	 */
	@Nonnull
	static ElementMatcher.Junction<TypeDescription> javaErrorTypes() {
		return isSubTypeOf(VirtualMachineError.class)
			.and(not(isAbstract()))
			.and(not(isAnnotatedWith(NotMonitored.class)));
	}

	/**
	 * The single root of the evitaDB internal-error hierarchy.
	 *
	 * Only the root is instrumented, not every subtype. Advice on a constructor fires once per constructor
	 * *entered*, so matching subtypes counted a concrete class extending another concrete class once per level -
	 * three increments for one instance at two levels deep. Every internal error, at any depth, passes through
	 * exactly one constructor of this root, which makes the root the only place that counts once per object.
	 *
	 * This holds only while no constructor of the root delegates to another via `this(...)`; see the note at the
	 * bottom of `EvitaInternalError`.
	 *
	 * @return matcher selecting the internal-error root
	 */
	@Nonnull
	static ElementMatcher.Junction<TypeDescription> evitaInternalErrorRoot() {
		return is(EvitaInternalError.class);
	}

	/**
	 * The single root of the evitaDB client-error hierarchy - see {@link #evitaInternalErrorRoot()} for why only the
	 * root is instrumented.
	 *
	 * @return matcher selecting the client-error root
	 */
	@Nonnull
	static ElementMatcher.Junction<TypeDescription> clientErrorRoot() {
		return is(EvitaInvalidUsageException.class);
	}

	/**
	 * Get the bytes of a particular class from classpath.
	 * @param clazz Class to get bytes of.
	 * @return Byte array of the class.
	 */
	@Nonnull
	public static byte[] getClassBytes(@Nonnull Class<?> clazz) {
		try {
			final String classAsResource = clazz.getName().replace('.', '/') + ".class";
			try (InputStream classStream = ErrorMonitoringAgent.class.getClassLoader().getResourceAsStream(classAsResource)) {
				if (classStream == null) {
					System.err.println("Class `" + clazz.getName() + "` not found in classpath and is required by ErrorMonitoringAgent.");
					System.exit(1);
					throw new IllegalStateException("Class `" + clazz.getName() + "` not found in classpath and is required by ErrorMonitoringAgent.");
				}
				return classStream.readAllBytes();
			}
		} catch (IOException e) {
			System.err.println("Class `" + clazz.getName() + "` not found in classpath and is required by ErrorMonitoringAgent.");
			System.exit(1);
			throw new IllegalStateException("Class `" + clazz.getName() + "` not found in classpath and is required by ErrorMonitoringAgent.");
		}
	}

	/**
	 * Advice reporting a JVM error as it is constructed.
	 *
	 * The body is inlined into every matched constructor, so it stays as small as possible: it hands the throwable
	 * over and lets the consumer, an ordinary application class, decide what to do with it.
	 */
	public static class JavaErrorConstructorInterceptAdvice {

		@OnMethodExit
		public static boolean after(@Advice.This Object thiz) {
			ErrorMonitor.registerJavaError((Throwable) thiz);
			return true;
		}

	}

	/**
	 * Advice reporting an evitaDB internal error as it is constructed.
	 *
	 * Because only the hierarchy root is instrumented, the `NotMonitored` opt-out can no longer be applied while
	 * matching - the marker sits on the concrete subtype, which is not the type being woven. The consumer applies it
	 * at runtime instead, before touching any metric, so the opt-out means exactly what it did before.
	 */
	public static class EvitaDbErrorConstructorInterceptAdvice {

		@OnMethodExit
		public static boolean after(@Advice.This Object thiz) {
			ErrorMonitor.registerEvitaError((Throwable) thiz);
			return true;
		}

	}

	/**
	 * Advice reporting an evitaDB client error as it is constructed - see
	 * {@link EvitaDbErrorConstructorInterceptAdvice} for why the `NotMonitored` opt-out moved to the consumer.
	 */
	public static class ClientErrorConstructorInterceptAdvice {

		@OnMethodExit
		public static boolean after(@Advice.This Object thiz) {
			ErrorMonitor.registerClientError((Throwable) thiz);
			return true;
		}

	}

}
