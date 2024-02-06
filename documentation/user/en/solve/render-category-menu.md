---
title: Render category menu
perex: |
  The vast majority of catalogs view items through a hierarchically organized menu of categories of various kinds, 
  typically by displaying items from the category the user has selected, as well as from all subcategories of that 
  category. Because this is such a common scenario, evitaDB has a full set of expressive resources for this area, 
  while optimizing its indexes so that queries into the hierarchical structure are faster than queries without 
  this targeting.
date: '4.2.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---

Menu is a common way to navigate through the catalog. It is often used to display categories and subcategories. This 
chapter provides examples of how to render a category menu in typical scenarios. Menus can be rendered along with 
the listed items in a single request. You shouldn't need to issue a separate request to render the menu unless you are
pre-rendering the menu for caching purposes (which is a good practice for large menu variants such as [mega-menu](#mega-menu)). 
All examples in this chapter will query the `Product` collection to retrieve the associated category menu, but will 
not list the products themselves, as would be the case in a real-world scenario.

The sample queries also don't include any filtering constraints on the products. In a real-world scenario, you would 
typically want to filter the products by some criteria, such as availability, price, or other attributes. You would need 
to add these constraints to the query according to your requirements. The presence of such constraints would also affect
the results of the menu calculation, automatically discarding those categories that don't contain products matching 
the constraints (unless you allow [`LEAVE_EMPTY`](../query/requirements/hierarchy#hierarchy-of-reference) categories 
to remain).

## Mega menu

The mega menu usually lists two to three levels of categories and subcategories. It is often used in large e-commerce 
applications. It looks like this:

![Mega-menu example](../query/requirements/assets/mega-menu.png "Mega-menu example")

The following example shows how to get all the data needed to render a mega-menu in a single query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Requesting data for 2-level deep mega menu](documentation/user/en/solve/examples/render-category-menu/mega-menu.evitaql)

</SourceCodeTabs>

Which produces the following result:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.Hierarchy.referenceHierarchies.categories.megaMenu">[Result for mega-menu](documentation/user/en/solve/examples/render-category-menu/mega-menu.evitaql.json.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.hierarchy.categories.megaMenu">[Result for mega-menu](documentation/user/en/solve/examples/render-category-menu/mega-menu.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude sourceVariable="extraResults.hierarchy.categories.megaMenu">[Result for mega-menu](documentation/user/en/solve/examples/render-category-menu/mega-menu.rest.json.md)</MDInclude>

</LS>

Sometimes you'll want to list the number of products in each category. 
This can be accomplished by adding the <LS to="e,j,c,r">[`QUERIED_ENTITY_COUNT` statistics](../query/requirements/hierarchy#statistics)</LS><LS to="g">[`queriedEntityCount` statistics](../query/requirements/hierarchy#statistics)</LS> request to the query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Requesting data for 2-level deep mega menu with product statistics](documentation/user/en/solve/examples/render-category-menu/mega-menu-with-product-statistics.evitaql)

</SourceCodeTabs>

<Note type="warning">

<strong>Beware though!</strong> Calculating the statistics in this case will probably require going through all 
the products in the database (if they are attached to any of the categories in the hierarchy). This can be a costly 
operation, and we don't recommend doing it for every request. Consider pre-rendering the mega-menu and caching 
the rendered result. Or make sure that the evitaDB cache is enabled and properly configured. If the request for 
the mega-menu is repeated often, it should probably be cached, since calculating the menu is an expensive operation.

</Note>

The results now also include the number of products matching the filter in each of the categories:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.Hierarchy.referenceHierarchies.categories.megaMenu">[Result for mega-menu](documentation/user/en/solve/examples/render-category-menu/mega-menu-with-product-statistics.evitaql.json.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.hierarchy.categories.megaMenu">[Result for mega-menu](documentation/user/en/solve/examples/render-category-menu/mega-menu-with-product-statistics.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude sourceVariable="extraResults.hierarchy.categories.megaMenu">[Result for mega-menu](documentation/user/en/solve/examples/render-category-menu/mega-menu-with-product-statistics.rest.json.md)</MDInclude>

</LS>

## Dynamic collapsible menu

Another common scenario is the dynamic collapsible menu. It is similar to the mega menu, but is usually used 
in administration interfaces. To illustrate this type of menu, take a look at the following screen:

![Dynamic collapsible menu example](../query/requirements/assets/dynamic-tree.png "Dynamic collapsible menu example")

The menu shows only a single level of categories with the option to open each of them on demand. To render such a menu, 
you'd need a very simple query, but it must contain a request to calculate 
the <LS to="e,j,c,r">[`CHILDREN_COUNT` statistics](../query/requests/hierarchy#statistics)</LS><LS to="g">[`childrenCount` statistics](../query/requests/hierarchy#statistics)</LS>:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Requesting data for dynamic collapsible menu](documentation/user/en/solve/examples/render-category-menu/dynamic-collapsible-menu.evitaql)

</SourceCodeTabs>

The result will include the number of subcategories in each category, so you can display the plus sign next to 
the category name to allow the user to expand the category:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.Hierarchy.referenceHierarchies.categories.dynamicMenu">[Result for top level of dynamic menu](documentation/user/en/solve/examples/render-category-menu/dynamic-collapsible-menu.evitaql.json.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.hierarchy.categories.dynamicMenu">[Result for top level of dynamic menu](documentation/user/en/solve/examples/render-category-menu/dynamic-collapsible-menu.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude sourceVariable="extraResults.hierarchy.categories.dynamicMenu">[Result for top level of dynamic menu](documentation/user/en/solve/examples/render-category-menu/dynamic-collapsible-menu.rest.json.md)</MDInclude>

</LS>

Then, when the user expands the category, you can issue another query to retrieve the subcategories of the expanded 
categories in a similar way:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Requesting data for nested categories in the dynamic menu](documentation/user/en/solve/examples/render-category-menu/dynamic-collapsible-menu-sub-category.evitaql)

</SourceCodeTabs>

Note that the primary key of the parent category is used in the filter of the subhierarchy calculation requirement. 
Also, <LS to="e,j,c">`stop(level(1))`</LS><LS to="g,r">`stopAt: { level: 1 }`</LS> has been replaced by <LS to="e,j,c>`stop(distance(1))`</LS><LS to="g,r">`stopAt: { distance: 1 }`</LS>
because level is different for each category parent, while distance is relative to the parent node and allows us to 
express the retrieved depth in a more general way. 
The result will be identical to the root category listing:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.Hierarchy.referenceHierarchies.categories.dynamicMenuSubcategories">[Result for nested categories in the dynamic menu](documentation/user/en/solve/examples/render-category-menu/dynamic-collapsible-menu-sub-category.evitaql.json.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.hierarchy.categories.dynamicMenuSubcategories">[Result for nested categories in the dynamic menu](documentation/user/en/solve/examples/render-category-menu/dynamic-collapsible-menu-sub-category.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude sourceVariable="extraResults.hierarchy.categories.dynamicMenuSubcategories">[Result for nested categories in the dynamic menu](documentation/user/en/solve/examples/render-category-menu/dynamic-collapsible-menu-sub-category.rest.json.md)</MDInclude>

</LS>

## Listing sub-categories

It's quite common to list a few promoted subcategories of a current category just above the list of products. You can
find similar listings all over the web:

![Sub-categories listing example](../query/requirements/assets/category-listing.png "Sub-categories listing example")

The following query will help you retrieve such a list for any of the rendered category listings:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Requesting data for sub-categories listing](documentation/user/en/solve/examples/render-category-menu/sub-categories-listing.evitaql)

</SourceCodeTabs>

Because we're using the [`children`](../query/requirements/hierarchy#children) requirement, the result will be computed 
correctly even if the current category is changed in the `hierarchyWithin` filter part, and will always contain 
the currently filtered category along with the single level of its subcategories:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.Hierarchy.referenceHierarchies.categories.subcategories">[Result for sub-categories listing](documentation/user/en/solve/examples/render-category-menu/sub-categories-listing.evitaql.json.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.hierarchy.categories.subcategories">[Result for sub-categories listing](documentation/user/en/solve/examples/render-category-menu/sub-categories-listing.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude sourceVariable="extraResults.hierarchy.categories.subcategories">[Result for sub-categories listing](documentation/user/en/solve/examples/render-category-menu/sub-categories-listing.rest.json.md)</MDInclude>

</LS>

## Hybrid menu

There are many variations of the menu, but let's end our article with an example of a hybrid menu. This menu is often
used as a kind of vertical menu that displays root-level categories, with an open axis to the currently selected 
category, accompanied by a sibling category of the same level. It looks like this:

![Hybrid menu example](../query/requirements/assets/hybrid-menu.png "Hybrid menu example")

This menu has to be combined from three calculated results. The first one, called `topLevel`, will contain 
the categories of the root level, the second one, called `siblings`, will contain the siblings of the currently selected
category, and the third one, called `parents`, will contain the parents of the selected category. By combining these 
three results, you can easily render the hybrid menu:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Requesting data for hybrid menu](documentation/user/en/solve/examples/render-category-menu/hybrid-menu.evitaql)

</SourceCodeTabs>

The result will be the root level categories and the siblings of the currently selected category:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.Hierarchy.referenceHierarchies.categories">[Result for hybrid menu](documentation/user/en/solve/examples/render-category-menu/hybrid-menu.evitaql.json.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.hierarchy.categories">[Result for hybrid menu](documentation/user/en/solve/examples/render-category-menu/hybrid-menu.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude sourceVariable="extraResults.hierarchy.categories">[Result for hybrid menu](documentation/user/en/solve/examples/render-category-menu/hybrid-menu.rest.json.md)</MDInclude>

</LS>


## Hiding parts of the category tree

Sometimes you may have noticed that a certain part of the shelves in shopping malls is behind a curtain - because a new 
sales area with a specialized offer is being prepared. Similarly, in catalogs, new sections are often prepared that only 
the people working on them have access to. In our demo dataset we have an attribute called `status` which can have 
either the value `ACTIVE` or `PRIVATE`. The value `ACTIVE` means that the category is not yet ready for the public and 
therefore should not be visible in the menu and accessible. To achieve this, you can list the products and render 
the menu for visitors using the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Requesting data for a menu excluding private categories](documentation/user/en/solve/examples/render-category-menu/excluding-private-categories.evitaql)

</SourceCodeTabs>

Temporary offers can be handled in a similarly elegant way. Suppose we want to prepare a *"Christmas electronics"* in 
the *Accessories* category in advance, which includes LED Christmas tree lights, pyrotechnics, and so on. If we create 
an attribute of type `DateTimeRange` named `validity` in the category entity and set its value to Christmas only 
(as we did in our demo dataset), we can then define the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Requesting data for a menu excluding categories with expired validity](documentation/user/en/solve/examples/render-category-menu/excluding-expired-categories.evitaql)

</SourceCodeTabs>

So: list for me all products in the category `accessories`, assuming they are in a category without a defined validity
or have a defined validity range that includes the current moment. Notice that there is no *"Christmas electronics"* 
category in the result, because it is not valid at the moment. But if we modify the query a little bit to rewind 
the time to the Christmas season, we will get the category in the result:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Requesting data for a menu at the Christmas time](documentation/user/en/solve/examples/render-category-menu/excluding-expired-categories-at-correct-time.evitaql)

</SourceCodeTabs>

<Note type="info">

Some items tend to be listed in more than one category - for example, you may see chewing gum in the *candy* section
of a store, but also in the checkout area, among the products you have time to look at before paying. If a department 
store fences off the candy section because they are redesigning it, should you no longer be able to buy chewing gum at
the checkout? Of course not. evitaDB will do the same, and if it finds even one product reference in the visible part 
of the hierarchical tree, it will include that product in the search results.

</Note>
