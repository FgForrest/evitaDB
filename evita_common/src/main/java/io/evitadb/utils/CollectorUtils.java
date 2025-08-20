/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.utils;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * Copy of {@link Collectors} methods that are not available in JDK.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class CollectorUtils {
	static final Set<Collector.Characteristics> CH_UNORDERED_NOID
		= Collections.unmodifiableSet(EnumSet.of(Collector.Characteristics.UNORDERED));

	/**
	 * Returns a {@code Collector} that accumulates the input elements into an
	 * <a href="../Set.html#unmodifiable">unmodifiable Set</a>. The returned
	 * Collector disallows null values and will throw {@code NullPointerException}
	 * if it is presented with a null value. If the input contains duplicate elements,
	 * an arbitrary element of the duplicates is preserved.
	 *
	 * <p>This is an {@link Collector.Characteristics#UNORDERED unordered}
	 * Collector.
	 *
	 * @param <T> the type of the input elements
	 * @return a {@code Collector} that accumulates the input elements into an
	 * <a href="../Set.html#unmodifiable">unmodifiable Set</a>
	 * @see Collectors#toUnmodifiableSet()
	 */
	@SuppressWarnings("unchecked")
	public static <T> Collector<T, ?, Set<T>> toUnmodifiableLinkedHashSet() {
		return new CollectorImpl<>(LinkedHashSet::new, Set::add,
			(left, right) -> {
				if (left.size() < right.size()) {
					right.addAll(left); return right;
				} else {
					left.addAll(right); return left;
				}
			},
			set -> (Set<T>)Collections.unmodifiableSet(set),
			CH_UNORDERED_NOID);
	}

	@SuppressWarnings("unchecked")
	private static <I, R> Function<I, R> castingIdentity() {
		return i -> (R) i;
	}

	/**
	 * Simple implementation class for {@code Collector}.
	 *
	 * @param <T> the type of elements to be collected
	 * @param <R> the type of the result
	 */
	static class CollectorImpl<T, A, R> implements Collector<T, A, R> {
		private final Supplier<A> supplier;
		private final BiConsumer<A, T> accumulator;
		private final BinaryOperator<A> combiner;
		private final Function<A, R> finisher;
		private final Set<Characteristics> characteristics;

		CollectorImpl(Supplier<A> supplier,
		              BiConsumer<A, T> accumulator,
		              BinaryOperator<A> combiner,
		              Function<A,R> finisher,
		              Set<Characteristics> characteristics) {
			this.supplier = supplier;
			this.accumulator = accumulator;
			this.combiner = combiner;
			this.finisher = finisher;
			this.characteristics = characteristics;
		}

		CollectorImpl(Supplier<A> supplier,
		              BiConsumer<A, T> accumulator,
		              BinaryOperator<A> combiner,
		              Set<Characteristics> characteristics) {
			this(supplier, accumulator, combiner, castingIdentity(), characteristics);
		}

		@Override
		public BiConsumer<A, T> accumulator() {
			return this.accumulator;
		}

		@Override
		public Supplier<A> supplier() {
			return this.supplier;
		}

		@Override
		public BinaryOperator<A> combiner() {
			return this.combiner;
		}

		@Override
		public Function<A, R> finisher() {
			return this.finisher;
		}

		@Override
		public Set<Characteristics> characteristics() {
			return this.characteristics;
		}
	}

}
