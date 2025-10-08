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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.requestResponse.data.Droppable;

import java.io.Serial;
import java.io.Serializable;
import java.util.function.Predicate;

/**
 * This interface defines a query that a predicate must be serializable. Using lambdas for this
 * interface is discouraged - see: https://stackoverflow.com/questions/38018415/how-to-safely-serialize-a-lambda
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface SerializablePredicate<T> extends Serializable, Predicate<T> {

	default SerializablePredicate<T> or(SerializablePredicate<T> other) {
		return new OrPredicate<>(this, other);
	}

	default SerializablePredicate<T> and(SerializablePredicate<T> other) {
		return new AndPredicate<>(this, other);
	}

	/**
	 * This predicate joins multiple predicates by OR relation.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
	 */
	class OrPredicate<T> implements SerializablePredicate<T> {
		@Serial private static final long serialVersionUID = -4534779807816763893L;
		private final SerializablePredicate<T> predicateA;
		private final SerializablePredicate<T> predicateB;

		public OrPredicate(SerializablePredicate<T> predicateA, SerializablePredicate<T> predicateB) {
			this.predicateA = predicateA;
			this.predicateB = predicateB;
		}

		@Override
		public boolean test(T t) {
			return this.predicateA.test(t) || this.predicateB.test(t);
		}

	}

	/**
	 * This predicate joins multiple predicates by OR relation.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
	 */
	class AndPredicate<T> implements SerializablePredicate<T> {
		@Serial private static final long serialVersionUID = -4534779807816763893L;
		private final SerializablePredicate<T> predicateA;
		private final SerializablePredicate<T> predicateB;

		public AndPredicate(SerializablePredicate<T> predicateA, SerializablePredicate<T> predicateB) {
			this.predicateA = predicateA;
			this.predicateB = predicateB;
		}

		@Override
		public boolean test(T t) {
			return this.predicateA.test(t) && this.predicateB.test(t);
		}

	}

	/**
	 * This predicate is true only when passed attribute exists.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
	 */
	class ExistsPredicate<T extends Droppable> implements SerializablePredicate<T> {
		private static final ExistsPredicate<? extends Droppable> INSTANCE = new ExistsPredicate<>();
		@Serial private static final long serialVersionUID = 7176072107501299504L;

		public static <T extends Droppable> ExistsPredicate<T> instance() {
			return (ExistsPredicate<T>) INSTANCE;
		}

		@Override
		public boolean test(T t) {
			return t.exists();
		}

	}

}
