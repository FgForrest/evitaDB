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

import static io.evitadb.api.query.QueryConstraints.defaultAccompanyingPriceLists;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link DefaultAccompanyingPriceLists} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class DefaultAccompanyingPriceTest {

    @Test
    void shouldCreateViaFactoryClassWorkAsExpected() {
        final DefaultAccompanyingPriceLists accompanyingPrice = defaultAccompanyingPriceLists("basic", "reference");
        assertArrayEquals(new String[] {"basic", "reference"}, accompanyingPrice.getPriceLists());
    }

    @Test
    void shouldCreateViaFactoryClassWorkAsExpectedNullInArray() {
        final DefaultAccompanyingPriceLists accompanyingPrice = defaultAccompanyingPriceLists("basic", null, "reference");
        assertArrayEquals(new String[] {"basic", "reference"}, accompanyingPrice.getPriceLists());
    }

    @Test
    void shouldCreateViaFactoryClassWorkAsExpectedForNullVariable() {
        final String nullString = null;
        final DefaultAccompanyingPriceLists accompanyingPrice = defaultAccompanyingPriceLists(nullString);
        assertNull(accompanyingPrice);
    }

    @Test
    void shouldCreateViaFactoryClassWorkAsExpectedNullValueInArray() {
        final DefaultAccompanyingPriceLists accompanyingPrice = defaultAccompanyingPriceLists(ArrayUtils.EMPTY_STRING_ARRAY);
        assertNull(accompanyingPrice);
    }

    @Test
    void shouldRecognizeApplicability() {
        assertTrue(new DefaultAccompanyingPriceLists(new String[0]).isApplicable());
        assertTrue(defaultAccompanyingPriceLists("A").isApplicable());
        assertTrue(defaultAccompanyingPriceLists("A", "B").isApplicable());
    }

    @Test
    void shouldToStringReturnExpectedFormat() {
        final DefaultAccompanyingPriceLists accompanyingPrice = defaultAccompanyingPriceLists("basic", "reference");
        assertEquals("defaultAccompanyingPriceLists('basic','reference')", accompanyingPrice.toString());
    }

    @Test
    void shouldConformToEqualsAndHashContract() {
        assertNotSame(defaultAccompanyingPriceLists("basic", "reference"), defaultAccompanyingPriceLists("basic", "reference"));
        assertEquals(defaultAccompanyingPriceLists("basic", "reference"), defaultAccompanyingPriceLists("basic", "reference"));
        assertNotEquals(defaultAccompanyingPriceLists("basic", "reference"), defaultAccompanyingPriceLists("basic", "action"));
        assertNotEquals(defaultAccompanyingPriceLists("basic", "reference"), defaultAccompanyingPriceLists("basic"));
        assertEquals(defaultAccompanyingPriceLists("basic", "reference").hashCode(), defaultAccompanyingPriceLists("basic", "reference").hashCode());
        assertNotEquals(defaultAccompanyingPriceLists("basic", "reference").hashCode(), defaultAccompanyingPriceLists("basic", "action").hashCode());
        assertNotEquals(defaultAccompanyingPriceLists("basic", "reference").hashCode(), defaultAccompanyingPriceLists("basic").hashCode());
    }

}
