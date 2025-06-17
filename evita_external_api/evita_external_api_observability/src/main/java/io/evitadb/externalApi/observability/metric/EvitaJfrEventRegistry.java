/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.externalApi.observability.metric;

import io.evitadb.api.observability.annotation.EventGroup;
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import io.evitadb.core.metric.event.cache.AnteroomRecordStatisticsUpdatedEvent;
import io.evitadb.core.metric.event.cache.AnteroomWastedEvent;
import io.evitadb.core.metric.event.cdc.ChangeCatalogCaptureStatisticsEvent;
import io.evitadb.core.metric.event.cdc.ChangeCatalogCaptureStatisticsPerAreaEvent;
import io.evitadb.core.metric.event.cdc.ChangeCatalogCaptureStatisticsPerEntityTypeEvent;
import io.evitadb.core.metric.event.query.EntityEnrichEvent;
import io.evitadb.core.metric.event.query.EntityFetchEvent;
import io.evitadb.core.metric.event.query.FinishedEvent;
import io.evitadb.core.metric.event.session.ClosedEvent;
import io.evitadb.core.metric.event.session.KilledEvent;
import io.evitadb.core.metric.event.session.OpenedEvent;
import io.evitadb.core.metric.event.storage.CatalogStatisticsEvent;
import io.evitadb.core.metric.event.storage.DataFileCompactEvent;
import io.evitadb.core.metric.event.storage.ObservableOutputChangeEvent;
import io.evitadb.core.metric.event.storage.OffsetIndexFlushEvent;
import io.evitadb.core.metric.event.storage.OffsetIndexHistoryKeptEvent;
import io.evitadb.core.metric.event.storage.OffsetIndexNonFlushedEvent;
import io.evitadb.core.metric.event.storage.OffsetIndexRecordTypeCountChangedEvent;
import io.evitadb.core.metric.event.storage.ReadOnlyHandleClosedEvent;
import io.evitadb.core.metric.event.storage.ReadOnlyHandleOpenedEvent;
import io.evitadb.core.metric.event.system.BackgroundTaskFinishedEvent;
import io.evitadb.core.metric.event.system.BackgroundTaskRejectedEvent;
import io.evitadb.core.metric.event.system.BackgroundTaskStartedEvent;
import io.evitadb.core.metric.event.system.BackgroundTaskTimedOutEvent;
import io.evitadb.core.metric.event.system.EvitaStatisticsEvent;
import io.evitadb.core.metric.event.system.RequestForkJoinPoolStatisticsEvent;
import io.evitadb.core.metric.event.system.ScheduledExecutorStatisticsEvent;
import io.evitadb.core.metric.event.system.TransactionForkJoinPoolStatisticsEvent;
import io.evitadb.core.metric.event.transaction.*;
import io.evitadb.externalApi.event.ReadinessEvent;
import io.evitadb.externalApi.event.RequestEvent;
import io.evitadb.externalApi.grpc.metric.event.EvitaProcedureCalledEvent;
import io.evitadb.externalApi.grpc.metric.event.SessionProcedureCalledEvent;
import io.evitadb.store.traffic.event.TrafficRecorderStatisticsEvent;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ReflectionLookup;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * This class is used as a provider of custom metrics events. It provides a set of all registered custom metrics events
 * and a map of all registered custom metrics events by their package name.
 *
 * All package names containing custom metrics events must be registered here in {@link #EVENTS_TYPES}.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
@NoArgsConstructor
public class EvitaJfrEventRegistry {
	private static final Set<Class<? extends CustomMetricsExecutionEvent>> EVENTS_TYPES = Set.of(
		// transaction events
		CatalogGoesLiveEvent.class,
		TransactionStartedEvent.class,
		TransactionFinishedEvent.class,
		TransactionAcceptedEvent.class,
		TransactionAppendedToWalEvent.class,
		TransactionIncorporatedToTrunkEvent.class,
		TransactionProcessedEvent.class,
		TransactionQueuedEvent.class,
		NewCatalogVersionPropagatedEvent.class,
		WalStatisticsEvent.class,
		WalRotationEvent.class,
		WalCacheSizeChangedEvent.class,
		IsolatedWalFileOpenedEvent.class,
		IsolatedWalFileClosedEvent.class,
		OffHeapMemoryAllocationChangeEvent.class,

		// storage events
		OffsetIndexFlushEvent.class,
		DataFileCompactEvent.class,
		OffsetIndexRecordTypeCountChangedEvent.class,
		OffsetIndexNonFlushedEvent.class,
		OffsetIndexHistoryKeptEvent.class,
		ObservableOutputChangeEvent.class,
		ReadOnlyHandleOpenedEvent.class,
		ReadOnlyHandleClosedEvent.class,
		CatalogStatisticsEvent.class,
		TrafficRecorderStatisticsEvent.class,

		// query events
		FinishedEvent.class,
		EntityFetchEvent.class,
		EntityEnrichEvent.class,

		// session events
		OpenedEvent.class,
		ClosedEvent.class,
		KilledEvent.class,

		// system events
		EvitaStatisticsEvent.class,
		BackgroundTaskStartedEvent.class,
		BackgroundTaskRejectedEvent.class,
		BackgroundTaskTimedOutEvent.class,
		BackgroundTaskFinishedEvent.class,
		RequestForkJoinPoolStatisticsEvent.class,
		TransactionForkJoinPoolStatisticsEvent.class,
		ScheduledExecutorStatisticsEvent.class,

		//cdc
		ChangeCatalogCaptureStatisticsEvent.class,
		ChangeCatalogCaptureStatisticsPerAreaEvent.class,
		ChangeCatalogCaptureStatisticsPerEntityTypeEvent.class,

		//cache
		AnteroomRecordStatisticsUpdatedEvent.class,
		AnteroomWastedEvent.class,

		// api
		ReadinessEvent.class,
		RequestEvent.class,

		// api - gRPC
		EvitaProcedureCalledEvent.class,
		SessionProcedureCalledEvent.class,

		// api - GraphQL
		io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent.class,
		io.evitadb.externalApi.graphql.metric.event.instance.BuiltEvent.class,

		// api - REST
		io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent.class,
		io.evitadb.externalApi.rest.metric.event.instance.BuiltEvent.class
	);
	private static final Map<String, Class<? extends CustomMetricsExecutionEvent>> EVENT_MAP;
	private static final Map<String, EvitaEventGroup> EVENT_MAP_BY_PACKAGE;

	private static final Map<String, EventGroup> KNOWN_EVITA_GROUPS = CollectionUtils.createHashMap(64);
	private static final Set<String> KNOWN_JDK_EVENTS = CollectionUtils.createHashSet(256);
	private static final Map<String, JdkEventGroup> JDK_EVENT_GROUPS = CollectionUtils.createHashMap(64);

	static {
		// Security-related events
		JDK_EVENT_GROUPS.put(
			"Security",
			new JdkEventGroup(
				"Security",
				"JDK - Security",
				"Events related to security and cryptography.",
				new String[]{
					"jdk.X509Validation",
					"jdk.X509Certificate",
					"jdk.TLSHandshake",
					"jdk.SecurityPropertyModification"
				}
			)
		);
		// Events related to process and container management
		JDK_EVENT_GROUPS.put(
			"ProcessAndContainerManagement",
			new JdkEventGroup(
				"ProcessAndContainerManagement",
				"JDK - Process and container management",
				"Events related to process and container management.",
				new String[]{
					"jdk.ProcessStart",
					"jdk.ContainerIOUsage",
					"jdk.ContainerMemoryUsage",
					"jdk.ContainerCPUThrottling",
					"jdk.ContainerCPUUsage",
					"jdk.ContainerConfiguration"
				}
			)
		);
		// Events related to thread activities
		JDK_EVENT_GROUPS.put(
			"ThreadManagement",
			new JdkEventGroup(
				"ThreadManagement",
				"JDK - Thread management",
				"Events related to thread management.",
				new String[]{
					"jdk.ThreadStart",
					"jdk.ThreadEnd",
					"jdk.ThreadSleep",
					"jdk.ThreadPark",
					"jdk.ThreadContextSwitchRate",
					"jdk.ThreadCPULoad",
					"jdk.ThreadAllocationStatistics"
				}
			)
		);
		// Events related to GC activities
		JDK_EVENT_GROUPS.put(
			"GarbageCollection",
			new JdkEventGroup(
				"GarbageCollection",
				"JDK - Garbage collection",
				"Events related to garbage collection.",
				new String[]{
					"jdk.GCHeapSummary",
					"jdk.MetaspaceSummary",
					"jdk.MetaspaceGCThreshold",
					"jdk.MetaspaceAllocationFailure",
					"jdk.MetaspaceOOM",
					"jdk.MetaspaceChunkFreeListSummary",
					"jdk.PSHeapSummary",
					"jdk.G1HeapSummary",
					"jdk.GarbageCollection",
					"jdk.SystemGC",
					"jdk.ParallelOldGarbageCollection",
					"jdk.YoungGarbageCollection",
					"jdk.OldGarbageCollection",
					"jdk.G1GarbageCollection",
					"jdk.G1MMU",
					"jdk.EvacuationInformation",
					"jdk.GCReferenceStatistics",
					"jdk.ObjectCountAfterGC",
					"jdk.G1EvacuationYoungStatistics",
					"jdk.G1EvacuationOldStatistics",
					"jdk.G1BasicIHOP",
					"jdk.G1AdaptiveIHOP",
					"jdk.PromoteObjectInNewPLAB",
					"jdk.PromoteObjectOutsidePLAB",
					"jdk.PromotionFailed",
					"jdk.EvacuationFailed",
					"jdk.ConcurrentModeFailure",
					"jdk.GCPhasePause",
					"jdk.GCPhasePauseLevel1",
					"jdk.GCPhasePauseLevel2",
					"jdk.GCPhasePauseLevel3",
					"jdk.GCPhasePauseLevel4",
					"jdk.GCPhaseConcurrent",
					"jdk.GCPhaseConcurrentLevel1",
					"jdk.GCPhaseParallel",
					"jdk.AllocationRequiringGC",
					"jdk.TenuringDistribution",
					"jdk.G1HeapRegionTypeChange",
					"jdk.GCConfiguration",
					"jdk.GCSurvivorConfiguration",
					"jdk.GCTLABConfiguration",
					"jdk.GCHeapConfiguration",
					"jdk.YoungGenerationConfiguration",
					"jdk.ZAllocationStall",
					"jdk.ZPageAllocation",
					"jdk.ZRelocationSet",
					"jdk.ZRelocationSetGroup",
					"jdk.ZStatisticsCounter",
					"jdk.ZStatisticsSampler",
					"jdk.ZThreadPhase",
					"jdk.ZUncommit",
					"jdk.ZUnmap",
					"jdk.ShenandoahHeapRegionStateChange",
					"jdk.ShenandoahHeapRegionInformation",
					"jdk.GCLocker"
				}
			)
		);
		// Events related to file operations
		JDK_EVENT_GROUPS.put(
			"FileOperations",
			new JdkEventGroup(
				"FileOperations",
				"JDK - File operations",
				"Events related to file operations.",
				new String[]{
					"jdk.SocketWrite",
					"jdk.SocketRead",
					"jdk.FileWrite",
					"jdk.FileRead",
					"jdk.FileForce"
				}
			)
		);

		// Events related to exception handling and errors
		JDK_EVENT_GROUPS.put(
			"ExceptionsAndErrors",
			new JdkEventGroup(
				"ExceptionsAndErrors",
				"JDK - Exceptions and errors",
				"Events related to exception handling and errors.",
				new String[]{
					"jdk.JavaErrorThrow",
					"jdk.ExceptionStatistics",
					"jdk.JavaExceptionThrow",
					"jdk.Deserialization",
					"jdk.DataLoss"
				}
			)
		);
		// Events related to class loading and modification
		JDK_EVENT_GROUPS.put(
			"ClassLoadingAndModification",
			new JdkEventGroup(
				"ClassLoadingAndModification",
				"JDK - Class loading and modification",
				"Events related to class loading and modification.",
				new String[]{
					"jdk.ClassLoad",
					"jdk.ClassDefine",
					"jdk.ClassRedefinition",
					"jdk.RedefineClasses",
					"jdk.RetransformClasses",
					"jdk.ClassUnload",
					"jdk.ModuleRequire",
					"jdk.ModuleExport"
				}
			)
		);
		// Events related to system and JVM information
		JDK_EVENT_GROUPS.put(
			"SystemAndJVMInfo",
			new JdkEventGroup(
				"SystemAndJVMInfo",
				"JDK - System and JVM information",
				"Events related to system and JVM information.",
				new String[]{
					"jdk.JVMInformation",
					"jdk.OSInformation",
					"jdk.VirtualizationInformation",
					"jdk.InitialSystemProperty",
					"jdk.InitialEnvironmentVariable",
					"jdk.SystemProcess",
					"jdk.CPUInformation",
					"jdk.CPUTimeStampCounter",
					"jdk.CPULoad",
					"jdk.PhysicalMemory",
					"jdk.DumpReason",
					"jdk.HeapDump",
					"jdk.Shutdown"
				}
			)
		);
		// Events related to memory allocation
		JDK_EVENT_GROUPS.put(
			"MemoryAllocation",
			new JdkEventGroup(
				"MemoryAllocation",
				"JDK - Memory allocation",
				"Events related to memory allocation.",
				new String[]{
					"jdk.ObjectAllocationInNewTLAB",
					"jdk.ObjectAllocationOutsideTLAB",
					"jdk.ObjectAllocationSample",
					"jdk.OldObjectSample",
					"jdk.ObjectCount"
				}
			)
		);
		// Events related to monitoring and statistics
		JDK_EVENT_GROUPS.put(
			"MonitoringAndStatistics",
			new JdkEventGroup(
				"MonitoringAndStatistics",
				"JDK - Monitoring and statistics",
				"Events related to monitoring and statistics.",
				new String[]{
					"jdk.ActiveRecording",
					"jdk.ActiveSetting",
					"jdk.SyncOnValueBasedClass",
					"jdk.BiasedLockRevocation",
					"jdk.BiasedLockSelfRevocation",
					"jdk.BiasedLockClassRevocation",
					"jdk.ReservedStackActivation",
					"jdk.IntFlagChanged",
					"jdk.UnsignedIntFlagChanged",
					"jdk.LongFlagChanged",
					"jdk.UnsignedLongFlagChanged",
					"jdk.DoubleFlagChanged",
					"jdk.BooleanFlagChanged",
					"jdk.StringFlagChanged",
					"jdk.DirectBufferStatistics",
					"jdk.G1EvacuationOldStatistics",
					"jdk.GCPhasePauseLevel2",
					"jdk.NetworkUtilization",
					"jdk.JavaThreadStatistics",
					"jdk.ClassLoadingStatistics",
					"jdk.ClassLoaderStatistics",
					"jdk.SymbolTableStatistics",
					"jdk.StringTableStatistics",
					"jdk.PlaceholderTableStatistics",
					"jdk.LoaderConstraintsTableStatistics",
					"jdk.ProtectionDomainCacheTableStatistics",
					"jdk.ThreadDump"
				}
			)
		);
		// Events related to compilation and optimization
		JDK_EVENT_GROUPS.put(
			"CompilationAndOptimization",
			new JdkEventGroup(
				"CompilationAndOptimization",
				"JDK - Compilation and optimization",
				"Events related to compilation and optimization.",
				new String[]{
					"jdk.Compilation",
					"jdk.CompilerPhase",
					"jdk.CompilationFailure",
					"jdk.CompilerInlining",
					"jdk.SweepCodeCache",
					"jdk.CodeCacheFull",
					"jdk.Deoptimization",
					"jdk.ExecuteVMOperation",
					"jdk.CompilerStatistics",
					"jdk.CompilerConfiguration",
					"jdk.CodeCacheStatistics",
					"jdk.CodeCacheConfiguration",
					"jdk.CodeSweeperStatistics",
					"jdk.CodeSweeperConfiguration"
				}
			)
		);
		// Events related to safe points
		JDK_EVENT_GROUPS.put(
			"Safepoints",
			new JdkEventGroup(
				"Safepoints",
				"JDK - Safepoints",
				"Events related to safe points.",
				new String[]{
					"jdk.SafepointBegin",
					"jdk.SafepointStateSynchronization",
					"jdk.SafepointCleanup",
					"jdk.SafepointCleanupTask",
					"jdk.SafepointEnd"
				}
			)
		);
		// Events related to flags
		JDK_EVENT_GROUPS.put(
			"Flags",
			new JdkEventGroup(
				"Flags",
				"JDK - Flags",
				"Events related to flags.",
				new String[]{
					"jdk.IntFlag",
					"jdk.UnsignedIntFlag",
					"jdk.LongFlag",
					"jdk.UnsignedLongFlag",
					"jdk.DoubleFlag",
					"jdk.BooleanFlag",
					"jdk.StringFlag"
				}
			)
		);
		// Events related to flushing and cleaning up
		JDK_EVENT_GROUPS.put(
			"FlushingAndCleaningUp",
			new JdkEventGroup(
				"FlushingAndCleaningUp",
				"JDK - Flushing and cleaning up",
				"Events related to flushing and cleaning up.",
				new String[]{
					"jdk.Flush",
					"jdk.SafepointCleanup",
					"jdk.SafepointCleanupTask"
				}
			)
		);
		// Events related to method profiling
		JDK_EVENT_GROUPS.put(
			"MethodProfiling",
			new JdkEventGroup(
				"MethodProfiling",
				"JDK - Method profiling",
				"Events related to performance profiling.",
				new String[]{
					"jdk.ExecutionSample",
					"jdk.MethodProfiling"
				}
			)
		);

		final String[] unknownJdkEvents = FlightRecorder.getFlightRecorder()
			.getEventTypes()
			.stream()
			.map(EventType::getName)
			.filter(it -> !KNOWN_JDK_EVENTS.contains(it))
			.toArray(String[]::new);
		if (!ArrayUtils.isEmpty(unknownJdkEvents)) {
			JDK_EVENT_GROUPS.put(
				"Other",
				new JdkEventGroup(
					"Other",
					"JDK - Other",
					"Other JDK events, that were not categorized.",
					unknownJdkEvents
				)
			);
		}

		EVENT_MAP = EVENTS_TYPES
			.stream()
			.collect(Collectors.toMap(Class::getName, Function.identity()));

		EVENT_MAP_BY_PACKAGE = EVENTS_TYPES
			.stream()
			.collect(
				Collectors.groupingBy(
					EvitaJfrEventRegistry::getMetricsGroup,
					Collectors.collectingAndThen(
						Collectors.toSet(),
						events -> {
							EventGroup groupInfo = KNOWN_EVITA_GROUPS.get(
								EvitaJfrEventRegistry.getMetricsGroup(events.iterator().next())
							);
							//noinspection unchecked
							return new EvitaEventGroup(
								groupInfo.value(),
								groupInfo.name(),
								groupInfo.description(),
								events.toArray(new Class[0])
							);
						}
					)
				)
			);
	}

	/**
	 * Gets the group name of the specified custom metrics event class.
	 * @param eventClass the custom metrics event class
	 * @return the group name of the specified custom metrics event class
	 */
	@Nonnull
	public static String getMetricsGroup(@Nonnull Class<? extends CustomMetricsExecutionEvent> eventClass) {
		final EventGroup group = ReflectionLookup.NO_CACHE_INSTANCE.getClassAnnotation(eventClass, EventGroup.class);
		Assert.isPremiseValid(
			group != null,
			"Custom metrics event class `" + eventClass.getName() + "` must be annotated with @EventGroup " +
				"annotation that defines the event group assignment."
		);
		final String name = group.value();
		KNOWN_EVITA_GROUPS.compute(name, (key, value) -> group.description().isBlank() && value != null ? value : group);
		return name;
	}

	/**
	 * Gets {@link Class} for specified custom event class name.
	 */
	@Nullable
	public static Class<? extends CustomMetricsExecutionEvent> getEventClass(@Nonnull String eventClassName) {
		return EVENT_MAP.get(eventClassName);
	}

	/**
	 * Gets a set of {@link Class}es located in a specified package.
	 */
	@Nonnull
	public static Optional<Class<? extends CustomMetricsExecutionEvent>[]> getEventClassesFromPackage(@Nonnull String eventPackageWithWildcard) {
		return ofNullable(EVENT_MAP_BY_PACKAGE.get(eventPackageWithWildcard)).map(EvitaEventGroup::events);
	}

	/**
	 * Returns a set of all registered classes fetched from the registry.
	 */
	@Nonnull
	public static Set<Class<? extends CustomMetricsExecutionEvent>> getEventClasses() {
		return EVENTS_TYPES;
	}

	/**
	 * Returns a map of all registered event packages fetched from the registry.
	 *
	 * @return a map of all registered event packages fetched from the registry
	 */
	@Nonnull
	public static Map<String, EvitaEventGroup> getEvitaEventGroups() {
		return EVENT_MAP_BY_PACKAGE;
	}

	/**
	 * Returns a map of grouped JDK events.
	 *
	 * @return a map of grouped JDK events
	 */
	@Nonnull
	public static Map<String, JdkEventGroup> getJdkEventGroups() {
		return JDK_EVENT_GROUPS;
	}

	/**
	 * Group contains information about JFR event group that could be enabled/disabled.
	 *
	 * @param name        the name of the event group
	 * @param description the description of the event group
	 * @param events  the names of the events in the group
	 */
	public record EvitaEventGroup(
		@Nonnull String id,
		@Nonnull String name,
		@Nullable String description,
		@Nonnull Class<? extends CustomMetricsExecutionEvent>[] events
	) {

		public EvitaEventGroup(
			@Nonnull String id,
			@Nonnull String name,
			@Nullable String description,
			@Nonnull Class<? extends CustomMetricsExecutionEvent>[] events
		) {
			this.id = id;
			this.name = name;
			this.description = description;
			this.events = events;
			for (Class<? extends CustomMetricsExecutionEvent> event : events) {
				FlightRecorder.register(event);
			}
		}
	}

	/**
	 * Group contains information about JFR event group that could be enabled/disabled.
	 *
	 * @param name        the name of the event group
	 * @param description the description of the event group
	 * @param events  the names of the events in the group
	 */
	public record JdkEventGroup(
		@Nonnull String id,
		@Nonnull String name,
		@Nullable String description,
		@Nonnull String[] events
	) {

		public JdkEventGroup(@Nonnull String id, @Nonnull String name, @Nullable String description, @Nonnull String[] events) {
			this.id = id;
			this.name = name;
			this.description = description;
			this.events = events;
			Collections.addAll(KNOWN_JDK_EVENTS, events);
		}
	}

}
