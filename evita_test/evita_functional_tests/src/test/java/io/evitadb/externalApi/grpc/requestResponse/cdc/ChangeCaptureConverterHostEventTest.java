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

package io.evitadb.externalApi.grpc.requestResponse.cdc;

import io.evitadb.api.CatalogState;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.cdc.SystemCaptureArea;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcCatalogInstalledIntoLiveView;
import io.evitadb.externalApi.grpc.generated.GrpcCatalogRemovedFromLiveView;
import io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaUpdated;
import io.evitadb.externalApi.grpc.generated.GrpcChangeCaptureOperation;
import io.evitadb.externalApi.grpc.generated.GrpcChangeSystemCapture;
import io.evitadb.externalApi.grpc.generated.GrpcChangeSystemCaptureCriteria;
import io.evitadb.externalApi.grpc.generated.GrpcEngineMutation;
import io.evitadb.externalApi.grpc.generated.GrpcHostSystemEvent;
import io.evitadb.externalApi.grpc.generated.GrpcSystemCaptureArea;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingEngineMutationConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Round-trip tests for the {@link ChangeCaptureConverter} surface that converts
 * {@link HostSystemEvent}s, {@link ChangeSystemCaptureCriteria}, and
 * {@link SystemCaptureArea} between the domain types and their gRPC wire equivalents.
 *
 * Each test constructs a domain instance, converts it to gRPC, converts it back, and
 * asserts the round-trip preserves the relevant fields. Negative tests cover the
 * defensive paths in the converter (unknown body / unset oneof discriminators).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ChangeCaptureConverter host event surface")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(CDC)
class ChangeCaptureConverterHostEventTest {

	/**
	 * Stable timestamp used in capture round-trips so the assertions don't depend on wall clock.
	 */
	private static final OffsetDateTime TIMESTAMP =
		OffsetDateTime.of(2026, 5, 6, 12, 0, 0, 0, ZoneOffset.UTC);

	@Nested
	@DisplayName("HostSystemEvent round-trip")
	class HostEventRoundTrip {

		@ParameterizedTest(name = "should round-trip CatalogInstalledIntoLiveView with state {0}")
		@DisplayName("should round-trip CatalogInstalledIntoLiveView for every non-transient state")
		@EnumSource(value = CatalogState.class)
		void shouldRoundTripCatalogInstalledIntoLiveView(@javax.annotation.Nonnull final CatalogState state) {
			// CatalogInstalledIntoLiveView's compact constructor rejects transient states; we
			// therefore restrict the parameterized run to the non-transient subset
			if (state.isTransitional()) {
				return;
			}

			final HostSystemEvent.CatalogInstalledIntoLiveView source =
				new HostSystemEvent.CatalogInstalledIntoLiveView("ctlg", state, 17L);

			final GrpcHostSystemEvent grpc = ChangeCaptureConverter.toGrpcHostSystemEvent(source);
			final HostSystemEvent roundTripped = ChangeCaptureConverter.toHostSystemEvent(grpc);

			final HostSystemEvent.CatalogInstalledIntoLiveView typed = assertInstanceOf(
				HostSystemEvent.CatalogInstalledIntoLiveView.class, roundTripped
			);
			assertEquals(source.catalogName(), typed.catalogName());
			assertEquals(source.observedState(), typed.observedState());
			assertEquals(source.currentEngineVersion(), typed.currentEngineVersion());
		}

		@Test
		@DisplayName("should round-trip CatalogRemovedFromLiveView preserving fields")
		void shouldRoundTripCatalogRemovedFromLiveView() {
			final HostSystemEvent.CatalogRemovedFromLiveView source =
				new HostSystemEvent.CatalogRemovedFromLiveView("removed", 42L);

			final GrpcHostSystemEvent grpc = ChangeCaptureConverter.toGrpcHostSystemEvent(source);
			final HostSystemEvent roundTripped = ChangeCaptureConverter.toHostSystemEvent(grpc);

			final HostSystemEvent.CatalogRemovedFromLiveView typed = assertInstanceOf(
				HostSystemEvent.CatalogRemovedFromLiveView.class, roundTripped
			);
			assertEquals(source.catalogName(), typed.catalogName());
			assertEquals(source.currentEngineVersion(), typed.currentEngineVersion());
		}

		@Test
		@DisplayName("should round-trip CatalogSchemaUpdated preserving fields")
		void shouldRoundTripCatalogSchemaUpdated() {
			// Coalesced schema-refresh host event must traverse the gRPC
			// converter without losing catalogName / newSchemaVersion / currentEngineVersion.
			final HostSystemEvent.CatalogSchemaUpdated source =
				new HostSystemEvent.CatalogSchemaUpdated("c", 7, 99L);

			final GrpcHostSystemEvent grpc = ChangeCaptureConverter.toGrpcHostSystemEvent(source);

			// assert wire-level oneof and payload before round-tripping back to domain
			assertEquals(GrpcHostSystemEvent.EventCase.CATALOGSCHEMAUPDATED, grpc.getEventCase());
			final GrpcCatalogSchemaUpdated payload = grpc.getCatalogSchemaUpdated();
			assertEquals("c", payload.getCatalogName());
			assertEquals(7, payload.getNewSchemaVersion());
			assertEquals(99L, payload.getCurrentEngineVersion());

			final HostSystemEvent roundTripped = ChangeCaptureConverter.toHostSystemEvent(grpc);

			final HostSystemEvent.CatalogSchemaUpdated typed = assertInstanceOf(
				HostSystemEvent.CatalogSchemaUpdated.class, roundTripped
			);
			assertEquals(source.catalogName(), typed.catalogName());
			assertEquals(source.newSchemaVersion(), typed.newSchemaVersion());
			assertEquals(source.currentEngineVersion(), typed.currentEngineVersion());
			assertEquals(source, typed);
		}

		@Test
		@DisplayName("should populate catalogInstalled oneof when converting installed event")
		void shouldPopulateInstalledOneof() {
			final HostSystemEvent.CatalogInstalledIntoLiveView source =
				new HostSystemEvent.CatalogInstalledIntoLiveView("ctlg", CatalogState.ALIVE, 0L);

			final GrpcHostSystemEvent grpc = ChangeCaptureConverter.toGrpcHostSystemEvent(source);

			assertEquals(GrpcHostSystemEvent.EventCase.CATALOGINSTALLED, grpc.getEventCase());
			final GrpcCatalogInstalledIntoLiveView installed = grpc.getCatalogInstalled();
			assertEquals("ctlg", installed.getCatalogName());
		}

		@Test
		@DisplayName("should populate catalogRemoved oneof when converting removed event")
		void shouldPopulateRemovedOneof() {
			final HostSystemEvent.CatalogRemovedFromLiveView source =
				new HostSystemEvent.CatalogRemovedFromLiveView("ctlg", 0L);

			final GrpcHostSystemEvent grpc = ChangeCaptureConverter.toGrpcHostSystemEvent(source);

			assertEquals(GrpcHostSystemEvent.EventCase.CATALOGREMOVED, grpc.getEventCase());
			final GrpcCatalogRemovedFromLiveView removed = grpc.getCatalogRemoved();
			assertEquals("ctlg", removed.getCatalogName());
		}

		@Test
		@DisplayName("should return null for forward-compat unknown gRPC host event variant")
		void shouldReturnNullForUnknownGrpcHostEventCase() {
			// Forward-compat: when an old client receives a tag from a newer server whose variant
			// the client does not yet know about, the parser drops the unknown field and the
			// oneof discriminator collapses to `EVENT_NOT_SET`. The converter must return `null`
			// (not throw) so the subscription stream survives — caller drops captures with a null
			// body. FU forward-compat.
			final GrpcHostSystemEvent unset = GrpcHostSystemEvent.newBuilder().build();

			final HostSystemEvent result = ChangeCaptureConverter.toHostSystemEvent(unset);

			assertNull(
				result,
				"Forward-compat: EVENT_NOT_SET must yield null, not throw — got " + result
			);
		}
	}

	@Nested
	@DisplayName("ChangeSystemCaptureCriteria round-trip")
	class CriteriaRoundTrip {

		@Test
		@DisplayName("should round-trip ENGINE area criterion")
		void shouldRoundTripEngineCriterion() {
			final ChangeSystemCaptureCriteria source =
				new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE);

			final GrpcChangeSystemCaptureCriteria grpc =
				ChangeCaptureConverter.toGrpcChangeSystemCaptureCriteria(source);
			final ChangeSystemCaptureCriteria roundTripped =
				ChangeCaptureConverter.toChangeSystemCaptureCriteria(grpc);

			assertEquals(SystemCaptureArea.ENGINE, roundTripped.area());
		}

		@Test
		@DisplayName("should round-trip HOST area criterion")
		void shouldRoundTripInfrastructureCriterion() {
			final ChangeSystemCaptureCriteria source =
				new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST);

			final GrpcChangeSystemCaptureCriteria grpc =
				ChangeCaptureConverter.toGrpcChangeSystemCaptureCriteria(source);
			final ChangeSystemCaptureCriteria roundTripped =
				ChangeCaptureConverter.toChangeSystemCaptureCriteria(grpc);

			assertEquals(SystemCaptureArea.HOST, roundTripped.area());
		}

		@Test
		@DisplayName("should round-trip null area criterion preserving the match-any sentinel")
		void shouldRoundTripNullAreaCriterion() {
			// Issue #1151 — the proto enum carries a dedicated SYSTEM_AREA_UNSPECIFIED zero value
			// so a `null` area survives the round-trip instead of collapsing into ENGINE through
			// proto3 default-value semantics. The converter writes the default-zero on the wire
			// and reads it back as `null`, preserving the OR-of-criteria "match any area"
			// semantic on the system stream.
			final ChangeSystemCaptureCriteria source = new ChangeSystemCaptureCriteria(null);

			final GrpcChangeSystemCaptureCriteria grpc =
				ChangeCaptureConverter.toGrpcChangeSystemCaptureCriteria(source);
			final ChangeSystemCaptureCriteria roundTripped =
				ChangeCaptureConverter.toChangeSystemCaptureCriteria(grpc);

			assertNull(roundTripped.area(), "null area must round-trip as null");
			assertNull(source.area(), "sanity: original area was null");
		}
	}

	@Nested
	@DisplayName("SystemCaptureArea round-trip")
	class AreaRoundTrip {

		@Test
		@DisplayName("should round-trip ENGINE system area")
		void shouldRoundTripEngineSystemArea() {
			final GrpcSystemCaptureArea grpc =
				ChangeCaptureConverter.toGrpcSystemCaptureArea(SystemCaptureArea.ENGINE);

			assertEquals(GrpcSystemCaptureArea.SYSTEM_AREA_ENGINE, grpc);
			assertEquals(SystemCaptureArea.ENGINE, ChangeCaptureConverter.toSystemCaptureArea(grpc));
		}

		@Test
		@DisplayName("should round-trip HOST system area")
		void shouldRoundTripInfrastructureSystemArea() {
			final GrpcSystemCaptureArea grpc =
				ChangeCaptureConverter.toGrpcSystemCaptureArea(SystemCaptureArea.HOST);

			assertEquals(GrpcSystemCaptureArea.SYSTEM_AREA_HOST, grpc);
			assertEquals(
				SystemCaptureArea.HOST,
				ChangeCaptureConverter.toSystemCaptureArea(grpc)
			);
		}
	}

	@Nested
	@DisplayName("ChangeSystemCapture body dispatch")
	class CaptureBodyDispatch {

		@Test
		@DisplayName("should dispatch engine mutation body to systemMutation oneof")
		void shouldDispatchEngineMutationBodyToSystemMutationOneof() {
			final CreateCatalogSchemaMutation mutation = new CreateCatalogSchemaMutation("convCatalog");
			final ChangeSystemCapture source = new ChangeSystemCapture(
				1L, 0, TIMESTAMP, Operation.UPSERT, mutation
			);

			final GrpcChangeSystemCapture grpc =
				ChangeCaptureConverter.toGrpcChangeSystemCapture(source);

			assertEquals(GrpcChangeSystemCapture.BodyCase.SYSTEMMUTATION, grpc.getBodyCase());
		}

		@Test
		@DisplayName("should dispatch host event body to hostEvent oneof")
		void shouldDispatchHostEventBodyToHostEventOneof() {
			final HostSystemEvent.CatalogInstalledIntoLiveView event =
				new HostSystemEvent.CatalogInstalledIntoLiveView("convCatalog", CatalogState.ALIVE, 0L);
			final ChangeSystemCapture source = new ChangeSystemCapture(
				2L, 0, TIMESTAMP, Operation.UPSERT, event
			);

			final GrpcChangeSystemCapture grpc =
				ChangeCaptureConverter.toGrpcChangeSystemCapture(source);

			assertEquals(GrpcChangeSystemCapture.BodyCase.HOSTEVENT, grpc.getBodyCase());
		}

		@Test
		@DisplayName("should leave body oneof unset when capture body is null")
		void shouldLeaveBodyOneofUnsetWhenBodyNull() {
			final ChangeSystemCapture source = new ChangeSystemCapture(
				3L, 0, TIMESTAMP, Operation.UPSERT, null
			);

			final GrpcChangeSystemCapture grpc =
				ChangeCaptureConverter.toGrpcChangeSystemCapture(source);

			assertEquals(GrpcChangeSystemCapture.BodyCase.BODY_NOT_SET, grpc.getBodyCase());
		}

		@Test
		@DisplayName("should deserialize BODY_NOT_SET as null body")
		void shouldDeserializeBodyNotSetAsNullBody() {
			// Build a header-only gRPC capture with the body oneof intentionally unset.
			final GrpcChangeSystemCapture grpc = GrpcChangeSystemCapture.newBuilder()
				.setVersion(4L)
				.setIndex(0)
				.setTimestamp(EvitaDataTypesConverter.toGrpcOffsetDateTime(TIMESTAMP))
				.setOperation(GrpcChangeCaptureOperation.UPSERT)
				.build();

			final ChangeSystemCapture roundTripped =
				ChangeCaptureConverter.toChangeSystemCapture(grpc);

			assertNull(roundTripped.body());
			assertEquals(4L, roundTripped.version());
		}

		@Test
		@DisplayName("should round-trip engine-mutation capture preserving header and body")
		void shouldRoundTripEngineMutationCapture() {
			final CreateCatalogSchemaMutation mutation = new CreateCatalogSchemaMutation("rtCatalog");
			final ChangeSystemCapture source = new ChangeSystemCapture(
				5L, 1, TIMESTAMP, Operation.UPSERT, mutation
			);

			final GrpcChangeSystemCapture grpc =
				ChangeCaptureConverter.toGrpcChangeSystemCapture(source);
			final ChangeSystemCapture roundTripped =
				ChangeCaptureConverter.toChangeSystemCapture(grpc);

			assertEquals(source.version(), roundTripped.version());
			assertEquals(source.index(), roundTripped.index());
			assertEquals(source.operation(), roundTripped.operation());
			assertNotNull(roundTripped.body());
			assertInstanceOf(CreateCatalogSchemaMutation.class, roundTripped.body());
			assertEquals(
				"rtCatalog",
				((CreateCatalogSchemaMutation) roundTripped.body()).getCatalogName()
			);
		}

		@Test
		@DisplayName("should round-trip host-event capture preserving header and body")
		void shouldRoundTripHostEventCapture() {
			final HostSystemEvent.CatalogInstalledIntoLiveView event =
				new HostSystemEvent.CatalogInstalledIntoLiveView("rtCatalog", CatalogState.ALIVE, 9L);
			final ChangeSystemCapture source = new ChangeSystemCapture(
				6L, 0, TIMESTAMP, Operation.UPSERT, event
			);

			final GrpcChangeSystemCapture grpc =
				ChangeCaptureConverter.toGrpcChangeSystemCapture(source);
			final ChangeSystemCapture roundTripped =
				ChangeCaptureConverter.toChangeSystemCapture(grpc);

			assertEquals(source.version(), roundTripped.version());
			assertEquals(source.operation(), roundTripped.operation());
			final HostSystemEvent.CatalogInstalledIntoLiveView typed = assertInstanceOf(
				HostSystemEvent.CatalogInstalledIntoLiveView.class, roundTripped.body()
			);
			assertEquals("rtCatalog", typed.catalogName());
			assertEquals(CatalogState.ALIVE, typed.observedState());
			assertEquals(9L, typed.currentEngineVersion());
		}

		@Test
		@DisplayName("should ensure engine-mutation gRPC body decodes back to engine mutation")
		void shouldDecodeEngineMutationGrpcBody() {
			final CreateCatalogSchemaMutation mutation = new CreateCatalogSchemaMutation("decode");
			final GrpcEngineMutation grpcMutation =
				DelegatingEngineMutationConverter.INSTANCE.convert(mutation);
			assertNotNull(grpcMutation);

			final GrpcChangeSystemCapture grpc = GrpcChangeSystemCapture.newBuilder()
				.setVersion(7L)
				.setIndex(0)
				.setTimestamp(EvitaDataTypesConverter.toGrpcOffsetDateTime(TIMESTAMP))
				.setOperation(GrpcChangeCaptureOperation.UPSERT)
				.setSystemMutation(grpcMutation)
				.build();

			final ChangeSystemCapture roundTripped =
				ChangeCaptureConverter.toChangeSystemCapture(grpc);

			assertInstanceOf(CreateCatalogSchemaMutation.class, roundTripped.body());
		}
	}
}
