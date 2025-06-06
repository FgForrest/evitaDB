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

package io.evitadb.api.query.require;

import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.defaultAccompanyingPrice;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link DefaultAccompanyingPrice} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class DefaultAccompanyingPriceTest {

    @Test
    void shouldCreateViaFactoryClassWorkAsExpected() {
        final DefaultAccompanyingPrice accompanyingPrice = defaultAccompanyingPrice("basic", "reference");
        assertArrayEquals(new String[] {"basic", "reference"}, accompanyingPrice.getPriceLists());
    }

    @Test
    void shouldCreateViaFactoryClassWorkAsExpectedNullInArray() {
        final DefaultAccompanyingPrice accompanyingPrice = defaultAccompanyingPrice("basic", null, "reference");
        assertArrayEquals(new String[] {"basic", "reference"}, accompanyingPrice.getPriceLists());
    }

    @Test
    void shouldCreateViaFactoryClassWorkAsExpectedForNullVariable() {
        final String nullString = null;
        final DefaultAccompanyingPrice accompanyingPrice = defaultAccompanyingPrice(nullString);
        assertNull(accompanyingPrice);
    }

    @Test
    void shouldCreateViaFactoryClassWorkAsExpectedNullValueInArray() {
        final DefaultAccompanyingPrice accompanyingPrice = defaultAccompanyingPrice(ArrayUtils.EMPTY_STRING_ARRAY);
        assertNull(accompanyingPrice);
    }

    @Test
    void shouldRecognizeApplicability() {
        assertTrue(new DefaultAccompanyingPrice(new String[0]).isApplicable());
        assertTrue(defaultAccompanyingPrice("A").isApplicable());
        assertTrue(defaultAccompanyingPrice("A", "B").isApplicable());
    }

    @Test
    void shouldToStringReturnExpectedFormat() {
        final DefaultAccompanyingPrice accompanyingPrice = defaultAccompanyingPrice("basic", "reference");
        assertEquals("defaultAccompanyingPrice('basic','reference')", accompanyingPrice.toString());
    }

    @Test
    void shouldConformToEqualsAndHashContract() {
        assertNotSame(defaultAccompanyingPrice("basic", "reference"), defaultAccompanyingPrice("basic", "reference"));
        assertEquals(defaultAccompanyingPrice("basic", "reference"), defaultAccompanyingPrice("basic", "reference"));
        assertNotEquals(defaultAccompanyingPrice("basic", "reference"), defaultAccompanyingPrice("basic", "action"));
        assertNotEquals(defaultAccompanyingPrice("basic", "reference"), defaultAccompanyingPrice("basic"));
        assertEquals(defaultAccompanyingPrice("basic", "reference").hashCode(), defaultAccompanyingPrice("basic", "reference").hashCode());
        assertNotEquals(defaultAccompanyingPrice("basic", "reference").hashCode(), defaultAccompanyingPrice("basic", "action").hashCode());
        assertNotEquals(defaultAccompanyingPrice("basic", "reference").hashCode(), defaultAccompanyingPrice("basic").hashCode());
    }

}
