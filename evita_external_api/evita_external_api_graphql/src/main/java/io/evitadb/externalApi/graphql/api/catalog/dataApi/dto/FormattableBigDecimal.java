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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.dto;

import io.evitadb.dataType.EvitaDataTypes;
import lombok.Value;
import lombok.experimental.NonFinal;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Wrapper for {@link BigDecimal} value to support formatting this value for client.
 * It specifies metadata for target format which is gathered from client query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Value
@NonFinal
public class FormattableBigDecimal {

    @Nonnull BigDecimal value;
    /**
     * If present, value will be formatted for this locale.
     */
    @Nullable Locale formatLocale;

    /**
     * @return whether the original value should be formatted or not (depending on locale and possibly other metadata)
     */
    public boolean isShouldFormat() {
        return this.formatLocale != null;
    }

    @Nonnull
    public String toFormattedString() {
        if (!isShouldFormat()) {
            return EvitaDataTypes.formatValue(getValue());
        }
        return NumberFormat.getNumberInstance(getFormatLocale()).format(getValue());
    }
}
