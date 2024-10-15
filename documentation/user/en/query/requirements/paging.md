---
title: Paging
perex: |
  Paging request constraints help to traverse large lists of records by splitting them into several parts that are requested
  separately. This technique is used to reduce the amount of data transferred over the network and to reduce the load
  on the server. evitaDB supports several ways to paginate query results, which are described in this section.
date: '23.7.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
---

<LS to="g">

In GraphQL, there are multiple different ways to paginate results. The main distinction is between the
[`list` queries](../../use/api/query-data.md#list-queries) and [`query` queries](../../use/api/query-data.md#query-queries).
The `query` queries are then further divided into `page` and `strip` pagination.

## Pagination of `list` queries

As mentioned in [detailed description of `list` queries](../../use/api/query-data.md#list-queries), the `list` queries
are meant to be used for quick listing of entities, and so they offer only a limited set of pagination features.

The pagination is controlled by the `limit` and `offset` arguments on a `listCollectionName` field, and it doesn't provide
any pagination metadata (e.g., total number of records, page number, and so on):

<SourceCodeTabs langSpecificTabOnly>

[Third page of results retrieval using list query example](/documentation/user/en/query/requirements/examples/paging/listEntities.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of list query pagination example
</NoteTitle>

The result contains the result from the 11th through the 15th record of the listing result. It returns only a primary key
of the records because no content request was specified, and it is sorted by the primary key in ascending order because
no order was specified in the query.

<LS to="g">

<MDInclude sourceVariable="data.listProduct">[The data chunk with paginated data](/documentation/user/en/query/requirements/examples/paging/listEntities.graphql.json.md)</MDInclude>

</LS>

</Note>

</LS>

<LS to="g">

## Pagination of `query` queries

The fully-featured [`query` queries](../../use/api/query-data.md#query-queries) support fully-featured pagination.
The pagination in this case has two versions - `page` (`recordPage` field) and `strip` (`recordStrip` field), and both provide pagination metadata.

### Page (`recordPage`)

</LS>
<LS to="e,j,r,c">

## Page

```evitaql-syntax
page(
    argument:int!,
    argument:int!,
    requireConstraint:spacing?
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
    <dt>requireConstraint:spacing?</dt>
    <dd>
        an optional constraint that specifies rules for leaving spaces on certain pages of the query result
        (see [spacing constraint](#spacing) chapter for more details)
    </dd>
</dl>

</LS>

The `page`
<LS to="e,j,r">(<SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/Page.java</SourceClass>)</LS><LS to="c">(<SourceClass>EvitaDB.Client/Queries/Requires/Page.cs</SourceClass>) requirement</LS>
<LS to="g">approach</LS>
controls the number and slice of entities returned in the query response<LS to="g"> and is specified by usage of the `recordPage` field (in combination with `number` and `size` arguments)</LS>.
If no
<LS to="e,j,r,c">page requirement is</LS>
<LS to="g">page arguments are</LS> used
<LS to="e,j,r,c">in the query</LS>
<LS to="g">on the field</LS>,
the default page `1` with the default page size `20` is used. If the requested page exceeds the number of available
pages, a result with the first page is returned. An empty result is only returned if the query returns no result at all
or the page size is set to zero. By automatically returning the first page result when the requested page is exceeded,
we try to avoid the need to issue a secondary request to fetch the data.

The information about the actual returned page and data statistics can be found in the query response, which is wrapped
in a so-called data chunk object. <LS to="e,j,r,c">In case of the `page` constraint,
the <LS to="e,j,r"><SourceClass>evita_common/src/main/java/io/evitadb/dataType/PaginatedList.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/DataTypes/PaginatedList.cs</SourceClass></LS> is used as data chunk
object.</LS> The data chunk object contains the following information:

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

The <LS to="e,j,r,c">`page` requirement</LS><LS to="g">`recordPage` field</LS>
is the most natural and commonly used requirement for the pagination of the query results.
To get the second page of the query result, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Second page of results retrieval example](/documentation/user/en/query/requirements/examples/paging/page.evitaql).

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of second page example
</NoteTitle>

The result contains the result from the 6th through the 10th record of the query result. It returns only a primary key
of the records because no content request was specified, and it is sorted by the primary key in ascending order because
no order was specified in the query.

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The data chunk with paginated data](/documentation/user/en/query/requirements/examples/paging/page.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[The data chunk with paginated data](/documentation/user/en/query/requirements/examples/paging/page.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The data chunk with paginated data](/documentation/user/en/query/requirements/examples/paging/page.rest.json.md)</MDInclude>

</LS>

</Note>

<LS to="g">

### Strip (`recordStrip`)

</LS>

<LS to="e,j,r,c">

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

</LS>

The `strip`
<LS to="e,j,r">(<SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/Strip.java</SourceClass>)</LS><LS to="c">(<SourceClass>EvitaDB.Client/Queries/Requires/Strip.cs</SourceClass>) requirement</LS>
<LS to="g">approach</LS>
controls the number and slice of entities returned in the query response<LS to="g"> and is specified by usage of the `recordStrip` field (in combination with `limit` and `offset` arguments)</LS>.
If the requested strip exceeds the number of
available records, a result from the zero offset with retained limit is returned. An empty result is only returned if
the query returns no result at all or the limit is set to zero. By automatically returning the first strip result when
the requested page is exceeded, we try to avoid the need to issue a secondary request to fetch the data.

The information about the actual returned page and data statistics can be found in the query response, which is wrapped
in a so-called data chunk object. <LS to="e,j,r,c">In case of the `strip` constraint,
the <LS to="e,j,r"><SourceClass>evita_common/src/main/java/io/evitadb/dataType/StripList.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/DataTypes/StripList.cs</SourceClass></LS> is used as data chunk
object.</LS>The data chunk object contains the following information:

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

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The data chunk with strip list](/documentation/user/en/query/requirements/examples/paging/strip.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordStrip">[The data chunk with strip list](/documentation/user/en/query/requirements/examples/paging/strip.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The data chunk with strip list](/documentation/user/en/query/requirements/examples/paging/strip.rest.json.md)</MDInclude>

</LS>

</Note>

### Spacing

```evitaql-syntax
spacing(
    requireConstraint:gap+   
)
```

<dl>
    <dt>requireConstraint:gap+</dt>
    <dd>
        one or more constraints that specify rules for leaving spaces on certain pages of the query result
    </dd>
</dl>

The `spacing` requirement is a container for one or more `gap` constraints that specify rules for leaving spaces on
certain pages of the query result. It modifies the default behavior of the [`page`](#page) constraint and decreases
the number of records returned on particular pages. It also affects the total page count (the `lastPageNumber` property
of the <LS to="e,j,r"><SourceClass>evita_common/src/main/java/io/evitadb/dataType/PaginatedList.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/DataTypes/PaginatedList.cs</SourceClass></LS>). The [`gap`](#gap) rules are 
additive, and the gap sizes are summed up from all the gap rules that apply to the given page.

<Note type="info">

<NoteTitle toggles="true">

##### Performance considerations

</NoteTitle>

To avoid recalculation of the rule for each page in the query result, you could limit the scope by adding a constant
expression to the rule. For example, the rule `$pageNumber % 2 == 0 && $pageNumber <= 10` will be recalculated only for
the first 10 pages of the query result, because the interpreter knows that the rule will never be satisfied for the
remaining pages.

</Note>

Spacing constraints are helpful when you need to make space for additional content on certain pages of the query result,
such as advertisements, banners, blog posts or other external content that should be displayed between the records. Let's
say you want to display an advertisement on every even page until the 10th page, and also you want to display a blog post
on the 1st and 4th page. To achieve this, you need to use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Inserted spacing example](/documentation/user/en/query/requirements/examples/paging/spacing_page1.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of requested page with inserted spacing example
</NoteTitle>

The first page contains 9 records (one slot is left for blog post), the second page contains 9 records (one slot is left
for advertisement, because page number is even), and the fourth page contains only 8 records (one slot for blog post and
one for the advertisement, because page number is even), the last page number will be recalculated, because total of
7 records were left out on the leading pages. 

**First page:**

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The data chunk with strip list](/documentation/user/en/query/requirements/examples/paging/spacing_page1.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[The data chunk with strip list](/documentation/user/en/query/requirements/examples/paging/spacing_page1.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The data chunk with strip list](/documentation/user/en/query/requirements/examples/paging/spacing_page1.rest.json.md)</MDInclude>

</LS>

**Second page:**

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Inserted spacing example](/documentation/user/en/query/requirements/examples/paging/spacing_page2.evitaql)

</SourceCodeTabs>

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The data chunk with strip list](/documentation/user/en/query/requirements/examples/paging/spacing_page2.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[The data chunk with strip list](/documentation/user/en/query/requirements/examples/paging/spacing_page2.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The data chunk with strip list](/documentation/user/en/query/requirements/examples/paging/spacing_page2.rest.json.md)</MDInclude>

</LS>

**Fourth page:**

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Inserted spacing example](/documentation/user/en/query/requirements/examples/paging/spacing_page4.evitaql)

</SourceCodeTabs>

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The data chunk with strip list](/documentation/user/en/query/requirements/examples/paging/spacing_page4.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[The data chunk with strip list](/documentation/user/en/query/requirements/examples/paging/spacing_page4.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The data chunk with strip list](/documentation/user/en/query/requirements/examples/paging/spacing_page4.rest.json.md)</MDInclude>

</LS>

</Note>

### Gap

```evitaql-syntax
gap(
    argument:int!,
    argument:expression!
)
```

<dl>
    <dt>argument:int</dt>
    <dd>
        a mandatory number specifying the size of the gap to be left empty on the page (i.e. the number of records to 
        be skipped on the page)
    </dd>
    <dt>argument:expression</dt>
    <dd>
        <p>a mandatory [expression](../expression-language.md) that must be evaluated to a boolean value, indicating 
        whether or not the gap should be applied to the given page</p>
        <p>the expression can use the following variables:
            <ul>
                <li>`int`: `pageNumber` - the number of the page to be evaluated</li>
            </ul>
        </p>
    </dd>
</dl>

The `gap` requirements specifies single rule for leaving a gap of a certain size on a certain page identified by the
expression for each page. Detailed usage is documented in the [spacing constraint](#spacing) chapter.