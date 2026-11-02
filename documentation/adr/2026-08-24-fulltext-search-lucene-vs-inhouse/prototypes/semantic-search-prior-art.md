# Semantic search: prior art in six engines and one book

Supporting material of the fulltext record ([`../README.md`](../README.md)), collected on 2026-11-02 while the
semantic-search design (delivery phase F2) was settled. It is the evidence behind the 2026-11-02 rows of the
record's *Decisions taken* and *Rejected outright* tables; the decisions themselves and their reasoning live in the
record, not here.

**Sources.** Local checkouts of Elasticsearch, OpenSearch (core only — the neural-search and ml-commons plugins are
not in it, so OpenSearch facts come from its documentation and the plugins' sources on GitHub), Vespa, Solr,
Meilisearch and Typesense, read on 2026-11-02, plus the reference implementation of *AI-Powered Search* (Grainger,
Turnbull, Irwin; Manning 2025) — its code, not its text. Every claim is labelled with a `path:line` in the checkout,
a documentation URL, an issue or PR number, or marked *inferred* / *unverified*. Line numbers drift with the
checkouts; the claims were true of the checkouts on that day.

**How to read it.** Part 1 answers the same thirteen questions per engine (where an embedder is defined, where its
key lives, user-provided vectors, which text is embedded, when and how failures behave, re-embedding, storage,
query-time embedding, document vs. query asymmetry, chunking, mixing, providers, hybrid). Part 2 looks for the
*stated* reasons behind synchronous embedding in the write path. Part 3 covers how scores of several fields and of
the text and vector legs are combined, and what *AI-Powered Search* does about it.

## Part 1 — how the engines take embeddings in

### Embedders in Elasticsearch and OpenSearch

**Sources.** ES: local checkout `oss/elasticsearch` (main, includes the newer `semantic` field, bfloat16 and Jina-v5 defaults). OpenSearch: the core checkout has none of neural-search / ml-commons (grep `text_embedding|NeuralQueryBuilder|knn_vector` → 0 hits), so OS facts come from docs (3.x) and the plugin repos on GitHub `main`, read raw. Line numbers are given only for local files.

**Abbreviations.** `inf/` = `x-pack/plugin/inference/src/main/java/org/elasticsearch/xpack/inference/`; `srv/` = `server/src/main/java/org/elasticsearch/`; `SFM` = `inf/mapper/SemanticFieldMapper.java`; `STFM` = `inf/mapper/SemanticTextFieldMapper.java`; `SBIAF` = `inf/action/filter/ShardBulkInferenceActionFilter.java`.

#### Elasticsearch

1. **Definition & identity.** An *inference endpoint* is a cluster-level object (`PUT _inference/<task>/<inference_id>`) kept by `ModelRegistry` in the `.inference` system index. A `semantic_text` field names it with `inference_id`; when that is omitted, it defaults to the index setting `index.semantic_text.default_inference_id`, then to a preconfigured Jina-v5 or ELSER endpoint. An optional `search_inference_id` overrides the endpoint at query time. (source: inf/InferenceIndex.java:24; SFM:215-253; STFM:144-195)
2. **Credentials.** Secrets are a separate `ModelSecrets` (`secret_settings`) document in the `.secrets-inference` system index. GET returns only `ModelConfigurations` via `toFilteredXContent`, which has no secrets member, and a mapping read-back shows only `inference_id` and `model_settings` (service, task_type, dims, similarity, element_type). Creating an endpoint makes one validation call to the provider. (source: inf/InferenceSecretsIndex.java:25; srv/inference/ModelSecrets.java:27; srv/inference/ModelConfigurations.java:252; inf/services/validation/SimpleEmbeddingServiceIntegrationValidator.java:52)
3. **User-provided vectors.** `dense_vector`: `dims` (≤ 4096, inferred from the first doc if omitted), `similarity`, element_type float/byte/bit/bfloat16. No model provenance is stored; the only query check is dimension ("The query vector has a different number of dimensions…"). (source: srv/index/mapper/vectors/DenseVectorFieldMapper.java:285,848)
4. **Which text.** The field's own value plus every field that `copy_to`s into it (new format only; the legacy format refuses copy_to). Each source value is a separate input, chunked and embedded on its own, and the chunks are grouped per source field. Nothing is concatenated or templated. (source: SBIAF:666-741,997; STFM:328-333)
5. **Timing & failure.** An action filter on the shard-bulk action runs inference on the coordinator **before** the shard write, and the bulk response waits for it. The provider calls themselves are async and batched up to `indices.inference.batch_size` (default 1 MB). If inference fails for any field, `item.abort` rejects that document; the rest of the bulk proceeds, and there is no "index without the vector" mode. HTTP providers retry with backoff within `xpack.inference.http.retry.timeout`. Scripted updates on indices that have semantic_text fields are refused. (source: SBIAF:108-121,191-203,424-433,608-617,969-974; inf/external/http/retry/RetrySettings.java:20-34)
6. **Re-embedding & model change.** There is no content hash. Any write whose source carries the text re-embeds it; inference is skipped only when the source already carries `_inference_fields.<field>`. A partial update embeds only the fields present in the partial doc, and the update API re-reads the old source without inference fields "to ensure embeddings are recomputed". `inference_id` can be updated. Before any document has been indexed (no `model_settings` yet) it switches freely. After that, the new endpoint must match task_type, dims, similarity and element_type, or the update is refused. Either way, **nothing is re-embedded**: vectors from the old model stay in the index, and model identity itself is never compared. (source: SBIAF:652-660,672-704; srv/action/update/TransportUpdateAction.java:249-250; SFM:431-490; srv/inference/EndpointClusterState.java:280-285)
7. **Storage.** Chunks are stored as offsets into the original text, not as copies. They and their embeddings live in nested `<field>.inference.chunks` Lucene fields. `_inference_fields` is stripped from the stored `_source` and rebuilt only when requested (`exclude_vectors: false` / explicit include), so clients don't see the vectors by default. Peer-recovery/CCR change snapshots do include them, so replicas reuse the stored vectors. Reindex fetches with `FETCH_ALL_SOURCE_EXCLUDE_INFERENCE_FIELDS`, which keeps dense vectors and drops inference fields. The destination therefore **re-embeds** semantic_text, while hand-supplied `dense_vector` values are copied verbatim (I did not verify the `needsVectors` condition that gates this branch). A restart does not re-run inference. (source: STFM:261-279; srv/index/mapper/SourceFieldMapper.java:568-571,605-616; srv/search/fetch/subphase/FetchSourcePhase.java:92-101; srv/index/engine/SearchBasedChangesSnapshot.java:148-158; srv/search/fetch/subphase/FetchSourceContext.java:69-75; modules/reindex/src/main/java/org/elasticsearch/reindex/AbstractAsyncBulkByPaginatedSearchAction.java:342-350)
8. **Query.** `{"semantic":{"field":"f","query":"…"}}`, or a plain `match`/`knn`/`sparse_vector` on a semantic_text field, which interceptors rewrite. On a `dense_vector`, use `knn.query_vector_builder.text_embedding{model_id,model_text}` or `embedding{inference_id,input,timeout}`. Inference runs in the coordinator's async rewrite phase, once per distinct inference id per request, and the result is shipped to the shards. I found no cross-request embedding cache; the only caches are for endpoint configs, preferences and OAuth tokens. If inference fails, the search fails. (source: inf/queries/InferenceQueryUtils.java:547-690; inf/queries/SemanticKnnVectorQueryRewriteInterceptor.java:35-51; x-pack/plugin/core/src/main/java/org/elasticsearch/xpack/core/ml/vectors/TextEmbeddingQueryVectorBuilder.java:43-117; inf/vectors/EmbeddingQueryVectorBuilder.java:46-50)
9. **Asymmetry.** Every call carries an `InputType`: `INTERNAL_INGEST` when indexing, `INTERNAL_SEARCH` when querying. Services translate it into the provider's input_type (Cohere, Voyage, Jina, Vertex, Bedrock, Nvidia…), and ML-node models get ingest/search `prefix_strings`. `search_inference_id` can name a different endpoint for queries. (source: SBIAF:464,546; InferenceQueryUtils.java:590; inf/services/elasticsearch/BaseElasticsearchInternalService.java:317-318; TextEmbeddingQueryVectorBuilder.java:117)
10. **Chunking.** Built in. Chunking is configured on the endpoint and can be overridden per field with `chunking_settings`. Strategies are word, sentence, recursive and none. The default is sentence chunking, max 250 words with 1 sentence of overlap; the old default was word chunking, 250 words with 100 overlap. A document scores its best chunk (nested query, `ScoreMode.Max`). (source: srv/inference/ChunkingStrategy.java:16-20; x-pack/plugin/core/src/main/java/org/elasticsearch/xpack/core/inference/chunking/ChunkingSettingsBuilder.java:17-19; SFM:1175)
11. **Mixing.** Possible on `semantic_text`: a document may arrive with pre-computed `_inference_fields.<field>`, inference is then skipped, and the mapper accepts it only if its inference_id matches the field's and its model_settings are compatible. Reindex and recovery use this path; whether it is a documented user feature is unverified. On a plain `dense_vector`, the client supplies vectors or an ingest `inference` processor computes them. (source: SBIAF:652-660; SFM:829-866)
12. **Providers.** About 30 services in total. Local: ML-node models (ELSER, E5, uploaded models) and the Elastic Inference Service. Remote: OpenAI, Azure OpenAI/AI Studio, Cohere, Google AI Studio/Vertex, Bedrock, SageMaker, Hugging Face, Jina, Voyage, Mistral, Nvidia, watsonx, Alibaba, and others. A `custom` HTTP service takes templated requests. Plugins can add more through `InferenceServiceExtension`. (source: inf/services/ listing; srv/inference/InferenceServiceExtension.java:24)
13. **Hybrid.** Fusion is expressed in the request's retriever tree: `rrf` (rank_constant 60) or `linear` (weights + normalizer) over `standard` and `knn` retrievers. The legacy top-level `query` + `knn` combination simply sums the scores. (source: x-pack/plugin/rank-rrf/src/main/java/org/elasticsearch/xpack/rank/rrf/RRFRetrieverBuilder.java:61-68; …/rank/linear/LinearRetrieverBuilder.java:66)

**What is distinctive about ES**
- Embedding is a *field type*: `semantic_text` owns inference, chunking and storage, and the bulk path rewrites the document before the shard ever sees it.
- The field's `model_settings` are frozen by the first document, and every entry point is checked against them (mapping update, pre-computed docs). The check covers shape only, never model identity.
- Embeddings are not in `_source`; they are rebuilt from Lucene on demand, so `_source` stays small and clients don't get vectors by default.
- Re-embedding is driven by presence of the text, not by a change in it. There is no dirty check, and reindex deliberately re-embeds.
- `InputType` ingest/search runs through every provider uniformly; fusion lives in retrievers.

#### OpenSearch

1. **Definition & identity.** An ml-commons *model* is a cluster object with a generated, opaque `model_id`; it is registered, then deployed. A remote model wraps a connector, which is either standalone (connector index) or inline (model index). The `text_embedding` processor, the `neural` query and the `semantic` field (3.1+) all reference it by `model_id`. The semantic field also accepts `search_model_id`, or `model_selection`, which is resolved from a cluster setting once, when the mapping is written. (docs: https://docs.opensearch.org/latest/ml-commons-plugin/remote-models/connectors/ ; https://docs.opensearch.org/latest/mappings/supported-field-types/semantic/)
2. **Credentials.** The connector's `credential` map is encrypted with AES/GCM under a random 32-byte key that ml-commons auto-creates in a system index. GET connector strips credentials: the `getConnector` overload that the GET action uses calls `removeCredential()`, and the docs' example response has no credential field. (docs: https://docs.opensearch.org/3.0/ml-commons-plugin/remote-models/blueprints/ ; source: ml-commons main, ConnectorAccessControlHelper.java, AbstractConnector.java; docs: …/api/connector-apis/get-connector/)
3. **User-provided vectors.** `knn_vector`: `dimension` 1–16,000, `data_type` float/half_float/byte/binary, `space_type`, `method`, `mode`, `compression_level`. Its `model_id` names a *trained ANN* model (IVF/PQ), not an embedding model, so no embedding provenance is stored. Query-vector dimension validation: unverified. (docs: https://docs.opensearch.org/latest/mappings/supported-field-types/knn-vector/)
4. **Which text.** The `text_embedding` processor's `field_map` maps one input field to one vector field (nested paths are supported); concatenation is not documented. A `semantic` field embeds only its own raw value. (docs: https://docs.opensearch.org/latest/ingest-pipelines/processors/text-embedding/)
5. **Timing & failure.** Inference runs synchronously in the ingest pipeline before indexing (`batch_size` default 1). If it fails, the document fails unless `ignore_failure`/`on_failure` is set; if the input field is missing, the doc is indexed without a vector. A semantic field is served by a system processor (`system_ingest_processor_semantic_field`) that runs automatically, and on failure it attaches the exception to the affected document only. (docs: text-embedding page; source: neural-search main, SemanticFieldProcessor.java)
6. **Re-embedding & model change.** By default every ingest re-embeds. With `skip_existing` (processor) or `skip_existing_embedding` (semantic field), the stored doc is fetched by multi-get, and its embedding is reused only if the raw text, the model id and, when chunking is on, the chunk texts are all unchanged. Changing the model_id in a pipeline is not checked and triggers no re-embedding, so the old vectors remain until a manual reindex. The semantic `model_id` is updatable; existing docs are re-embedded only when re-ingested. Compatibility checks on that update are unverified. (source: SemanticFieldProcessor.java; docs: semantic page)
7. **Storage.** Vectors live in `knn_vector`. Since 3.0, derived source is on by default (`index.knn.derived_source.enabled`): stored `_source` holds a placeholder that is filled back in on read, so clients get the vectors. A semantic field writes `<field>_semantic_info` holding the chunk *text*, the embedding and `model{id,name,type}` (not indexed). A restart does not re-run inference. Replicas receiving the post-pipeline document, and reindex re-running the destination pipeline / system processor, follow from the ingest architecture but are unverified. (docs: https://docs.opensearch.org/3.0/vector-search/settings ; semantic page; source: SemanticFieldProcessor.java)
8. **Query.** `neural{query_text|query_image, model_id, k|min_score|max_distance, filter}`; `model_id` is optional on semantic fields. The embedding is computed in the async query rewrite. It is reused only inside that query instance (`modelIdToVectorSupplierMap`); I found no cross-request cache. A failure ("Failed to rewrite the neural query against the semantic field…") fails the search. Neural queries on semantic fields don't work in cross-cluster search. (docs: https://docs.opensearch.org/latest/query-dsl/specialized/neural/ ; source: neural-search main, NeuralQueryBuilder.java)
9. **Asymmetry.** The model's `model_config` has `query_prefix`/`passage_prefix`, and `content_type` (passage/query) selects between them. The semantic field and the neural query apply them automatically; whether the `text_embedding` processor does is unverified. `search_model_id` can name a separate query model. (docs: https://docs.opensearch.org/latest/tutorials/vector-search/semantic-search/semantic-search-asymmetric/)
10. **Chunking.** The `text_chunking` processor offers `fixed_token_length` (384 tokens, overlap 0, max 100 chunks), `fixed_char_length` (2048 chars) and `delimiter` (`\n\n`). It can be cascaded and outputs a string list, which a later `text_embedding` turns into a nested knn_vector; the docs recommend a nested query with `score_mode: max`. On a semantic field, `chunking` is off by default (`true` means fixed_token_length defaults), and the neural query rewrites to a nested query with `ScoreMode.Max`. (docs: https://docs.opensearch.org/latest/ingest-pipelines/processors/text-chunking/ ; https://docs.opensearch.org/2.18/search-plugins/text-chunking/ ; source: NeuralQueryBuilder.java)
11. **Mixing.** Not on a semantic field: the processor overwrites any incoming semantic_info. With `knn_vector` + pipeline it is possible: a doc without the input field gets no computed vector, and a processor `if` can protect user vectors. (source: SemanticFieldProcessor.java; docs: text-embedding page)
12. **Providers.** Local: pretrained Hugging Face sentence-transformers (TorchScript/ONNX) and custom uploads, run on ML nodes. Remote, through connectors: SageMaker, OpenAI, Cohere, Bedrock, Vertex AI, plus more blueprints. New providers are added as JSON blueprints (request templates, pre/post-process functions), with no Java. (docs: connectors page)
13. **Hybrid.** A `hybrid` query plus a *search pipeline*. Either the `normalization-processor` (min_max default, l2, z_score; mean combination, weights) or the `score-ranker-processor` RRF (2.19+, rank_constant 60, weights). The fusion lives in the pipeline, not the query. (docs: https://docs.opensearch.org/latest/search-plugins/search-pipelines/normalization-processor/ ; …/score-ranker-processor/)

**What is distinctive about OS**
- Embedding is pipeline-shaped: ingest processors, or a system processor auto-attached to `semantic` fields; the model is an opaque `model_id`.
- Reuse is decided per document by comparing against the stored copy (text + model id + chunks), which is a real dirty check, but opt-in.
- The semantic field records the producing model `{id,name,type}` beside each embedding; ES records shape only.
- Chunk text is copied into the document (ES keeps offsets).
- Providers are data (connector blueprints) rather than code; fusion lives in search pipelines rather than retrievers.

### Embedders in Vespa and Solr — research for evitaDB semantic search

Sources: local checkouts `oss/vespa`, `oss/solr` (paths relative to each repo root), plus official docs where labelled.

#### Vespa

1. **Where defined / identity.** An embedder is a `<component id=… type="hugging-face-embedder">` in a container cluster of `services.xml`, shared across schemas (docs: https://docs.vespa.ai/en/embedding.html). Fields reference it by component id (`input title | embed e5 | attribute`); queries use `embed(e5, @text)`, and the id may be omitted when exactly one embedder exists (source: container-search/src/main/java/com/yahoo/search/schema/internal/TensorConverter.java:71-93). At deploy time the only check is that the id exists (source: config-model/src/main/java/com/yahoo/vespa/model/application/validation/EmbedExpressionValidator.java:66-70).
2. **Credentials.** Remote embedders take `api-key-secret-ref`, a reference name, never the key (source: model-integration/src/main/java/ai/vespa/embedding/OpenAIEmbedder.java:50, VoyageAIEmbedder.java:55-57). Self-hosted, the default `Secrets` reads env var `VESPA_SECRET_<NAME>` (source: container-disc/src/main/java/com/yahoo/container/jdisc/SecretsProvider.java:26-53). Vespa Cloud uses its secret store instead (docs: embedding.html). The key never sits in the application package, so reading config back cannot leak it.
3. **User-provided vectors.** These are a document field `tensor<float>(x[N])` with `attribute { distance-metric: … }` and an optional `index { hnsw … }` (source: config-model/src/test/derived/hnsw_index/test.sd:4-15). The query tensor must be declared in the rank profile's inputs. Compatibility is tensor-type assignability only (source: TensorConverter.java:46-47). Nothing records which model produced a vector.
4. **What text is embedded.** `embed` is an indexing-language expression, so its input is any string expression: one field, or a concatenation such as `input title . " " . input body | embed`. `for_each` can prefix every chunk, e.g. with the title (source: indexinglanguage/src/test/java/com/yahoo/vespa/indexinglanguage/EmbeddingScriptTestCase.java:108). The input must be `string` or `array<string>` (source: indexinglanguage/src/main/java/com/yahoo/vespa/indexinglanguage/expressions/EmbedExpression.java:104-108).
5. **Indexing timing / failure.** Embedding runs synchronously inside the feed operation, in the container's indexing docproc. Optional dynamic cross-document batching is available (source: EmbedExpression.java:69-80, 285-299). Failures fail the whole operation: they map to `Progress.INVALID_INPUT/OVERLOAD/TIMEOUT` and the document is never indexed without its vector (source: docprocs/src/main/java/com/yahoo/docprocs/indexing/IndexingProcessor.java:120-134). The feed deadline propagates to the embedder (source: EmbedExpression.java:333-339). HTTP embedders retry 5xx responses (source: AbstractHttpEmbedder.java:182-192).
6. **Re-embedding.** Every put re-runs the script; there is no comparison with the old value (source: EmbedExpression.java:159-178). Partial updates seem to re-run only statements whose input is in the update; this is inferred from Expression.java:218-254 and is unverified. **A model change behind the same component id triggers nothing:** `IndexingScriptChangeValidator` compares scripts (source: config-model/.../change/search/IndexingScriptChangeValidator.java:55-73), and `EmbedExpression.equals` compares only the embedder id and arguments (source: indexinglanguage/.../expressions/Components.java:175-184). No change validator mentions embedders (source: grep of config-model/.../validation/change). Changing the id or arguments in the script produces an `indexing-change` reindex action (source: IndexingScriptChangeValidator.java:58-62). That reindex is automatic on Vespa Cloud and triggered manually self-hosted (docs: https://docs.vespa.ai/en/operations/reindexing.html).
7. **Storage / reuse.** Computed tensors are stored as attributes on the content nodes, and inference happens only in the container docproc. Restarts and replicas therefore reuse the stored vectors; this is inferred from the architecture (Java embedders, C++ proton). Reindexing re-feeds stored documents through the indexing chain and recomputes all derived fields, which re-runs inference (docs: reindexing.html). Whether the vectors are returned to clients depends on summary configuration (unverified).
8. **Query time.** The query is `input.query(q)=embed(e5, @text)` plus `nearestNeighbor(field, q)` in YQL (docs: embedding.html). Embedding runs synchronously while the rank-profile inputs are set, with the query's remaining time as deadline (source: container-search/.../query/properties/RankProfileInputProperties.java:45-55). The cache only lives for one request, keyed by (embedderId, text) (source: linguistics/.../process/InvocationContext.java:45-50; HuggingFaceEmbedder.java:172-175); nothing is cached across requests. Blank text gives a zero tensor (source: TensorConverter.java:96-104). Errors become `IllegalAssignmentException` (source: RankProfileInputProperties.java:58-59); a `TimeoutException` is not caught there and surfaces as a request error (unverified beyond that).
9. **Asymmetry.** `DestinationType` is derived structurally: a destination `query(…)` means QUERY, and `schema.field` means DOCUMENT (source: InvocationContext.java:22-31). This drives the `<prepend><query/><document/>` prefixes (source: model-integration/.../embedding/TextPrepender.java:14-32) and VoyageAI `input_type` (source: VoyageAIEmbedder.java:83).
10. **Chunking.** Chunking is built in: `chunk fixed-length N` and a sentence chunker (source: indexinglanguage/.../ChunkExpression.java:51-63; linguistics/.../chunker/FixedLengthChunker.java, SentenceChunker.java). The `array<string>` chunks embed into a mixed tensor with one subspace per chunk (source: EmbedExpression.java:187-222). The document score is the maximum rawscore, i.e. the minimum distance, over subspaces (source: searchlib/src/vespa/searchlib/tensor/distance_calculator.h:59-67, 80-88), and `closest()` reports the winning chunk (source: searchlib/.../features/closest_feature.cpp:135-141). VoyageAI contextual models embed all chunks of a document together (docs: embedding.html).
11. **Mixing.** A synthetic `embed` field cannot be fed: "Field … is not part of the declared document" (source: docprocs/.../DocumentScript.java:75-77). Client-supplied vectors therefore need a separate document field. A single field accepting both kinds is not shown in source or docs (unverified).
12. **Providers / extensibility.** Local: HuggingFace ONNX, BERT, ColBERT (multi-vector), SPLADE (sparse) and GGUF. Remote: OpenAI(-compatible), Mistral and VoyageAI (source: model-integration/src/main/java/ai/vespa/embedding/). A custom embedder implements `com.yahoo.language.process.Embedder` and is registered as a component (source: linguistics/.../process/Embedder.java:18-80). The target tensor type selects the output form: dense, int8 binary, mapped sparse or multi-vector (source: EmbedExpression.java:110-134).
13. **Hybrid.** Retrieval combines `userQuery() or nearestNeighbor(...)` in YQL, and fusion lives server-side in rank-profile expressions. Those combine `bm25` with `closeness`, or use global-phase `reciprocal_rank_fusion` / normalizers (source: config-model/.../expressiontransforms/NormalizerFunctionExpander.java:25). The profile is picked per query.

**What is distinctive about Vespa**
- Embedding is just another indexing-language expression, so input composition, chunking and concatenation are all script.
- The tensor type is the whole contract; no model identity is recorded or checked.
- Query and document roles are inferred from where the result goes, not passed as a flag.
- Feed is strict: an embedder failure fails the operation, so no vector-less document is ever indexed.
- Gap: swapping the model behind an unchanged id is invisible to change validation, and stale vectors remain until a reindex.

#### Solr

1. **Where defined / identity.** Models live in a REST managed resource, `/schema/text-to-vector-model-store`, per collection (source: solr/modules/language-models/src/java/org/apache/solr/languagemodels/store/rest/TextToVectorModelStore.java:33). It is persisted as JSON in ZooKeeper or in a conf-dir file named after the resource (source: solr/core/src/java/org/apache/solr/rest/ManagedResourceStorage.java:161, 233, 445). The update processor (in `solrconfig.xml`) and the query parser reference a model by `name` (source: TextToVectorUpdateProcessorFactory.java:72-74; TextToVectorQParserPlugin.java:94-96).
2. **Credentials.** The API key is plain `params.apiKey` in the model JSON (docs in repo: solr/solr-ref-guide/modules/query-guide/pages/text-to-vector.adoc:205-223). It is **echoed verbatim on GET**: `doGet` returns `params` unchanged (source: ManagedLanguageModelStore.java:73-78, 140-143). A test asserts `params/apiKey=='apiKey-cohere'` after reload and restart (source: solr/modules/language-models/src/test/.../TestTextToVectorModelManagerPersistence.java:49, 65, 81).
3. **User-provided vectors.** `DenseVectorField` takes `vectorDimension`, `similarityFunction` (euclidean/cosine/dot_product) and `vectorEncoding` FLOAT32/BYTE (source: dense-vector-search.adoc:74-102). Compatibility is a dimension check only (source: TextToVectorQParserTest.java:132-141). Text-to-vector queries also require FLOAT32 (source: TextToVectorQParserPlugin.java:79-89). The engine records no model identity.
4. **What text is embedded.** One `inputField` per processor, embedded as `getValue().toString()`, so a multi-valued field becomes its list's string form (source: TextToVectorUpdateProcessor.java:63-67). Several processors embed several fields (docs: text-to-vector.adoc:297). Concatenation needs other processors earlier in the chain; this is inferred, not documented on that page.
5. **Indexing timing / failure.** Embedding is synchronous inside the update request. On failure the error is **logged and the document is indexed without a vector** (source: TextToVectorUpdateProcessor.java:73-86; test TextToVectorUpdateProcessorTest.java:144). A missing model fails at processor construction, not per document (source: TextToVectorUpdateProcessorFactory.java:107-116). `timeout` and `maxRetries` are model params passed to LangChain4j (source: SolrTextToVectorModel.java:83-91). For slow APIs, the docs recommend indexing first and vectorising in a second atomic-update pass (docs: text-to-vector.adoc:327-430).
6. **Re-embedding.** There is no change detection: every add with a non-empty input re-embeds, and atomic updates fetch stored content and re-embed (source: TextToVectorUpdateProcessorTest.java:167). Model names are unique; to change one, delete it and re-upload (source: ManagedLanguageModelStore.java:191-196). The model is looked up per request (source: TextToVectorUpdateProcessorFactory.java:107-108), so a swap takes effect at once and old vectors silently stay. The only way to re-embed is a client re-feed.
7. **Storage / reuse.** Vectors are Lucene index content, returnable when `stored="true"` (source: dense-vector-search.adoc:75), and restarts do not re-infer. **Replica cost depends on where the processor sits relative to `DistributedUpdateProcessor`.** Placed before it (the default auto-insertion), inference runs once on the receiving node. Placed after it, the placement the docs require for atomic updates, inference runs on the leader *and every replica* (source: update-request-processors.adoc:96, 141-165; text-to-vector.adoc:406). Whether REINDEXCOLLECTION runs the chain is unverified.
8. **Query time.** The syntax is `{!knn_text_to_vector model=m f=vector topK=10}query text` (source: dense-vector-search.adoc:597). Embedding runs synchronously in `parse()` with no embedding cache (source: TextToVectorQParserPlugin.java:92-101). A failure propagates out of `parse()`; the HTTP status is unverified.
9. **Asymmetry.** Nothing in the engine handles it. Cohere `inputType` is a per-model parameter (source: text-to-vector.adoc:238), so query and document roles need two store entries. This is inferred.
10. **Chunking.** Not built in. Multi-valued vector fields are nested child documents, aggregated with `{!parent … score=max}` (source: dense-vector-search.adoc:603-611). The processor emits one vector per input field (source: TextToVectorUpdateProcessor.java:67-72).
11. **Mixing.** This works implicitly. The output field is overwritten only when the input is non-empty and embedding succeeds, so a client-supplied vector survives otherwise (source: TextToVectorUpdateProcessor.java:64-72).
12. **Providers / extensibility.** All providers are remote via LangChain4j: HuggingFace, MistralAI, OpenAI and Cohere (docs: text-to-vector.adoc:133-138, 118 "the model doesn't run internally in Solr"). Any LangChain4j `EmbeddingModel` class with a `builder()` is instantiated reflectively (source: SolrTextToVectorModel.java:62-121).
13. **Hybrid.** The `CombinedQuerySearchHandler` runs several named queries and fuses them in the request via `combiner.*` params, with RRF by default and plugin algorithms possible (source: json-combined-query-dsl.adoc:41-77). Alternatives are a `rerank` with `vectorSimilarity`/knn that adds weighted scores (source: dense-vector-search.adoc:840-868), or knn `seedQuery` (source: dense-vector-search.adoc:493).

**What is distinctive about Solr**
- Embedding is a pluggable update processor plus a query-parser wrapper; the vector field itself knows nothing about models.
- Models are runtime REST resources, not schema, and their credentials are stored and served back in plaintext.
- Indexing is best-effort: a failed embedding yields a document without a vector and only a log line.
- There is no provenance and no change detection; consistency after a model swap is entirely the operator's job.
- Inference location is a chain-ordering detail, and it can multiply API calls by the replica count.

### Embedders in Meilisearch and Typesense

Source labels: `M:` = `oss/meilisearch`, `T:` = `oss/typesense` (local checkouts, read 2026-11-02).

#### Meilisearch

1. **Definition and identity.** Embedders live in **index settings**, as an `embedders` map from a user-chosen name to a config: `source` ∈ openAi | huggingFace | ollama | rest | userProvided | composite, plus `model`, `url`, `documentTemplate`, and so on (source: M:crates/milli/src/vector/settings.rs:22-254). Documents (`_vectors.<name>`) and queries (`hybrid.embedder`) refer to it by **name**. Internally each name gets a stable `u8` embedder id (source: M:crates/milli/src/vector/db.rs:360-363).

2. **Credentials.** `apiKey` is stored with the embedder options in the index database (source: M:crates/milli/src/vector/embedder/mod.rs:44-53). When settings are read back, and in task views, it is partly masked: a short prefix stays, followed by `XXX...` (source: M:crates/meilisearch-types/src/settings.rs:439-479; M:crates/meilisearch-types/src/task_view.rs:533). **Dumps reveal it** in clear text (source: M:crates/index-scheduler/src/scheduler/process_dump_creation.rs:249-253). Changing `apiKey` or `headers` never re-embeds (source: M:settings.rs:1024-1025).

3. **User-provided vectors.** Use `source: userProvided`; `dimensions` is mandatory (source: M:settings.rs:92-104). A document supplies `_vectors.<name>` as an array, an array of arrays, `{embeddings, regenerate}`, or `null` to opt out (source: M:crates/milli/src/vector/parsed_vectors.rs:46-55). No model identity is recorded. A query `vector` is checked **only against `embedder.dimensions()`** (source: M:crates/meilisearch/src/search/mod.rs:733-742).

4. **Text selection.** `documentTemplate` is a **Liquid** template over the whole document, e.g. `{{doc.title}}: {{doc.overview}}`. The default template prints `name: value` for every searchable, non-null field (source: M:crates/milli/src/prompt/mod.rs:66-72). The rendered text is truncated to `documentTemplateMaxBytes`, default **400 bytes** (source: M:prompt/mod.rs:74-75,153). REST embedders may instead define named `indexingFragments` (multimodal), each yielding its own vector (source: M:settings.rs:157-169).

5. **Timing and failure.** Indexing is **asynchronous**. Writes are enqueued tasks that the index scheduler processes in batches, and embedding happens inside batch processing. An embedding error fails the **whole batch**: every task in it is marked `Failed` with the error (source: M:crates/index-scheduler/src/scheduler/mod.rs:369-395). The REST client retries 5xx, network and 429 errors with jittered exponential backoff, up to 10+1 attempts, and gives up immediately on 400/401. Defaults: 30 s HTTP timeout, at most 60 s per wait (source: M:crates/milli/src/vector/embedder/rest.rs:57-65,189-221,468-530,546-569).

6. **Re-embedding.** On a document update, both the **old and the new document version are rendered through the template**, and Meilisearch re-embeds only if the rendered texts differ. No hash or rendered text is stored (source: M:crates/milli/src/update/new/extract/vectors/mod.rs:1119-1134). Settings changes are classified per field (source: M:settings.rs:849-1026):
   - `source`, `model`, `revision`, `pooling`, `request` or `response` → `FullReindex`. This drops generated vectors and keeps user-provided ones.
   - `url` → re-embed, except for openAi.
   - `dimensions` → re-embed only for openAi.
   - `documentTemplate` → `RegeneratePrompts`, i.e. only documents whose rendering changed.
   - `documentTemplateMaxBytes` → re-embed only when **increased**.

   Changes to the composite `searchEmbedder` never re-embed (source: M:settings.rs:645-646). Removing an embedder writes user-provided vectors back into the documents' `_vectors` field (source: M:crates/milli/src/update/settings.rs:1146-1167).

7. **Storage.** Vectors are persisted in an arroy/hannoy vector store inside the index's LMDB, so restarts and snapshots reuse them. Per embedder, the index also keeps a `user_provided` bitmap and a `skip_regenerate` bitmap (source: M:db.rs:110-157). Dumps write each vector into `_vectors` together with its `regenerate` flag, so a restore reuses it instead of re-embedding (source: M:process_dump_creation.rs:223-241; M:vectors/mod.rs:222-237). Clients get vectors back with `retrieveVectors`, as `{embeddings, regenerate}` (source: M:search/mod.rs:2419-2436).

8. **Query time.** The query is `q` plus `hybrid: {embedder, semanticRatio}`; a raw `vector` may be passed instead. Embedding is synchronous within the request. There is an LRU cache keyed by query text (texts up to 2000 bytes), but `MEILI_EXPERIMENTAL_EMBEDDING_CACHE_ENTRIES` **defaults to 0, so it is off** (source: M:crates/milli/src/vector/embedder/mod.rs:250-281,437-475; M:crates/meilisearch/src/option.rs:1289-1291). Failure behaviour differs by mode:
   - Hybrid: failure **falls back to keyword-only results** and is logged (source: M:crates/milli/src/search/hybrid.rs:336-346).
   - Pure semantic (`semanticRatio` = 1.0, or no `q`): the request fails with an error (source: M:search/mod.rs:1687-1690; M:crates/meilisearch/src/routes/indexes/search.rs:1033-1047).

9. **Document/query asymmetry.** Text embedders have no query-prefix setting. Only REST `searchFragments`, separate from `indexingFragments`, give a different query payload (source: M:rest.rs:378-393). The `composite` source pairs an `indexingEmbedder` with a `searchEmbedder`, but at construction it **requires both to produce near-identical vectors**: three probe texts, distance ≤ 0.01 (source: M:crates/milli/src/vector/embedder/composite.rs:11,96-136). It is meant for running the same model through two transports, not for asymmetric models.

10. **Chunking.** Not built in; long text is truncated (point 4). A document can hold several vectors per embedder: user arrays of arrays, or one vector per fragment. They are stored in up to 256 per-embedder sub-stores, and any beyond that are silently ignored (source: M:crates/milli/src/vector/store.rs:301-329). At search time, results from all sub-stores are merged and sorted by distance with no deduplication in the store layer (source: M:store.rs:990-1004). The ranking rule emits a document at its smallest-distance bucket, so the **best vector wins** (inferred from M:crates/milli/src/search/new/vector_sort.rs:80-95; the dedup path itself is unverified).

11. **Mixing.** Yes, per document:
   - `_vectors.<name>: {embeddings, regenerate: false}` pins a user vector even under a generating embedder.
   - `{embeddings, regenerate: true}` seeds a vector that is replaced once the rendered text changes.
   - `{regenerate: true}` with no embeddings asks for generation.

   (source: M:vectors/mod.rs:137-180,218-255; M:parsed_vectors.rs:295-361)

12. **Providers and extensibility.** OpenAI, Hugging Face (local, via candle), Ollama, user-provided, composite, and a **generic REST embedder** (source: M:embedder/mod.rs:23-36). The REST embedder takes a `request` JSON template containing `{{text}}`, a `response` template marking `{{embedding}}`, plus `headers` (source: M:settings.rs:184-220).

13. **Hybrid.** `semanticRatio` (default 0.5) is passed per query (source: M:search/mod.rs:66). The keyword search runs first; if every result scores ≥ 0.45 after weighting, the semantic leg is **skipped** (source: M:hybrid.rs:306-312,378-396). Otherwise the two lists are **merged by score**, each score scaled by its ratio, rather than by rank (source: M:hybrid.rs:32-80,361-375). A per-embedder `distribution` (mean, sigma) applies an affine shift to semantic scores to make them comparable with keyword scores (source: M:vector_sort.rs:86-90).

**What is distinctive about Meilisearch's design**
- Change detection is **semantic, not field-based**: it re-renders the template on the old and new document and re-embeds only when the text differs.
- A per-document `regenerate` flag plus two bitmaps let generated, pinned and seeded vectors share one embedder.
- Every settings field has a declared re-embedding consequence (full, regenerate-prompts, none), and changes are applied asynchronously.
- Fail-closed on writes (the whole batch fails), fail-open on hybrid reads (keyword fallback).
- Vectors travel through dumps with their flags, so a restore needs no inference.

#### Typesense

1. **Definition and identity.** The embedder is declared **per field in the collection schema**: a `float[]` field with `embed: {from: [...], model_config: {model_name, api_key, url, indexing_prefix, query_prefix, ...}}` (source: T:src/field.cpp:305-361). Queries refer to it by **field name**, via `query_by` or `vector_query`. Loaded models are shared process-wide, keyed by `model_name`, and for remote models also by `api_key` (source: T:src/embedder_manager.cpp:63; T:src/text_embedder_remote.cpp:341).

2. **Credentials.** They are stored verbatim in the field's `embed` config (source: T:field.cpp:940-941). When a collection is read back, `api_key`, `access_token`, `refresh_token`, `client_id`, `client_secret`, `project_id` and the service-account keys are masked to their first 5 characters followed by `*` (source: T:src/collection.cpp:930-947,8917-8928). `api_key` is the **only** embed property that can be changed in place (source: T:collection.cpp:7917-7955).

3. **User-provided vectors.** A `float[]` field with `num_dim` and `vec_dist` ∈ cosine | ip (source: T:field.cpp:367-391,949-951). No model is recorded. A query vector is checked only for dimension count (source: T:collection.cpp:2086-2088).

4. **Text selection.** `embed.from` lists `string`, `string[]` or `image` fields (source: T:field.cpp:855-885). Their values are **concatenated with spaces**, prefixed by `indexing_prefix`; there is no template language (source: T:src/index.cpp:8263-8284). When a field has both text and image sources, their embeddings are **averaged** into one vector (source: T:index.cpp:8397-8416).

5. **Timing and failure.** Embedding is **synchronous inside the import/write request**, with remote calls batched 200 per request, a 60 s timeout and 2 tries by default (source: T:src/core_api.cpp:1632-1686). A failed embedding rejects **only that document**, carrying the provider's status code, while the rest of the import proceeds (source: T:index.cpp:8370-8374).

6. **Re-embedding.** On an update, `get_doc_changes` reduces the update document to fields whose **values actually changed** (source: T:index.cpp:7980,8007-8008). The document is re-embedded only if one of the changed fields is a `from` field; missing `from` values are filled in from the old document (source: T:index.cpp:565-598). No other change to an embed field's config is allowed: the field must be **dropped and re-added**, and the alter then re-embeds every stored document (source: T:collection.cpp:7943-7945,7219-7280).

7. **Storage.** The computed vector is **written into the stored document JSON** (source: T:index.cpp:8414-8415). On restore or restart, a document that already carries the vector is skipped, so nothing is re-inferred (source: T:index.cpp:8229-8234). Vectors are returned in search results unless listed in `exclude_fields` (docs: https://typesense.org/docs/29.0/api/vector-search.html).

8. **Query time.** Two ways to query with text:
   - Put the embedding field in `query_by`: `q` is embedded synchronously, with `query_prefix` prepended (source: T:collection.cpp:2859-2905).
   - `vector_query: field:([], k:, alpha:, distance_threshold:, queries:[...], query_weights:[...], id:)`. Here `queries` embeds several texts and averages them, or weights them by `query_weights` (source: T:src/vector_query_ops.cpp:149-287; T:collection.cpp:1982-2047).

   Cache: one process-wide static **100-entry LRU**, for remote embedders only, keyed by `text + model_name` (source: T:include/text_embedder_remote.h:54; T:text_embedder_remote.cpp:177-213). Defaults at search time are 5 s and 2 tries (source: T:collection.cpp:9596-9597). A failure is an **HTTP 400, with no lexical fallback**, and prefix search is refused for remote embedders (source: T:collection.cpp:2879-2900).

9. **Document/query asymmetry.** `indexing_prefix` and `query_prefix` are prepended as text; for public `ts/` models they come from the downloaded model config (source: T:embedder_manager.cpp:263-291). The GCP embedder also carries separate `document_task` and `query_task` values (source: T:text_embedder_remote.cpp:509-511).

10. **Chunking.** None. BERT and DistilBERT inputs are truncated at 512 tokens (source: T:src/text_embedder_tokenizer.cpp:23-27,41-45). Each field holds exactly one vector.

11. **Mixing.** A document may carry a precomputed value for an embedding field, and then no embedding is generated (source: T:index.cpp:8229-8233). The vector is not marked as user-provided, so a later change to a `from` field overwrites it with a computed one (inferred from T:index.cpp:576-598).

12. **Providers and extensibility.**
   - Local: ONNX models, either public `ts/…` from `models.typesense.org` or custom `bert`, `xlm_roberta`, `distilbert`, `clip` or `siglip` models in the model directory (source: T:include/embedder_manager.h:62; T:embedder_manager.cpp:98-142).
   - Remote: namespaces `openai`, `google`, `gcp` and `azure` only (source: T:embedder_manager.cpp:37-60).
   - There is **no generic REST template**; OpenAI with a custom `url` is the escape hatch for compatible APIs (source: T:text_embedder_remote.cpp:90-93).

13. **Hybrid.** Hybrid search happens when `query_by` mixes text fields with an embedding field. The two result lists are combined by **weighted reciprocal rank**: `(1/keyword_rank)(1−α) + (1/semantic_rank)·α`, with no k constant and α defaulting to 0.3, set through `vector_query` (source: T:index.cpp:8986-8991; T:include/vector_query_ops.h:19). `rerank_hybrid_matches` computes the missing leg's score for documents that only one leg found (source: T:index.cpp:4365).

**What is distinctive about Typesense's design**
- The embedder is a **property of a schema field**, not a named, reusable object, and it is effectively immutable: only the API key can be rotated.
- Change detection is **field-value based**: any change to a `from` field re-embeds, whether or not the rendered text would differ.
- Synchronous embedding with per-document failure keeps the import contract simple, but it holds the write request open for the duration of inference.
- Vectors live inside the stored document, so restart and restore reuse them for free, and they are returned by default.
- Fusion is rank-based (RRF without k), so no score calibration is needed; the query cache is a small global one, for remote models only.

## Part 2 — why five of them embed synchronously

### Why Elasticsearch and OpenSearch embed synchronously in the write path

**Main finding.** Neither project ever treated sync vs. async as a decision. I found no RFC, meta issue or PR that argues "synchronous because X, async rejected because Y". Both inherited the ingest-pipeline model (transform the document, then index it) from their older `inference` / `text_embedding` processors. What they did debate is *where* inside that synchronous path the inference call runs. The reasons below are the ones stated in those debates. Anything marked *inferred* is my own reading, not a quote.

#### Elasticsearch (`semantic_text`, 8.15+)

**Where inference runs, and why there.**
- Plugin ingest pipelines were tried first ([#102390](https://github.com/elastic/elasticsearch/pull/102390), [#103464](https://github.com/elastic/elasticsearch/pull/103464)) and dropped: "After discussing with @jimczi and @original-brownbear, we will not follow this implementation."
- Inference then moved into `TransportBulkAction` ([#103697](https://github.com/elastic/elasticsearch/pull/103697)). benwtrent's review used the word "asynchronously" ("there is this thing that needs to handle docs asynchronously"), but he meant non-blocking *before* the shard write, not after it.
- It finally moved into an action filter ([#106357](https://github.com/elastic/elasticsearch/pull/106357), jimczi): "With all the ML dependencies it is challenging to rely on things available in server … The action filter is a good compromise … since it allows to modify the bulk shard request transparently". The filter "intercepts `TransportShardBulkAction`s (before being sent to the data nodes)", with "a single inference request for each bulk request" ([#108102](https://github.com/elastic/elasticsearch/pull/108102)).
- The class javadoc says: "This transformation happens on the bulk coordinator node, and the `SemanticTextFieldMapper` parses the results during indexing on the shard" (`x-pack/plugin/inference/.../action/filter/ShardBulkInferenceActionFilter.java:96-104`).
- The selling points were simplicity ("no need to create an inference pipeline") and batching ("a single inference request with 20 texts …"). Source: [Search Labs blog, 2024-06-24](https://www.elastic.co/search-labs/blog/semantic-search-simplified-semantic-text).
- A stated goal was to make `_reindex` and `_update_by_query` work "so inference is not recalculated again" (carlosdelest in #103697). The embedding goes into the stored document, so later copies of it reuse the embedding.
- *Inferred:* inference runs once, on the coordinator, and replicas, recovery and reindex never call the model again.

**Async alternatives.** I found nothing official, in issues or docs, about deferred or background inference. The closest item is [#149785](https://github.com/elastic/elasticsearch/issues/149785) / [#149786](https://github.com/elastic/elasticsearch/pull/149786), an open proposal from an external contributor (2026): bring your own chunks and vectors, so that batch embedding APIs, which it calls "significantly cheaper and faster", can feed `semantic_text`. It is also the only explicit statement about visibility I found: "Staged data would be invisible to search until committed, preserving the atomicity model that `semantic_text` has today."

**Downsides they accepted.**
- **A failed inference rejects the document.** The code calls `item.abort(item.index(), failure)` (`ShardBulkInferenceActionFilter.java:969-973`). [#141079](https://github.com/elastic/elasticsearch/pull/141079) says: "any failure will prevent a given doc in a bulk request from being indexed."
- **No retry of their own.** An Elastic staff member said on the [forum](https://discuss.elastic.co/t/how-does-azure-openai-inference-with-semantic-text-field-handle-rate-limiting-and-chunking-for-high-token-volume/380302) (2025-07-21): "The only automatic retries are those that already exist in the _bulk API. Our recommendation is to ensure that you are sending traffic that is compatible with your token and traffic limits." That thread was about a 429 from Azure OpenAI.
- **Memory and OOMs.** [#125517](https://github.com/elastic/elasticsearch/pull/125517): "We have observed many OOMs due to the memory required to inject chunked inference results". The fix was coordinating indexing pressure, which surfaces as a 429 rejection. [#138254](https://github.com/elastic/elasticsearch/issues/138254) was an OOM from 168K "inference id not found" exceptions. The [rejected-requests docs](https://www.elastic.co/docs/troubleshoot/elasticsearch/rejected-requests) advise: "Reduce the batch size".
- **Timeouts.** [#141774](https://github.com/elastic/elasticsearch/pull/141774) (WIP): "Current timeout is basically 'never'."
- **Reindex is fragile.** [#113948](https://github.com/elastic/elasticsearch/issues/113948): "it is now possible to exceed the inference queue even with small reindex sizes".
- **Updates cost inference.** From the [ingestion docs](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/semantic-text-ingestions): "Full document updates re-run inference on all `semantic_text` fields, even if their values did not change." Bulk partial updates that omit the field reuse the stored embeddings; Update API partial updates do not. "Scripted updates will fail".

#### OpenSearch (`text_embedding` processor; `semantic` field, 3.1+)

**Where inference runs, and why there.**
- The HLD ([#1211](https://github.com/opensearch-project/neural-search/issues/1211)) and LLD ([#1212](https://github.com/opensearch-project/neural-search/issues/1212)) never ask *when* to embed. The LLD: "The proposed solution still uses IngestService to handle text chunking and embedding generation", injected automatically. It shipped as the *system ingest pipeline* ([OpenSearch#17817](https://github.com/opensearch-project/OpenSearch/pull/17817)), appended to the final pipeline "because we don't allow target index change in the final ingest pipeline", reusing the existing TextChunking/TextEmbedding/SparseEncoding processors.
- Running inference inside the field mapper was the real alternative ([#803](https://github.com/opensearch-project/neural-search/issues/803)). navneet1v argued for it: "system is free from ingest pipelines. Since ingest pipelines require separate node roles and compute power."
- bzhangam objected that mapper-time inference loses batching: "we still need to make multiple inference calls. And some ML models may bill us by the number of the API calls."

**Async alternatives — raised in #803 and never formally decided.**
- navneet1v: "putting up a queue which can queue up all the embedding generation in a batch and then use ML Commons predict api."
- heemin32: "custom codec for neural search so that batch inference can happen during flush. However, handling it inside mapper might be way simpler to implement."
- *De facto reason they lost:* the ingest-processor route reused existing code and kept batching.
- **Offline batch inference** ([ml-commons#2891](https://github.com/opensearch-project/ml-commons/issues/2891), 2.16/2.17) is the one asynchronous path they built.
  - Its motivation: "many cases where requests do not need an immediate response or certain rate limits prevent you from executing a large number of queries quickly", with "50% cost discount" and "higher rate limits". The triggering case was throttling that meant "data ingestion cannot finish" ([#2249](https://github.com/opensearch-project/ml-commons/issues/2249)).
  - It still never made text visible before its vector: it "ingests both documents and their embeddings" together, from the batch output file ([docs](https://docs.opensearch.org/3.5/ml-commons-plugin/remote-models/async-batch-ingestion/)).
  - It was deprecated in 3.0, and its REST handler was unregistered in #3688 ([#5112](https://github.com/opensearch-project/ml-commons/issues/5112)): "the usage is low", and users were pointed to Data Prepper.
- [ml-commons#4889](https://github.com/opensearch-project/ml-commons/issues/4889) (2026) keeps ingest synchronous. It adds size-based batching, plus queued cross-request batching only for *search*. Its reason: "Remote model endpoints throttle based on the rate of requests they receive."

**Downsides they accepted.**
- **Text without a vector is treated as a bug, not as a state.** In [#1735](https://github.com/opensearch-project/neural-search/issues/1735), documents were indexed without embeddings after throttling, and the issue was filed as "Silent Failures". The maintainer confirmed that the intended behaviour is to fail the item with `429 "Request is throttled at model level."` [OpenSearch#18909](https://github.com/opensearch-project/OpenSearch/issues/18909) has the same shape: the system pipeline was skipped on a non-ingest node, so "We will not auto generate the embedding".
- **Repeated inference cost.** The processor "makes model inference calls for every document ingestion or update" ([#1138](https://github.com/opensearch-project/neural-search/issues/1138)). `skip_existing` was later added for the semantic field ([#1350](https://github.com/opensearch-project/neural-search/issues/1350)).
- **Mixed vectors after a model change.** This is the one place they *accept* inconsistency. Changing `model_id` "will not automatically regenerate the embeddings … potentially leading to inconsistent results" ([#1211](https://github.com/opensearch-project/neural-search/issues/1211), R2.3).

#### Things an async design must handle that these sources point at

1. **Where failures go.** Both engines return a per-item error with the write; after an async commit nobody is listening. Needed: per-entity "vector pending/failed" state, metrics, retry with a dead-letter. Otherwise we ship the exact defect in OpenSearch #1735.
2. **Rate limits and backpressure that we own.** Expect 429s. Retry with backoff and bound the queue in bytes; ES's OOMs came from unbounded accumulation (#125517, #138254).
3. **The stale-vector window on update.** ES and OpenSearch never pair old vectors with new text; an async design always will, briefly. Tag each result with its input version or hash and drop superseded results; skip unchanged text (ES bulk partial updates, OpenSearch `skip_existing`). *Inferred:* drop results for entities deleted meanwhile.
4. **Visibility.** ES's implied contract is atomic (#149785). Unembedded entities will rank on the lexical side only, so vector coverage should be observable.
5. **Durability and restart.** ES stores the vector with the document, so nothing re-infers. Persist vectors through our own storage, and persist or re-derive the backlog ("entities lacking a vector for model M"), or a restart re-embeds everything.
6. **Timeouts and cancellation.** ES's is still "basically never" (#141774).
7. **Model identity on every vector.** It turns the backfill that OpenSearch R2.3 deferred into a natural feature.
8. **The upside.** Batching across commits, and the cheaper, higher-quota provider batch APIs that users of both engines asked for (#149785, ml-commons#2891).

### Why Vespa, Solr and Typesense embed in the write path, and why Meilisearch is asynchronous

Sources checked 2026-11-02. Code facts are from local checkouts (`oss/vespa`, `oss/solr`, `oss/meilisearch`).

#### Vespa

**Stated reasons.** No source I read gives an explicit rationale. Vespa presents it as convenience: it "should make it easy to get started with embeddings" ([blog, 2022-09-20](https://blog.vespa.ai/text-embedding-made-simple/)), and lets you embed "inside Vespa to reduce vector transfer costs" ([docs](https://docs.vespa.ai/en/rag/embedding.html)). Once embedding lives in the schema, new embedding fields "will be automatically populated" ([blog, 2024-02-16](https://blog.vespa.ai/embedding-flexibility-in-vespa/)).

**Mechanism.** `embed` is an indexing-language expression. Indexing runs in the container cluster ("also responsible for embedding inference", [sizing guide](https://docs.vespa.ai/en/performance/sizing-feeding.html)) before the operation reaches the content nodes. When the embedder fails, the whole operation is rejected: `OverloadException` becomes `Progress.OVERLOAD` and `TimeoutException` becomes `Progress.TIMEOUT` (`docprocs/src/main/java/com/yahoo/docprocs/indexing/IndexingProcessor.java:126-137`). A 429 from a remote embedder becomes an `OverloadException`, and the embedder's timeout is taken from the feed deadline (`model-integration/src/main/java/ai/vespa/embedding/AbstractHttpEmbedder.java:83,144-149`). *Inferred:* a document becomes visible with its vector, or not at all.

**Downsides Vespa states.** "Each document being fed blocks a thread while waiting for the embedding API response." "Effective throughput can never exceed the rate limit of your API key." "Increasing feed timeout settings might be required when documents have many `embed` inputs." The docs also advise separate feed and search clusters "so that bursty feed load cannot affect query latency" ([docs](https://docs.vespa.ai/en/rag/embedding.html)). A [2026-04-28 blog post](https://blog.vespa.ai/scaling-a-vespa-application-feeding-fast-and-furiously/) needed 100 GPUs and 40 content nodes to feed 8.8M passages in about 20 minutes. In [#26730](https://github.com/vespa-engine/vespa/issues/26730), the client timed out on 7k paragraphs while the document appeared later; the advice was to raise the client timeout.

**Async alternatives.** Vespa has no deferred embedding. You either feed precomputed tensors or let reindexing backfill, and Vespa admits that backfill is not atomic: "a field's data can be in one state or another, while the queries to it have the most current state." Its mitigation: "Once reindexing is completed, switch queries to use the new field" ([reindexing](https://docs.vespa.ai/en/operations/reindexing.html)). Partial updates are a known gap. In [#37627](https://github.com/vespa-engine/vespa/issues/37627) (open, 2026-08), an append leaves the derived embedding silently stale. Bratseth: "The task here is more to decide what to do".

#### Solr

**Stated reasons.** [PR #3151 / SOLR-17632](https://github.com/apache/solr/pull/3151) built embedding as an update request processor (URP) that calls "the vectorisation service for each document to process". Review argued the fork explicitly. epugh asked for the opposite design: "since enrichment is SLOW, come back with a streaming expression and do things like vectorization, and an atomic update.. pump your data in as fast as possible, and then enrich at your leisure… This model constrains your indexing speed to your vectorization speed… I also want to see a 'out of band' vectorization process." Benedetti answered: "given that 'update.chain' is a parameter… what prevents you from first index using the 'no vectors' chain and then slowly updating the index with atomic updates that add vectors". The fork was settled in documentation; no async mechanism was built.

**The second pass, as documented** (`solr/solr-ref-guide/modules/query-guide/pages/text-to-vector.adoc:317-430`). The guide warns: "It will slow down substantially your indexing pipeline." Then: "Vectorising text using a hosted service may be slow, so… it could be a good idea to index first your documents and then add vectors iteratively." The costs it names: documents are sent "twice" and "literally replac[ed]… one by one", with the other processors re-run. The atomic-update variant needs stored fields, `DistributedUpdateProcessorFactory` first "so that it sees normal/complete documents", and a `vectorised` boolean field, because "Faceting or querying on the boolean 'vectorised' field can also give you a quick idea on how many documents have been enriched".

**Failure.** cpoerschke asked whether some users "prefer to index only documents with (all the) vectors and others would rather have the document indexed even if some vectors are missing". The outcome: catch the exception, log it, and index the document without the vector (`solr/modules/language-models/src/java/org/apache/solr/languagemodels/update/processor/TextToVectorUpdateProcessor.java:73-86`). So Solr's synchronous path already yields "text visible, vector missing" when embedding fails.

#### Typesense

**Stated reasons.** The reason is architectural. Writes enter the Raft log unprocessed, and embedding runs at apply time on every node. On why there is no preprocessing step, tharropoulos wrote: it "would have added significant operational complexity and tightly coupled the clustering and indexing layers, which we've intentionally kept separate" ([#2616](https://github.com/typesense/typesense/issues/2616)). The workaround is to send vectors yourself, and Typesense "will skip the auto-embedding step".

**Async.** In [#2278](https://github.com/typesense/typesense/issues/2278), an upsert of ~100 documents takes "5 seconds to almost 2 minutes" because embedding runs "synchronously… before returning success". kishorenc (2025-04-29): "Yes, this is on our roadmap. It's a large change, so we will probably begin with async single document writes". The issue is still open, and I found no shipped async-write API.

**Failure: Typesense rejects the document.** An import returns `"code":408, "embedding_error", "success":false` for that document; the user wanted it to "index the document and queue embedding generation", and ended up embedding externally ([#1256](https://github.com/typesense/typesense/issues/1256)). Per a maintainer, failures surface "at the per-document level", but Typesense does not "retry 429 rate limit responses, Retry-After, or maintain an automatic deferred retry mechanism" ([#2839](https://github.com/typesense/typesense/issues/2839)).

**Downsides Typesense accepts.**
- One indexer thread per collection, so "embedding latency in the write path caps that collection's write throughput"; an update re-embeds whenever a source field is present, even unchanged ([#2986](https://github.com/typesense/typesense/issues/2986), user analysis).
- A restart replays "all the writes since that snapshot (which might include generating embeddings)" ([#2737](https://github.com/typesense/typesense/issues/2737)). In [#3147](https://github.com/typesense/typesense/issues/3147) (open, 2026-10-28), acknowledged writes are **lost** when the embedder is down during replay.
- Remote outages have blocked startup ([#1314](https://github.com/typesense/typesense/issues/1314)) and silently dropped the embedding field ([#2988](https://github.com/typesense/typesense/issues/2988), fixed).

#### Meilisearch

**Async because every write is async.** "Every operation that might take a long time to be processed is handled asynchronously", so that Meilisearch can "handle resource-intensive tasks without impacting search performance" ([docs](https://www.meilisearch.com/docs/learn/async/asynchronous_operations)). Embedding is not deferred within that model; it runs inside the indexing batch.

**Visibility: together, at batch commit.** `apply_index_operation`, which includes the embedding requests, runs inside one index write transaction that is committed once (`crates/index-scheduler/src/scheduler/process_batch.rs:165-197`). LMDB readers see only committed state, so a document and its vector become searchable at the same moment, never the document first.

**Failure.** An embedding error becomes `DocumentEmbeddingError` (`crates/milli/src/update/new/extract/vectors/mod.rs:513-565`), and **every task in the batch** is marked `Failed` (`crates/index-scheduler/src/scheduler/mod.rs:369-413`). The retry policy is 10 attempts at `100 + 10^attempt` ms, capped at 60 s (`crates/milli/src/vector/embedder/rest.rs:58-64,215-221,468-510`). A user in [#5973](https://github.com/meilisearch/meilisearch/issues/5973) showed that this backoff runs into RPM limits during a bulk rebuild. v1.26.0 added the experimental `ignore_embedder_failures`: "documents will not have embeddings, but the associated batch of tasks will not be marked as failed… it is harder to notice an issue" ([release](https://github.com/meilisearch/meilisearch/releases/tag/v1.26.0)).

**Task retry rejected.** dureuill gave three reasons in [#5937](https://github.com/meilisearch/meilisearch/issues/5937) (2026-01-06):
- Keeping the payload makes "its lifecycle harder to manage".
- A manual retry "is racy… the user could call the retry and accidentally 'restore' an earlier version of a document".
- An automatic retry "has the potential of clogging the task queue".

His suggested workarounds are a full resync, or failure modes combined with vector filters to find the documents that have no vector.

**`regenerate`.** With `true`, embeddings are generated "immediately" and "every time the document is updated". With `false`, Meilisearch "keeps the last value… and never overwrites it" ([docs](https://meilisearch.com/docs/capabilities/hybrid_search/how_to/search_with_user_provided_embeddings)). In practice the code re-embeds only when the rendered template text changes (`crates/milli/src/update/new/extract/vectors/mod.rs:1121-1134`).

#### Things an async design must handle

1. **Restarts must not call the model.** Typesense re-embeds on replay ([#2737](https://github.com/typesense/typesense/issues/2737)) and loses acknowledged writes when the embedder is down ([#3147](https://github.com/typesense/typesense/issues/3147)). Persist vectors and pending markers.
2. **Stale results.** A late result for version *n* must never overwrite *n+1* (dureuill's race). Key jobs by entity version.
3. **Queryable coverage.** Solr's `vectorised` field; Meilisearch's vector filters and per-batch last error ([#5706](https://github.com/meilisearch/meilisearch/issues/5706)). Without it, embeddings "drift somewhat silently" ([#2839](https://github.com/typesense/typesense/issues/2839)).
4. **Visible failures.** Vespa rejects the operation, Typesense the document, Solr indexes without the vector. Async means the last, so it needs metrics, a last error, and retries that honour 429 and Retry-After ([#5973](https://github.com/meilisearch/meilisearch/issues/5973)).
5. **Queue isolation.** A long-failing embedder must not block other writes ("clogging").
6. **Re-embed on changed input, not on field presence.** Meilisearch diffs; Typesense does not ([#2986](https://github.com/typesense/typesense/issues/2986)); Vespa misses appends ([#37627](https://github.com/vespa-engine/vespa/issues/37627)).
7. **Mixed state** during backfill or a model change ("one state or another"). Hybrid queries must tolerate missing vectors; a model switch wants a new field.
8. **Lost read-your-write.** Synchronous engines never show a document without its vector. Clients need a status or wait handle, as [#2278](https://github.com/typesense/typesense/issues/2278) asked.

## Part 3 — combining relevance across fields and legs

### Multi-field relevance across six engines

Path shorthands (local checkouts): `ES qd/` = `oss/elasticsearch:docs/reference/query-languages/query-dsl/`, `ES ret/` = `oss/elasticsearch:docs/reference/elasticsearch/rest-apis/`, `Solr rg/` = `oss/solr:solr/solr-ref-guide/modules/query-guide/pages/`. "Inferred" marks my reading, not the engine's words.

#### First: "best field wins" means three different things

- **Field-centric max.** Score the whole query in each field and keep the best field (ES/OpenSearch `best_fields`).
- **Term-centric max.** Take each query term's best field, then add up across terms (Solr dismax/edismax, Meilisearch `attributeRank`).
- **Coverage first.** Count query tokens matched in *any* field, then compare the best field's score (Typesense `max_score`).

Every tie mechanism found is **linear** (`best + t·Σ others`). No engine saturates the contribution of the secondary fields, so evitaDB's saturating bonus has no precedent to borrow from.

#### Elasticsearch

- **Lexical.** The default `multi_match` type is `best_fields` (source: `oss/elasticsearch:server/src/main/java/org/elasticsearch/index/query/MultiMatchQueryBuilder.java:48`; `ES qd/query-dsl-multi-match-query.md:80-81`). It runs as a `dis_max` over one `match` per field (`:102`). `tie_breaker` defaults to `0.0` (`ES qd/query-dsl-dis-max-query.md:38`), so the result is pure max unless it is set. With a tie value t, the score is "the score from the best matching field plus `tie_breaker * _score` for all other matching fields" (multi-match `:135-138`).
- **Stated rationale.** "brown fox in a single field is more meaningful than brown in one field and fox in the other" (`:100`). The tie value rewards "the same term in multiple fields … without confusing this with the better case of two different terms in the multiple fields" (dis-max `:40`).
- **What goes wrong with summing.** The docs say `most_fields` is meant for the same text analysed in different ways (`:184`). Summed across different fields, "the Smith Jones document will probably appear above the better matching Will Smith" because of differing IDF (`:280-282`). `cross_fields` blending "can even be incorrect", and the docs point to `combined_fields` (BM25F, term-centric) instead (`:330`; `ES qd/query-dsl-combined-fields-query.md:25`).
- **Counter-signal.** The 9.1 multi-field retriever format builds its lexical side as `MOST_FIELDS`, a sum (source: `oss/elasticsearch:x-pack/plugin/rank-rrf/src/main/java/org/elasticsearch/xpack/rank/MultiFieldsInnerRetrieverUtils.java:332`). It normalizes each semantic field and adds them up: `Normalizer(Σ_s Normalizer(Score_s)) + Normalizer(Σ_l Score_l)` (`ES ret/retrievers/linear-retriever.md:24-27`), with each group carrying 50% (`ES ret/retrievers.md:98`).
- **Vector, multiple fields.** Several `knn` clauses plus a query "are combined via a disjunction (boolean OR)" and score as `0.9 * match_score + 0.1 * knn_score_image-vector + 0.5 * knn_score_title-vector`, a boosted sum (docs: https://www.elastic.co/docs/solutions/search/vector/knn/build-search-queries). The `linear` retriever computes `Σ weight_i × Normalizer(Score_i)` (linear-retriever.md:59-63). `rrf` takes weights too, with k=60 (`ES ret/retrievers/rrf-retriever.md:37-46`).
- **Chunks.** "nested `knn` only supports `score_mode=max`" (`ES qd/query-dsl-knn-query.md:363`). `semantic_text` chunks are queried through nested `ScoreMode.Max` (source: `oss/elasticsearch:x-pack/plugin/inference/src/main/java/org/elasticsearch/xpack/inference/mapper/SemanticFieldMapper.java:1175`). For ColBERT-style vectors there is `rank_vectors` with `maxSimDotProduct` (`oss/elasticsearch:docs/reference/elasticsearch/mapping-reference/rank-vectors.md:210-221`).
- **Related documents.** `nested` defaults to `avg` (`ES qd/query-dsl-nested-query.md:75-77`) and `has_child` to `none` (`ES qd/query-dsl-has-child-query.md:88-91`); the other options are avg/max/min/sum. The docs make no recommendation.

#### OpenSearch

- **Lexical.** Same code lineage: `DEFAULT_TYPE = BEST_FIELDS` (source: `oss/OpenSearch:server/src/main/java/org/opensearch/index/query/MultiMatchQueryBuilder.java:74`). The tie semantics match ES (inferred from the shared fork).
- **Vector, multiple fields.** A `hybrid` query goes through a normalization processor: `min_max` is the default, `arithmetic_mean` the default combination, and weights must lie in [0, 1] with "the sum of the values … must equal 1.0". The stated reason: "BM25 and vector search use different scales" (docs: https://docs.opensearch.org/latest/search-plugins/search-pipelines/normalization-processor/). The score-ranker processor does weighted RRF with k=60 (docs: https://docs.opensearch.org/latest/search-plugins/search-pipelines/score-ranker-processor/).
- **Chunks.** "By default, only the highest-scoring nested document is considered". Averaging is opt-in through `expand_nested_docs` plus `score_mode: avg` (docs: https://docs.opensearch.org/latest/vector-search/specialized-operations/nested-search-knn/).
- **Related documents.** `nested` defaults to `ScoreMode.Avg` (source: `oss/OpenSearch:server/src/main/java/org/opensearch/index/query/NestedQueryBuilder.java:210`).

#### Solr

- **Lexical.** `tie` defaults to `0.0f` (source: `oss/solr:solr/core/src/java/org/apache/solr/search/DisMaxQParser.java:169`; `ExtendedDismaxQParser.java:1753`). The docs describe 0.0 as a "pure 'disjunction max query'" and 1.0 as a "pure 'disjunction sum query'", and add "Typically a low value, such as 0.1, is useful" (`Solr rg/dismax-query-parser.adoc:141-143`). `qf` boosts multiply (`:69-75`).
- **Granularity is term-centric.** Each term expands to `DisjunctionMaxQuery(fields, tie)` (source: `oss/solr:solr/core/src/java/org/apache/solr/util/SolrPluginUtils.java:884-902`; `ExtendedDismaxQParser.java:1177-1191`). The terms are then combined as boolean clauses, so they add up (inferred).
- **Vector, multiple fields.** Several `{!knn}` clauses in a boolean query add up, by Lucene semantics (inferred). The Combined Query DSL fuses queries with RRF, k=60 by default (`Solr rg/json-combined-query-dsl.adoc:73-77`).
- **Chunks.** A multivalued vector field is stored as nested documents. The documented query is `{!parent which=$allParents score=max …}` (`Solr rg/dense-vector-search.adoc:604-609`). Late interaction is offered only for re-ranking, through `lateVector()` (`:999-1006`).
- **Related documents.** Block join `score` takes avg/max/min/total, and the "Implicit default is `none`" (`Solr rg/block-join-query-parser.adoc:243-245`). The join parser offers the same options (`Solr rg/join-query-parser.adoc:84-93`). The docs make no recommendation.

#### Vespa

- **Lexical.** The default first phase is `nativeRank` (source: `oss/vespa:searchlib/src/vespa/searchlib/fef/indexproperties.cpp:98`). `nativeFieldMatch` adds up contributions per term and field, weighted by `fieldWeight`. It divides by the total weight of every field the term *searches*, not just the fields it matches (`oss/vespa:searchlib/src/vespa/searchlib/features/nativefieldmatchfeature.cpp:31-42, 84-91`). The result is a field-weighted average: a term that matches only one of two equally weighted fields scores at most half (inferred). The tutorials add up per-field scores, as in `bm25(title) + bm25(text)` (docs: https://docs.vespa.ai/en/learn/tutorials/hybrid-search.html). **Vespa is the lexical outlier.**
- **Vector, multiple fields.** Combination is a free-form rank expression. The hybrid tutorial warns that unbounded BM25 swamps closeness, which is bounded to [0, 1]. It offers a global phase with `normalize_linear` or `reciprocal_rank` (same tutorial). Several `nearestNeighbor` operators on the same field: Vespa "uses the maximum score to represent the unlabeled `closeness(field,embedding)`" (docs: https://docs.vespa.ai/en/querying/nearest-neighbor-search-guide.html).
- **Chunks.** "the minimum distance between the vectors of a document and the query vector is used", and `closest()` labels the winning vector (docs: https://docs.vespa.ai/en/reference/ranking/rank-features.html). For text arrays, the default output of `elementSimilarity` is `max((0.35*p+0.15*o+0.30*q+0.20*f)*w)`, the best element (`oss/vespa:searchlib/src/vespa/searchlib/features/element_similarity_feature.cpp:352`). The chunk guide also shows `top(3, …)` and `sum(chunk_scores)` for rewarding overall fit (docs: https://docs.vespa.ai/en/rag/working-with-chunks.html).
- **Related documents.** No score aggregation found.

#### Meilisearch

- **Lexical.** Meilisearch adds no scores together; it bucket-sorts. "Once a rule separates two documents, later rules have no effect". `attributeRank` "Returns documents that contain query terms in more important attributes first" (docs: https://www.meilisearch.com/docs/learn/relevancy/ranking_rules). In the code, each (term, field) pair is an edge whose cost is the field's weight, which follows the `searchableAttributes` order (`oss/meilisearch:crates/milli/src/search/new/ranking_rule_graph/fid/mod.rs:81-92`). The cheapest path takes each term's best field and adds those costs up across terms. Other fields that match add nothing (inferred from cheapest-path semantics).
- **Vector, multiple fields.** A `hybrid` query uses one embedder. Keyword and semantic scores are weighted by `semanticRatio`, merged in score order, and duplicates are dropped, so each document keeps its higher weighted score (`oss/meilisearch:crates/milli/src/search/hybrid.rs:160-178, 361-362`). Several embedders need federated multi-search. There `weight` multiplies the score, results are k-merged by weighted score (`crates/meilisearch/src/search/federated/perform.rs:551-560`), and documents already seen are skipped (`:1664`). Net effect: the maximum of the weighted scores.
- **Chunks.** `embeddings` "may also be an array of arrays … multiple embeddings for the same document" (docs: https://www.meilisearch.com/docs/learn/ai_powered_search/search_with_user_provided_embeddings). Buckets are ordered by ascending distance over all of a document's vectors (`crates/milli/src/vector/store.rs:1004`; `crates/milli/src/search/new/vector_sort.rs:80-92`), so the closest vector decides (inferred).
- **Related documents.** None found.

#### Typesense

- **Lexical.** `text_match_type` defaults to `max_score`, where "the best text match score across all fields are used as the representative score". The alternatives are `max_weight` and `sum_score`. "Field weights are used as tie breakers", and `prioritize_num_matching_fields` defaults to `true` (docs: https://typesense.org/docs/30.0/api/search.html). The code packs the score in this order: `tokens_matched` (across fields), then the best field's score, then that field's weight, then the number of matching fields (`oss/typesense:src/index.cpp:5417-5442`). Only `sum_score` multiplies scores by weights (`:5405-5406`).
- **Vector, multiple fields.** "Only one embedding field is allowed in the query" (`oss/typesense:src/collection.cpp:2840`). Hybrid search uses weighted reciprocal rank, with `alpha` defaulting to 0.3 for the vector side (`src/index.cpp:8989-8990`; docs: https://typesense.org/docs/30.0/api/vector-search.html).
- **Chunks.** No multi-vector field found.
- **Related documents.** Reference joins exist (`src/index.cpp:2016`), but no score aggregation was found.

#### Summary table

| Engine | Lexical default | Vector multi-field | Chunk aggregation | Related-doc aggregation |
|---|---|---|---|---|
| Elasticsearch | field-centric `dis_max`, tie 0.0 (pure max); boosts multiply | boosted **sum**; `linear`/`rrf` weighted | **max** (nested knn allows only max; `semantic_text` max) | `nested` avg default; `has_child` none; avg/max/min/sum |
| OpenSearch | same as ES | `hybrid`: min_max plus weighted arithmetic mean; or weighted RRF | **max** default; avg opt-in | `nested` avg default |
| Solr | term-centric dismax, tie 0.0; docs advise ~0.1 | boolean sum (inferred) or RRF | documented `score=max` | none default; avg/max/min/total |
| Vespa | `nativeRank` = field-weighted **average**; tutorials sum bm25 | user expression (sum in tutorials); same-field multi-NN = max | **min distance** (best); top-k/sum optional | not found |
| Meilisearch | per-term best attribute, lexicographic | one embedder; federated = **max** of weighted scores | closest vector (inferred) | not found |
| Typesense | `max_score`: coverage, then best field; weight and field count only break ties | one field per query | no multi-vector found | joins exist; no score aggregation |

#### Verdict

1. **Lexical.** Best field is the prevailing default in five of six engines (ES, OpenSearch, Solr, Typesense, Meilisearch). Vespa's field-weighted average is the outlier.
2. **Defaults.** The shipped default is *pure* max (tie 0.0 in ES and Solr). Max plus a small linear bonus is the documented *recommendation*: Solr suggests 0.1 and ES examples use 0.3.
3. **evitaDB's precedent.** Its plan matches ES `best_fields` (field-centric). Solr and Meilisearch work per term, and Typesense ranks cross-field coverage first. No engine saturates the bonus.
4. **Vectors.** Max is universal *within* a field (chunks). Across *different* vector fields, the norm is a weighted sum or fusion (ES, OpenSearch, Vespa, Solr); only Meilisearch federation ends up at max.
5. **Net.** Best field plus a tie-breaker on vectors is established practice for chunks but an outlier for separate fields. ES's newest multi-field format even sums its lexical fields.

### AI-Powered Search (Grainger, Turnbull, Irwin; Manning 2025): combining relevance across fields and legs

**Labels.** `[code]` = path:line in Piper project `oss/ai-powered-search` (notebook lines = `.ipynb` JSON lines); `[web]` = public URL; `[inferred]` = my reading of engine defaults or code. I had no book text; the only prose is what the notebooks embed, and ch03's is a draft (contains "BIG TODO").

#### 1. Lexical multi-field relevance

- **edismax is the default parser** for every collection `[code] engines/solr/SolrEngine.py:159-168`. The generic `query_fields` argument maps straight to `qf` `[code] engines/solr/SolrCollection.py:89-90`.
- **ch03** shows field boosts as `qf: "title^10 description^2.5"`, and phrase boosts through `pf`, `pf2` and `pf3` `[code] chapters/ch03/2.controlling-relevance.ipynb:265-304`. Most later queries list their fields without boosts. Examples: ch15 hybrid uses `["title","overview"]` `[code] ch15/2.multimodal-and-hybrid-search.ipynb:673-677`; ch08 uses `name, manufacturer, long_description` `[code] ch08/1.signals-boosting.ipynb:974`; ch11 uses five fields `[code] ch11/4…ipynb:2917`.
- **`tie` / `tie_breaker` is never set anywhere in the repo.** A repo-wide grep finds no match. The OpenSearch adapter uses `query_string` over the field list, with the comment "does dis_max by default" `[code] engines/opensearch/OpenSearchCollection.py:96-109`. The OpenSearch LTR adapter uses `multi_match` with no `type` `[code] OpenSearchLTR.py:168-176`.
- `[inferred]` The engine defaults then apply: Solr edismax `tie=0.0`, and `best_fields` with `tie_breaker=0` for query_string and multi_match. So the book's behavior is **pure winner-takes-all over fields**: max of the boosted per-field scores, plus additive phrase boosts. The book never discusses a tie-breaker in the material I could read.
- The best-field framing does appear in Turnbull's earlier book *Relevant Search* (with Berryman), ch.5 "Basic multifield search". That chapter covers best_fields as "winner-takes-all search", gives `score = S_title + tie_breaker × (S_others)`, and argues that "each field score is a signal" `[web] https://livebook.manning.com/concept/elasticsearch/best_field`. This is not AIPS text.

#### 2. Vector / semantic search over several fields

- **One vector field per query, no exceptions.** Both adapters build kNN on `query_fields[0]` and silently drop any other fields `[code] SolrCollection.py:55-69`, `OpenSearchCollection.py:11-30`.
- **Fields are concatenated, not embedded separately.** ch13 quantization embeds `post["title"] + " " + post["body"]` `[code] ch13/5.quantization.ipynb:421`. ch13 semantic search embeds titles only `[code] ch13/4.semantic-search.ipynb:335-337`. ch09 embeds product names only `[code] ch09/2…ipynb:207`.
- **Multiple vectors per entity are never max-aggregated.**
  - In `tmdb_with_embeddings`, each movie image is its own document, with no grouping `[code] ch15/2:126-134`.
  - The hybrid collection keeps **one image per movie** (`MIN(image_id)`), which is an arbitrary pick, not an aggregate `[code] ch15/2:171-176`.
- **Combining embeddings means averaging query-side vectors.**
  - Text plus image: `numpy.average` of the normalized embeddings `[code] ch15/2:242-246`.
  - Personalization: a weighted average of the query embedding and the user's item embeddings, 50/50 by default `[code] ch09/2:1151-1171`.
- There is no per-field vector weighting, no max over fields, and no chunk aggregation.

#### 3. Hybrid search (ch15)

- **RRF is the default and the only fusion that is not Solr-specific.** `hybrid_search(..., algorithm="rrf")` runs each request separately. `reciprocal_rank_fusion` then sums `1/(k+rank)` per document across the lists, with default **k = 60** `[code] engines/Collection.py:76-87, 107-114`.
  - The ch15 demo passes `k: 60` explicitly `[code] ch15/2:722-727`.
  - It over-requests 15 per leg for a final 10 `[code] ch15/2:667-671`.
  - The lexical leg is edismax over title and overview with `OR`; the vector leg is CLIP over `image_embedding` `[code] ch15/2:673-683`.
- **Second pattern: lexical match as a filter, then vector rerank** (`lexical_vector_rerank`). It maps to Solr `{!rerank reRankQuery=$rq_query reRankDocs=500 reRankWeight=1}` with a `{!knn}` rerank query `[code] Collection.py:88-92`, `SolrCollection.py:79-86`. The OpenSearch adapter has no `rerank_query` case `[code] OpenSearchCollection.py:144-145`.
  - `[inferred]` Solr's default `reRankOperator` is `add`. For the top 500 documents this pattern therefore **adds the raw BM25 and kNN scores**: the one raw-score sum in the hybrid code.
- **There is no linear or normalized score combination anywhere.** A comment notes that for kNN-parser queries "the rank won't change and score is not considered" `[code] SolrCollection.py:130-134`.
- **Author positions, public:**
  - Turnbull, "RRF is not enough": "RRF'ing bad search into good search will just drag down the good search". Use RRF "when you actually have distinct, disjoint sources of relevant search results". He proposes weighting RRF by intent probability, e.g. `80/vector_rank + 20/bm25_rank` `[web] https://softwaredoug.com/blog/2024/11/03/rrf-is-not-enough`.
  - Turnbull's course lists "Reciprocal Rank Fusion (RRF) and Its Limitations" as a topic, says "naive RRF and interleaving doesn't solve hybrid search's chicken and egg problem", and says you'll need to "filter the vector results by almost everything in the lexical side" `[web] https://maven.com/p/ec3299/why-your-hybrid-search-sucks`.
  - No public statement by Grainger found; Turnbull's Hybrid Search slide deck could not be parsed and is not used.

#### 4. Learning to rank: learned weights replace hand-set ones

- ch03 builds an additive relevance function by **scaling each feature to 0–25** so that they become comparable. It then says this "requires significant manual effort", and that LTR will "automatically make those decisions" `[code] ch03/2:316-335`.
- ch10 features are **per field**: `title_bm25`, `overview_bm25`, `release_year` `[code] ch10/2.judgments-and-logging.ipynb:181-183`.
  - Features are z-normalized because "SVMs are sensitive to the range" `[code] ch10/3…ipynb:178`.
  - The model is a pairwise `LinearSVC`, exported as a Solr `LinearModel` with a `StandardNormalizer` per feature. Learned weights: **title_bm25 0.345, overview_bm25 0.266, release_year 0.080** `[code] ch10/4…ipynb:412-426`.
  - So the learned combination is a **weighted sum of z-scored per-field BM25**. Title is ~1.3× overview, nothing like the 10:2.5 hand boost, and a linear model cannot express a max.
- ch12 adds fuzzy, bigram and constant-score per-field features `[code] ch12/1…ipynb:837-920`; LambdaMART is supported via RankLib `[code] ltr/helpers/convert.py:7`. No vector-similarity LTR feature exists.

#### 5. Signals and related-entity boosting

- **Signals boosting aggregates by COUNT, later by SUM.**
  - ch04: per (query, doc) `COUNT(clicks)` `[code] ch04/2.signals-boosting.ipynb:212-216`.
  - ch08: `SUM` per signal type, then a **SUM of time-decayed weighted signals** `weight × 0.5^(age/half_life)` `[code] ch08/1:1122-1124, 1177-1182`.
- The boost is applied **multiplicatively**: `boost = sum(1, query($boost_query))` `[code] SolrCollection.py:106-112`.
  - ch03 argues for this choice. Additive boosts "can be a pain to manage, because you have to explicitly scale them", while multiplicative ones let boosts "pile up" `[code] ch03/2:409-427`.
- **Category is a hard filter, not a score.**
  - The SKG-inferred category becomes `+doc_type:"…"` (mandatory).
  - SKG-relatedness-weighted expansion terms carry the scoring `[code] SolrSparseSemanticSearch.py:41-47`, `ch07/2…ipynb:906`.
- **The book has no score propagation from related entities.** Categories are denormalized fields on the document. There is no max, sum or average of a related entity's match score.

#### Agreement / disagreement with the evitaDB plan

- **Best field + tie-breaker (lexical): consistent, but not argued by the book.**
  - The code is effectively best-field with `tie=0`, and per-field boosts come from the query.
  - The tie-breaker rationale exists only in Turnbull's *Relevant Search*.
  - Caveat: when the book *learns* the combination (ch10), it gets an additive sum of normalized per-field scores, not a max, and much flatter weights than hand boosts. Query-supplied weights plus `tie` are a defensible hand-tuned prior, but the book would expect learned weights to replace them.
  - ch03's point applies too: weights on raw per-field BM25 are only meaningful if the field score scales are comparable.
- **Best field for vectors: no precedent either way.**
  - The book never scores several vector fields per entity. It concatenates fields into one embedding, keeps a single vector, or averages vectors on the query side.
  - Max-over-fields with a tie-breaker is a new choice for evitaDB. The book's alternative, one concatenated embedding, should be the baseline it is measured against.
- **RRF between legs: strong agreement, with two qualifications.**
  - RRF with k=60 is the book's only portable fusion, and there is no normalized linear blend.
  - However, its lexical-filter-plus-vector-rerank pattern *does* add raw scores `[inferred]`.
  - Turnbull warns that plain RRF hurts when one leg is weak, and advocates intent-weighted RRF. Consider per-leg RRF weights, and lexical filtering of the vector candidates.
- **Best related score: not covered; one tension.**
  - There is no related-entity propagation in the book.
  - Its nearest analogues aggregate evidence by SUM/COUNT and apply it multiplicatively, and ch03 warns that additive boosts need explicit scaling.
  - Max is reasonable for *match quality* (as opposed to evidence volume). But if the related score is *added* to the entity's own text score, the book's warning applies: scale it, use it as a multiplier, or feed it in by rank.

