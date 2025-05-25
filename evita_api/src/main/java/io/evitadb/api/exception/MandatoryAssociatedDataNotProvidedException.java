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

package io.evitadb.api.exception;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.of;

/**
 * Exception is thrown when store entity lacks mandatory associated data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class MandatoryAssociatedDataNotProvidedException extends InvalidMutationException {
	@Serial private static final long serialVersionUID = -8575134582708076162L;

	public MandatoryAssociatedDataNotProvidedException(@Nonnull String entityName, @Nonnull List<AssociatedDataKey> missingMandatedAssociatedData) {
		super(composeErrorMessage(entityName, missingMandatedAssociatedData));
	}

	private static String composeErrorMessage(@Nonnull String entityName, @Nonnull List<AssociatedDataKey> missingMandatedAssociatedData) {
		final String missingGlobalAttributes = of(missingMandatedAssociatedData.stream()
			.sorted()
			.filter(it -> it.locale() == null)
			.map(it -> "`" + it.associatedDataName() + "`")
			.collect(Collectors.joining(", ")))
			.filter(it -> !it.isBlank())
			.map(it ->
				"Entity `" + entityName + "` requires these associated data to be non-null, " +
					"but they are missing: " + it + "."
			)
			.orElse("");

		final String missingLocalizedAttributes = of(
			missingMandatedAssociatedData.stream()
				.filter(it -> it.locale() != null)
				.collect(Collectors.groupingBy(AssociatedDataKey::associatedDataName))
				.entrySet()
				.stream()
				.map(it ->
					"`" + it.getKey() + "` in locales: " +
						it.getValue()
							.stream()
							.map(AssociatedDataKey::locale)
							.filter(Objects::nonNull)
							.map(Locale::toLanguageTag)
							.sorted()
							.map(locale -> "`" + locale + "`")
							.collect(Collectors.joining(", "))
				)
				.collect(Collectors.joining(", "))
		)
			.filter(it -> !it.isBlank())
			.map(it ->
				"Entity `" + entityName + "` requires these localized associated data to be " +
					"specified for all localized versions of the entity, " +
					"but values for some locales are missing: " + it + "."
			)
			.orElse("");

		return Stream.of(
				missingGlobalAttributes,
				missingLocalizedAttributes
			)
			.filter(it -> !it.isBlank())
			.collect(Collectors.joining("\n"));
	}
}
