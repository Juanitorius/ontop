package it.unibz.inf.ontop.utils;

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

import com.google.common.collect.ImmutableList;
import it.unibz.inf.ontop.model.*;
import it.unibz.inf.ontop.model.Function;
import it.unibz.inf.ontop.model.Predicate.COL_TYPE;
import it.unibz.inf.ontop.parser.SQLQueryDeepParser;
import it.unibz.inf.ontop.sql.*;
import it.unibz.inf.ontop.sql.api.*;
import it.unibz.inf.ontop.model.CQIE;
import it.unibz.inf.ontop.model.Constant;
import it.unibz.inf.ontop.model.OBDAMappingAxiom;
import it.unibz.inf.ontop.model.OBDASQLQuery;
import it.unibz.inf.ontop.model.Predicate;
import it.unibz.inf.ontop.model.Term;
import it.unibz.inf.ontop.model.Variable;
import it.unibz.inf.ontop.sql.RDBMetadata;

import java.util.*;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.arithmetic.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.SubSelect;

import com.google.common.collect.ImmutableMap;

import static it.unibz.inf.ontop.model.impl.OntopModelSingletons.DATA_FACTORY;

public class Mapping2DatalogConverter implements IMapping2DatalogConverter {

	/**
	 * Creates a mapping analyzer by taking into account the OBDA model.
	 */
	public ImmutableList<CQIE> constructDatalogProgram(Collection<OBDAMappingAxiom> mappings, DBMetadata metadata) {

        if (!(metadata instanceof RDBMetadata)) {
            throw new IllegalArgumentException("A DBMetadata was expected");
        }

        RDBMetadata dbMetadata = (RDBMetadata) metadata;
		
		ImmutableList.Builder<CQIE> listBuilder = ImmutableList.builder();
		List<String> errorMessages = new ArrayList<>();
		
		QuotedIDFactory idfac = dbMetadata.getQuotedIDFactory();
		
		for (OBDAMappingAxiom mappingAxiom : mappings) {
			try {
				// Obtain the target and source query from each mapping axiom in
				// the model.

				OBDASQLQuery sourceQuery = (OBDASQLQuery) mappingAxiom.getSourceQuery();

				// Parse the SQL query tree from the source query
				ParsedSQLQuery parsedSQLQuery = SQLQueryDeepParser.parse(dbMetadata, sourceQuery.toString());

				// Create a lookup table for variable swapping
				AttributeLookupTable lookupTable = createLookupTable(parsedSQLQuery, dbMetadata, idfac);

				// Construct the body from the source query
				List<Function> bodyAtoms = new ArrayList<>();

                // For each table, creates an atom and adds it to the body
                addTableAtoms(bodyAtoms, parsedSQLQuery, lookupTable, dbMetadata);

                // For each join condition, creates an atom and adds it to the body
                List<Expression> joinConditions = parsedSQLQuery.getJoinConditions();
                for (Expression condition : joinConditions) {
                    Expression2FunctionConverter visitor = new Expression2FunctionConverter(lookupTable, idfac);
                    Term atom = visitor.visitEx(condition);
                    bodyAtoms.add((Function) atom);
                }

                // For the "where" clause, creates an atom and adds it to the body
                Expression conditions = parsedSQLQuery.getWhereClause();
                if (conditions != null) {
                    Expression2FunctionConverter converter = new Expression2FunctionConverter(lookupTable, idfac);
                    Function filterFunction =  converter.convert(conditions);
                    bodyAtoms.add(filterFunction);
                }

                // For each body atom in the target query,
                //  (1) renameVariables its variables and
                //  (2) use it as the head atom of a new rule
                List<Function> targetQuery = mappingAxiom.getTargetQuery();
                for (Function atom : targetQuery) {
                    // Construct the head from the target query 
                	// (includes dealing with functions like concat as well).
                    Function head = (Function)renameVariables(atom, lookupTable, idfac);
                    // Create a new rule from the new head and the body
                    CQIE rule = DATA_FACTORY.getCQIE(head, bodyAtoms);
                    listBuilder.add(rule);
                }
			} 
			catch (Exception e) {
				errorMessages.add("Error in mapping with id: " + mappingAxiom.getId()
                        + " \n Description: " + e.getMessage()
                        + " \nMapping: [" + mappingAxiom.toString() + "]");
				break;
			}
		}

		if (errorMessages.size() > 0) {
			StringBuilder errors = new StringBuilder();
			for (String error : errorMessages) {
				errors.append(error + "\n");
			}
			final String msg = "There was an error analyzing the following mappings. Please correct the issue(s) to continue.\n"
					+ errors.toString();

			throw  new IllegalArgumentException(msg);
		}

		return listBuilder.build();
	}


    
    /**
     * For each table, creates an atom and adds it to the body
     * @param bodyAtoms
     *  will be extended
     * @param parsedSQLQuery
     * @param lookupTable
     */
    private static void addTableAtoms(List<Function> bodyAtoms, ParsedSQLQuery parsedSQLQuery, AttributeLookupTable lookupTable, RDBMetadata dbMetadata) throws JSQLParserException {
        // Tables mentioned in the SQL query
        Map<RelationID, RelationID> tables = parsedSQLQuery.getTables();

        for (Map.Entry<RelationID, RelationID> entry : tables.entrySet()) {
        	RelationID relationId = entry.getValue();
           	RelationID aliasId = entry.getKey();
                   	
            // Construct the predicate using the table name
            RelationDefinition td = dbMetadata.getRelation(relationId);
            List<Term> terms = new ArrayList<>(td.getAttributes().size());
            // Swap the column name with a new variable from the lookup table
            for (Attribute attribute : td.getAttributes()) {
            	Term term = lookupTable.get(new QualifiedAttributeID(aliasId, attribute.getID()));
            	if (term == null)
            		term = lookupTable.get(new QualifiedAttributeID(null, attribute.getID()));
                if (term == null) 
                    throw new IllegalStateException("Column '" + aliasId + "." + attribute.getID() + "'was not found in the lookup table: ");
                
                terms.add(term);
            }
            // Create an atom for a particular table
            Function atom = Relation2DatalogPredicate.getAtom(td, terms);
            bodyAtoms.add(atom);
        }
    }


    /**
     * Returns a new term by renaming variables occurring in the  {@code term}
     *  according to the {@code lookupTable}
     */
    private static Term renameVariables(Term term, AttributeLookupTable lookupTable, QuotedIDFactory idfac) {
 
        if (term instanceof Variable) {
            Variable var = (Variable) term;
            String[] varNameComponents = var.getName().split("\\.");
            String schemaName, tableName, attributeName;
            if (varNameComponents.length == 1) {
            	schemaName = tableName = null;
            	attributeName = varNameComponents[0];		
            }
            else if (varNameComponents.length == 2) {
            	schemaName = null;
            	tableName = varNameComponents[0];
            	attributeName = varNameComponents[1];
            } 	
            else if (varNameComponents.length == 3) {
            	schemaName = varNameComponents[0];
            	tableName = varNameComponents[1];
            	attributeName = varNameComponents[2];
            } 	
            else
            	throw new IllegalArgumentException("Wrong number of components in the column name " + var);

            // ROMAN (26 Sep 2015)
        	// HACKY WAY OF DEALING WITH VARIABLES THAT ARE CASE-SENSITIVE 
            RelationID relationId;
            if (tableName != null)
            	relationId = idfac.createRelationID(quote(idfac, schemaName), quote(idfac, tableName));
            else
            	relationId = null;
            QualifiedAttributeID a = new QualifiedAttributeID(relationId, 
            						idfac.createAttributeID(quote(idfac, attributeName)));         
            Term termR = lookupTable.get(a);
            
            if (termR == null) {
                if (tableName != null)
                	relationId = idfac.createRelationID(schemaName, tableName);
                else
                	relationId = null;
                a = new QualifiedAttributeID(relationId, idfac.createAttributeID(attributeName));
                termR = lookupTable.get(a);
            }

            // ROMAN (10 Oct 2015): hack for FQDN
            if (termR == null)
            	termR = lookupTable.get(new QualifiedAttributeID(null, 
						idfac.createAttributeID(quote(idfac, attributeName))));
            
            if (termR == null)
            	termR = lookupTable.get(new QualifiedAttributeID(null, 
						idfac.createAttributeID(attributeName)));
            
            if (termR == null) {
                String messageFormat = "Error in identifying column name \"%s\", " +
                        "please check the query source in the mappings.\n" +
                        "Possible reasons:\n" +
                        "1. The name is ambiguous, or\n" +
                        "2. The name is not defined in the database schema.";
                final String msg = String.format(messageFormat, var) + "\n" + lookupTable;
                throw new RuntimeException(msg);
            }
            return termR;
        } 
        else if (term instanceof Function) {
            Function func = (Function) term;
            List<Term> terms = func.getTerms();
            List<Term> newTerms = new ArrayList<>(terms.size());
            for (Term innerTerm : terms) 
                newTerms.add(renameVariables(innerTerm, lookupTable, idfac));
            
            return DATA_FACTORY.getFunction(func.getFunctionSymbol(), newTerms);
        } 
        else if (term instanceof Constant) {
            return term.clone();
        }
        throw new RuntimeException("Unknown term type");
    }

    private static String quote(QuotedIDFactory idfac, String s) {
    	if (s == null)
    		return s;
    	return idfac.getIDQuotationString() + s + idfac.getIDQuotationString();
    }
    
    private static final class AttributeLookupTable {
    	private final Map<QualifiedAttributeID, Term> lookupTable = new HashMap<>();
    	
    	void put(RelationID relationId, QuotedID attributeId, Term expression) {
    		QualifiedAttributeID qualifiedId = new QualifiedAttributeID(relationId, attributeId);
    		Term prev = lookupTable.put(qualifiedId, expression);
    		if (prev != null && !prev.equals(expression)) {
    			// System.err.println("DUPLICATE: " + prev + " AND " + expression + " FOR " + qualifiedId);
    			// there is another expression for the same qualified attribute id
    			// (i.e., ambiguous column name) 
    			// reset the value to null to indicate the ambiguity
    			// THIS DOES NOT MAKE MUCH SENSE BUT NEEDED FOR R2rmlJoinTest (WHICH HAS AMBIGUOUS REFS ANYWAY)
    			// lookupTable.put(qualifiedId, null);
    		}
    	}
    	
    	Term get(QualifiedAttributeID qualifiedId) {
    		return lookupTable.get(qualifiedId);
    	}
    	
    	@Override
		public String toString() {
    		return lookupTable.toString();
    	}
    }
    
    /**
     * Creates a lookupTable:
     * (1) Collects all the possible column names from the tables mentioned in the query, and aliases.
      */
    private static AttributeLookupTable createLookupTable(ParsedSQLQuery queryParsed, RDBMetadata dbMetadata, QuotedIDFactory idfac) throws JSQLParserException {
    	AttributeLookupTable lookupTable = new AttributeLookupTable();

		Map<RelationID, RelationID> tables = queryParsed.getTables();

		// Collect all known column aliases
		Map<QuotedID, Expression> aliasMap = queryParsed.getAliasMap();
		
		// assigned index number
		int index = 0; 

		for (Map.Entry<RelationID, RelationID> entry : tables.entrySet()) {
			
			RelationID relationId = entry.getValue();
			RelationDefinition tableDefinition = dbMetadata.getRelation(relationId);
            if (tableDefinition == null) 
                throw new RuntimeException("Definition not found for table '" + relationId + "'.");
            
 			for (Attribute attribute : tableDefinition.getAttributes()) {

				Term var = DATA_FACTORY.getVariable("t" + index);
				QuotedID attributeId = attribute.getID();

				lookupTable.put(null, attributeId, var);

				// full qualified attribute name using table alias
				RelationID tableAlias = entry.getKey();
				if (tableAlias != relationId) 
					lookupTable.put(tableAlias, attributeId, var);
				else {
					lookupTable.put(relationId.getSchemalessID(), attributeId, var);
					lookupTable.put(relationId, attributeId, var);
				}

				index++;
			}
		}
	
        for (Map.Entry<QuotedID, Expression> item : aliasMap.entrySet()) {
 			Expression2FunctionConverter visitor = new Expression2FunctionConverter(lookupTable, idfac);
			Term atom = visitor.visitEx(item.getValue());
            lookupTable.put(null, item.getKey(), atom);
        }
		
		return lookupTable;
	}
      
    
	private static final ImmutableMap<String, OperationPredicate> operations = 
			new ImmutableMap.Builder<String, OperationPredicate>()
				.put("=", ExpressionOperation.EQ)
				.put(">=", ExpressionOperation.GTE)
				.put(">", ExpressionOperation.GT)
				.put("<=", ExpressionOperation.LTE)
				.put("<", ExpressionOperation.LT)
				.put("<>", ExpressionOperation.NEQ)
				.put("!=", ExpressionOperation.NEQ)
				.put("+", ExpressionOperation.ADD)
				.put("-", ExpressionOperation.SUBTRACT)
				.put("*", ExpressionOperation.MULTIPLY)
				.put("/", ExpressionOperation.DIVIDE)
				.build();


    /**
     * This visitor class converts the SQL Expression to a Function
     */
    private static class Expression2FunctionConverter implements ExpressionVisitor {

        private final AttributeLookupTable lookupTable;
        private final QuotedIDFactory idfac; 

        private Term result;

        public Expression2FunctionConverter(AttributeLookupTable lookupTable, QuotedIDFactory idfac) {
            this.lookupTable = lookupTable;
        	this.idfac = idfac;
        }

        public Function convert(Expression expression){
            expression.accept(this);
            return (Function)result;
        }

        /**
         * Visits the expression and gets the result
         */
        private Term visitEx(Expression expression) {
            expression.accept(this);
            return this.result;
        }

        private void visitBinaryExpression(BinaryExpression expression){
            Expression left = expression.getLeftExpression();
            Expression right = expression.getRightExpression();

            Term t1 = visitEx(left);
            if (t1 == null)
                throw new RuntimeException("Unable to find column name for variable: " +left);

            Term t2 = visitEx(right);

            Function compositeTerm;
            
            //get boolean operation
            String op = expression.getStringExpression();
            Predicate p = operations.get(op);
            if (p != null) {
            	compositeTerm = DATA_FACTORY.getFunction(p, t1, t2);
            }
            else {
                switch (op) {
                case "AND":
                    compositeTerm = DATA_FACTORY.getFunctionAND(t1, t2);
                    break;
                case "OR":
                    compositeTerm = DATA_FACTORY.getFunctionOR(t1, t2);
                    break;
                case "LIKE":
                    compositeTerm = DATA_FACTORY.getSQLFunctionLike(t1, t2);
                    break;
                case "~":
                    compositeTerm = DATA_FACTORY.getFunction(ExpressionOperation.REGEX, t1, t2, DATA_FACTORY.getConstantLiteral(""));
                    break;
                case "~*":
                    compositeTerm = DATA_FACTORY.getFunction(ExpressionOperation.REGEX, t1, t2, DATA_FACTORY.getConstantLiteral("i")); // i flag for case insensitivity
                    break;
                case "!~":
                    compositeTerm = DATA_FACTORY.getFunctionNOT(
                            DATA_FACTORY.getFunction(ExpressionOperation.REGEX, t1, t2, DATA_FACTORY.getConstantLiteral("")));
                    break;
                case "!~*":
                    compositeTerm = DATA_FACTORY.getFunctionNOT(
                            DATA_FACTORY.getFunction(ExpressionOperation.REGEX,t1, t2, DATA_FACTORY.getConstantLiteral("i")));
                    break;
                case "REGEXP":
                    compositeTerm = DATA_FACTORY.getFunction(ExpressionOperation.REGEX, t1, t2, DATA_FACTORY.getConstantLiteral("i"));
                    break;
                case "REGEXP BINARY":
                    compositeTerm = DATA_FACTORY.getFunction(ExpressionOperation.REGEX, t1, t2, DATA_FACTORY.getConstantLiteral(""));
                    break;
                default:
                    throw new UnsupportedOperationException("Unknown operator: " + op);
                }
            }
            result = compositeTerm;
        }

        @Override
        public void visit(NullValue nullValue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(net.sf.jsqlparser.expression.Function func) {
            if (func.getName().toLowerCase().equals("regexp_like")) {

                List<Expression> expressions = func.getParameters().getExpressions();
                if (expressions.size() == 2 || expressions.size() == 3) {
                    // first parameter is a source_string, generally a column
                    Expression first = expressions.get(0);
                    Term t1 = visitEx(first);
                    if (t1 == null)
                        throw new RuntimeException("Unable to find column name for variable: "
                                + first);

                    // second parameter is a pattern, so generally a regex string
                    Expression second = expressions.get(1);
                    Term t2 = visitEx(second);

                    /*
                     * Term t3 is optional for match_parameter in regexp_like
			         */
                    Term t3;
                    if (expressions.size() == 3){
                        Expression third = expressions.get(2);
                        t3 = visitEx(third);
                    } 
                    else {
                        t3 = DATA_FACTORY.getConstantLiteral("");
                    }
                    result = DATA_FACTORY.getFunction(ExpressionOperation.REGEX, t1, t2, t3);
                } 
                else
                	throw new UnsupportedOperationException("Wrong number of arguments (found " + expressions.size() + ", only 2 or 3 supported) to sql function Regex");
            } 
            else if (func.getName().toLowerCase().endsWith("replace")) {

                List<Expression> expressions = func.getParameters().getExpressions();
                if (expressions.size() == 2 || expressions.size() == 3) {

                    Term t1; // first parameter is a function expression
                    Expression first = expressions.get(0);
                    t1 = visitEx(first);

                    if (t1 == null)
                        throw new RuntimeException("Unable to find source expression: "
                                + first);

                    // second parameter is a string
                    Expression second = expressions.get(1);
                    Term out_string = visitEx(second);
                    
                    /*
                     * Term t3 is optional: no string means delete occurrences of second param
			         */
                    Term in_string;
                    if (expressions.size() == 3) {
                        Expression third = expressions.get(2);
                        in_string = visitEx(third);
                    } 
                    else {
                        in_string = DATA_FACTORY.getConstantLiteral("");
                    }
                    result = DATA_FACTORY.getFunction(ExpressionOperation.REPLACE, t1, out_string, in_string,
                                DATA_FACTORY.getConstantLiteral("")); // the 4th argument is flags
                } 
                else
                    throw new UnsupportedOperationException("Wrong number of arguments (found " + expressions.size() + ", only 2 or 3 supported) to sql function REPLACE");
            }  
            else if (func.getName().toLowerCase().endsWith("concat")){

                List<Expression> expressions = func.getParameters().getExpressions();

                int nParameters = expressions.size();
                Function topConcat = null;

                for (int i= 0; i<nParameters; i+=2) {

                    if (topConcat == null){

                        Expression first = expressions.get(i);
                        Term first_string = visitEx(first);

                        Expression second = expressions.get(i+1);
                        Term second_string = visitEx(second);

                        topConcat = DATA_FACTORY.getFunction(ExpressionOperation.CONCAT, first_string, second_string);
                    }
                    else {

                        Expression second = expressions.get(i);
                        Term second_string = visitEx(second);

                        topConcat = DATA_FACTORY.getFunction(ExpressionOperation.CONCAT, topConcat, second_string);
                    }

                }

                result = topConcat;
            } 
            else {
                throw new UnsupportedOperationException("Unsupported expression " + func);
            }
        }

        @Override
        public void visit(SignedExpression signedExpression) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(JdbcParameter jdbcParameter) {
            // do nothing
        }

        @Override
        public void visit(JdbcNamedParameter jdbcNamedParameter) {
            // do nothing
        }

        @Override
        public void visit(DoubleValue expression) {
            String termRightName = expression.toString();
            result = DATA_FACTORY.getConstantLiteral(termRightName, COL_TYPE.DOUBLE);
        }

        @Override
        public void visit(LongValue expression) {
            String termRightName = expression.getStringValue();
            result = DATA_FACTORY.getConstantLiteral(termRightName, COL_TYPE.LONG);
        }

        @Override
        public void visit(DateValue expression) {
            String termRightName = expression.getValue().toString();
            result = DATA_FACTORY.getConstantLiteral(termRightName, COL_TYPE.DATE);
        }

        @Override
        public void visit(TimeValue expression) {
            String termRightName = expression.getValue().toString();
            result = DATA_FACTORY.getConstantLiteral(termRightName, COL_TYPE.TIME);
        }

        @Override
        public void visit(TimestampValue expression) {
            String termRightName = expression.getValue().toString();
            result = DATA_FACTORY.getConstantLiteral(termRightName, COL_TYPE.DATETIME);
        }

        @Override
        public void visit(Parenthesis expression) {
            Expression inside = expression.getExpression();

            //Consider the case of NOT(...)
            if (expression.isNot()) {
                result = DATA_FACTORY.getFunctionNOT(visitEx(inside));
            } else {
                result = visitEx(inside);
            }
        }

        @Override
        public void visit(StringValue expression) {
            String termRightName = expression.getValue();
            result = DATA_FACTORY.getConstantLiteral(termRightName, COL_TYPE.STRING);
        }

        @Override
        public void visit(Addition addition) {
            visitBinaryExpression(addition);
        }

        @Override
        public void visit(Division division) {
            visitBinaryExpression(division);
        }

        @Override
        public void visit(Multiplication multiplication) {
            visitBinaryExpression(multiplication);
        }

        @Override
        public void visit(Subtraction subtraction) {
            visitBinaryExpression(subtraction);
        }

        @Override
        public void visit(AndExpression andExpression) {
            visitBinaryExpression(andExpression);
        }

        @Override
        public void visit(OrExpression orExpression) {
            visitBinaryExpression(orExpression);
        }

        @Override
        public void visit(Between expression) {
            Expression left = expression.getLeftExpression();
            Expression e1 = expression.getBetweenExpressionStart();
            Expression e2 = expression.getBetweenExpressionEnd();

            GreaterThanEquals gte = new GreaterThanEquals();
            gte.setLeftExpression(left);
            gte.setRightExpression(e1);

            MinorThanEquals mte = new MinorThanEquals();
            mte.setLeftExpression(left);
            mte.setRightExpression(e2);

            AndExpression e = new AndExpression(gte, mte);

            result = visitEx(e);
        }

        @Override
        public void visit(EqualsTo expression) {
            visitBinaryExpression(expression);
        }

        @Override
        public void visit(GreaterThan expression) {
            visitBinaryExpression(expression);
        }

        @Override
        public void visit(GreaterThanEquals expression) {
            visitBinaryExpression(expression);
        }

        @Override
        public void visit(InExpression expression) {
            Expression left = expression.getLeftExpression();
            ExpressionList rightItemsList = (ExpressionList) expression.getRightItemsList();

            List<EqualsTo> equalsToList = new ArrayList<>();
            for (Expression item : rightItemsList.getExpressions()) {
                EqualsTo eq = new EqualsTo();
                eq.setLeftExpression(left);
                eq.setRightExpression(item);
                equalsToList.add(eq);
            }
            int size = equalsToList.size();
            if (size > 1) {
                OrExpression or = new OrExpression(equalsToList.get(size - 1),
                        equalsToList.get(size - 2));
                for (int i = size - 3; i >= 0; i--) {
                    or = new OrExpression(equalsToList.get(i), or);
                }
                result = visitEx(or);
            } else {
                result = visitEx(equalsToList.get(0));
            }
        }

        @Override
        public void visit(IsNullExpression expression) {
            Column column = (Column)expression.getLeftExpression();
            Term var = getVariable(column);
            if (var == null) {
                throw new RuntimeException(
                        "Unable to find column name for variable: " + column);
            }

            if (!expression.isNot()) {
                result = DATA_FACTORY.getFunctionIsNull(var);
            } else {
                result = DATA_FACTORY.getFunctionIsNotNull(var);
            }
        }

        @Override
        public void visit(LikeExpression likeExpression) {
            visitBinaryExpression(likeExpression);
        }

        @Override
        public void visit(MinorThan minorThan) {
            visitBinaryExpression(minorThan);
        }

        @Override
        public void visit(MinorThanEquals minorThanEquals) {
            visitBinaryExpression(minorThanEquals);
        }

        @Override
        public void visit(NotEqualsTo notEqualsTo) {
            visitBinaryExpression(notEqualsTo);
        }

        @Override
        public void visit(Column expression) {
        	
            Term term = getVariable(expression);

            if (term != null) {
                /*
                 * If the termName is not null, create a variable
                 */
                result = term;
            } 
            else {
                // Constructs constant
                // if the columns contains a boolean value
                String columnName = expression.getColumnName();
                // check whether it is an SQL boolean value
                String lowerCase = columnName.toLowerCase();
                if (lowerCase.equals("true")) {
                    result = DATA_FACTORY.getBooleanConstant(true);
                }
                else if (lowerCase.equals("false")) {
                	result = DATA_FACTORY.getBooleanConstant(false);
                }
                else
                    throw new RuntimeException( "Unable to find column name for variable: "
                                    + columnName);
            }

        }
        

        private Term getVariable(Column expression) {
        	QuotedID column = idfac.createAttributeID(expression.getColumnName());
        	RelationID relation = null;
        	if (expression.getTable().getName() != null)
        		relation = idfac.createRelationID(expression.getTable().getSchemaName(), expression.getTable().getName());
        	
        	QualifiedAttributeID qa = new QualifiedAttributeID(relation, column);
        	
            return lookupTable.get(qa);
        }
        
        @Override
        public void visit(SubSelect subSelect) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(CaseExpression caseExpression) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(WhenClause whenClause) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(ExistsExpression existsExpression) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(AllComparisonExpression allComparisonExpression) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(AnyComparisonExpression anyComparisonExpression) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(Concat concat) {
        	Expression left = concat.getLeftExpression();
        	Expression right = concat.getRightExpression();
        	Term l = visitEx(left);
        	Term r = visitEx(right);
        	result = DATA_FACTORY.getFunction(ExpressionOperation.CONCAT, l, r);
        }

        @Override
        public void visit(Matches matches) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(BitwiseAnd bitwiseAnd) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(BitwiseOr bitwiseOr) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(BitwiseXor bitwiseXor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(CastExpression expression) {
            // TODO
            Expression column = expression.getLeftExpression();
            String columnName = column.toString();
        //    String variableName = lookupTable.lookup(columnName);
        //    if (variableName == null) {
        //        throw new RuntimeException(
        //                "Unable to find column name for variable: " + columnName);
        //    }
        //    Term var = DATA_FACTORY.getVariable(variableName);

       //     ColDataType datatype = expression.getType();



        //    Term var2 = null;

            //first value is a column, second value is a datatype. It can  also have the size

        //    result = DATA_FACTORY.getFunctionCast(var, var2);

        }

        @Override
        public void visit(Modulo modulo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(AnalyticExpression expression) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(ExtractExpression expression) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(IntervalExpression expression) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(OracleHierarchicalExpression expression) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(RegExpMatchOperator expression) {
            visitBinaryExpression(expression);
        }

        @Override
        public void visit(JsonExpression jsonExpr) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void visit(RegExpMySQLOperator regExpMySQLOperator) {
            visitBinaryExpression(regExpMySQLOperator);
        }
    }
}
