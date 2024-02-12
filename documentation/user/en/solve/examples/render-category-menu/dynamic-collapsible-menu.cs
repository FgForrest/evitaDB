EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	Require(
        		HierarchyOfReference(
        			"categories",
        			RemoveEmpty,
        			FromRoot(
        				"dynamicMenu",
        				EntityFetch(
        					AttributeContent("code")
        				),
        				StopAt(
        					Level(1)
        				),
        				Statistics(WithoutUserFilter, ChildrenCount)
        			)
        		),
        		Page(1, 0)
        	)
        )
	)
);