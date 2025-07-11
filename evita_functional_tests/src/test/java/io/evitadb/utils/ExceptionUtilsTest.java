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

package io.evitadb.utils;

import org.junit.jupiter.api.Test;

import java.io.Serial;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link ExceptionUtils}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class ExceptionUtilsTest {

    @Test
    void shouldReturnSameExceptionWhenNoCause() {
        // given
        final RuntimeException exception = new RuntimeException("Test exception");

        // when
        final Throwable rootCause = ExceptionUtils.getRootCause(exception);

        // then
        assertSame(exception, rootCause);
    }

    @Test
    void shouldFindRootCauseInExceptionChain() {
        // given
        final IllegalArgumentException rootCause = new IllegalArgumentException("Root cause");
        final IllegalStateException intermediate = new IllegalStateException("Intermediate", rootCause);
        final RuntimeException topLevel = new RuntimeException("Top level", intermediate);

        // when
        final Throwable foundRootCause = ExceptionUtils.getRootCause(topLevel);

        // then
        assertSame(rootCause, foundRootCause);
    }

    @Test
    void shouldHandleCircularReferenceInExceptionChain() {
        // given
        final CircularException exception1 = new CircularException("Exception 1");
        final CircularException exception2 = new CircularException("Exception 2", exception1);
        // Create circular reference
        exception1.initCause(exception2);

        // when
        final Throwable rootCause = ExceptionUtils.getRootCause(exception1);

        // then
        // Should not enter infinite loop and should return one of the exceptions in the chain
        // In this case, it returns exception1 because that's where the algorithm stops
        assertSame(exception1, rootCause);
    }

    /**
     * Custom exception class for testing circular references
     */
    private static class CircularException extends Exception {
        @Serial private static final long serialVersionUID = -6294796000814985596L;

        public CircularException(String message) {
            super(message);
        }

        public CircularException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
