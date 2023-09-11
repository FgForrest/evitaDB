EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("code", "audio")
        		)
        	),
        	Require(
        		HierarchyOfReference(
        			"categories",
        			RemoveEmpty,
        			FromNode(
        				"sideMenu1",
        				Node(
        					FilterBy(
        						AttributeEquals("code", "portables")
        					)
        				),
        				EntityFetch(
        					AttributeContent("code")
        				),
        				StopAt(
        					Distance(1)
        				),
        				Statistics(WithoutUserFilter, ChildrenCount, QueriedEntityCount)
        			),
        			FromNode(
        				"sideMenu2",
        				Node(
        					FilterBy(
        						AttributeEquals("code", "laptops")
        					)
        				),
        				EntityFetch(
        					AttributeContent("code")
        				),
        				StopAt(
        					Distance(1)
        				),
        				Statistics(WithoutUserFilter, ChildrenCount, QueriedEntityCount)
        			)
        		)
        	)
        )
	)
);