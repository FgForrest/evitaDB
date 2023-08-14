EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("code", "true-wireless")
        		)
        	),
        	Require(
        		HierarchyOfReference(
        			"categories",
        			RemoveEmpty,
        			Parents(
        				"parentAxis",
        				EntityFetch(
        					AttributeContent("code")
        				),
        				Statistics(WithoutUserFilter, ChildrenCount, QueriedEntityCount)
        			)
        		)
        	)
        )
	)
);