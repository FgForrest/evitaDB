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

package io.evitadb.annotation;

/**
 * Compiled input for the class-file reader tests in {@link InternalApiUsageTest}. Every declaration here exists to
 * put one specific structure into the bytes javac emits, so that the reader is checked against a *known* class file
 * rather than against whatever the reactor happens to contain on the day:
 *
 * - a marked type, field, constructor and method, so each of the reader's declaration paths has something to find;
 * - an **unmarked overload** of both the constructor and the method, so a reader that lost descriptor precision
 *   would index a member that carries no annotation;
 * - `long` and `double` constants, whose pool entries occupy **two** slots each - drop the compensating `i++` in
 *   the pool walk and every index resolved afterwards points at the wrong constant;
 * - a reference to {@link InternalArrayElementFixture}, named **only** through an array descriptor, which is the
 *   one shape that reaches `stripArrayDescriptor` on a real class file.
 *
 * These classes live in `target/test-classes` and are therefore invisible to the reactor-wide scan, which reads
 * `target/classes` only - marking them does not add members to the enforced set.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class ClassFileReaderFixtures {

	private ClassFileReaderFixtures() {
		throw new UnsupportedOperationException("Fixture holder cannot be instantiated!");
	}

	/**
	 * Carries a marker on the type itself and on one member of each kind the annotation targets, each paired with an
	 * unmarked sibling that only the descriptor tells apart.
	 */
	@Internal("use a supported fixture - this type exists to be found by the scan")
	static class Marked {
		/**
		 * Marked field. An array initializer is deliberately not a compile-time constant: a constant would be
		 * inlined into every reader and no `CONSTANT_Fieldref` would ever be emitted for it.
		 */
		@Internal("read the supported accessor")
		static final String[] MARKED_FIELD = {"marked"};
		/**
		 * Unread on purpose - a `static final long` still forces a `CONSTANT_Long` into the pool through its
		 * `ConstantValue` attribute, and that entry occupies two pool slots.
		 */
		static final long UNMARKED_LONG = 0x0102030405060708L;
		/**
		 * The same, for the other two-slot constant kind.
		 */
		static final double UNMARKED_DOUBLE = 0.125d;

		/**
		 * Marked constructor.
		 *
		 * @param marked ignored - only the descriptor matters
		 */
		@Internal("call the string constructor")
		Marked(int marked) {
		}

		/**
		 * Unmarked overload of the constructor above, told apart from it by its descriptor alone.
		 *
		 * @param unmarked ignored - only the descriptor matters
		 */
		Marked(String unmarked) {
		}

		/**
		 * Marked method.
		 *
		 * @param values values to report
		 * @return the first value, or an empty string
		 */
		@Internal("call the int overload")
		String overload(String[] values) {
			return values.length > 0 ? values[0] : "";
		}

		/**
		 * Unmarked overload of the method above, told apart from it by its descriptor alone.
		 *
		 * @param value value to report
		 * @return the value as a string
		 */
		String overload(int value) {
			return String.valueOf(value);
		}
	}

	/**
	 * Holds one reference to every member of {@link Marked}, marked and unmarked alike, plus the array reference to
	 * {@link InternalArrayElementFixture}.
	 */
	static final class Referencing {

		private Referencing() {
			throw new UnsupportedOperationException("Fixture holder cannot be instantiated!");
		}

		/**
		 * Emits the constant pool entries the reference test asserts on. The result is returned rather than
		 * discarded so that no part of it can be optimised out of the bytecode.
		 *
		 * @return whatever the referenced members produced
		 */
		static Object[] reachEveryFixtureMember() {
			final Marked marked = new Marked(1);
			final Marked unmarked = new Marked("unmarked");
			return new Object[]{
				marked.overload(Marked.MARKED_FIELD),
				unmarked.overload(1),
				new InternalArrayElementFixture[1][1]
			};
		}

	}

}
