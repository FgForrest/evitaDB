# Shards in Elasticsearch

In Elasticsearch indices are made up of one or more shards. Each shard is an instance of a Lucene index, which you can 
think of as a self-contained search engine that indexes and handles queries for a subset of the data in an Elasticsearch 
cluster. [1](https://www.elastic.co/blog/how-many-shards-should-i-have-in-my-elasticsearch-cluster)

Shards are divided into two groups: primary shards and replicas. Each document in an index belongs to one primary shard 
(ie. contains part of the index data). A replica shard is a copy of a primary shard which prevent service outages. Replica 
shards provide a redundant copy of data from primary shards to protect the index against hardware failure and increase capacity 
to serve read requests like searching or retrieving a document. [2](https://www.elastic.co/guide/en/elasticsearch/reference/7.10/scalability.html),
[3](https://www.siscale.com/elasticsearch-shard-optimization/) However in our single node case replica shards 
cannot be used, but still it is important to remember that in a cluster they have important role in data protection and 
even in performance for Read-heavy indices (see doc. types). [5](https://www.elastic.co/guide/en/elasticsearch/reference/7.10/scalability.html)

The number of primary shards is static from the time of creating index when this number was given. Later change of this 
static number is possible but quite complicated because it would impact the structure of the master data. Compared to 
that the number of replica shards can be changed at any time, without interrupting indexing or query operations. 
[5](https://www.elastic.co/guide/en/elasticsearch/reference/7.10/scalability.html) , [6](https://coralogix.com/log-analytics-blog/elasticsearch-update-index-settings/)

## Brief demonstration of shards in cluster

The following description serves as an example of the possibility of having two primary shards in one node. By all accounts, 
this can be used on a single node where splitting may be desirable for some index use cases (better sequence search performance 
[1](https://www.elastic.co/blog/how-many-shards-should-i-have-in-my-elasticsearch-cluster))
or needed because of some shard restrictions. (more in Shard property tips)

One of the possible cluster schemes can be seen in the picture bellow. As you can see in node1, one node can contain more 
than one primary shard of different indices (a,b). However, when node3 crashes or is removed from cluster, thus primary 
shard b1 too. Then the first step will be promoting replica b1 in node1 into the primary shard b1. At that moment there will 
be 2 primary shards in one node. For more information about shards in a cluster see the article at the following link: 
[7](https://www.elastic.co/blog/every-shard-deserves-a-home)

![Shards in cluster](Shards_in_cluster.PNG "Shards in cluster schema")

## Shard property tips

### Shard size

Each shard should have appropriate size because more shards can increase performance, but also increases overhead. 
Appropriate size of one shard should be between 20-40 GB for most of use cases. [1](https://www.elastic.co/blog/how-many-shards-should-i-have-in-my-elasticsearch-cluster),
[2](https://www.elastic.co/guide/en/elasticsearch/reference/7.10/scalability.html) The maximum limit of one node 
should be 50 GB. [8](https://www.elastic.co/guide/en/elasticsearch/reference/current/size-your-shards.html) The minimum 
is not firmly set up so generally shards can be however small as needed with risk of node overhead.

Otherwise, you can also force smaller segments to merge into larger ones through a force-merge operation which can reduce 
node overhead and improve query performance. However, this operation is quite expensive on resources, so you should 
perform such operations during off-peak hours and ideally do so once no more data is written to index. 
[1](https://www.elastic.co/blog/how-many-shards-should-i-have-in-my-elasticsearch-cluster)

### Number of shards

The number of shards has no fixed limit enforced by Elasticsearch. Ideal number should be proportional to the amount of 
heap which node has available. Rule-of-thumb says to keep the number of shards per node below 20 shards per one GB heap 
it has configured. It means that node with a 30 GB heap should have a maximum of 600 shards, however the further below 
this limit you can keep it the better. This will generally help the cluster stay in good health. 
[1](https://www.elastic.co/blog/how-many-shards-should-i-have-in-my-elasticsearch-cluster), 
[2](https://www.elastic.co/guide/en/elasticsearch/reference/7.10/scalability.html), 
[8](https://www.elastic.co/guide/en/elasticsearch/reference/current/size-your-shards.html)

In Elasticsearch, each query is executed in a single thread per a shard. Multiple shards can however be processed in 
parallel, as can multiple queries and aggregations against the same shard. This means that the minimum query latency,
when no caching is involved, will depend on the data, the type of query, as well as the size of the shard. Querying lots 
of small shards will make the processing per shard faster, but as many more tasks need to be queued up and processed 
in sequence, it is not necessarily going to be faster than querying a smaller number of larger shards. Having lots
of small shards can also reduce the query throughput if there are multiple concurrent queries. 
[1](https://www.elastic.co/blog/how-many-shards-should-i-have-in-my-elasticsearch-cluster)

## Experiment

Following experiment was performed on a single node with different number of shards. 
As you can see in the table below number of shards can reduce time needed for searching. 
However, there can be risk of decreasing the query throughput which was mentioned above.

Tests were run on elastic machine running in docker on machine with:

- 16 cores from Intel i7-8550U CPU @ 1.80GHz
- 32 GB RAM of DDR-4
- 35 GB of 512 GB SSD with SAS interface, read speed 1700 MB/s write speed 1540 MB/s

Each test is repeatable by creating indexes in class <SourceClass>docs/research/en/nosql/spike_tests/src/test/java/io/evitadb/referential/experiment/ESSpikeTest.java</SourceClass> 
with different shards in java module <SourceClass>docs/research/en/nosql/spike_tests/</SourceClass>.
This test allows you to generate data and inject data into ES (including specific number of records) 
and also you can specify number of shards either in the test itself (recreates the index) or on Document annotation(only 
in case it doesn't already exist) of general entity: <SourceClass>docs/research/en/nosql/spike_tests/src/main/java/io/evitadb/referential/nosql/model/Entity.java</SourceClass>.
See [testing section](../thesis.md#testing) in general document

| # of total records in thousands | # of shard | Query time |       Query       |
|:-------------------------------:|:----------:|:----------:|:-----------------:|
|               100               |     1      |   7-10ms   | [Query](#query-1) |
|               100               |     5      |   4-7ms    | [Query](#query-2) |
|               100               |     10     |   4-8ms    | [Query](#query-3) |
|              1000               |     1      |  60-100ms  | [Query](#query-4) |
|              1000               |     5      |  20-40ms   | [Query](#query-5) |
|              1000               |     10     |  15-35ms   | [Query](#query-6) |
|              5000               |     1      | 240-300ms  | [Query](#query-7) |
|              5000               |     5      |  70-120ms  | [Query](#query-8) |
|              5000               |     10     |  60-120ms  | [Query](#query-9) |

#### Query 1
```elasticsearch
GET /sh-100-1/_search {
    "size": 20,
    "sort" : [
        { "name": {"order" : "asc"}}
    ]
}
```

#### Query 2
```elasticsearch
GET /sh-100-5/_search {
    "size": 20,
    "sort" : [
      { "name": {"order" : "asc"}}
    ]
}
```

#### Query 3
```elasticsearch
GET /sh-100-10/_search {
    "size": 20,
    "sort" : [
      { "name": {"order" : "asc"}}
    ]
}
```

#### Query 4
```elasticsearch
GET /sh-1000-1/_search {
    "size": 20,
    "sort" : [
      { "name": {"order" : "asc"}}
    ]
}
```

#### Query 5
```elasticsearch
GET /sh-1000-5/_search {
    "size": 20,
    "sort" : [
      { "name": {"order" : "asc"}}
    ]
}
```

#### Query 6
```elasticsearch
GET /sh-1000-10/_search {
    "size": 20,
    "sort" : [
      { "name": {"order" : "asc"}}
    ]
}
```

#### Query 7
```elasticsearch
GET /sh-5000-1/_search {
    "size": 20,
    "sort" : [
      { "name": {"order" : "asc"}}
    ]
}
```

#### Query 8
```elasticsearch
GET /sh-5000-5/_search {
    "size": 20,
    "sort" : [
      { "name": {"order" : "asc"}}
    ]
}
```

#### Query 9
```elasticsearch
GET /sh-5000-10/_search {
    "size": 20,
    "sort" : [
      { "name": {"order" : "asc"}}
    ]
}
```

## Resources:

1. [https://www.elastic.co/blog/how-many-shards-should-i-have-in-my-elasticsearch-cluster](https://www.elastic.co/blog/how-many-shards-should-i-have-in-my-elasticsearch-cluster)
2. [https://www.elastic.co/guide/en/elasticsearch/reference/7.10/scalability.html](https://www.elastic.co/guide/en/elasticsearch/reference/7.10/scalability.html)
3. [https://www.siscale.com/elasticsearch-shard-optimization/](https://www.siscale.com/elasticsearch-shard-optimization/)
4. [https://medium.com/@alikzlda/elasticsearch-cluster-sizing-and-performance-tuning-42c7dd54de3c](https://medium.com/@alikzlda/elasticsearch-cluster-sizing-and-performance-tuning-42c7dd54de3c)
5. [https://www.elastic.co/guide/en/elasticsearch/reference/7.10/scalability.html](https://www.elastic.co/guide/en/elasticsearch/reference/7.10/scalability.html)
6. [https://coralogix.com/log-analytics-blog/elasticsearch-update-index-settings/](https://coralogix.com/log-analytics-blog/elasticsearch-update-index-settings/)
7. [https://www.elastic.co/blog/every-shard-deserves-a-home](https://www.elastic.co/blog/every-shard-deserves-a-home)
8. [https://www.elastic.co/guide/en/elasticsearch/reference/current/size-your-shards.html](https://www.elastic.co/guide/en/elasticsearch/reference/current/size-your-shards.html)
