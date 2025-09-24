/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.index;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RepresentativeReferenceKey comparison")
class RepresentativeReferenceKeyTest {
	@Nonnull
	private static RepresentativeReferenceKey rrk(@Nonnull String name, int pk, @Nonnull Serializable... attrs) {
		final ReferenceKey rk = new ReferenceKey(name, pk);
		return new RepresentativeReferenceKey(rk, attrs);
	}

	@Test
	@DisplayName("shouldOrderByReferenceNameWhenDifferent")
	void shouldOrderByReferenceNameWhenDifferent() {
		final RepresentativeReferenceKey a = rrk("A", 1);
		final RepresentativeReferenceKey b = rrk("B", 1);

		final int result = a.compareTo(b);
		assertTrue(result < 0, "Expected 'A' to be before 'B'");
	}

	@Test
	@DisplayName("shouldOrderByPrimaryKeyWhenReferenceNameEqual")
	void shouldOrderByPrimaryKeyWhenReferenceNameEqual() {
		final RepresentativeReferenceKey a = rrk("A", 1);
		final RepresentativeReferenceKey b = rrk("A", 2);

		final int result = a.compareTo(b);
		assertTrue(result < 0, "Expected PK 1 to be before PK 2 when names equal");
	}

	@Test
	@DisplayName("shouldOrderByRepresentativeAttributesWhenNameAndPkEqual")
	void shouldOrderByRepresentativeAttributesWhenNameAndPkEqual() {
		final RepresentativeReferenceKey first = rrk("A", 1, 1, "x");
		final RepresentativeReferenceKey secondHigherFirstAttr = rrk("A", 1, 2, "x");
		final RepresentativeReferenceKey secondHigherSecondAttr = rrk("A", 1, 1, "y");

		final int c1 = first.compareTo(secondHigherFirstAttr);
		assertTrue(c1 < 0, "Expected lower first attribute to come first");

		final int c2 = first.compareTo(secondHigherSecondAttr);
		assertTrue(c2 < 0, "Expected lower second attribute to come first when first equals");
	}

	@Test
	@DisplayName("shouldReturnZeroWhenAllComponentsEqual")
	void shouldReturnZeroWhenAllComponentsEqual() {
		final RepresentativeReferenceKey a = rrk("A", 1, 1, "x");
		final RepresentativeReferenceKey b = rrk("A", 1, 1, "x");

		final int result = a.compareTo(b);
		assertEquals(0, result, "Expected equal keys to compare as zero");
	}

	@Test
	@DisplayName("shouldThrowExceptionWhenRepresentativeAttributeLengthsDiffer")
	void shouldThrowExceptionWhenRepresentativeAttributeLengthsDiffer() {
		final RepresentativeReferenceKey a = rrk("A", 1, 1);
		final RepresentativeReferenceKey b = rrk("A", 1, 1, 2);

		assertThrows(GenericEvitaInternalError.class, () -> a.compareTo(b));
	}
}
