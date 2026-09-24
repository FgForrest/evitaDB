/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.grpc.requestResponse;

import io.evitadb.api.statistics.StoragePartGroup;
import io.evitadb.api.statistics.StoragePartKind;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.generated.GrpcStoragePartGroup;
import io.evitadb.externalApi.grpc.generated.GrpcStoragePartKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the storage-part classification mappings of {@link EvitaEnumConverter} - the wire form of
 * {@link StoragePartGroup} and {@link StoragePartKind} a client renders a storage composition table from.
 *
 * The statistics converter tests exercise these indirectly, but only for whichever groups their fixtures happen to
 * carry; a wrong arm for any of the remaining values would travel the wire unnoticed. This test reaches the arms those
 * cannot: every group in both directions, every kind, and the defensive throw that decides what a client does when a
 * newer server classifies a part into a group the client has never heard of.
 *
 * The expected mappings are written out in full rather than derived. A derived expectation would restate the
 * converter's own switch and could never disagree with it - and a *symmetric* mistake, the two arms of one group
 * swapped in both directions at once, is exactly what a bare round-trip lets through.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("EvitaEnumConverter storage part mappings")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
class EvitaEnumConverterStoragePartTest {

	/**
	 * The wire constant each storage-part group is published as. Written out per value so that changing one is a
	 * visible edit here rather than a silent change in what an operator's composition chart means.
	 */
	private static final Map<StoragePartGroup, GrpcStoragePartGroup> EXPECTED_GROUPS =
		new EnumMap<>(
			Map.ofEntries(
				Map.entry(StoragePartGroup.ENTITY_BODY, GrpcStoragePartGroup.STORAGE_PART_GROUP_ENTITY_BODY),
				Map.entry(StoragePartGroup.ATTRIBUTE_DATA, GrpcStoragePartGroup.STORAGE_PART_GROUP_ATTRIBUTE_DATA),
				Map.entry(StoragePartGroup.ASSOCIATED_DATA, GrpcStoragePartGroup.STORAGE_PART_GROUP_ASSOCIATED_DATA),
				Map.entry(StoragePartGroup.PRICE_DATA, GrpcStoragePartGroup.STORAGE_PART_GROUP_PRICE_DATA),
				Map.entry(StoragePartGroup.REFERENCE_DATA, GrpcStoragePartGroup.STORAGE_PART_GROUP_REFERENCE_DATA),
				Map.entry(StoragePartGroup.INDEX_MANIFEST, GrpcStoragePartGroup.STORAGE_PART_GROUP_INDEX_MANIFEST),
				Map.entry(StoragePartGroup.ATTRIBUTE_INDEX, GrpcStoragePartGroup.STORAGE_PART_GROUP_ATTRIBUTE_INDEX),
				Map.entry(StoragePartGroup.PRICE_INDEX, GrpcStoragePartGroup.STORAGE_PART_GROUP_PRICE_INDEX),
				Map.entry(StoragePartGroup.REFERENCE_INDEX, GrpcStoragePartGroup.STORAGE_PART_GROUP_REFERENCE_INDEX),
				Map.entry(StoragePartGroup.FACET_INDEX, GrpcStoragePartGroup.STORAGE_PART_GROUP_FACET_INDEX),
				Map.entry(StoragePartGroup.HIERARCHY_INDEX, GrpcStoragePartGroup.STORAGE_PART_GROUP_HIERARCHY_INDEX),
				Map.entry(
					StoragePartGroup.REFERENCE_HISTOGRAM_INDEX,
					GrpcStoragePartGroup.STORAGE_PART_GROUP_REFERENCE_HISTOGRAM_INDEX
				),
				Map.entry(StoragePartGroup.SCHEMA, GrpcStoragePartGroup.STORAGE_PART_GROUP_SCHEMA),
				Map.entry(StoragePartGroup.HEADER, GrpcStoragePartGroup.STORAGE_PART_GROUP_HEADER)
			)
		);

	/**
	 * The wire constant each storage-part kind is published as. There is deliberately no inverse converter - the kind
	 * is derived from the decoded group - so this table is used in one direction only.
	 */
	private static final Map<StoragePartKind, GrpcStoragePartKind> EXPECTED_KINDS =
		new EnumMap<>(
			Map.ofEntries(
				Map.entry(StoragePartKind.ENTITY_DATA, GrpcStoragePartKind.STORAGE_PART_KIND_ENTITY_DATA),
				Map.entry(StoragePartKind.INDEX, GrpcStoragePartKind.STORAGE_PART_KIND_INDEX),
				Map.entry(StoragePartKind.METADATA, GrpcStoragePartKind.STORAGE_PART_KIND_METADATA)
			)
		);

	@ParameterizedTest
	@EnumSource(StoragePartGroup.class)
	@DisplayName("map every group to its wire constant and read the same constant back as that group")
	void shouldMapEveryStoragePartGroupToItsWireConstantAndBack(@Nonnull StoragePartGroup group) {
		final GrpcStoragePartGroup expected = EXPECTED_GROUPS.get(group);
		assertEquals(
			expected, EvitaEnumConverter.toGrpcStoragePartGroup(group),
			"The group is sent as a constant other than the one it is published under"
		);
		// asserted against the same pinned constant rather than against whatever the encoder produced, so two arms
		// swapped in *both* switches - which a round-trip through the converter cannot see - fails here
		assertEquals(
			group, EvitaEnumConverter.toStoragePartGroup(expected),
			"The published constant decodes to a group other than the one it names"
		);
	}

	@Test
	@DisplayName("name every wire constant after the group or kind it carries")
	void shouldNameEveryWireConstantAfterItsGroup() {
		// the guard on the table above rather than on the converter: two groups pinned to each other's constant would
		// agree with an equally-swapped converter and both tests would pass. The proto names each constant after its
		// Java value, so the name is an independent statement of what the constant means
		assertEquals(
			EnumSet.allOf(StoragePartGroup.class), EXPECTED_GROUPS.keySet(),
			"A group was added or removed without visiting the mapping this test pins"
		);
		assertEquals(
			EnumSet.allOf(StoragePartKind.class), EXPECTED_KINDS.keySet(),
			"A kind was added or removed without visiting the mapping this test pins"
		);
		for (final Map.Entry<StoragePartGroup, GrpcStoragePartGroup> entry : EXPECTED_GROUPS.entrySet()) {
			assertEquals(
				"STORAGE_PART_GROUP_" + entry.getKey().name(), entry.getValue().name(),
				"The wire constant of " + entry.getKey() + " does not name it"
			);
		}
		for (final Map.Entry<StoragePartKind, GrpcStoragePartKind> entry : EXPECTED_KINDS.entrySet()) {
			assertEquals(
				"STORAGE_PART_KIND_" + entry.getKey().name(), entry.getValue().name(),
				"The wire constant of " + entry.getKey() + " does not name it"
			);
		}
	}

	@Test
	@DisplayName("refuse a wire storage-part group this client cannot name")
	void shouldRefuseAWireStoragePartGroupItCannotName() {
		// the published forward-compatibility contract: a server newer than this client can classify a part into a
		// group the client was never taught, and the decision is that the whole statistics call fails rather than one
		// row silently landing in a plausible-looking bucket
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> EvitaEnumConverter.toStoragePartGroup(GrpcStoragePartGroup.STORAGE_PART_GROUP_UNSPECIFIED),
			"An unset group must be refused rather than defaulted"
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> EvitaEnumConverter.toStoragePartGroup(GrpcStoragePartGroup.UNRECOGNIZED),
			"A group only a newer server knows must be refused rather than defaulted"
		);
	}

	@ParameterizedTest
	@EnumSource(StoragePartKind.class)
	@DisplayName("map every kind to its wire constant")
	void shouldMapEveryStoragePartKindToItsWireConstant(@Nonnull StoragePartKind kind) {
		assertEquals(
			EXPECTED_KINDS.get(kind), EvitaEnumConverter.toGrpcStoragePartKind(kind),
			"The kind is sent as a constant other than the one it is published under"
		);
	}

}
