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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.parser;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import javax.annotation.Nonnull;
import java.lang.reflect.Array;
import java.util.stream.StreamSupport;

/**
 * Represents EvitaQL single classifier or variadic array of classifiers. It is wrapper for parsed classifiers, either literals or arguments.
 * All values must be of type {@link String}. Variadic arrays can be arrays or iterables.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@EqualsAndHashCode(cacheStrategy = EqualsAndHashCode.CacheStrategy.LAZY)
@ToString
public class Classifier {

    /**
     * Concrete value of parsed literal or parameter in target data type.
     */
    @Nonnull
    private final Object actualValue;

    public Classifier(@Nonnull String actualValue) {
        this.actualValue = actualValue;
    }

    public Classifier(@Nonnull String[] actualValue) {
        this.actualValue = actualValue;
    }

    public Classifier(@Nonnull Iterable<String> actualValue) {
        this.actualValue = actualValue;
    }

    @Nonnull
    public String asSingleClassifier() {
        // correct passed type from client should be checked at visitor level, here should be should correct checked type
        // if everything is correct on parser side
        Assert.isPremiseValid(
            actualValue instanceof String,
            "Expected single classifier but got `" + actualValue.getClass().getName() + "`."
        );
        return (String) actualValue;
    }

    @Nonnull
    public String[] asClassifierArray() {
        final Object values = actualValue;
        if (values instanceof Iterable<?> iterableValues) {
            return StreamSupport.stream(iterableValues.spliterator(), false)
                .map(v -> (String) v)
                .toArray(String[]::new);
        } else if (values.getClass().isArray()) {
            final int length = Array.getLength(values);
            if (length == 0) {
                return new String[0];
            }
            return (String[]) values;
        } else {
            // correct passed type from client should be checked at visitor level, here should be should correct checked type
            // if everything is correct on parser side
            throw new GenericEvitaInternalError("Expected variadic string value but got `" + actualValue.getClass().getName() + "`.");
        }
    }
}
