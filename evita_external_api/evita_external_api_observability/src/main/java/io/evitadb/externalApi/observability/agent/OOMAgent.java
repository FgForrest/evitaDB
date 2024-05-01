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
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.SuperMethodCall;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

import static net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy.REDEFINITION;
import static net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy.RETRANSFORMATION;
import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class OOMAgent {
	private static final Method HANDLE_OOM;

	static {
		try {
			HANDLE_OOM = OOMAgent.class.getDeclaredMethod("handleOOM");
		} catch (NoSuchMethodException e) {
			throw new EvitaInternalError("!!! OOMAgent initialization failed !!!", e);
		}
	}

	public static void premain(String agentArgs, Instrumentation inst) {
		if (HANDLE_OOM != null) {
			new AgentBuilder.Default()
				.disableClassFormatChanges()
				.with(new AgentBuilder.InjectionStrategy.UsingInstrumentation(inst, new File("/www/oss/evitaDB-temporary/evita_external_api/evita_external_api_observability/target/evita_external_api_observability-2024.5-SNAPSHOT.jar")))
				.with(REDEFINITION)
				// Make sure we see helpful logs
				.with(AgentBuilder.RedefinitionStrategy.Listener.StreamWriting.toSystemError())
				.with(AgentBuilder.Listener.StreamWriting.toSystemError().withTransformationsOnly())
				.with(AgentBuilder.InstallationListener.StreamWriting.toSystemError())
				.ignore(none())
				// Ignore Byte Buddy and JDK classes we are not interested in
				.ignore(
					nameStartsWith("net.bytebuddy.")
						.or(nameStartsWith("jdk.internal.reflect."))
						.or(nameStartsWith("java.lang.invoke."))
						.or(nameStartsWith("com.sun.proxy."))
				)
				.disableClassFormatChanges()
				.with(RETRANSFORMATION)
				.with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
				.with(AgentBuilder.TypeStrategy.Default.REDEFINE)
				.type(named("java.lang.OutOfMemoryError"))
				.transform(
					(builder, typeDescription, classLoader, javaModule, protectionDomain) -> builder
						.constructor(any())
						.intercept(SuperMethodCall.INSTANCE.andThen(MethodCall.invoke(HANDLE_OOM)))
				)
				.installOn(inst);
		}
	}

	public static void handleOOM() {
		System.out.println("!!! OOM !!!");
	}

}
