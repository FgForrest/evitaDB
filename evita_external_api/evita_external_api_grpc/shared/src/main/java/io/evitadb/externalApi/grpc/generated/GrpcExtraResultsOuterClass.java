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

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcExtraResults.proto

package io.evitadb.externalApi.grpc.generated;

public final class GrpcExtraResultsOuterClass {
  private GrpcExtraResultsOuterClass() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_GrpcBucket_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_GrpcBucket_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatisticsType_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatisticsType_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatisticsType_FacetGroupStatisticsEntry_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatisticsType_FacetGroupStatisticsEntry_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatistics_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatistics_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetStatistics_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetStatistics_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfos_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfos_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfo_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfo_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcQueryTelemetry_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcQueryTelemetry_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_AttributeHistogramEntry_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_AttributeHistogramEntry_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_HierarchyEntry_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_HierarchyEntry_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\026GrpcExtraResults.proto\022%io.evitadb.ext" +
      "ernalApi.grpc.generated\032\036google/protobuf" +
      "/wrappers.proto\032\020GrpcEntity.proto\032\030GrpcE" +
      "vitaDataTypes.proto\032\017GrpcEnums.proto\"\373\002\n" +
      "\rGrpcHistogram\022B\n\003min\030\001 \001(\01325.io.evitadb" +
      ".externalApi.grpc.generated.GrpcBigDecim" +
      "al\022B\n\003max\030\002 \001(\01325.io.evitadb.externalApi" +
      ".grpc.generated.GrpcBigDecimal\022\024\n\014overal" +
      "lCount\030\003 \001(\005\022P\n\007buckets\030\004 \003(\0132?.io.evita" +
      "db.externalApi.grpc.generated.GrpcHistog" +
      "ram.GrpcBucket\032z\n\nGrpcBucket\022\r\n\005index\030\001 " +
      "\001(\005\022H\n\tthreshold\030\002 \001(\01325.io.evitadb.exte" +
      "rnalApi.grpc.generated.GrpcBigDecimal\022\023\n" +
      "\013occurrences\030\003 \001(\005\"\231\002\n\034GrpcFacetGroupSta" +
      "tisticsType\022{\n\024facetGroupStatistics\030\001 \003(" +
      "\0132].io.evitadb.externalApi.grpc.generate" +
      "d.GrpcFacetGroupStatisticsType.FacetGrou" +
      "pStatisticsEntry\032|\n\031FacetGroupStatistics" +
      "Entry\022\013\n\003key\030\001 \001(\005\022N\n\005value\030\002 \001(\0132?.io.e" +
      "vitadb.externalApi.grpc.generated.GrpcFa" +
      "cetGroupStatistics:\0028\001\"\275\002\n\030GrpcFacetGrou" +
      "pStatistics\022\025\n\rreferenceName\030\001 \001(\t\022X\n\024gr" +
      "oupEntityReference\030\002 \001(\0132:.io.evitadb.ex" +
      "ternalApi.grpc.generated.GrpcEntityRefer" +
      "ence\022L\n\013groupEntity\030\003 \001(\01327.io.evitadb.e" +
      "xternalApi.grpc.generated.GrpcSealedEnti" +
      "ty\022\r\n\005count\030\004 \001(\005\022S\n\017facetStatistics\030\005 \003" +
      "(\0132:.io.evitadb.externalApi.grpc.generat" +
      "ed.GrpcFacetStatistics\"\214\002\n\023GrpcFacetStat" +
      "istics\022X\n\024facetEntityReference\030\001 \001(\0132:.i" +
      "o.evitadb.externalApi.grpc.generated.Grp" +
      "cEntityReference\022L\n\013facetEntity\030\002 \001(\01327." +
      "io.evitadb.externalApi.grpc.generated.Gr" +
      "pcSealedEntity\022\021\n\trequested\030\003 \001(\010\022\r\n\005cou" +
      "nt\030\004 \001(\005\022+\n\006impact\030\005 \001(\0132\033.google.protob" +
      "uf.Int32Value\"Z\n\016GrpcLevelInfos\022H\n\nlevel" +
      "Infos\030\001 \003(\01324.io.evitadb.externalApi.grp" +
      "c.generated.GrpcLevelInfo\"\224\002\n\rGrpcLevelI" +
      "nfo\022S\n\017entityReference\030\001 \001(\0132:.io.evitad" +
      "b.externalApi.grpc.generated.GrpcEntityR" +
      "eference\022G\n\006entity\030\002 \001(\01327.io.evitadb.ex" +
      "ternalApi.grpc.generated.GrpcSealedEntit" +
      "y\022\023\n\013cardinality\030\003 \001(\005\022P\n\022childrenStatis" +
      "tics\030\004 \003(\01324.io.evitadb.externalApi.grpc" +
      ".generated.GrpcLevelInfo\"\335\001\n\022GrpcQueryTe" +
      "lemetry\022H\n\toperation\030\001 \001(\01625.io.evitadb." +
      "externalApi.grpc.generated.GrpcQueryPhas" +
      "e\022\r\n\005start\030\002 \001(\003\022H\n\005steps\030\003 \003(\01329.io.evi" +
      "tadb.externalApi.grpc.generated.GrpcQuer" +
      "yTelemetry\022\021\n\targuments\030\004 \003(\t\022\021\n\tspentTi" +
      "me\030\005 \001(\003\"\325\006\n\020GrpcExtraResults\022Q\n\023attribu" +
      "teHistograms\030\001 \003(\01324.io.evitadb.external" +
      "Api.grpc.generated.GrpcHistogram\022k\n\022attr" +
      "ibuteHistogram\030\002 \003(\0132O.io.evitadb.extern" +
      "alApi.grpc.generated.GrpcExtraResults.At" +
      "tributeHistogramEntry\022L\n\016priceHistogram\030" +
      "\003 \001(\01324.io.evitadb.externalApi.grpc.gene" +
      "rated.GrpcHistogram\022]\n\024facetGroupStatist" +
      "ics\030\004 \003(\0132?.io.evitadb.externalApi.grpc." +
      "generated.GrpcFacetGroupStatistics\022L\n\rse" +
      "lfHierarchy\030\005 \001(\01325.io.evitadb.externalA" +
      "pi.grpc.generated.GrpcLevelInfos\022Y\n\thier" +
      "archy\030\006 \003(\0132F.io.evitadb.externalApi.grp" +
      "c.generated.GrpcExtraResults.HierarchyEn" +
      "try\022Q\n\016queryTelemetry\030\007 \001(\01329.io.evitadb" +
      ".externalApi.grpc.generated.GrpcQueryTel" +
      "emetry\032o\n\027AttributeHistogramEntry\022\013\n\003key" +
      "\030\001 \001(\t\022C\n\005value\030\002 \001(\01324.io.evitadb.exter" +
      "nalApi.grpc.generated.GrpcHistogram:\0028\001\032" +
      "g\n\016HierarchyEntry\022\013\n\003key\030\001 \001(\t\022D\n\005value\030" +
      "\002 \001(\01325.io.evitadb.externalApi.grpc.gene" +
      "rated.GrpcLevelInfos:\0028\001B\002P\001b\006proto3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
          com.google.protobuf.WrappersProto.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcEntity.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor(),
        });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_descriptor,
        new java.lang.String[] { "Min", "Max", "OverallCount", "Buckets", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_GrpcBucket_descriptor =
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_descriptor.getNestedTypes().get(0);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_GrpcBucket_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcHistogram_GrpcBucket_descriptor,
        new java.lang.String[] { "Index", "Threshold", "Occurrences", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatisticsType_descriptor =
      getDescriptor().getMessageTypes().get(1);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatisticsType_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatisticsType_descriptor,
        new java.lang.String[] { "FacetGroupStatistics", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatisticsType_FacetGroupStatisticsEntry_descriptor =
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatisticsType_descriptor.getNestedTypes().get(0);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatisticsType_FacetGroupStatisticsEntry_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatisticsType_FacetGroupStatisticsEntry_descriptor,
        new java.lang.String[] { "Key", "Value", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatistics_descriptor =
      getDescriptor().getMessageTypes().get(2);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatistics_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetGroupStatistics_descriptor,
        new java.lang.String[] { "ReferenceName", "GroupEntityReference", "GroupEntity", "Count", "FacetStatistics", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetStatistics_descriptor =
      getDescriptor().getMessageTypes().get(3);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetStatistics_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcFacetStatistics_descriptor,
        new java.lang.String[] { "FacetEntityReference", "FacetEntity", "Requested", "Count", "Impact", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfos_descriptor =
      getDescriptor().getMessageTypes().get(4);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfos_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfos_descriptor,
        new java.lang.String[] { "LevelInfos", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfo_descriptor =
      getDescriptor().getMessageTypes().get(5);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfo_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfo_descriptor,
        new java.lang.String[] { "EntityReference", "Entity", "Cardinality", "ChildrenStatistics", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcQueryTelemetry_descriptor =
      getDescriptor().getMessageTypes().get(6);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcQueryTelemetry_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcQueryTelemetry_descriptor,
        new java.lang.String[] { "Operation", "Start", "Steps", "Arguments", "SpentTime", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_descriptor =
      getDescriptor().getMessageTypes().get(7);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_descriptor,
        new java.lang.String[] { "AttributeHistograms", "AttributeHistogram", "PriceHistogram", "FacetGroupStatistics", "SelfHierarchy", "Hierarchy", "QueryTelemetry", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_AttributeHistogramEntry_descriptor =
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_descriptor.getNestedTypes().get(0);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_AttributeHistogramEntry_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_AttributeHistogramEntry_descriptor,
        new java.lang.String[] { "Key", "Value", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_HierarchyEntry_descriptor =
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_descriptor.getNestedTypes().get(1);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_HierarchyEntry_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcExtraResults_HierarchyEntry_descriptor,
        new java.lang.String[] { "Key", "Value", });
    com.google.protobuf.WrappersProto.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEntity.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
