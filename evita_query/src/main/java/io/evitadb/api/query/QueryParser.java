/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.api.query;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Transforms string representation of EvitaQL queries to actual objects.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
public interface QueryParser {

    /**
     * Creates {@link Query} corresponding to string representation in {@code query}. All positional parameters
     * will be replaced with {@code positionalArguments}.
     *
     * @param query string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link Query}
     */
    @Nonnull
    Query parseQuery(@Nonnull String query, @Nonnull Object... positionalArguments);

    /**
     * Creates {@link Query} corresponding to string representation in {@code query}. All positional parameters
     * will be replaced with {@code positionalArguments}.
     *
     * @param query string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link Query}
     */
    @Nonnull
    Query parseQuery(@Nonnull String query, @Nonnull List<Object> positionalArguments);

    /**
     * Creates {@link Query} corresponding to string representation in {@code query}. All named parameters
     * will be replaced with {@code namedArguments}.
     *
     * @param query string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @return parsed {@link Query}
     */
    @Nonnull
    Query parseQuery(@Nonnull String query, @Nonnull Map<String, Object> namedArguments);

    /**
     * Creates {@link Query} corresponding to string representation in {@code query}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param query string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link Query}
     */
    @Nonnull
    Query parseQuery(@Nonnull String query, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments);

    /**
     * Creates {@link Query} corresponding to string representation in {@code query}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param query string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link Query}
     */
    @Nonnull
    Query parseQuery(@Nonnull String query, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments);



    /**
     * Creates {@link HeadConstraint} list corresponding to string representation in {@code headConstraintList}. All parameters will be replaced
     * with {@code positionalArguments}.
     *
     * @param headConstraintList string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link HeadConstraint} list
     */
    @Nonnull
    List<HeadConstraint> parseHeadConstraintList(@Nonnull String headConstraintList, @Nonnull Object... positionalArguments);

    /**
     * Creates {@link HeadConstraint} list corresponding to string representation in {@code headConstraintList}. All parameters will be replaced
     * with {@code positionalArguments}.
     *
     * @param headConstraintList string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link HeadConstraint} list
     */
    @Nonnull
    List<HeadConstraint> parseHeadConstraintList(@Nonnull String headConstraintList, @Nonnull List<Object> positionalArguments);

    /**
     * Creates {@link HeadConstraint} list corresponding to string representation in {@code headConstraintList}. All parameters will be replaced
     * with {@code namedArguments}.
     *
     * @param headConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @return parsed {@link HeadConstraint} list
     */
    @Nonnull
    List<HeadConstraint> parseHeadConstraintList(@Nonnull String headConstraintList, @Nonnull Map<String, Object> namedArguments);

    /**
     * Creates {@link HeadConstraint} list corresponding to string representation in {@code headConstraintList}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param headConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link HeadConstraint} list
     */
    @Nonnull
    List<HeadConstraint> parseHeadConstraintList(@Nonnull String headConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments);

    /**
     * Creates {@link HeadConstraint} list corresponding to string representation in {@code headConstraintList}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param headConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link HeadConstraint} list
     */
    @Nonnull
    List<HeadConstraint> parseHeadConstraintList(@Nonnull String headConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments);



    /**
     * Creates {@link FilterConstraint} list corresponding to string representation in {@code filterConstraintList}. All parameters will be replaced
     * with {@code positionalArguments}.
     *
     * @param filterConstraintList string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link FilterConstraint} list
     */
    @Nonnull
    List<FilterConstraint> parseFilterConstraintList(@Nonnull String filterConstraintList, @Nonnull Object... positionalArguments);

    /**
     * Creates {@link FilterConstraint} list corresponding to string representation in {@code filterConstraintList}. All parameters will be replaced
     * with {@code positionalArguments}.
     *
     * @param filterConstraintList string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link FilterConstraint} list
     */
    @Nonnull
    List<FilterConstraint> parseFilterConstraintList(@Nonnull String filterConstraintList, @Nonnull List<Object> positionalArguments);

    /**
     * Creates {@link FilterConstraint} list corresponding to string representation in {@code filterConstraintList}. All parameters will be replaced
     * with {@code namedArguments}.
     *
     * @param filterConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @return parsed {@link FilterConstraint} list
     */
    @Nonnull
    List<FilterConstraint> parseFilterConstraintList(@Nonnull String filterConstraintList, @Nonnull Map<String, Object> namedArguments);

    /**
     * Creates {@link FilterConstraint} list corresponding to string representation in {@code filterConstraintList}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param filterConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link FilterConstraint} list
     */
    @Nonnull
    List<FilterConstraint> parseFilterConstraintList(@Nonnull String filterConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments);

    /**
     * Creates {@link FilterConstraint} list corresponding to string representation in {@code filterConstraintList}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param filterConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link FilterConstraint} list
     */
    @Nonnull
    List<FilterConstraint> parseFilterConstraintList(@Nonnull String filterConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments);



    /**
     * Creates {@link OrderConstraint} list corresponding to string representation in {@code orderConstraintList}. All parameters will be replaced
     * with {@code positionalArguments}.
     *
     * @param orderConstraintList string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link OrderConstraint} list
     */
    @Nonnull
    List<OrderConstraint> parseOrderConstraintList(@Nonnull String orderConstraintList, @Nonnull Object... positionalArguments);

    /**
     * Creates {@link OrderConstraint} list corresponding to string representation in {@code orderConstraintList}. All parameters will be replaced
     * with {@code positionalArguments}.
     *
     * @param orderConstraintList string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link OrderConstraint} list
     */
    @Nonnull
    List<OrderConstraint> parseOrderConstraintList(@Nonnull String orderConstraintList, @Nonnull List<Object> positionalArguments);

    /**
     * Creates {@link OrderConstraint} list corresponding to string representation in {@code orderConstraintList}. All parameters will be replaced
     * with {@code namedArguments}.
     *
     * @param orderConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @return parsed {@link OrderConstraint} list
     */
    @Nonnull
    List<OrderConstraint> parseOrderConstraintList(@Nonnull String orderConstraintList, @Nonnull Map<String, Object> namedArguments);

    /**
     * Creates {@link OrderConstraint} list corresponding to string representation in {@code orderConstraintList}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param orderConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link OrderConstraint} list
     */
    @Nonnull
    List<OrderConstraint> parseOrderConstraintList(@Nonnull String orderConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments);

    /**
     * Creates {@link OrderConstraint} list corresponding to string representation in {@code orderConstraintList}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param orderConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link OrderConstraint} list
     */
    @Nonnull
    List<OrderConstraint> parseOrderConstraintList(@Nonnull String orderConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments);



    /**
     * Creates {@link RequireConstraint} list corresponding to string representation in {@code requireConstraintList}. All parameters will be replaced
     * with {@code positionalArguments}.
     *
     * @param requireConstraintList string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link RequireConstraint} list
     */
    @Nonnull
    List<RequireConstraint> parseRequireConstraintList(@Nonnull String requireConstraintList, @Nonnull Object... positionalArguments);

    /**
     * Creates {@link RequireConstraint} list corresponding to string representation in {@code requireConstraintList}. All parameters will be replaced
     * with {@code positionalArguments}.
     *
     * @param requireConstraintList string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link RequireConstraint} list
     */
    @Nonnull
    List<RequireConstraint> parseRequireConstraintList(@Nonnull String requireConstraintList, @Nonnull List<Object> positionalArguments);

    /**
     * Creates {@link RequireConstraint} list corresponding to string representation in {@code requireConstraintList}. All parameters will be replaced
     * with {@code namedArguments}.
     *
     * @param requireConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @return parsed {@link RequireConstraint} list
     */
    @Nonnull
    List<RequireConstraint> parseRequireConstraintList(@Nonnull String requireConstraintList, @Nonnull Map<String, Object> namedArguments);

    /**
     * Creates {@link RequireConstraint} list corresponding to string representation in {@code requireConstraintList}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param requireConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link RequireConstraint} list
     */
    @Nonnull
    List<RequireConstraint> parseRequireConstraintList(@Nonnull String requireConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments);

    /**
     * Creates {@link RequireConstraint} list corresponding to string representation in {@code requireConstraintList}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param requireConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link RequireConstraint} list
     */
    @Nonnull
    List<RequireConstraint> parseRequireConstraintList(@Nonnull String requireConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments);



    /**
     * Creates actual classifier for any supported literal or parameter in string representation in {@code classifier}.
     * <p>
     * <b>Note: </b> that unlike `parseQuery` and `parseConstraint` methods, `parseClassifier` runs in {@link io.evitadb.api.query.parser.ParseMode#UNSAFE}
     * because in this case for safe mode you don't need parser altogether.
     *
     * @param classifier string representation of classifier
     * @return parsed classifier
     */
    @Nonnull
    String parseClassifier(@Nonnull String classifier);

    /**
     * Creates actual classifier for any supported literal or parameter in string representation in {@code classifier}. All parameters will be replaced
     * with {@code positionalArguments}.
     * <p>
     * <b>Note: </b> that unlike `parseQuery` and `parseConstraint` methods, `parseClassifier` runs in {@link io.evitadb.api.query.parser.ParseMode#UNSAFE}
     * because in this case for safe mode you don't need parser altogether.
     *
     * @param classifier string representation of classifier
     * @param positionalArgument positional arguments for passed query
     * @return parsed classifier
     */
    @Nonnull
    String parseClassifier(@Nonnull String classifier, @Nonnull Object positionalArgument);

    /**
     * Creates actual classifier for any supported literal or parameter in string representation in {@code classifier}. All parameters will be replaced
     * with {@code namedArguments}.
     * <p>
     * <b>Note: </b> that unlike `parseQuery` and `parseConstraint` methods, `parseClassifier` runs in {@link io.evitadb.api.query.parser.ParseMode#UNSAFE}
     * because in this case for safe mode you don't need parser altogether.
     *
     * @param classifier string representation of classifier
     * @param namedArguments named arguments for passed query
     * @return parsed classifier
     */
    @Nonnull
    String parseClassifier(@Nonnull String classifier, @Nonnull Map<String, Object> namedArguments);

    /**
     * Creates actual classifier for any supported literal or parameter in string representation in {@code classifier}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     * <p>
     * <b>Note: </b> that unlike `parseQuery` and `parseConstraint` methods, `parseClassifier` runs in {@link io.evitadb.api.query.parser.ParseMode#UNSAFE}
     * because in this case for safe mode you don't need parser altogether.
     *
     * @param classifier string representation of classifier
     * @param namedArguments named arguments for passed query
     * @param positionalArgument positional arguments for passed query
     * @return parsed classifier
     */
    @Nonnull
    String parseClassifier(@Nonnull String classifier, @Nonnull Map<String, Object> namedArguments, @Nonnull Object positionalArgument);



    /**
     * Creates actual value for any supported literal or parameter in string representation in {@code value}.
     * <p>
     * <b>Note: </b> that unlike `parseQuery` and `parseConstraint` methods, `parseValue` runs in {@link io.evitadb.api.query.parser.ParseMode#UNSAFE}
     * because in this case for safe mode you don't need parser altogether.
     *
     * @param value string representation of value
     * @param <T> parsed value type
     * @return parsed value
     */
    @Nonnull
    <T extends Serializable> T parseValue(@Nonnull String value);

    /**
     * Creates actual value for any supported literal or parameter in string representation in {@code value}. All parameters will be replaced
     * with {@code positionalArguments}.
     * <p>
     * <b>Note: </b> that unlike `parseQuery` and `parseConstraint` methods, `parseValue` runs in {@link io.evitadb.api.query.parser.ParseMode#UNSAFE}
     * because in this case for safe mode you don't need parser altogether.
     *
     * @param value string representation of value
     * @param positionalArgument positional argument for passed value
     * @param <T> parsed value type
     * @return parsed value
     */
    @Nonnull
    <T extends Serializable> T parseValue(@Nonnull String value, @Nonnull Object positionalArgument);

    /**
     * Creates actual value for any supported literal or parameter in string representation in {@code value}. All parameters will be replaced
     * with {@code namedArguments}.
     * <p>
     * <b>Note: </b> that unlike `parseQuery` and `parseConstraint` methods, `parseValue` runs in {@link io.evitadb.api.query.parser.ParseMode#UNSAFE}
     * because in this case for safe mode you don't need parser altogether.
     *
     * @param value string representation of value
     * @param namedArguments named arguments for passed query
     * @param <T> parsed value type
     * @return parsed value
     */
    @Nonnull
    <T extends Serializable> T parseValue(@Nonnull String value, @Nonnull Map<String, Object> namedArguments);

    /**
     * Creates actual value for any supported literal or parameter in string representation in {@code value}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     * <p>
     * <b>Note: </b> that unlike `parseQuery` and `parseConstraint` methods, `parseValue` runs in {@link io.evitadb.api.query.parser.ParseMode#UNSAFE}
     * because in this case for safe mode you don't need parser altogether.
     *
     * @param value string representation of value
     * @param namedArguments named arguments for passed query
     * @param positionalArgument positional argument for passed query
     * @param <T> parsed value type
     * @return parsed value
     */
    @Nonnull
    <T extends Serializable> T parseValue(@Nonnull String value, @Nonnull Map<String, Object> namedArguments, @Nonnull Object positionalArgument);



    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using e.g. {@link #parseQuery(String, Map)}} instead.
     * <p>
     * Creates {@link Query} corresponding to string representation in {@code query}.
     *
     * @param query string representation of query in specific format
     * @return parsed {@link Query}
     */
    @Nonnull
    Query parseQueryUnsafe(@Nonnull String query);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseQuery(String, Object...)} instead.
     * <p>
     * Creates {@link Query} corresponding to string representation in {@code query}. All positional parameters
     * will be replaced with {@code positionalArguments}.
     *
     * @param query string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link Query}
     */
    @Nonnull
    Query parseQueryUnsafe(@Nonnull String query, @Nonnull Object... positionalArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseQuery(String, Object...)} instead.
     * <p>
     * Creates {@link Query} corresponding to string representation in {@code query}. All positional parameters
     * will be replaced with {@code positionalArguments}.
     *
     * @param query string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link Query}
     */
    @Nonnull
    Query parseQueryUnsafe(@Nonnull String query, @Nonnull List<Object> positionalArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseQuery(String, Map)} instead.
     * <p>
     * Creates {@link Query} corresponding to string representation in {@code query}. All named parameters
     * will be replaced with {@code namedArguments}.
     *
     * @param query string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @return parsed {@link Query}
     */
    @Nonnull
    Query parseQueryUnsafe(@Nonnull String query, @Nonnull Map<String, Object> namedArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseQuery(String, Map, Object...)} instead.
     * <p>
     * Creates {@link Query} corresponding to string representation in {@code query}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param query string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link Query}
     */
    @Nonnull
    Query parseQueryUnsafe(@Nonnull String query, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseQuery(String, Map, Object...)} instead.
     * <p>
     * Creates {@link Query} corresponding to string representation in {@code query}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param query string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link Query}
     */
    @Nonnull
    Query parseQueryUnsafe(@Nonnull String query, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments);



    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseHeadConstraintList(String, Object...)} instead.
     * <p>
     * Creates {@link HeadConstraint} list corresponding to string representation in {@code headConstraintList}. All parameters will be replaced
     * with {@code positionalArguments}.
     *
     * @param headConstraintList string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link HeadConstraint} list
     */
    @Nonnull
    List<HeadConstraint> parseHeadConstraintListUnsafe(@Nonnull String headConstraintList, @Nonnull Object... positionalArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseHeadConstraintList(String, List)} instead.
     * <p>
     * Creates {@link HeadConstraint} list corresponding to string representation in {@code headConstraintList}. All parameters will be replaced
     * with {@code positionalArguments}.
     *
     * @param headConstraintList string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link HeadConstraint} list
     */
    @Nonnull
    List<HeadConstraint> parseHeadConstraintListUnsafe(@Nonnull String headConstraintList, @Nonnull List<Object> positionalArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseHeadConstraintList(String, Map)} instead.
     * <p>
     * Creates {@link HeadConstraint} list corresponding to string representation in {@code headConstraintList}. All parameters will be replaced
     * with {@code namedArguments}.
     *
     * @param headConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @return parsed {@link HeadConstraint} list
     */
    @Nonnull
    List<HeadConstraint> parseHeadConstraintListUnsafe(@Nonnull String headConstraintList, @Nonnull Map<String, Object> namedArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseHeadConstraintList(String, Map, Object...)} instead.
     * <p>
     * Creates {@link HeadConstraint} list corresponding to string representation in {@code headConstraintList}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param headConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link HeadConstraint} list
     */
    @Nonnull
    List<HeadConstraint> parseHeadConstraintListUnsafe(@Nonnull String headConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseHeadConstraintList(String, Map, List)} instead.
     * <p>
     * Creates {@link HeadConstraint} list corresponding to string representation in {@code headConstraintList}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param headConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link HeadConstraint} list
     */
    @Nonnull
    List<HeadConstraint> parseHeadConstraintListUnsafe(@Nonnull String headConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments);



    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseFilterConstraintList(String, Object...)} instead.
     * <p>
     * Creates {@link FilterConstraint} list corresponding to string representation in {@code filterConstraintList}. All parameters will be replaced
     * with {@code positionalArguments}.
     *
     * @param filterConstraintList string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link FilterConstraint} list
     */
    @Nonnull
    List<FilterConstraint> parseFilterConstraintListUnsafe(@Nonnull String filterConstraintList, @Nonnull Object... positionalArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseFilterConstraintList(String, List)} instead.
     * <p>
     * Creates {@link FilterConstraint} list corresponding to string representation in {@code filterConstraintList}. All parameters will be replaced
     * with {@code positionalArguments}.
     *
     * @param filterConstraintList string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link FilterConstraint} list
     */
    @Nonnull
    List<FilterConstraint> parseFilterConstraintListUnsafe(@Nonnull String filterConstraintList, @Nonnull List<Object> positionalArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseFilterConstraintList(String, Map)} instead.
     * <p>
     * Creates {@link FilterConstraint} list corresponding to string representation in {@code filterConstraintList}. All parameters will be replaced
     * with {@code namedArguments}.
     *
     * @param filterConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @return parsed {@link FilterConstraint} list
     */
    @Nonnull
    List<FilterConstraint> parseFilterConstraintListUnsafe(@Nonnull String filterConstraintList, @Nonnull Map<String, Object> namedArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseFilterConstraintList(String, Map, Object...)} instead.
     * <p>
     * Creates {@link FilterConstraint} list corresponding to string representation in {@code filterConstraintList}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param filterConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link FilterConstraint} list
     */
    @Nonnull
    List<FilterConstraint> parseFilterConstraintListUnsafe(@Nonnull String filterConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseFilterConstraintList(String, Map, List)} instead.
     * <p>
     * Creates {@link FilterConstraint} list corresponding to string representation in {@code filterConstraintList}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param filterConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link FilterConstraint} list
     */
    @Nonnull
    List<FilterConstraint> parseFilterConstraintListUnsafe(@Nonnull String filterConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments);



    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseOrderConstraintList(String, Object...)} instead.
     * <p>
     * Creates {@link OrderConstraint} list corresponding to string representation in {@code orderConstraintList}. All parameters will be replaced
     * with {@code positionalArguments}.
     *
     * @param orderConstraintList string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link OrderConstraint} list
     */
    @Nonnull
    List<OrderConstraint> parseOrderConstraintListUnsafe(@Nonnull String orderConstraintList, @Nonnull Object... positionalArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseOrderConstraintList(String, List)} instead.
     * <p>
     * Creates {@link OrderConstraint} list corresponding to string representation in {@code orderConstraintList}. All parameters will be replaced
     * with {@code positionalArguments}.
     *
     * @param orderConstraintList string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link OrderConstraint} list
     */
    @Nonnull
    List<OrderConstraint> parseOrderConstraintListUnsafe(@Nonnull String orderConstraintList, @Nonnull List<Object> positionalArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseOrderConstraintList(String, Map)} instead.
     * <p>
     * Creates {@link OrderConstraint} list corresponding to string representation in {@code orderConstraintList}. All parameters will be replaced
     * with {@code namedArguments}.
     *
     * @param orderConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @return parsed {@link OrderConstraint} list
     */
    @Nonnull
    List<OrderConstraint> parseOrderConstraintListUnsafe(@Nonnull String orderConstraintList, @Nonnull Map<String, Object> namedArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseOrderConstraintList(String, Map, Object...)} instead.
     * <p>
     * Creates {@link OrderConstraint} list corresponding to string representation in {@code orderConstraintList}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param orderConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link OrderConstraint} list
     */
    @Nonnull
    List<OrderConstraint> parseOrderConstraintListUnsafe(@Nonnull String orderConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseOrderConstraintList(String, Map, List)} instead.
     * <p>
     * Creates {@link OrderConstraint} list corresponding to string representation in {@code orderConstraintList}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param orderConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link OrderConstraint} list
     */
    @Nonnull
    List<OrderConstraint> parseOrderConstraintListUnsafe(@Nonnull String orderConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments);



    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseRequireConstraintList(String, Object...)} instead.
     * <p>
     * Creates {@link RequireConstraint} list corresponding to string representation in {@code requireConstraintList}. All parameters will be replaced
     * with {@code positionalArguments}.
     *
     * @param requireConstraintList string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link RequireConstraint} list
     */
    @Nonnull
    List<RequireConstraint> parseRequireConstraintListUnsafe(@Nonnull String requireConstraintList, @Nonnull Object... positionalArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseRequireConstraintList(String, List)} instead.
     * <p>
     * Creates {@link RequireConstraint} list corresponding to string representation in {@code requireConstraintList}. All parameters will be replaced
     * with {@code positionalArguments}.
     *
     * @param requireConstraintList string representation of query in specific format
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link RequireConstraint} list
     */
    @Nonnull
    List<RequireConstraint> parseRequireConstraintListUnsafe(@Nonnull String requireConstraintList, @Nonnull List<Object> positionalArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseRequireConstraintList(String, Map)} instead.
     * <p>
     * Creates {@link RequireConstraint} list corresponding to string representation in {@code requireConstraintList}. All parameters will be replaced
     * with {@code namedArguments}.
     *
     * @param requireConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @return parsed {@link RequireConstraint} list
     */
    @Nonnull
    List<RequireConstraint> parseRequireConstraintListUnsafe(@Nonnull String requireConstraintList, @Nonnull Map<String, Object> namedArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseRequireConstraintList(String, Map, Object...)} instead.
     * <p>
     * Creates {@link RequireConstraint} list corresponding to string representation in {@code requireConstraintList}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param requireConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link RequireConstraint} list
     */
    @Nonnull
    List<RequireConstraint> parseRequireConstraintListUnsafe(@Nonnull String requireConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments);

    /**
     * <b>WARNING: </b> this method is unsafe to use as it allows passing value literals. This method should be used ONLY
     * in prototypes or tests. Consider using {@link #parseRequireConstraintList(String, Map, List)} instead.
     * <p>
     * Creates {@link RequireConstraint} list corresponding to string representation in {@code requireConstraintList}. All parameters will be replaced
     * either by {@code namedArguments} or {@code positionalArguments}.
     *
     * @param requireConstraintList string representation of query in specific format
     * @param namedArguments named arguments for passed query
     * @param positionalArguments positional arguments for passed query
     * @return parsed {@link RequireConstraint} list
     */
    @Nonnull
    List<RequireConstraint> parseRequireConstraintListUnsafe(@Nonnull String requireConstraintList, @Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments);
}
