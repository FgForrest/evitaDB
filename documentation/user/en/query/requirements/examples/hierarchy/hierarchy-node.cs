EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("code", "accessories")
        		)
        	),
        	Require(
        		HierarchyOfReference(
        			"categories",
        			RemoveEmpty,
        			Children(
        				"subMenu",
        				EntityFetch(
        					AttributeContent("code")
        				),
        				StopAt(
        					Node(
        						FilterBy(
        							AttributeStartsWith("code", "w")
        						)
        					)
        				)
        			)
        		)
        	)
        )
	)
);