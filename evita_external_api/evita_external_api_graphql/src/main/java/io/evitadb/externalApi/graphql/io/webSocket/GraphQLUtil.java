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

package io.evitadb.externalApi.graphql.io.webSocket;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

import java.util.List;

/**
 * Utility for handling the GraphQL protocol.
 */
public final class GraphQLUtil {

    /**
     * Returns the negotiated {@link MediaType}. {@link MediaType#JSON} and {@link MediaType#GRAPHQL_JSON}
     * are commonly used for the Content-Type of a GraphQL response.
     * {@link MediaType#GRAPHQL_JSON} is used by default when {@link HttpHeaderNames#ACCEPT} is not specified or
     * the {@link RequestHeaders} contains {@link MediaType#MULTIPART_FORM_DATA}.
     *
     * <p>Note that the negotiated {@link MediaType} could not be used by the implementation of
     * AbstractGraphqlService which may choose to respond in one of several ways
     * specified the
     * <a href="https://github.com/graphql/graphql-over-http/blob/main/spec/GraphQLOverHTTP.md#body">
     * specification</a>.
     */
    @Nullable
    public static MediaType produceType(RequestHeaders headers) {
        final List<MediaType> acceptTypes = headers.accept();
        // Check the accept header first.
        if (!acceptTypes.isEmpty()) {
            for (MediaType accept : acceptTypes) {
                if (MediaType.ANY_TYPE.is(accept) || MediaType.ANY_APPLICATION_TYPE.is(accept)) {
                    // This will be changed to return MediaType.GRAPHQL_RESPONSE_JSON after 2025.
                    // https://github.com/graphql/graphql-over-http/blob/main/spec/GraphQLOverHTTP.md#legacy-watershed-1
                    return MediaType.JSON;
                }
                if (accept.is(MediaType.GRAPHQL_RESPONSE_JSON) ||
                    accept.is(MediaType.GRAPHQL_JSON) ||
                    accept.is(MediaType.JSON)) {
                    return accept;
                }
            }

            // When the accept header is invalid, we have 2 options:
            // - Disregard the Accept header and respond with the default media type.
            // - Respond with a 406 Not Acceptable.
            // https://github.com/graphql/graphql-over-http/blob/main/spec/GraphQLOverHTTP.md#body
            // We just return null to send 406 response, but we might revisit later to use different option.
            return null;
        }

        // This will be changed to return MediaType.GRAPHQL_RESPONSE_JSON after 2025.
        // https://github.com/graphql/graphql-over-http/blob/main/spec/GraphQLOverHTTP.md#legacy-watershed-1
        return MediaType.JSON;
    }

    private GraphQLUtil() {}
}
