/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.EvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;

import javax.annotation.Nonnull;

/**
 * ByteBuddy transformer that injects {@link InterruptionAdvice} into specific methods used during query execution
 * shared logic that checks for thread interruption. If the thread is interrupted, the method throws an
 * {@link InterruptedException} and effectively stops the query execution.
 */
public class InterruptionTransformer implements Plugin {

	@Override
	public boolean matches(TypeDescription target) {
		return true;
	}

	@Nonnull
	@Override
	public DynamicType.Builder<?> apply(
		DynamicType.Builder<?> builder,
		@Nonnull TypeDescription typeDescription,
		@Nonnull ClassFileLocator classFileLocator
	) {
		return builder.method(
				ElementMatchers.anyOf(
					/* any interruptible method */
					ElementMatchers.isAnnotatedWith(Interruptible.class)
						.and(ElementMatchers.not(ElementMatchers.isAbstract())),
					/* analysis of filtering constraints and conversion to Formulas */
					ElementMatchers.isOverriddenFrom(FilteringConstraintTranslator.class)
						.and(ElementMatchers.named("translate"))
						.and(ElementMatchers.not(ElementMatchers.isAbstract())),
					/* Formula calculation */
					ElementMatchers.isOverriddenFrom(Formula.class)
						.and(ElementMatchers.named("compute"))
						.and(ElementMatchers.not(ElementMatchers.isAbstract())),
					/* analysis of ordering constraints and conversion to Sorters */
					ElementMatchers.isOverriddenFrom(OrderingConstraintTranslator.class)
						.and(ElementMatchers.named("createSorter"))
						.and(ElementMatchers.isAbstract()),
					/* Sorters application */
					ElementMatchers.isOverriddenFrom(Sorter.class)
						.and(ElementMatchers.named("sortAndSlice"))
						.and(ElementMatchers.isAbstract()),
					/* analysis of require constraints and conversion to ExtraResultComputers */
					ElementMatchers.isOverriddenFrom(ExtraResultProducer.class)
						.and(ElementMatchers.named("fabricate"))
						.and(ElementMatchers.isAbstract()),
					/* ExtraResultComputers invocation */
					ElementMatchers.isOverriddenFrom(EvitaResponseExtraResultComputer.class)
						.and(ElementMatchers.named("compute"))
						.and(ElementMatchers.isAbstract())
				)
			)
			.intercept(Advice.to(InterruptionAdvice.class));
	}

	@Override
	public void close() {
		// No resources to release
	}

	/**
	 * Injected logic that checks for thread interruption. If the thread is interrupted, the method throws an
	 * {@link InterruptedException} and effectively stops the query execution.
	 */
	public static class InterruptionAdvice {

		@Advice.OnMethodEnter
		public static void onMethodEnter() throws InterruptedException {
			if (Thread.currentThread().isInterrupted()) {
				throw new InterruptedException("Thread interrupted");
			}
		}

	}

}
