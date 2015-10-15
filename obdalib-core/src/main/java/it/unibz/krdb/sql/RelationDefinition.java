package it.unibz.krdb.sql;

/*
 * #%L
 * ontop-obdalib-core
 * %%
 * Copyright (C) 2009 - 2014 Free University of Bozen-Bolzano
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Basis of the representation for information on relational tables and views
 * (attributes and integrity constraints: primary keys, unique keys and foreign keys)
 * 
 * @author Roman Kontchakov
 *
 */

public abstract class RelationDefinition {

	private final RelationID id;
	
	protected RelationDefinition(RelationID id) {
		this.id = id;
	}

	public RelationID getID() {
		return id;
	}
	
	public abstract Attribute getAttribute(int index);

	public abstract List<Attribute> getAttributes();
}
