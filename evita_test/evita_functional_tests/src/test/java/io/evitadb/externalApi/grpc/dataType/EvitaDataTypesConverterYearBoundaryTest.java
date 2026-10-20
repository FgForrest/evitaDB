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

package io.evitadb.externalApi.grpc.dataType;

import com.google.protobuf.Timestamp;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.AttributeLessThan;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaDataType;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaValue;
import io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that temporal values at the edge of the gRPC `Timestamp` range survive the chain a gRPC request takes on
 * the server: {@link EvitaDataTypesConverter} decodes the wire value, and the decoded value is then handed to the
 * constructor of an attribute mutation or a query constraint, both of which normalize it through
 * {@link io.evitadb.dataType.EvitaDataTypes}.
 *
 * Only the first and last second of the `Timestamp` range - what a C# client sends for `DateTime.MinValue` /
 * `MaxValue`, and what the Java converter sends for the JDK sentinels - is decoded as `OffsetDateTime.MIN` / `MAX`
 * (`LocalDateTime.MIN` / `MAX`). The data-type boundary clamps that sentinel to the edge of the window of moments
 * expressible as epoch milliseconds, on the write and the query path alike. Every other moment of year 1 or year 9999
 * is decoded as the moment that was sent. The outbound direction is verified too: whatever moment evitaDB holds
 * leaves as a `Timestamp` a protobuf client in another language can decode, and an edge moment reads back as the
 * sentinel it was written as.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("gRPC temporal values at the year 9999 boundary")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(DATA_TYPE)
class EvitaDataTypesConverterYearBoundaryTest {

	/**
	 * Largest `Timestamp#seconds` value the protobuf specification permits - `9999-12-31T23:59:59Z`.
	 */
	private static final long PROTOBUF_MAX_SECONDS = 253_402_300_799L;
	/**
	 * Smallest `Timestamp#seconds` value the protobuf specification permits - `0001-01-01T00:00:00Z`.
	 */
	private static final long PROTOBUF_MIN_SECONDS = -62_135_596_800L;
	/**
	 * The last whole second of the gRPC `Timestamp` range.
	 */
	private static final OffsetDateTime LAST_GRPC_MOMENT =
		OffsetDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
	/**
	 * What a C# client sends for `DateTime.MaxValue` - the last tick of the `Timestamp` range, 100 ns short of the
	 * next second.
	 */
	private static final OffsetDateTime CSHARP_DATE_TIME_MAX_VALUE =
		OffsetDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_900, ZoneOffset.UTC);
	/**
	 * Half a second after the first moment of the range - an ordinary finite moment, not the lower sentinel.
	 */
	private static final OffsetDateTime HALF_SECOND_AFTER_FIRST_GRPC_MOMENT =
		OffsetDateTime.of(1, 1, 1, 0, 0, 0, 500_000_000, ZoneOffset.UTC);
	/**
	 * The last millisecond before the final second of the range - an ordinary finite moment, not the upper sentinel.
	 */
	private static final OffsetDateTime LAST_MILLISECOND_BEFORE_FINAL_SECOND =
		OffsetDateTime.of(9999, 12, 31, 23, 59, 58, 999_000_000, ZoneOffset.UTC);
	/**
	 * The first moment of the gRPC `Timestamp` range - what a C# client sends for `DateTime.MinValue`.
	 */
	private static final OffsetDateTime FIRST_GRPC_MOMENT = OffsetDateTime.of(1, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	/**
	 * An ordinary moment in year 9999 that is not the end of the range.
	 */
	private static final OffsetDateTime MID_YEAR_9999 = OffsetDateTime.of(9999, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	/**
	 * One second short of the end of the range - the latest moment that is not the sentinel.
	 */
	private static final OffsetDateTime SECOND_TO_LAST_GRPC_MOMENT = LAST_GRPC_MOMENT.minusSeconds(1L);
	/**
	 * Control - the last moment of year 9998, which the converter decodes verbatim.
	 */
	private static final OffsetDateTime LAST_MOMENT_OF_9998 =
		OffsetDateTime.of(9998, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
	/**
	 * The last whole millisecond evitaDB can hold, written out independently of the production constants.
	 */
	private static final Instant LATEST_SUPPORTED_INSTANT =
		Instant.ofEpochMilli((Long.MAX_VALUE / 1_000L - 1L) * 1_000L + 999L);
	/**
	 * The first whole millisecond evitaDB can hold, written out independently of the production constants.
	 */
	private static final Instant EARLIEST_SUPPORTED_INSTANT =
		Instant.ofEpochMilli((Long.MIN_VALUE / 1_000L + 1L) * 1_000L);

	/**
	 * Encodes the moment the way any gRPC client puts it on the wire - as the raw instant, without any substitution.
	 *
	 * @param moment the moment to encode
	 * @return the wire representation
	 */
	@Nonnull
	private static GrpcOffsetDateTime onTheWire(@Nonnull OffsetDateTime moment) {
		final Instant instant = moment.toInstant();
		return GrpcOffsetDateTime.newBuilder()
			.setTimestamp(Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()))
			.setOffset(moment.getOffset().getId())
			.build();
	}

	/**
	 * Decodes a wire moment the way the server decodes an attribute value or a query parameter of the given type.
	 *
	 * @param moment the moment the client sent
	 * @param type   the declared gRPC data type of the value
	 * @return the decoded value
	 */
	@Nonnull
	private static Serializable decodedOnTheServer(@Nonnull OffsetDateTime moment, @Nonnull GrpcEvitaDataType type) {
		return EvitaDataTypesConverter.toEvitaValue(
			GrpcEvitaValue.newBuilder()
				.setType(type)
				.setOffsetDateTimeValue(onTheWire(moment))
				.build()
		);
	}

	/**
	 * Returns the value an attribute upsert of the wire moment ends up carrying on the server.
	 *
	 * @param moment the moment the client sent
	 * @param type   the declared gRPC data type of the value
	 * @return the normalized attribute value
	 */
	@Nonnull
	private static Serializable storedOnTheServer(@Nonnull OffsetDateTime moment, @Nonnull GrpcEvitaDataType type) {
		return new UpsertAttributeMutation("validUntil", decodedOnTheServer(moment, type)).getAttributeValue();
	}

	@Nested
	@DisplayName("Inbound")
	class Inbound {

		@Test
		@DisplayName("should store the last gRPC moment as the latest moment evitaDB can hold")
		void shouldAcceptAttributeUpsertOfLastGrpcMoment() {
			assertEquals(OffsetDateTime.MAX, decodedOnTheServer(LAST_GRPC_MOMENT, GrpcEvitaDataType.OFFSET_DATE_TIME));
			assertEquals(
				OffsetDateTime.ofInstant(LATEST_SUPPORTED_INSTANT, ZoneOffset.MIN),
				storedOnTheServer(LAST_GRPC_MOMENT, GrpcEvitaDataType.OFFSET_DATE_TIME)
			);
		}

		@Test
		@DisplayName("should take C# DateTime.MaxValue, nanoseconds and all, for the upper sentinel")
		void shouldTakeCSharpDateTimeMaxValueForUpperSentinel() {
			assertEquals(
				OffsetDateTime.MAX, decodedOnTheServer(CSHARP_DATE_TIME_MAX_VALUE, GrpcEvitaDataType.OFFSET_DATE_TIME)
			);
			assertEquals(
				LocalDateTime.MAX, decodedOnTheServer(CSHARP_DATE_TIME_MAX_VALUE, GrpcEvitaDataType.LOCAL_DATE_TIME)
			);
		}

		@Test
		@DisplayName("should decode the moments next to the sentinels as the finite moments they are")
		void shouldDecodeMomentsNextToSentinelsAsFinite() {
			assertEquals(
				HALF_SECOND_AFTER_FIRST_GRPC_MOMENT,
				decodedOnTheServer(HALF_SECOND_AFTER_FIRST_GRPC_MOMENT, GrpcEvitaDataType.OFFSET_DATE_TIME)
			);
			assertEquals(
				HALF_SECOND_AFTER_FIRST_GRPC_MOMENT.toLocalDateTime(),
				decodedOnTheServer(HALF_SECOND_AFTER_FIRST_GRPC_MOMENT, GrpcEvitaDataType.LOCAL_DATE_TIME)
			);
			assertEquals(
				LAST_MILLISECOND_BEFORE_FINAL_SECOND,
				decodedOnTheServer(LAST_MILLISECOND_BEFORE_FINAL_SECOND, GrpcEvitaDataType.OFFSET_DATE_TIME)
			);
			assertEquals(
				LAST_MILLISECOND_BEFORE_FINAL_SECOND.toLocalDateTime(),
				decodedOnTheServer(LAST_MILLISECOND_BEFORE_FINAL_SECOND, GrpcEvitaDataType.LOCAL_DATE_TIME)
			);
		}

		@Test
		@DisplayName("should take both sentinels as a C# client sends them")
		void shouldTakeSentinelsAsCSharpClientSendsThem() {
			// `DateTime.MinValue` is exactly the first moment, `DateTime.MaxValue` the last tick of the final second
			assertEquals(OffsetDateTime.MIN, decodedOnTheServer(FIRST_GRPC_MOMENT, GrpcEvitaDataType.OFFSET_DATE_TIME));
			assertEquals(LocalDateTime.MIN, decodedOnTheServer(FIRST_GRPC_MOMENT, GrpcEvitaDataType.LOCAL_DATE_TIME));
			assertEquals(
				OffsetDateTime.MAX, decodedOnTheServer(CSHARP_DATE_TIME_MAX_VALUE, GrpcEvitaDataType.OFFSET_DATE_TIME)
			);
			assertEquals(
				LocalDateTime.MAX, decodedOnTheServer(CSHARP_DATE_TIME_MAX_VALUE, GrpcEvitaDataType.LOCAL_DATE_TIME)
			);
		}

		@Test
		@DisplayName("should read a timestamp beyond the protobuf range as the sentinel on its side")
		void shouldReadTimestampBeyondProtobufRangeAsSentinel() {
			final GrpcOffsetDateTime beyondMax = GrpcOffsetDateTime.newBuilder()
				.setTimestamp(Timestamp.newBuilder().setSeconds(PROTOBUF_MAX_SECONDS + 1L))
				.setOffset("Z")
				.build();
			final GrpcOffsetDateTime beyondMin = GrpcOffsetDateTime.newBuilder()
				.setTimestamp(Timestamp.newBuilder().setSeconds(PROTOBUF_MIN_SECONDS - 1L).setNanos(500_000_000))
				.setOffset("Z")
				.build();
			assertEquals(OffsetDateTime.MAX, EvitaDataTypesConverter.toOffsetDateTime(beyondMax));
			assertEquals(OffsetDateTime.MIN, EvitaDataTypesConverter.toOffsetDateTime(beyondMin));
		}

		@Test
		@DisplayName("should store the first gRPC moment as the earliest moment evitaDB can hold")
		void shouldAcceptAttributeUpsertOfFirstGrpcMoment() {
			assertEquals(OffsetDateTime.MIN, decodedOnTheServer(FIRST_GRPC_MOMENT, GrpcEvitaDataType.OFFSET_DATE_TIME));
			assertEquals(
				OffsetDateTime.ofInstant(EARLIEST_SUPPORTED_INSTANT, ZoneOffset.MAX),
				storedOnTheServer(FIRST_GRPC_MOMENT, GrpcEvitaDataType.OFFSET_DATE_TIME)
			);
		}

		@Test
		@DisplayName("should store any other moment in year 9999 as the moment that was sent")
		void shouldAcceptAttributeUpsertOfAnyMomentInYear9999() {
			assertEquals(MID_YEAR_9999, storedOnTheServer(MID_YEAR_9999, GrpcEvitaDataType.OFFSET_DATE_TIME));
			assertEquals(
				SECOND_TO_LAST_GRPC_MOMENT,
				storedOnTheServer(SECOND_TO_LAST_GRPC_MOMENT, GrpcEvitaDataType.OFFSET_DATE_TIME)
			);
		}

		@Test
		@DisplayName("should store a local date-time in year 9999 as the moment that was sent")
		void shouldAcceptLocalDateTimeAttributeUpsertInYear9999() {
			assertEquals(
				MID_YEAR_9999.toLocalDateTime(), storedOnTheServer(MID_YEAR_9999, GrpcEvitaDataType.LOCAL_DATE_TIME)
			);
			assertEquals(
				LocalDateTime.ofInstant(LATEST_SUPPORTED_INSTANT, ZoneOffset.UTC),
				storedOnTheServer(LAST_GRPC_MOMENT, GrpcEvitaDataType.LOCAL_DATE_TIME)
			);
		}

		@Test
		@DisplayName("should store an array carrying a year 9999 element and the upper sentinel")
		void shouldAcceptArrayAttributeUpsertWithYear9999Element() {
			final OffsetDateTime[] decoded = new OffsetDateTime[]{
				(OffsetDateTime) decodedOnTheServer(LAST_MOMENT_OF_9998, GrpcEvitaDataType.OFFSET_DATE_TIME),
				(OffsetDateTime) decodedOnTheServer(MID_YEAR_9999, GrpcEvitaDataType.OFFSET_DATE_TIME),
				(OffsetDateTime) decodedOnTheServer(LAST_GRPC_MOMENT, GrpcEvitaDataType.OFFSET_DATE_TIME)
			};
			assertArrayEquals(
				new OffsetDateTime[]{
					LAST_MOMENT_OF_9998,
					MID_YEAR_9999,
					OffsetDateTime.ofInstant(LATEST_SUPPORTED_INSTANT, ZoneOffset.MIN)
				},
				(OffsetDateTime[]) new UpsertAttributeMutation("validUntil", decoded).getAttributeValue()
			);
		}

		@Test
		@DisplayName("should keep a query constant in year 9999 as the moment that was sent")
		void shouldAcceptQueryConstantInYear9999() {
			final OffsetDateTime decoded =
				(OffsetDateTime) decodedOnTheServer(MID_YEAR_9999, GrpcEvitaDataType.OFFSET_DATE_TIME);
			final AttributeLessThan constraint = QueryConstraints.attributeLessThan("validUntil", decoded);
			assertEquals(MID_YEAR_9999, constraint.getAttributeValue());
		}

		@Test
		@DisplayName("should clamp a sentinel query constant to the very value a stored sentinel is clamped to")
		void shouldClampSentinelQueryConstantLikeStoredValue() {
			final OffsetDateTime decoded =
				(OffsetDateTime) decodedOnTheServer(LAST_GRPC_MOMENT, GrpcEvitaDataType.OFFSET_DATE_TIME);
			final AttributeEquals constraint = QueryConstraints.attributeEquals("validUntil", decoded);
			assertEquals(
				storedOnTheServer(LAST_GRPC_MOMENT, GrpcEvitaDataType.OFFSET_DATE_TIME),
				constraint.getAttributeValue()
			);
		}

		@Test
		@DisplayName("should store the last moment of year 9998 unchanged")
		void shouldAcceptAttributeUpsertOfLastMomentOf9998() {
			assertEquals(
				LAST_MOMENT_OF_9998, decodedOnTheServer(LAST_MOMENT_OF_9998, GrpcEvitaDataType.OFFSET_DATE_TIME)
			);
			assertEquals(
				LAST_MOMENT_OF_9998, storedOnTheServer(LAST_MOMENT_OF_9998, GrpcEvitaDataType.OFFSET_DATE_TIME)
			);
		}

		@Test
		@DisplayName("should decode a year 9999 moment as the moment that was sent")
		void shouldDecodeYear9999MomentAsTheMomentSent() {
			assertEquals(
				MID_YEAR_9999.toInstant(),
				((OffsetDateTime) decodedOnTheServer(MID_YEAR_9999, GrpcEvitaDataType.OFFSET_DATE_TIME)).toInstant()
			);
		}

		@Test
		@DisplayName("should take only the edge days of the range for the local date sentinels")
		void shouldDecodeOnlyEdgeDaysAsLocalDateSentinels() {
			assertEquals(LocalDate.MAX, decodedOnTheServer(LAST_GRPC_MOMENT, GrpcEvitaDataType.LOCAL_DATE));
			final OffsetDateTime lastDayMidnight = OffsetDateTime.of(9999, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC);
			final OffsetDateTime dayBeforeLastMidnight = lastDayMidnight.minusDays(1L);
			assertEquals(LocalDate.MAX, decodedOnTheServer(lastDayMidnight, GrpcEvitaDataType.LOCAL_DATE));
			assertEquals(LocalDate.MIN, decodedOnTheServer(FIRST_GRPC_MOMENT, GrpcEvitaDataType.LOCAL_DATE));
			assertEquals(LocalDate.of(9999, 6, 1), decodedOnTheServer(MID_YEAR_9999, GrpcEvitaDataType.LOCAL_DATE));
			assertEquals(
				LocalDate.of(9999, 12, 30), decodedOnTheServer(dayBeforeLastMidnight, GrpcEvitaDataType.LOCAL_DATE)
			);
		}

	}

	@Nested
	@DisplayName("Outbound")
	class Outbound {

		@Test
		@DisplayName("should put an ordinary year 9999 moment on the wire as its raw instant")
		void shouldEncodeOrdinaryYear9999MomentAsRawInstant() {
			assertEquals(onTheWire(MID_YEAR_9999), EvitaDataTypesConverter.toGrpcOffsetDateTime(MID_YEAR_9999));
			assertEquals(onTheWire(LAST_GRPC_MOMENT), EvitaDataTypesConverter.toGrpcOffsetDateTime(LAST_GRPC_MOMENT));
		}

		@Test
		@DisplayName("should encode the latest moment evitaDB can hold as the last second of the protobuf range")
		void shouldEncodeLatestRepresentableMomentWithinProtobufRange() {
			final OffsetDateTime latestRepresentable =
				OffsetDateTime.ofInstant(LATEST_SUPPORTED_INSTANT, ZoneOffset.MIN);
			final GrpcOffsetDateTime encoded = EvitaDataTypesConverter.toGrpcOffsetDateTime(latestRepresentable);

			assertEquals(PROTOBUF_MAX_SECONDS, encoded.getTimestamp().getSeconds());
			assertEquals(0, encoded.getTimestamp().getNanos());
			assertEquals("-18:00", encoded.getOffset());
			// and it reads back as the sentinel it stands for
			assertEquals(OffsetDateTime.MAX, EvitaDataTypesConverter.toOffsetDateTime(encoded));
		}

		@Test
		@DisplayName("should encode the earliest moment evitaDB can hold as the first second of the protobuf range")
		void shouldEncodeEarliestRepresentableMomentWithinProtobufRange() {
			final OffsetDateTime earliestRepresentable =
				OffsetDateTime.ofInstant(EARLIEST_SUPPORTED_INSTANT, ZoneOffset.MAX);
			final GrpcOffsetDateTime encoded = EvitaDataTypesConverter.toGrpcOffsetDateTime(earliestRepresentable);

			assertEquals(PROTOBUF_MIN_SECONDS, encoded.getTimestamp().getSeconds());
			assertEquals(0, encoded.getTimestamp().getNanos());
			assertEquals(OffsetDateTime.MIN, EvitaDataTypesConverter.toOffsetDateTime(encoded));
		}

		@Test
		@DisplayName("should encode a local date-time beyond year 9999 as the last second of the protobuf range")
		void shouldEncodeLocalDateTimeBeyondYear9999WithinProtobufRange() {
			final GrpcOffsetDateTime encoded =
				EvitaDataTypesConverter.toGrpcLocalDateTime(LocalDateTime.of(10_000, 1, 1, 0, 0));

			assertEquals(PROTOBUF_MAX_SECONDS, encoded.getTimestamp().getSeconds());
			assertEquals(LocalDateTime.MAX, EvitaDataTypesConverter.toLocalDateTime(encoded));
		}

		@Test
		@DisplayName("should round-trip the JDK sentinels through the wire as themselves")
		void shouldRoundTripJdkSentinels() {
			final GrpcOffsetDateTime max = EvitaDataTypesConverter.toGrpcOffsetDateTime(OffsetDateTime.MAX);
			final GrpcOffsetDateTime min = EvitaDataTypesConverter.toGrpcOffsetDateTime(OffsetDateTime.MIN);
			assertEquals(OffsetDateTime.MAX, EvitaDataTypesConverter.toOffsetDateTime(max));
			assertEquals(OffsetDateTime.MIN, EvitaDataTypesConverter.toOffsetDateTime(min));
			assertEquals(
				LocalDateTime.MAX,
				EvitaDataTypesConverter.toLocalDateTime(EvitaDataTypesConverter.toGrpcLocalDateTime(LocalDateTime.MAX))
			);
			assertEquals(
				LocalDate.MAX,
				EvitaDataTypesConverter.toLocalDate(EvitaDataTypesConverter.toGrpcLocalDate(LocalDate.MAX))
			);
			assertEquals(
				LocalDate.MIN,
				EvitaDataTypesConverter.toLocalDate(EvitaDataTypesConverter.toGrpcLocalDate(LocalDate.MIN))
			);
		}

		@Test
		@DisplayName("should encode a date beyond year 9999 within the protobuf range")
		void shouldEncodeLocalDateBeyondYear9999WithinProtobufRange() {
			assertEquals(
				PROTOBUF_MAX_SECONDS,
				EvitaDataTypesConverter.toGrpcLocalDate(LocalDate.of(10_000, 1, 1)).getTimestamp().getSeconds()
			);
		}

	}

}
