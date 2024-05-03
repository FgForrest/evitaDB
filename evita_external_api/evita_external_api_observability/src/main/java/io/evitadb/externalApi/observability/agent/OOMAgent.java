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

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.dynamic.loading.ClassInjector;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.none;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class OOMAgent {

	public static void premain(String agentArgs, Instrumentation inst) {
		ClassInjector.UsingUnsafe.Factory factory = ClassInjector.UsingUnsafe.Factory.resolve(inst);
		AgentBuilder agentBuilder = new AgentBuilder.Default();
		agentBuilder = agentBuilder.with(new AgentBuilder.InjectionStrategy.UsingUnsafe.OfFactory(factory));


		agentBuilder
			.disableClassFormatChanges()
			.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
			.ignore(none())
			.ignore(nameStartsWith("net.bytebuddy."))
			.type(is(OutOfMemoryError.class))
			.transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
				.visit(
					Advice
						.to(MyAdvice.class)
						.on(isConstructor())
				))
			.installOn(inst);
	}

	public static class MyAdvice {
		@OnMethodEnter
		public static boolean before() {
			System.out.println("!!! OOM !!!");
			return true;
		}
	}

}
