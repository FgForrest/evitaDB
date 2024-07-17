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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.grpc.generated;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
 * a way to create sessions and catalogs, and to update the catalog.
 * </pre>
 */

@io.grpc.stub.annotations.GrpcGenerated
public final class EvitaManagementServiceGrpc {

  private EvitaManagementServiceGrpc() {}

  public static final String SERVICE_NAME = "io.evitadb.externalApi.grpc.generated.EvitaManagementService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse> getServerStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ServerStatus",
      requestType = com.google.protobuf.Empty.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.google.protobuf.Empty,
      io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse> getServerStatusMethod() {
    io.grpc.MethodDescriptor<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse> getServerStatusMethod;
    if ((getServerStatusMethod = EvitaManagementServiceGrpc.getServerStatusMethod) == null) {
      synchronized (EvitaManagementServiceGrpc.class) {
        if ((getServerStatusMethod = EvitaManagementServiceGrpc.getServerStatusMethod) == null) {
          EvitaManagementServiceGrpc.getServerStatusMethod = getServerStatusMethod =
              io.grpc.MethodDescriptor.<com.google.protobuf.Empty, io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ServerStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaManagementServiceMethodDescriptorSupplier("ServerStatus"))
              .build();
        }
      }
    }
    return getServerStatusMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> getRestoreCatalogMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RestoreCatalog",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> getRestoreCatalogMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> getRestoreCatalogMethod;
    if ((getRestoreCatalogMethod = EvitaManagementServiceGrpc.getRestoreCatalogMethod) == null) {
      synchronized (EvitaManagementServiceGrpc.class) {
        if ((getRestoreCatalogMethod = EvitaManagementServiceGrpc.getRestoreCatalogMethod) == null) {
          EvitaManagementServiceGrpc.getRestoreCatalogMethod = getRestoreCatalogMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest, io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RestoreCatalog"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaManagementServiceMethodDescriptorSupplier("RestoreCatalog"))
              .build();
        }
      }
    }
    return getRestoreCatalogMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> getRestoreCatalogFromServerFileMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RestoreCatalogFromServerFile",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest,
      io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> getRestoreCatalogFromServerFileMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest, io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> getRestoreCatalogFromServerFileMethod;
    if ((getRestoreCatalogFromServerFileMethod = EvitaManagementServiceGrpc.getRestoreCatalogFromServerFileMethod) == null) {
      synchronized (EvitaManagementServiceGrpc.class) {
        if ((getRestoreCatalogFromServerFileMethod = EvitaManagementServiceGrpc.getRestoreCatalogFromServerFileMethod) == null) {
          EvitaManagementServiceGrpc.getRestoreCatalogFromServerFileMethod = getRestoreCatalogFromServerFileMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest, io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RestoreCatalogFromServerFile"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaManagementServiceMethodDescriptorSupplier("RestoreCatalogFromServerFile"))
              .build();
        }
      }
    }
    return getRestoreCatalogFromServerFileMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest,
      io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse> getListTaskStatusesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListTaskStatuses",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest,
      io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse> getListTaskStatusesMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest, io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse> getListTaskStatusesMethod;
    if ((getListTaskStatusesMethod = EvitaManagementServiceGrpc.getListTaskStatusesMethod) == null) {
      synchronized (EvitaManagementServiceGrpc.class) {
        if ((getListTaskStatusesMethod = EvitaManagementServiceGrpc.getListTaskStatusesMethod) == null) {
          EvitaManagementServiceGrpc.getListTaskStatusesMethod = getListTaskStatusesMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest, io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListTaskStatuses"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaManagementServiceMethodDescriptorSupplier("ListTaskStatuses"))
              .build();
        }
      }
    }
    return getListTaskStatusesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest,
      io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse> getGetTaskStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTaskStatus",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest,
      io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse> getGetTaskStatusMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest, io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse> getGetTaskStatusMethod;
    if ((getGetTaskStatusMethod = EvitaManagementServiceGrpc.getGetTaskStatusMethod) == null) {
      synchronized (EvitaManagementServiceGrpc.class) {
        if ((getGetTaskStatusMethod = EvitaManagementServiceGrpc.getGetTaskStatusMethod) == null) {
          EvitaManagementServiceGrpc.getGetTaskStatusMethod = getGetTaskStatusMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest, io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTaskStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaManagementServiceMethodDescriptorSupplier("GetTaskStatus"))
              .build();
        }
      }
    }
    return getGetTaskStatusMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest,
      io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse> getGetTaskStatusesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTaskStatuses",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest,
      io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse> getGetTaskStatusesMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest, io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse> getGetTaskStatusesMethod;
    if ((getGetTaskStatusesMethod = EvitaManagementServiceGrpc.getGetTaskStatusesMethod) == null) {
      synchronized (EvitaManagementServiceGrpc.class) {
        if ((getGetTaskStatusesMethod = EvitaManagementServiceGrpc.getGetTaskStatusesMethod) == null) {
          EvitaManagementServiceGrpc.getGetTaskStatusesMethod = getGetTaskStatusesMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest, io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTaskStatuses"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaManagementServiceMethodDescriptorSupplier("GetTaskStatuses"))
              .build();
        }
      }
    }
    return getGetTaskStatusesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest,
      io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse> getCancelTaskMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CancelTask",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest,
      io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse> getCancelTaskMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest, io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse> getCancelTaskMethod;
    if ((getCancelTaskMethod = EvitaManagementServiceGrpc.getCancelTaskMethod) == null) {
      synchronized (EvitaManagementServiceGrpc.class) {
        if ((getCancelTaskMethod = EvitaManagementServiceGrpc.getCancelTaskMethod) == null) {
          EvitaManagementServiceGrpc.getCancelTaskMethod = getCancelTaskMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest, io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CancelTask"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaManagementServiceMethodDescriptorSupplier("CancelTask"))
              .build();
        }
      }
    }
    return getCancelTaskMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest,
      io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse> getListFilesToFetchMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListFilesToFetch",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest,
      io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse> getListFilesToFetchMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest, io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse> getListFilesToFetchMethod;
    if ((getListFilesToFetchMethod = EvitaManagementServiceGrpc.getListFilesToFetchMethod) == null) {
      synchronized (EvitaManagementServiceGrpc.class) {
        if ((getListFilesToFetchMethod = EvitaManagementServiceGrpc.getListFilesToFetchMethod) == null) {
          EvitaManagementServiceGrpc.getListFilesToFetchMethod = getListFilesToFetchMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest, io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListFilesToFetch"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaManagementServiceMethodDescriptorSupplier("ListFilesToFetch"))
              .build();
        }
      }
    }
    return getListFilesToFetchMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest,
      io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse> getGetFileToFetchMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetFileToFetch",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest,
      io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse> getGetFileToFetchMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest, io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse> getGetFileToFetchMethod;
    if ((getGetFileToFetchMethod = EvitaManagementServiceGrpc.getGetFileToFetchMethod) == null) {
      synchronized (EvitaManagementServiceGrpc.class) {
        if ((getGetFileToFetchMethod = EvitaManagementServiceGrpc.getGetFileToFetchMethod) == null) {
          EvitaManagementServiceGrpc.getGetFileToFetchMethod = getGetFileToFetchMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest, io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetFileToFetch"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaManagementServiceMethodDescriptorSupplier("GetFileToFetch"))
              .build();
        }
      }
    }
    return getGetFileToFetchMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest,
      io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse> getFetchFileMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "FetchFile",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest,
      io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse> getFetchFileMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest, io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse> getFetchFileMethod;
    if ((getFetchFileMethod = EvitaManagementServiceGrpc.getFetchFileMethod) == null) {
      synchronized (EvitaManagementServiceGrpc.class) {
        if ((getFetchFileMethod = EvitaManagementServiceGrpc.getFetchFileMethod) == null) {
          EvitaManagementServiceGrpc.getFetchFileMethod = getFetchFileMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest, io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "FetchFile"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaManagementServiceMethodDescriptorSupplier("FetchFile"))
              .build();
        }
      }
    }
    return getFetchFileMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse> getDeleteFileMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeleteFile",
      requestType = io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest.class,
      responseType = io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest,
      io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse> getDeleteFileMethod() {
    io.grpc.MethodDescriptor<io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest, io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse> getDeleteFileMethod;
    if ((getDeleteFileMethod = EvitaManagementServiceGrpc.getDeleteFileMethod) == null) {
      synchronized (EvitaManagementServiceGrpc.class) {
        if ((getDeleteFileMethod = EvitaManagementServiceGrpc.getDeleteFileMethod) == null) {
          EvitaManagementServiceGrpc.getDeleteFileMethod = getDeleteFileMethod =
              io.grpc.MethodDescriptor.<io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest, io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeleteFile"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EvitaManagementServiceMethodDescriptorSupplier("DeleteFile"))
              .build();
        }
      }
    }
    return getDeleteFileMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static EvitaManagementServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EvitaManagementServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EvitaManagementServiceStub>() {
        @java.lang.Override
        public EvitaManagementServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EvitaManagementServiceStub(channel, callOptions);
        }
      };
    return EvitaManagementServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static EvitaManagementServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EvitaManagementServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EvitaManagementServiceBlockingStub>() {
        @java.lang.Override
        public EvitaManagementServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EvitaManagementServiceBlockingStub(channel, callOptions);
        }
      };
    return EvitaManagementServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static EvitaManagementServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EvitaManagementServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EvitaManagementServiceFutureStub>() {
        @java.lang.Override
        public EvitaManagementServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EvitaManagementServiceFutureStub(channel, callOptions);
        }
      };
    return EvitaManagementServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
   * a way to create sessions and catalogs, and to update the catalog.
   * </pre>
   */
  public static abstract class EvitaManagementServiceImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * Procedure used to obtain server status.
     * </pre>
     */
    public void serverStatus(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getServerStatusMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to restore a catalog from backup.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest> restoreCatalog(
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getRestoreCatalogMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to restore a catalog from backup.
     * </pre>
     */
    public void restoreCatalogFromServerFile(io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRestoreCatalogFromServerFileMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get listing of task statuses.
     * </pre>
     */
    public void listTaskStatuses(io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListTaskStatusesMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get detail of particular task status.
     * </pre>
     */
    public void getTaskStatus(io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTaskStatusMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get multiple details of particular task statuses.
     * </pre>
     */
    public void getTaskStatuses(io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTaskStatusesMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to cancel queued or running task.
     * </pre>
     */
    public void cancelTask(io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCancelTaskMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get listing of files available for fetching.
     * </pre>
     */
    public void listFilesToFetch(io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListFilesToFetchMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get single file by its id available for fetching.
     * </pre>
     */
    public void getFileToFetch(io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetFileToFetchMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get file contents
     * </pre>
     */
    public void fetchFile(io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getFetchFileMethod(), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to delete file contents
     * </pre>
     */
    public void deleteFile(io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeleteFileMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getServerStatusMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                com.google.protobuf.Empty,
                io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse>(
                  this, METHODID_SERVER_STATUS)))
          .addMethod(
            getRestoreCatalogMethod(),
            io.grpc.stub.ServerCalls.asyncClientStreamingCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest,
                io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse>(
                  this, METHODID_RESTORE_CATALOG)))
          .addMethod(
            getRestoreCatalogFromServerFileMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest,
                io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse>(
                  this, METHODID_RESTORE_CATALOG_FROM_SERVER_FILE)))
          .addMethod(
            getListTaskStatusesMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest,
                io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse>(
                  this, METHODID_LIST_TASK_STATUSES)))
          .addMethod(
            getGetTaskStatusMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest,
                io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse>(
                  this, METHODID_GET_TASK_STATUS)))
          .addMethod(
            getGetTaskStatusesMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest,
                io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse>(
                  this, METHODID_GET_TASK_STATUSES)))
          .addMethod(
            getCancelTaskMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest,
                io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse>(
                  this, METHODID_CANCEL_TASK)))
          .addMethod(
            getListFilesToFetchMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest,
                io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse>(
                  this, METHODID_LIST_FILES_TO_FETCH)))
          .addMethod(
            getGetFileToFetchMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest,
                io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse>(
                  this, METHODID_GET_FILE_TO_FETCH)))
          .addMethod(
            getFetchFileMethod(),
            io.grpc.stub.ServerCalls.asyncServerStreamingCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest,
                io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse>(
                  this, METHODID_FETCH_FILE)))
          .addMethod(
            getDeleteFileMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest,
                io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse>(
                  this, METHODID_DELETE_FILE)))
          .build();
    }
  }

  /**
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
   * a way to create sessions and catalogs, and to update the catalog.
   * </pre>
   */
  public static final class EvitaManagementServiceStub extends io.grpc.stub.AbstractAsyncStub<EvitaManagementServiceStub> {
    private EvitaManagementServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EvitaManagementServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EvitaManagementServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure used to obtain server status.
     * </pre>
     */
    public void serverStatus(com.google.protobuf.Empty request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getServerStatusMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to restore a catalog from backup.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest> restoreCatalog(
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncClientStreamingCall(
          getChannel().newCall(getRestoreCatalogMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * Procedure used to restore a catalog from backup.
     * </pre>
     */
    public void restoreCatalogFromServerFile(io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRestoreCatalogFromServerFileMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get listing of task statuses.
     * </pre>
     */
    public void listTaskStatuses(io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListTaskStatusesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get detail of particular task status.
     * </pre>
     */
    public void getTaskStatus(io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTaskStatusMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get multiple details of particular task statuses.
     * </pre>
     */
    public void getTaskStatuses(io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTaskStatusesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to cancel queued or running task.
     * </pre>
     */
    public void cancelTask(io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCancelTaskMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get listing of files available for fetching.
     * </pre>
     */
    public void listFilesToFetch(io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListFilesToFetchMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get single file by its id available for fetching.
     * </pre>
     */
    public void getFileToFetch(io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetFileToFetchMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to get file contents
     * </pre>
     */
    public void fetchFile(io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getFetchFileMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Procedure used to delete file contents
     * </pre>
     */
    public void deleteFile(io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest request,
        io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeleteFileMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
   * a way to create sessions and catalogs, and to update the catalog.
   * </pre>
   */
  public static final class EvitaManagementServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<EvitaManagementServiceBlockingStub> {
    private EvitaManagementServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EvitaManagementServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EvitaManagementServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure used to obtain server status.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse serverStatus(com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getServerStatusMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to restore a catalog from backup.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse restoreCatalogFromServerFile(io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRestoreCatalogFromServerFileMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get listing of task statuses.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse listTaskStatuses(io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListTaskStatusesMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get detail of particular task status.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse getTaskStatus(io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTaskStatusMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get multiple details of particular task statuses.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse getTaskStatuses(io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTaskStatusesMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to cancel queued or running task.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse cancelTask(io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCancelTaskMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get listing of files available for fetching.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse listFilesToFetch(io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListFilesToFetchMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get single file by its id available for fetching.
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse getFileToFetch(io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetFileToFetchMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to get file contents
     * </pre>
     */
    public java.util.Iterator<io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse> fetchFile(
        io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getFetchFileMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Procedure used to delete file contents
     * </pre>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse deleteFile(io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeleteFileMethod(), getCallOptions(), request);
    }
  }

  /**
   * <pre>
   * This service contains RPCs that could be called by gRPC clients on evitaDB. Main purpose of this service is to provide
   * a way to create sessions and catalogs, and to update the catalog.
   * </pre>
   */
  public static final class EvitaManagementServiceFutureStub extends io.grpc.stub.AbstractFutureStub<EvitaManagementServiceFutureStub> {
    private EvitaManagementServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EvitaManagementServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EvitaManagementServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Procedure used to obtain server status.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse> serverStatus(
        com.google.protobuf.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getServerStatusMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to restore a catalog from backup.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse> restoreCatalogFromServerFile(
        io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRestoreCatalogFromServerFileMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to get listing of task statuses.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse> listTaskStatuses(
        io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListTaskStatusesMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to get detail of particular task status.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse> getTaskStatus(
        io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTaskStatusMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to get multiple details of particular task statuses.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse> getTaskStatuses(
        io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTaskStatusesMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to cancel queued or running task.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse> cancelTask(
        io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCancelTaskMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to get listing of files available for fetching.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse> listFilesToFetch(
        io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListFilesToFetchMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to get single file by its id available for fetching.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse> getFileToFetch(
        io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetFileToFetchMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Procedure used to delete file contents
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse> deleteFile(
        io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeleteFileMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_SERVER_STATUS = 0;
  private static final int METHODID_RESTORE_CATALOG_FROM_SERVER_FILE = 1;
  private static final int METHODID_LIST_TASK_STATUSES = 2;
  private static final int METHODID_GET_TASK_STATUS = 3;
  private static final int METHODID_GET_TASK_STATUSES = 4;
  private static final int METHODID_CANCEL_TASK = 5;
  private static final int METHODID_LIST_FILES_TO_FETCH = 6;
  private static final int METHODID_GET_FILE_TO_FETCH = 7;
  private static final int METHODID_FETCH_FILE = 8;
  private static final int METHODID_DELETE_FILE = 9;
  private static final int METHODID_RESTORE_CATALOG = 10;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final EvitaManagementServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(EvitaManagementServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_SERVER_STATUS:
          serviceImpl.serverStatus((com.google.protobuf.Empty) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse>) responseObserver);
          break;
        case METHODID_RESTORE_CATALOG_FROM_SERVER_FILE:
          serviceImpl.restoreCatalogFromServerFile((io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogFromServerFileRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse>) responseObserver);
          break;
        case METHODID_LIST_TASK_STATUSES:
          serviceImpl.listTaskStatuses((io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusesResponse>) responseObserver);
          break;
        case METHODID_GET_TASK_STATUS:
          serviceImpl.getTaskStatus((io.evitadb.externalApi.grpc.generated.GrpcTaskStatusRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcTaskStatusResponse>) responseObserver);
          break;
        case METHODID_GET_TASK_STATUSES:
          serviceImpl.getTaskStatuses((io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcSpecifiedTaskStatusesResponse>) responseObserver);
          break;
        case METHODID_CANCEL_TASK:
          serviceImpl.cancelTask((io.evitadb.externalApi.grpc.generated.GrpcCancelTaskRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcCancelTaskResponse>) responseObserver);
          break;
        case METHODID_LIST_FILES_TO_FETCH:
          serviceImpl.listFilesToFetch((io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFilesToFetchResponse>) responseObserver);
          break;
        case METHODID_GET_FILE_TO_FETCH:
          serviceImpl.getFileToFetch((io.evitadb.externalApi.grpc.generated.GrpcFileToFetchRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFileToFetchResponse>) responseObserver);
          break;
        case METHODID_FETCH_FILE:
          serviceImpl.fetchFile((io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse>) responseObserver);
          break;
        case METHODID_DELETE_FILE:
          serviceImpl.deleteFile((io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchRequest) request,
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcDeleteFileToFetchResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_RESTORE_CATALOG:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.restoreCatalog(
              (io.grpc.stub.StreamObserver<io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class EvitaManagementServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    EvitaManagementServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaManagementAPI.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("EvitaManagementService");
    }
  }

  private static final class EvitaManagementServiceFileDescriptorSupplier
      extends EvitaManagementServiceBaseDescriptorSupplier {
    EvitaManagementServiceFileDescriptorSupplier() {}
  }

  private static final class EvitaManagementServiceMethodDescriptorSupplier
      extends EvitaManagementServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    EvitaManagementServiceMethodDescriptorSupplier(String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (EvitaManagementServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new EvitaManagementServiceFileDescriptorSupplier())
              .addMethod(getServerStatusMethod())
              .addMethod(getRestoreCatalogMethod())
              .addMethod(getRestoreCatalogFromServerFileMethod())
              .addMethod(getListTaskStatusesMethod())
              .addMethod(getGetTaskStatusMethod())
              .addMethod(getGetTaskStatusesMethod())
              .addMethod(getCancelTaskMethod())
              .addMethod(getListFilesToFetchMethod())
              .addMethod(getGetFileToFetchMethod())
              .addMethod(getFetchFileMethod())
              .addMethod(getDeleteFileMethod())
              .build();
        }
      }
    }
    return result;
  }
}
