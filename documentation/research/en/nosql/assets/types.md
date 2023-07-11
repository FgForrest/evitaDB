# Determining the index structure by its usage

The structure of index and its mapping is important for performance. First step is to determine what kind of index is 
needed. Your index purpose will determine if it is write-heavy or read-heavy index. One of the next recommendations is 
to design documents as flat as possible. When we cannot design flat documents, we may prefer nested or child (join) 
document types depending on the index’s behavior. Data denormalization may be an option if we do not care about its 
negative effects. [1](https://medium.com/@alikzlda/elasticsearch-cluster-sizing-and-performance-tuning-42c7dd54de3c)

Article [4](https://www.elastic.co/blog/managing-relations-inside-elasticsearch) contains good example of Inner Objects, 
Nested and Parent/Child types.

## Read-Heavy Indexes

Indexes, where the majority of operations are search requests, are known as Read-Heavy Indexes. Most common example is 
an index of the products used by an e-commerce application. Where usually a relatively small number of products are being 
updated, while the large number of users are searching for products. [1](https://medium.com/@alikzlda/elasticsearch-cluster-sizing-and-performance-tuning-42c7dd54de3c)

By all accounts, nested documents can increase search performance compare to child documents. It is important to keep 
in mind that writing data to nested documents is very costly and inefficient. [1](https://medium.com/@alikzlda/elasticsearch-cluster-sizing-and-performance-tuning-42c7dd54de3c) , [3](https://blog.usejournal.com/7-things-to-consider-for-improving-elasticsearch-query-performance-d9c19bc308b#:~:text=%207%20things%20to%20consider%20for%20improving%20ElasticSearch,the%20swapping,%20the%20slower%20the%20process...%20More) ,[4](https://www.elastic.co/blog/managing-relations-inside-elasticsearch)

In cluster by increasing the number of replicas can be improved performance up to a breaking point. At some point, 
performance will decrease due to hardware constraints. You can find this breaking point by increasing the number 
of replicas one by one. You can also use a tool like JMeter or Apache Bench to measure search performance while 
increasing the number of replicas. [1](https://medium.com/@alikzlda/elasticsearch-cluster-sizing-and-performance-tuning-42c7dd54de3c)

**Nested properties [2](https://stackoverflow.com/questions/14939078/scaling-with-regard-to-nested-vs-parent-child-documents) :**

- Nested docs are stored in the same Lucene block as each other, which helps read/query performance. Reading a nested 
  doc is faster than the equivalent parent/child.
- Updating a single field in a nested document (parent or nested children) forces ES to reindex the entire nested document. 
  This can be very expensive for large nested docs.
- Changing the "parent" means ES will: delete the old doc, reindex old doc with less nested data, delete the new doc, reindex 
  new doc with new nested data.


## Write-Heavy Indexes

Compared to previous type, Write-Heavy Indexes are typical by greater number of indexing operations than searching operations. 
In clusters that contain such indexes, resources are often used to write data. If you increase the number of replicas, 
the writing process will be increased as the documents coming to the shards will be copied to the replicas. This will 
lead to an increase in resource usage. [1](https://medium.com/@alikzlda/elasticsearch-cluster-sizing-and-performance-tuning-42c7dd54de3c),
[4](https://www.elastic.co/blog/managing-relations-inside-elasticsearch)

Child documents are slower to read by a query but faster when it comes to reindexing of documents. Therefore, this type
of index is more preferred. [1](https://medium.com/@alikzlda/elasticsearch-cluster-sizing-and-performance-tuning-42c7dd54de3c)

**Parent/Child properties [2](https://stackoverflow.com/questions/14939078/scaling-with-regard-to-nested-vs-parent-child-documents) :**

- Children are stored separately from the parent but are routed to the same shard. So, parent/children are slightly less 
  performance on read/query than nested.
- Parent/child mappings have a bit extra memory overhead since ES maintains a "join" list in memory.
- Updating a child doc does not affect the parent or any other children, which can potentially save a lot of indexing on large docs.
- Changing the parent means you will delete the old child document and then index an identical doc under the new parent.

# Measuring differences between types


| Object type | # of total records (in thousands) | # of shard | # of result records | Query time (in ms) | Query |
|:-------------:|:-------------:|:-------------:|:-------------:|:-------------:|:-------------:|
| Nested | 1000 | 5 | 10 | 2-4 | [Query](#1) |
| Object | 1000 | 5 | 10 | 2-4 | [Query](#2) |
| Flattened | 1000 | 5 | 10 | 3-5 | [Query](#3) |

In the following cells are shown query examples that were used for this measurement. As you can see, the queries are the 
same, but the results are slightly different even in such a small measurement.

#### <a id="1"></a> #1
```elasticsearch
GET /obj-nested/_search{
  "size": 20,
  "query": {
    "bool": {
      "must": [
        {
          "match": {
            "facets.attributes.path": "PRvjhFFBmi"
          }
        },
        {
          "match": {
            "facets.attributes.parentCatalog": "secondCatalog"
          }
        }
      ]
    }
  },
  "sort" : [
    { "name": {"order" : "asc"}}
  ]
}

```

#### <a id="2"></a> #2
```elasticsearch
GET /obj-object/_search{
  "size": 20,
  "query": {
    "bool": {
      "must": [
        {
          "match": {
            "facets.attributes.path": "oHkbEYayHO"
          }
        },
        {
          "match": {
            "facets.attributes.parentCatalog": "secondCatalog"
          }
        }
      ]
    }
  },
  "sort" : [
    { "name": {"order" : "asc"}}
  ]
}
```

#### <a id="3"></a> #3
```elasticsearch
GET /obj-flattened/_search{
  "size": 20,
  "query": {
    "bool": {
      "must": [
        {
          "match": {
            "facets.attributes.path": "sTqArWIOWO"
          }
        },
        {
          "match": {
            "facets.attributes.parentCatalog": "secondCatalog"
          }
        }
      ]
    }
  },
  "sort" : [
    { "name": {"order" : "asc"}}
  ]
}
```

## Resources

1. [https://medium.com/@alikzlda/elasticsearch-cluster-sizing-and-performance-tuning-42c7dd54de3c](https://medium.com/@alikzlda/elasticsearch-cluster-sizing-and-performance-tuning-42c7dd54de3c)
2. [https://stackoverflow.com/questions/14939078/scaling-with-regard-to-nested-vs-parent-child-documents](https://stackoverflow.com/questions/14939078/scaling-with-regard-to-nested-vs-parent-child-documents)
3. [https://blog.usejournal.com/7-things-to-consider-for-improving-elasticsearch-query-performance-d9c19bc308b#:~:text=%207%20things%20to%20consider%20for%20improving%20ElasticSearch,the%20swapping,%20the%20slower%20the%20process...%20More](https://blog.usejournal.com/7-things-to-consider-for-improving-elasticsearch-query-performance-d9c19bc308b#:~:text=%207%20things%20to%20consider%20for%20improving%20ElasticSearch,the%20swapping,%20the%20slower%20the%20process...%20More)
4. [https://www.elastic.co/blog/managing-relations-inside-elasticsearch](https://www.elastic.co/blog/managing-relations-inside-elasticsearch)
