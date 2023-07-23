---
title: Paging
perex: |
  Paging request constraints help to traverse large lists of records by splitting them into several parts that are requested 
  separately. This technique is used to reduce the amount of data transferred over the network and to reduce the load 
  on the server. evitaDB supports several ways to paginate query results, which are described in this section.
date: '23.7.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

## Page

```evitaql-syntax
page(
    argument:int!,
    argument:int!
)
```

<dl>
    <dt>argument:int</dt>
    <dd>
        a mandatory number of page to be returned, positive integer starting from 1
    </dd>
    <dt>argument:int</dt>
    <dd>
        a mandatory size of the page to be returned, positive integer
    </dd>
</dl>

The `page` (<SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/Page.java</SourceClass>) requirement
controls the number and slice of entities returned in the query response. If no page requirement is used in the query,
the default page `1` with the default page size `20` is used. If the requested page exceeds the number of available
pages, a result with the first page is returned. An empty result is only returned if the query returns no result at all
or the page size is set to zero. By automatically returning the first page result when the requested page is exceeded,
we try to avoid the need to issue a secondary request to fetch the data.

The information about the actual returned page and data statistics can be found in the query response, which is wrapped
in a so-called data chunk object. In case of the `page` constraint, 
the <SourceClass>evita_common/src/main/java/io/evitadb/dataType/PaginatedList.java</SourceClass> is used as data chunk 
object. The data chunk object contains the following information:

<dl>
    <dt>pageNumber</dt>
    <dd>
        the number of the page returned in the query response
    </dd>
    <dt>pageSize</dt>
    <dd>
        the size of the page returned in the query response
    </dd>
    <dt>lastPageNumber</dt>
    <dd>
        the last page number available for the query, the request for `lastPageNumber + 1` returns the first page
    </dd>
    <dt>firstPageItemNumber</dt>
    <dd>
        the offset of the first record of current page with current page size
    </dd>
    <dt>lastPageItemNumber</dt>
    <dd>
        the offset of the last record of current page with current page size
    </dd>
    <dt>first</dt>
    <dd>
        `TRUE` if the current page is the first page available
    </dd>
    <dt>last</dt>
    <dd>
        `TRUE` if the current page is the last page available
    </dd>
    <dt>hasNext</dt>
    <dd>
        `TRUE` if there is a data for the next page available (i.e. `pageNumber + 1 <= lastPageNumber`)
    </dd>
    <dt>hasPrevious</dt>
    <dd>
        `TRUE` if the current page is the last page available (i.e. `pageNumber - 1 > 0`)
    </dd>
    <dt>empty</dt>
    <dd>
        `TRUE` if the query returned no data at all (i.e. `totalRecordCount == 0`)
    </dd>
    <dt>singlePage</dt>
    <dd>
        `TRUE` if the query returned exactly one page of data (i.e. `pageNumber == 1 && lastPageNumber == 1 && totalRecordCount > 0`)
    </dd>
    <dt>totalRecordCount</dt>
    <dd>
        the total number of entities available for the query
    </dd>
    <dt>data</dt>
    <dd>
        the list of entities returned in the query response
    </dd>
</dl>

The `page` requirement is the most natural and commonly used requirement for the pagination of the query results.
To get the second page of the query result, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Second page of results retrieval example](/documentation/user/en/query/requirements/examples/paging/page.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of second page example
</NoteTitle>

The result contains the result from the 6th through the 10th record of the query result. It returns only a primary key 
of the records because no content request was specified, and it is sorted by the primary key in ascending order because 
no order was specified in the query.

<LanguageSpecific to="evitaql,java">

<MDInclude sourceVariable="recordPage">[The data chunk with paginated data](/documentation/user/en/query/requirements/examples/paging/page.evitaql.json.md)</MDInclude>

</LanguageSpecific>

</Note>

## Strip

```evitaql-syntax
strip(
    argument:int!,
    argument:int!
)
```

<dl>
    <dt>argument:int</dt>
    <dd>
        a mandatory offset of the first record of the page to be returned, positive integer starting from 0
    </dd>
    <dt>argument:int</dt>
    <dd>
        a mandatory limit of the records to be returned, positive integer
    </dd>
</dl>

The `strip` (<SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/Strip.java</SourceClass>) requirement
controls the number and slice of entities returned in the query response. If the requested strip exceeds the number of
available records, a result from the zero offset with retained limit is returned. An empty result is only returned if
the query returns no result at all or the limit is set to zero. By automatically returning the first strip result when
the requested page is exceeded, we try to avoid the need to issue a secondary request to fetch the data.

The information about the actual returned page and data statistics can be found in the query response, which is wrapped
in a so-called data chunk object. In case of the `strip` constraint,
the <SourceClass>evita_common/src/main/java/io/evitadb/dataType/StripList.java</SourceClass> is used as data chunk
object. The data chunk object contains the following information:

<dl>
    <dt>offset</dt>
    <dd>
        the offset of the first record returned in the query response
    </dd>
    <dt>limit</dt>
    <dd>
        the limit of the records returned in the query response
    </dd>
    <dt>first</dt>
    <dd>
        `TRUE` if the current strip starts with the first records of the query result
    </dd>
    <dt>last</dt>
    <dd>
        `TRUE` if the current strip ends with the last records of the query result
    </dd>
    <dt>hasNext</dt>
    <dd>
        `TRUE` if there is a data for the next next available (i.e. `last == false`)
    </dd>
    <dt>hasPrevious</dt>
    <dd>
        `TRUE` if the current page is the last page available (i.e. `first == false`)
    </dd>
    <dt>empty</dt>
    <dd>
        `TRUE` if the query returned no data at all (i.e. `totalRecordCount == 0`)
    </dd>
    <dt>totalRecordCount</dt>
    <dd>
        the total number of entities available for the query
    </dd>
    <dt>data</dt>
    <dd>
        the list of entities returned in the query response
    </dd>
</dl>

The `strip` requirement can be used to list query records in a non-uniform way - for example, when the entity listing is 
interleaved with an advertisement that requires an entity rendering to be skipped at certain positions. In other words, 
if you know that there is an "advertisement" block every 20 records, which means that the entity must be skipped for 
that position, and you want to correctly fetch records for the 5th page, you need to request a strip with offset `76` 
(4 pages * 20 positions per page - 4 records omitted on the previous 4 pages) and limit 19. To get such a strip, use 
the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Non-uniform strip of results retrieval example](/documentation/user/en/query/requirements/examples/paging/strip.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of requested strip example
</NoteTitle>

The result contains the result from the 76th through the 95th record of the query result. It returns only a primary key
of the records because no content request was specified, and it is sorted by the primary key in ascending order because
no order was specified in the query.

<LanguageSpecific to="evitaql,java">

<MDInclude sourceVariable="recordPage">[The data chunk with strip list](/documentation/user/en/query/requirements/examples/paging/strip.evitaql.json.md)</MDInclude>

</LanguageSpecific>

</Note>