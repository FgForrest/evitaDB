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

package io.evitadb.index.fulltext.analysis;

import org.apache.lucene.util.AttributeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.*;

/** A saved capture must own its state and copy it back when lookahead restores an earlier input. */
@Tag(FULLTEXT)
@Tag(INDEXING)
@DisplayName("Pre-stem capture state")
class FulltextPreStemAttributeTest {
	/** Lucene's exact state protocol, independently of any index oracle. */
	@Nested
	@DisplayName("Saved states")
	class SavedStates {
		/** A later distinct input must not survive restoration of the earlier input. */
		@Test
		@DisplayName("should restore a captured term after clear and overwrite")
		void shouldRestoreCapturedTerm() {
			final AttributeSource source = new AttributeSource();
			source.addAttributeImpl(new FulltextPreStemAttributeImpl());
			final FulltextPreStemAttribute attribute = source.getAttribute(FulltextPreStemAttribute.class);
			attribute.copyBuffer("cards".toCharArray(), 0, 5);
			final AttributeSource.State state = source.captureState();
			source.clearAttributes();
			attribute.copyBuffer("dogs".toCharArray(), 0, 4);
			source.restoreState(state);
			assertEquals("cards", attribute.toString());
			assertFalse(attribute.isFinalTerm());
			attribute.useFinalTerm();
			final AttributeSource.State tail = source.captureState();
			attribute.copyBuffer("dogs".toCharArray(), 0, 4);
			source.restoreState(tail);
			assertTrue(attribute.isFinalTerm());
			assertEquals(0, attribute.length());
		}

		/** A cleared token must read as no capture at all, neither a stale term nor a final-term mark. */
		@Test
		@DisplayName("should report an unavailable capture after clearing")
		void shouldReportUnavailableCaptureWhenCleared() {
			final AttributeSource source = new AttributeSource();
			source.addAttributeImpl(new FulltextPreStemAttributeImpl());
			final FulltextPreStemAttribute attribute = source.getAttribute(FulltextPreStemAttribute.class);
			attribute.copyBuffer("cards".toCharArray(), 0, 5);
			source.clearAttributes();

			assertEquals(0, attribute.length());
			assertEquals("", attribute.toString());
			assertFalse(attribute.isFinalTerm());

			attribute.useFinalTerm();
			source.clearAttributes();

			assertFalse(attribute.isFinalTerm());
		}

		/** Restoring an earlier plain capture over a live final-term mark must not keep the mark. */
		@Test
		@DisplayName("should drop the final-term mark when restoring a plain capture")
		void shouldClearFinalTermWhenRestoringPlainCapture() {
			final AttributeSource source = new AttributeSource();
			source.addAttributeImpl(new FulltextPreStemAttributeImpl());
			final FulltextPreStemAttribute attribute = source.getAttribute(FulltextPreStemAttribute.class);
			attribute.copyBuffer("cards".toCharArray(), 0, 5);
			final AttributeSource.State plain = source.captureState();
			attribute.useFinalTerm();

			source.restoreState(plain);

			assertEquals("cards", attribute.toString());
			assertFalse(attribute.isFinalTerm());
		}
	}

	/** The capture is compared and hashed as a character sequence, so it must never expose stale characters. */
	@Nested
	@DisplayName("Character view")
	class CharacterView {
		/** A shorter term reuses the longer term's buffer, whose tail must stay out of reach. */
		@Test
		@DisplayName("should expose only the current capture over a longer stale buffer")
		void shouldBoundAccessToCurrentCapture() {
			final FulltextPreStemAttributeImpl attribute = new FulltextPreStemAttributeImpl();
			attribute.copyBuffer("cards".toCharArray(), 0, 5);
			attribute.copyBuffer("dog".toCharArray(), 0, 3);

			assertEquals(3, attribute.length());
			assertEquals(0, CharSequence.compare("dog", attribute));
			assertEquals('g', attribute.charAt(2));
			assertEquals("og", attribute.subSequence(1, 3).toString());
			assertThrows(IndexOutOfBoundsException.class, () -> attribute.charAt(3));
			assertThrows(IndexOutOfBoundsException.class, () -> attribute.subSequence(0, 4));
		}
	}
}
