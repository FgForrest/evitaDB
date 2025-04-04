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

package io.evitadb.externalApi.configuration;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * This DTO record encapsulates header names configuration for HTTP requests.
 *
 * @param forwardedUri  array of Strings defining forwardedUri names
 * @param forwardedFor  array of Strings defining forwardedFor names
 * @param label         Header names for meta labels that allow to set traffic recording labels via HTTP headers.
 * @param clientId      array of Strings defining clientId names
 * @param traceParent   array of Strings defining traceParent names
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record HeaderOptions(
    @Nonnull List<String> forwardedUri,
    @Nonnull List<String> forwardedFor,
    @Nonnull List<String> label,
    @Nonnull List<String> clientId,
    @Nonnull List<String> traceParent
) {

    /**
     * Builder for the header options. Recommended to use to avoid binary compatibility problems in the future.
     */
    public static HeaderOptions.Builder builder() {
        return new HeaderOptions.Builder();
    }

    /**
     * Default constructor that initializes all arrays as empty.
     */
    public HeaderOptions() {
        this(
            List.of("X-Forwarded-Uri"),
            List.of("Forwarded", "X-Forwarded-For", "X-Real-IP"),
            List.of("X-EvitaDB-Label"),
            List.of("X-EvitaDB-ClientID"),
            List.of("traceparent")
        );
    }

    /**
     * Returns a stream of all headers combined from various components such as forwardedFor, forwardedUri,
     * label, clientId, and traceParent.
     *
     * @return a Stream of strings containing the combined headers from all components.
     */
    @Nonnull
    public Stream<String> allHeaders() {
        return Stream.of(
                this.forwardedFor.stream(),
                this.forwardedUri.stream(),
                this.label.stream(),
                this.clientId.stream(),
                this.traceParent.stream()
            )
            .flatMap(UnaryOperator.identity());
    }

    /**
     * Standard builder pattern implementation.
     */
    public static class Builder {
        private List<String> forwardedUri = List.of();
        private List<String> forwardedFor = List.of();
        private List<String> label = List.of();
        private List<String> clientId = List.of();
        private List<String> traceParent = List.of();

        /**
         * Sets the array of forwardedUri names.
         * @param forwardedUri array of Strings defining forwardedUri names
         * @return this builder instance
         */
        @Nonnull
        public Builder forwardedUri(@Nonnull String... forwardedUri) {
            this.forwardedUri = List.of(forwardedUri);
            return this;
        }

        /**
         * Sets the array of forwardedFor names.
         * @param forwardedFor array of Strings defining forwardedFor names
         * @return this builder instance
         */
        @Nonnull
        public Builder forwardedFor(@Nonnull String... forwardedFor) {
            this.forwardedFor = List.of(forwardedFor);
            return this;
        }

        /**
         * Sets the array of label names.
         * @param label array of Strings defining label names
         * @return this builder instance
         */
        @Nonnull
        public Builder label(@Nonnull String... label) {
            this.label = List.of(label);
            return this;
        }

        /**
         * Sets the array of clientId names.
         * @param clientId array of Strings defining clientId names
         * @return this builder instance
         */
        @Nonnull
        public Builder clientId(@Nonnull String... clientId) {
            this.clientId = List.of(clientId);
            return this;
        }

        /**
         * Sets the array of traceParent names.
         * @param traceParent array of Strings defining traceParent names
         * @return this builder instance
         */
        @Nonnull
        public Builder traceParent(@Nonnull String... traceParent) {
            this.traceParent = List.of(traceParent);
            return this;
        }

        /**
         * Builds a new instance of HeaderOptions with the configured values.
         * @return new instance of HeaderOptions
         */
        @Nonnull
        public HeaderOptions build() {
            return new HeaderOptions(
                this.forwardedUri,
                this.forwardedFor,
                this.label,
                this.clientId,
                this.traceParent
            );
        }
    }
}
