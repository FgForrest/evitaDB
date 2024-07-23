/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.query.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EnumWrapper}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
class EnumWrapperTest {

    @Test
    void shouldParseEnumValueInValidFormat() {
        final EnumWrapper enumWrapper1 = EnumWrapper.fromString("PRODUCT");
        assertEquals("PRODUCT", enumWrapper1.getValue());

        final EnumWrapper enumWrapper2 = EnumWrapper.fromString("WITH_TAX");
        assertEquals("WITH_TAX", enumWrapper2.getValue());

        final EnumWrapper enumWrapper3 = EnumWrapper.fromString("PRODUCT_WITH_ATTRIBUTES");
        assertEquals("PRODUCT_WITH_ATTRIBUTES", enumWrapper3.getValue());
    }

    @Test
    void shouldFailToParseEnumValueInInvalidFormat() {
        assertThrows(IllegalArgumentException.class, () -> EnumWrapper.fromString("product"));
        assertThrows(IllegalArgumentException.class, () -> EnumWrapper.fromString("withTax"));
        assertThrows(IllegalArgumentException.class, () -> EnumWrapper.fromString("WITH-TAX"));
        assertThrows(IllegalArgumentException.class, () -> EnumWrapper.fromString("product-"));
        assertThrows(IllegalArgumentException.class, () -> EnumWrapper.fromString("_product"));
        assertThrows(IllegalArgumentException.class, () -> EnumWrapper.fromString("100"));
    }

    @Test
    void shouldCompareEnumValues() {
        assertTrue(EnumWrapper.fromString("APRODUCT").compareTo(EnumWrapper.fromString("BPRODUCT")) < 0);
        assertTrue(EnumWrapper.fromString("BPRODUCT").compareTo(EnumWrapper.fromString("APRODUCT")) > 0);
        assertEquals(0, EnumWrapper.fromString("PRODUCT").compareTo(EnumWrapper.fromString("PRODUCT")));
    }

    @Test
    void shouldConvertToEnum() {
        final DummyEnum dummyEnum1 = EnumWrapper.fromString("VALUE_B").toEnum(DummyEnum.class);
        assertEquals(DummyEnum.VALUE_B, dummyEnum1);
    }

    @Test
    void shouldFailToConvertToEnum() {
        assertThrows(IllegalArgumentException.class, () -> EnumWrapper.fromString("VALUE_Z").toEnum(DummyEnum.class));
    }


    /**
     * Enum used only for testing wrapper to enum conversion
     */
    enum DummyEnum {
        VALUE_A, VALUE_B, VALUE_C
    }
}
