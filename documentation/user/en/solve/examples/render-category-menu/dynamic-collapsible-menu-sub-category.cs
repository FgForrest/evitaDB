EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	Require(
        		HierarchyOfReference(
        			"categories",
        			RemoveEmpty,
        			FromNode(
        				"dynamicMenuSubcategories",
        				Node(
        					FilterBy(
        						EntityPrimaryKeyInSet(66482)
        					)
        				),
        				EntityFetch(
        					AttributeContent("code")
        				),
        				StopAt(
        					Distance(1)
        				),
        				Statistics(WithoutUserFilter, ChildrenCount)
        			)
        		),
        		Page(1, 0)
        	)
        )
	)
);