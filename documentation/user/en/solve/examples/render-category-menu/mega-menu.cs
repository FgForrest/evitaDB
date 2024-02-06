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
        				"megaMenu",
        				EntityFetch(
        					AttributeContent("code")
        				),
        				StopAt(
        					Level(2)
        				)
        			)
        		),
        		Page(1, 0)
        	)
        )
	)
);