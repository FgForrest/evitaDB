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

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcEvitaAPI.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * Response to a server status request.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcReadyResponse}
 */
public final class GrpcReadyResponse extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcReadyResponse)
    GrpcReadyResponseOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcReadyResponse.newBuilder() to construct.
  private GrpcReadyResponse(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcReadyResponse() {
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcReadyResponse();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcReadyResponse(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    this();
    if (extensionRegistry == null) {
      throw new java.lang.NullPointerException();
    }
    com.google.protobuf.UnknownFieldSet.Builder unknownFields =
        com.google.protobuf.UnknownFieldSet.newBuilder();
    try {
      boolean done = false;
      while (!done) {
        int tag = input.readTag();
        switch (tag) {
          case 0:
            done = true;
            break;
          case 8: {

            ready_ = input.readBool();
            break;
          }
          default: {
            if (!parseUnknownField(
                input, unknownFields, extensionRegistry, tag)) {
              done = true;
            }
            break;
          }
        }
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw e.setUnfinishedMessage(this);
    } catch (java.io.IOException e) {
      throw new com.google.protobuf.InvalidProtocolBufferException(
          e).setUnfinishedMessage(this);
    } finally {
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcReadyResponse_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcReadyResponse_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcReadyResponse.class, io.evitadb.externalApi.grpc.generated.GrpcReadyResponse.Builder.class);
  }

  public static final int READY_FIELD_NUMBER = 1;
  private boolean ready_;
  /**
   * <pre>
   * Always true when returned
   * </pre>
   *
   * <code>bool ready = 1;</code>
   * @return The ready.
   */
  @java.lang.Override
  public boolean getReady() {
    return ready_;
  }

  private byte memoizedIsInitialized = -1;
  @java.lang.Override
  public final boolean isInitialized() {
    byte isInitialized = memoizedIsInitialized;
    if (isInitialized == 1) return true;
    if (isInitialized == 0) return false;

    memoizedIsInitialized = 1;
    return true;
  }

  @java.lang.Override
  public void writeTo(com.google.protobuf.CodedOutputStream output)
                      throws java.io.IOException {
    if (ready_ != false) {
      output.writeBool(1, ready_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (ready_ != false) {
      size += com.google.protobuf.CodedOutputStream
        .computeBoolSize(1, ready_);
    }
    size += unknownFields.getSerializedSize();
    memoizedSize = size;
    return size;
  }

  @java.lang.Override
  public boolean equals(final java.lang.Object obj) {
    if (obj == this) {
     return true;
    }
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcReadyResponse)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcReadyResponse other = (io.evitadb.externalApi.grpc.generated.GrpcReadyResponse) obj;

    if (getReady()
        != other.getReady()) return false;
    if (!unknownFields.equals(other.unknownFields)) return false;
    return true;
  }

  @java.lang.Override
  public int hashCode() {
    if (memoizedHashCode != 0) {
      return memoizedHashCode;
    }
    int hash = 41;
    hash = (19 * hash) + getDescriptor().hashCode();
    hash = (37 * hash) + READY_FIELD_NUMBER;
    hash = (53 * hash) + com.google.protobuf.Internal.hashBoolean(
        getReady());
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcReadyResponse parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReadyResponse parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReadyResponse parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReadyResponse parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReadyResponse parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReadyResponse parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReadyResponse parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReadyResponse parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReadyResponse parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReadyResponse parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReadyResponse parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReadyResponse parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }

  @java.lang.Override
  public Builder newBuilderForType() { return newBuilder(); }
  public static Builder newBuilder() {
    return DEFAULT_INSTANCE.toBuilder();
  }
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcReadyResponse prototype) {
    return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
  }
  @java.lang.Override
  public Builder toBuilder() {
    return this == DEFAULT_INSTANCE
        ? new Builder() : new Builder().mergeFrom(this);
  }

  @java.lang.Override
  protected Builder newBuilderForType(
      com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
    Builder builder = new Builder(parent);
    return builder;
  }
  /**
   * <pre>
   * Response to a server status request.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcReadyResponse}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcReadyResponse)
      io.evitadb.externalApi.grpc.generated.GrpcReadyResponseOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcReadyResponse_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcReadyResponse_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcReadyResponse.class, io.evitadb.externalApi.grpc.generated.GrpcReadyResponse.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcReadyResponse.newBuilder()
    private Builder() {
      maybeForceBuilderInitialization();
    }

    private Builder(
        com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
      super(parent);
      maybeForceBuilderInitialization();
    }
    private void maybeForceBuilderInitialization() {
      if (com.google.protobuf.GeneratedMessageV3
              .alwaysUseFieldBuilders) {
      }
    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      ready_ = false;

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcReadyResponse_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcReadyResponse getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcReadyResponse.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcReadyResponse build() {
      io.evitadb.externalApi.grpc.generated.GrpcReadyResponse result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcReadyResponse buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcReadyResponse result = new io.evitadb.externalApi.grpc.generated.GrpcReadyResponse(this);
      result.ready_ = ready_;
      onBuilt();
      return result;
    }

    @java.lang.Override
    public Builder clone() {
      return super.clone();
    }
    @java.lang.Override
    public Builder setField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.setField(field, value);
    }
    @java.lang.Override
    public Builder clearField(
        com.google.protobuf.Descriptors.FieldDescriptor field) {
      return super.clearField(field);
    }
    @java.lang.Override
    public Builder clearOneof(
        com.google.protobuf.Descriptors.OneofDescriptor oneof) {
      return super.clearOneof(oneof);
    }
    @java.lang.Override
    public Builder setRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        int index, java.lang.Object value) {
      return super.setRepeatedField(field, index, value);
    }
    @java.lang.Override
    public Builder addRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.addRepeatedField(field, value);
    }
    @java.lang.Override
    public Builder mergeFrom(com.google.protobuf.Message other) {
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcReadyResponse) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcReadyResponse)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcReadyResponse other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcReadyResponse.getDefaultInstance()) return this;
      if (other.getReady() != false) {
        setReady(other.getReady());
      }
      this.mergeUnknownFields(other.unknownFields);
      onChanged();
      return this;
    }

    @java.lang.Override
    public final boolean isInitialized() {
      return true;
    }

    @java.lang.Override
    public Builder mergeFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      io.evitadb.externalApi.grpc.generated.GrpcReadyResponse parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcReadyResponse) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private boolean ready_ ;
    /**
     * <pre>
     * Always true when returned
     * </pre>
     *
     * <code>bool ready = 1;</code>
     * @return The ready.
     */
    @java.lang.Override
    public boolean getReady() {
      return ready_;
    }
    /**
     * <pre>
     * Always true when returned
     * </pre>
     *
     * <code>bool ready = 1;</code>
     * @param value The ready to set.
     * @return This builder for chaining.
     */
    public Builder setReady(boolean value) {
      
      ready_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Always true when returned
     * </pre>
     *
     * <code>bool ready = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearReady() {
      
      ready_ = false;
      onChanged();
      return this;
    }
    @java.lang.Override
    public final Builder setUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.setUnknownFields(unknownFields);
    }

    @java.lang.Override
    public final Builder mergeUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.mergeUnknownFields(unknownFields);
    }


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcReadyResponse)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcReadyResponse)
  private static final io.evitadb.externalApi.grpc.generated.GrpcReadyResponse DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcReadyResponse();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcReadyResponse getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcReadyResponse>
      PARSER = new com.google.protobuf.AbstractParser<GrpcReadyResponse>() {
    @java.lang.Override
    public GrpcReadyResponse parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcReadyResponse(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcReadyResponse> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcReadyResponse> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcReadyResponse getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

