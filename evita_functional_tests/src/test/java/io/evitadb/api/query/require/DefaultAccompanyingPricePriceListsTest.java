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

import static io.evitadb.api.query.QueryConstraints.accompanyingPrice;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link DefaultAccompanyingPricePriceLists} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class DefaultAccompanyingPricePriceListsTest {

    @Test
    void shouldCreateViaFactoryClassWorkAsExpected() {
        final DefaultAccompanyingPricePriceLists accompanyingPrice = accompanyingPrice("basic", "reference");
        assertArrayEquals(new String[] {"basic", "reference"}, accompanyingPrice.getPriceLists());
    }

    @Test
    void shouldCreateViaFactoryClassWorkAsExpectedNullInArray() {
        final DefaultAccompanyingPricePriceLists accompanyingPrice = accompanyingPrice("basic", null, "reference");
        assertArrayEquals(new String[] {"basic", "reference"}, accompanyingPrice.getPriceLists());
    }

    @Test
    void shouldCreateViaFactoryClassWorkAsExpectedForNullVariable() {
        final String nullString = null;
        final DefaultAccompanyingPricePriceLists accompanyingPrice = accompanyingPrice(nullString);
        assertNull(accompanyingPrice);
    }

    @Test
    void shouldCreateViaFactoryClassWorkAsExpectedNullValueInArray() {
        final DefaultAccompanyingPricePriceLists accompanyingPrice = accompanyingPrice(ArrayUtils.EMPTY_STRING_ARRAY);
        assertArrayEquals(new String[0], accompanyingPrice.getPriceLists());
    }

    @Test
    void shouldRecognizeApplicability() {
        assertTrue(new DefaultAccompanyingPricePriceLists(new String[0]).isApplicable());
        assertTrue(accompanyingPrice("A").isApplicable());
        assertTrue(accompanyingPrice("A", "B").isApplicable());
    }

    @Test
    void shouldToStringReturnExpectedFormat() {
        final DefaultAccompanyingPricePriceLists accompanyingPrice = accompanyingPrice("basic", "reference");
        assertEquals("defaultAccompanyingPricePriceLists('basic','reference')", accompanyingPrice.toString());
    }

    @Test
    void shouldConformToEqualsAndHashContract() {
        assertNotSame(accompanyingPrice("basic", "reference"), accompanyingPrice("basic", "reference"));
        assertEquals(accompanyingPrice("basic", "reference"), accompanyingPrice("basic", "reference"));
        assertNotEquals(accompanyingPrice("basic", "reference"), accompanyingPrice("basic", "action"));
        assertNotEquals(accompanyingPrice("basic", "reference"), accompanyingPrice("basic"));
        assertEquals(accompanyingPrice("basic", "reference").hashCode(), accompanyingPrice("basic", "reference").hashCode());
        assertNotEquals(accompanyingPrice("basic", "reference").hashCode(), accompanyingPrice("basic", "action").hashCode());
        assertNotEquals(accompanyingPrice("basic", "reference").hashCode(), accompanyingPrice("basic").hashCode());
    }

}
