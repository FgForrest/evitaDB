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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.observability.agent;

import io.evitadb.exception.EvitaInternalError;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.loading.ClassInjector;

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
			.type(isSubTypeOf(VirtualMachineError.class).and(not(isAbstract())))
			.transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
				.visit(
					Advice
						.to(JavaErrorConstructorInterceptAdvice.class)
						.on(isConstructor())
				))
			.type(isSubTypeOf(EvitaInternalError.class).and(not(isAbstract())))
			.transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
				.visit(
					Advice
						.to(EvitaDbErrorConstructorInterceptAdvice.class)
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
	 * Advice that sends a metric to the MetricHandler when an Error is constructed.
	 */
	public static class JavaErrorConstructorInterceptAdvice {

		@OnMethodExit
		public static boolean after(@Advice.This Object thiz) {
			ErrorMonitor.registerJavaError(thiz.getClass().getSimpleName());
			return true;
		}

	}

	/**
	 * Advice that sends a metric to the MetricHandler when an Error is constructed.
	 */
	public static class EvitaDbErrorConstructorInterceptAdvice {

		@OnMethodExit
		public static boolean after(@Advice.This Object thiz) {
			ErrorMonitor.registerEvitaError(thiz.getClass().getSimpleName());
			return true;
		}

	}

}
