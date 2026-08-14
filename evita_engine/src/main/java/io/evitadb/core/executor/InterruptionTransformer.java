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
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import javax.annotation.Nonnull;

/**
 * ByteBuddy transformer that injects {@link InterruptionAdvice} into specific methods used during query execution
 * shared logic that checks for thread interruption. If the thread is interrupted, the method throws an
 * {@link InterruptedException} and effectively stops the query execution.
 *
 * Weaving mechanics and the build-time guard against a matcher that stops matching live in
 * {@link AbstractInterruptionTransformer}.
 */
public class InterruptionTransformer extends AbstractInterruptionTransformer {

	/**
	 * Matches every method that must poll the thread interrupt flag on entry.
	 *
	 * The union is assembled by chaining {@link ElementMatcher.Junction#or(ElementMatcher)} — deliberately **not**
	 * via `ElementMatchers.anyOf(...)`, which compares candidates with {@link Object#equals(Object)} against the
	 * supplied values and therefore silently matches nothing when handed matcher instances.
	 *
	 * Abstract methods are excluded last, so the exclusion applies to the whole union rather than to a single branch.
	 */
	@Nonnull
	@Override
	protected ElementMatcher.Junction<MethodDescription> interruptibleMethods() {
		return ElementMatchers.<MethodDescription>isAnnotatedWith(Interruptible.class)
			/* analysis of filtering constraints and conversion to Formulas */
			.or(
				ElementMatchers.<MethodDescription>isOverriddenFrom(FilteringConstraintTranslator.class)
					.and(ElementMatchers.named("translate"))
			)
			/* Formula calculation */
			.or(
				ElementMatchers.<MethodDescription>isOverriddenFrom(Formula.class)
					.and(ElementMatchers.named("compute"))
			)
			/* analysis of ordering constraints and conversion to Sorters */
			.or(
				ElementMatchers.<MethodDescription>isOverriddenFrom(OrderingConstraintTranslator.class)
					.and(ElementMatchers.named("createSorter"))
			)
			/* Sorters application */
			.or(
				ElementMatchers.<MethodDescription>isOverriddenFrom(Sorter.class)
					.and(ElementMatchers.named("sortAndSlice"))
			)
			/* analysis of require constraints and conversion to ExtraResultComputers */
			.or(
				ElementMatchers.<MethodDescription>isOverriddenFrom(ExtraResultProducer.class)
					.and(ElementMatchers.named("fabricate"))
			)
			/* ExtraResultComputers invocation */
			.or(
				ElementMatchers.<MethodDescription>isOverriddenFrom(EvitaResponseExtraResultComputer.class)
					.and(ElementMatchers.named("compute"))
			)
			.and(ElementMatchers.not(ElementMatchers.isAbstract()));
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
