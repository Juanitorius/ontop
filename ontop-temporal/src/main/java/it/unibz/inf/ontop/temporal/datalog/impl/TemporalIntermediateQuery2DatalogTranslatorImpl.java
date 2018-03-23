package it.unibz.inf.ontop.temporal.datalog.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import it.unibz.inf.ontop.datalog.*;
import it.unibz.inf.ontop.datalog.exception.DatalogConversionException;
import it.unibz.inf.ontop.datalog.impl.IntermediateQuery2DatalogTranslatorImpl;
import it.unibz.inf.ontop.datalog.impl.MutableQueryModifiersImpl;
import it.unibz.inf.ontop.datalog.impl.OrderByLifter;
import it.unibz.inf.ontop.injection.TemporalIntermediateQueryFactory;
import it.unibz.inf.ontop.iq.IQTree;
import it.unibz.inf.ontop.iq.IntermediateQuery;
import it.unibz.inf.ontop.iq.UnaryIQTree;
import it.unibz.inf.ontop.iq.exception.EmptyQueryException;
import it.unibz.inf.ontop.iq.node.*;
import it.unibz.inf.ontop.iq.tools.IQConverter;
import it.unibz.inf.ontop.model.atom.AtomFactory;
import it.unibz.inf.ontop.model.atom.AtomPredicate;
import it.unibz.inf.ontop.model.atom.DataAtom;
import it.unibz.inf.ontop.model.atom.DistinctVariableOnlyDataAtom;
import it.unibz.inf.ontop.model.term.*;
import it.unibz.inf.ontop.model.term.impl.ImmutabilityTools;
import it.unibz.inf.ontop.substitution.ImmutableSubstitution;
import it.unibz.inf.ontop.substitution.SubstitutionFactory;
import it.unibz.inf.ontop.utils.ImmutableCollectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Stream;

public class TemporalIntermediateQuery2DatalogTranslatorImpl implements IntermediateQuery2DatalogTranslator{
    private final TemporalIntermediateQueryFactory iqFactory;
    private final AtomFactory atomFactory;
    private final SubstitutionFactory substitutionFactory;
    private final DatalogFactory datalogFactory;
    private final ImmutabilityTools immutabilityTools;;
    private final OrderByLifter orderByLifter;
    private final IQConverter iqConverter;

private static class RuleHead {
    public final ImmutableSubstitution<ImmutableTerm> substitution;
    public final DataAtom atom;
    public final Optional<QueryNode> optionalChildNode;

    private RuleHead(ImmutableSubstitution<ImmutableTerm> substitution, DataAtom atom, Optional<QueryNode> optionalChildNode) {
        this.atom = atom;
        this.substitution = substitution;
        this.optionalChildNode = optionalChildNode;
    }
}


    private static final Logger LOG = LoggerFactory.getLogger(IntermediateQuery2DatalogTranslatorImpl.class);

    // Incremented
    private int subQueryCounter;
    private int dummyPredCounter;

    @Inject
    private TemporalIntermediateQuery2DatalogTranslatorImpl(TemporalIntermediateQueryFactory iqFactory, AtomFactory atomFactory,
                                                            SubstitutionFactory substitutionFactory, DatalogFactory datalogFactory,
                                                            ImmutabilityTools immutabilityTools, OrderByLifter orderByLifter, IQConverter iqConverter) {
        this.iqFactory = iqFactory;
        this.atomFactory = atomFactory;
        this.substitutionFactory = substitutionFactory;
        this.datalogFactory = datalogFactory;
        this.immutabilityTools = immutabilityTools;
        this.orderByLifter = orderByLifter;
        this.iqConverter = iqConverter;
        this.subQueryCounter = 0;
        this.dummyPredCounter = 0;
    }

    /**
     * Translate an intermediate query tree into a Datalog program.
     *
     * Each (strict) subquery will be translated as a rule with head Pred(var_1, .., var_n),
     * where the string for Pred is of the form SUBQUERY_PRED_PREFIX + y,
     * with y > subqueryCounter.
     */
    @Override
    public DatalogProgram translate(IntermediateQuery initialQuery) {

        IntermediateQuery orderLiftedQuery = liftOrderBy(initialQuery);
        Optional<MutableQueryModifiers> optionalModifiers =  extractTopQueryModifiers(orderLiftedQuery);

        // Mutable
        DatalogProgram dProgram;
        if (optionalModifiers.isPresent()){
            MutableQueryModifiers mutableModifiers = optionalModifiers.get();

            dProgram = datalogFactory.getDatalogProgram(mutableModifiers);
        }
        else {
            dProgram = datalogFactory.getDatalogProgram();
        }

        normalizeIQ(orderLiftedQuery)
                .forEach(q -> translate(q,  dProgram));

        return dProgram;
    }

     /* Assumes that ORDER BY is ABOVE the first construction node
	 * and the order between these operators is respected and they appear ONE time maximum
	 */
    private Optional<MutableQueryModifiers> extractTopQueryModifiers(IntermediateQuery query) {
        QueryNode rootNode = query.getRootNode();
        if (rootNode instanceof QueryModifierNode) {
            Optional<SliceNode> sliceNode = Optional.of(rootNode)
                    .filter(n -> n instanceof SliceNode)
                    .map(n -> (SliceNode)n);

            QueryNode firstNonSliceNode = sliceNode
                    .flatMap(query::getFirstChild)
                    .orElse(rootNode);

            Optional<DistinctNode> distinctNode = Optional.of(firstNonSliceNode)
                    .filter(n -> n instanceof DistinctNode)
                    .map(n -> (DistinctNode) n);

            QueryNode firstNonSliceDistinctNode = distinctNode
                    .flatMap(query::getFirstChild)
                    .orElse(firstNonSliceNode);

            Optional<OrderByNode> orderByNode = Optional.of(firstNonSliceDistinctNode)
                    .filter(n -> n instanceof OrderByNode)
                    .map(n -> (OrderByNode) n);

            MutableQueryModifiers mutableQueryModifiers = new MutableQueryModifiersImpl();

            sliceNode.ifPresent(n -> {
                n.getLimit()
                        .ifPresent(mutableQueryModifiers::setLimit);
                long offset = n.getOffset();
                if (offset > 0)
                    mutableQueryModifiers.setOffset(offset);
            });

            if(distinctNode.isPresent())
                mutableQueryModifiers.setDistinct();

            orderByNode
                    .ifPresent(n -> n.getComparators()
                            .forEach(c -> convertOrderComparator(c, mutableQueryModifiers)));

            return Optional.of(mutableQueryModifiers);
        }
        else
            return Optional.empty();
    }

    private static void convertOrderComparator(OrderByNode.OrderComparator comparator,
                                               MutableQueryModifiers queryModifiers) {
        NonGroundTerm term = comparator.getTerm();
        if (term instanceof Variable)
            queryModifiers.addOrderCondition((Variable) term,
                    comparator.isAscending() ? OrderCondition.ORDER_ASCENDING : OrderCondition.ORDER_DESCENDING);
        else
            // TODO: throw a better exception
            throw new IllegalArgumentException("The Datalog representation only supports variable in order conditions");
    }




    /**
     * Move ORDER BY above the highest construction node (required by Datalog)
     */
    private IntermediateQuery liftOrderBy(IntermediateQuery iq) {
        QueryNode topNonQueryModifierNode = getFirstNonQueryModifierNode(iq);
        if (iq.getFirstChild(topNonQueryModifierNode)
                .filter(c -> c instanceof OrderByNode)
                .isPresent()) {
            return orderByLifter.liftOrderBy(iq);
        }
        return iq;
    }

    /**
     * Assumes that ORDER BY is ABOVE the first construction node
     */
    private QueryNode getFirstNonQueryModifierNode(IntermediateQuery query) {
        // Non-final
        QueryNode queryNode = query.getRootNode();
        while (queryNode instanceof QueryModifierNode) {
            queryNode = query.getFirstChild(queryNode).get();
        }
        return queryNode;
    }

    private ImmutableList<IntermediateQuery> normalizeIQ(IntermediateQuery query) {
        IQTree tree = iqConverter.convert(query).getTree();
        while(tree.getRootNode() instanceof QueryModifierNode){
            tree = ((UnaryIQTree)tree).getChild();
        }
        return splitRootUnion(tree)
                .map(t -> enforceRootCn(t))
                .map(t -> convertAndCopyMetadata(t, query))
                .collect(ImmutableCollectors.toList());
    }

    private Stream<IQTree> splitRootUnion(IQTree tree) {
        return (tree.getRootNode() instanceof UnionNode) ?
                tree.getChildren().stream() :
                Stream.of(tree);
    }

    private IQTree enforceRootCn(IQTree tree) {
        return (tree.getRootNode() instanceof ConstructionNode)?
                tree:
                iqFactory.createUnaryIQTree(
                        iqFactory.createConstructionNode(tree.getVariables()),
                        tree
                );
    }

    private IntermediateQuery convertAndCopyMetadata(IQTree tree, IntermediateQuery query) {
        try {
            return iqConverter.convert(
                    iqFactory.createIQ(
                            query.getProjectionAtom(),
                            tree
                    ),
                    query.getDBMetadata(),
                    query.getExecutorRegistry()
            );
        } catch (EmptyQueryException e) {
            throw new DatalogConversionException("The query should not become empty");
        }
    }

    /**
     * Translate a given IntermediateQuery query object to datalog program.
     * Note that (the object ref of the) datalog program is passed as argument
     *
     * Assumption: the root is a construction node
     *
     * @return Datalog program that represents the construction of the SPARQL
     *         query.
     */
    private void translate(IntermediateQuery query, DatalogProgram pr) {
        QueryNode root = query.getRootNode();
        if(!(root instanceof ConstructionNode)){
            throw new DatalogConversionException("the root is expected to be a Construction Node");
        }

        ConstructionNode rootCn = (ConstructionNode) root;
        Queue<RuleHead> heads = new LinkedList<>();

        ImmutableSubstitution<ImmutableTerm> topSubstitution = Optional.of(rootCn)
                .map(ConstructionNode::getSubstitution)
                .orElseGet(substitutionFactory::getSubstitution);

        heads.add(new RuleHead(topSubstitution, query.getProjectionAtom(),query.getFirstChild(root)));

        // Mutable (append-only)
        Map<QueryNode, DataAtom> subQueryProjectionAtoms = new HashMap<>();
        subQueryProjectionAtoms.put(root, query.getProjectionAtom());

        //In heads we keep the heads of the sub-rules in the program, e.g. ans5() :- LeftJoin(....)
        while(!heads.isEmpty()) {

            RuleHead head = heads.poll();

            //Applying substitutions in the head.
            ImmutableFunctionalTerm substitutedHeadAtom = head.substitution.applyToFunctionalTerm(
                    head.atom);

            List<Function> atoms = new LinkedList<>();

            //Constructing the rule
            CQIE newrule = datalogFactory.getCQIE(immutabilityTools.convertToMutableFunction(substitutedHeadAtom), atoms);

            pr.appendRule(newrule);

            head.optionalChildNode.ifPresent(node -> {
                List<Function> uAtoms = getAtomFrom(query, node, heads, subQueryProjectionAtoms, false);
                newrule.getBody().addAll(uAtoms);
            });

        }
    }



    /**
     * This is the MAIN recursive method in this class!!
     * Takes a node and return the list of functions (atoms) that it represents.
     * Usually it will be a single atom, but it is different for the filter case.
     */
    private List<Function> getAtomFrom(IntermediateQuery te, QueryNode node, Queue<RuleHead> heads,
                                       Map<QueryNode, DataAtom> subQueryProjectionAtoms,
                                       boolean isNested) {

        List<Function> body = new ArrayList<>();

        /**
         * Basic Atoms
         */

        if (node instanceof ConstructionNode) {
            ConstructionNode constructionNode = (ConstructionNode) node;
            DataAtom projectionAtom = Optional.ofNullable(
                    subQueryProjectionAtoms.get(constructionNode))
                    //.map(atom -> adaptProjectionAtom(atom, constructionNode))
                    .orElseGet(() -> generateProjectionAtom(constructionNode.getVariables()));

            heads.add(new RuleHead(constructionNode.getSubstitution(), projectionAtom,te.getFirstChild(constructionNode)));
            subQueryProjectionAtoms.put(constructionNode, projectionAtom);
            Function mutAt = immutabilityTools.convertToMutableFunction(projectionAtom);
            body.add(mutAt);
            return body;

        } else if (node instanceof FilterNode) {
            ImmutableExpression filter = ((FilterNode) node).getFilterCondition();
            List<QueryNode> listnode =  te.getChildren(node);
            body.addAll(getAtomFrom(te, listnode.get(0), heads, subQueryProjectionAtoms, true));

            filter.flattenAND().stream()
                    .map(immutabilityTools::convertToMutableBooleanExpression)
                    .forEach(body::add);

            return body;


        } else if (node instanceof DataNode) {
            DataAtom atom = ((DataNode)node).getProjectionAtom();
            Function mutAt = immutabilityTools.convertToMutableFunction(atom);
            body.add(mutAt);
            return body;



            /**
             * Nested Atoms
             */
        } else  if (node instanceof InnerJoinNode) {
            return getAtomsFromJoinNode((InnerJoinNode)node, te, heads, subQueryProjectionAtoms, isNested);

        } else if (node instanceof LeftJoinNode) {
            Optional<ImmutableExpression> filter = ((LeftJoinNode)node).getOptionalFilterCondition();
            List<QueryNode> listnode =  te.getChildren(node);

            List<Function> atomsListLeft = getAtomFrom(te, listnode.get(0), heads, subQueryProjectionAtoms, true);
            List<Function> atomsListRight = getAtomFrom(te, listnode.get(1), heads, subQueryProjectionAtoms, true);

            if (filter.isPresent()){
                ImmutableExpression filter2 = filter.get();
                Expression mutFilter =  immutabilityTools.convertToMutableBooleanExpression(filter2);
                Function newLJAtom = datalogFactory.getSPARQLLeftJoin(atomsListLeft, atomsListRight, Optional.of(mutFilter));
                body.add(newLJAtom);
                return body;
            }else{
                Function newLJAtom = datalogFactory.getSPARQLLeftJoin(atomsListLeft, atomsListRight, Optional.empty());
                body.add(newLJAtom);
                return body;
            }

        } else if (node instanceof UnionNode) {

            Optional<ConstructionNode> parentNode = te.getParent(node)
                    .filter(p -> p instanceof ConstructionNode)
                    .map(p -> (ConstructionNode) p);

            DistinctVariableOnlyDataAtom freshHeadAtom;
            if(parentNode.isPresent()) {
                freshHeadAtom = generateProjectionAtom(parentNode.get().getChildVariables());
            }
            else{
                freshHeadAtom = generateProjectionAtom(((UnionNode) node).getVariables());
            }


            for (QueryNode child : te.getChildren(node)) {

                if (child instanceof ConstructionNode) {
                    ConstructionNode cn = (ConstructionNode) child;
                    Optional<QueryNode> grandChild = te.getFirstChild(cn);
                    subQueryProjectionAtoms.put(cn, freshHeadAtom);
                    heads.add(new RuleHead(cn.getSubstitution(), freshHeadAtom, grandChild));
                } else {
                    ConstructionNode cn = iqFactory.createConstructionNode(((UnionNode) node).getVariables());
                    subQueryProjectionAtoms.put(cn, freshHeadAtom);
                    heads.add(new RuleHead(cn.getSubstitution(), freshHeadAtom, Optional.ofNullable(child)));
                }


            } //end for

            Function bodyAtom = immutabilityTools.convertToMutableFunction(freshHeadAtom);
            body.add(bodyAtom);
            return body;

        } else if (node instanceof TrueNode) {

            /**
             *
             * TODO: what should we do when it is the left child of a LJ?
             *
             * Add a 0-ary atom
             */
            //DataAtom projectionAtom = generateProjectionAtom(ImmutableSet.of());
            //heads.add(new RuleHead(new ImmutableSubstitutionImpl<>(ImmutableMap.of()), projectionAtom,Optional.empty()));
            //return body;
            if (isNested) {
                body.add(atomFactory.getDistinctVariableOnlyDataAtom(
                        atomFactory.getAtomPredicate(
                                "dummy" + (++dummyPredCounter),
                                0
                        ),
                        ImmutableList.of()
                ));
            }
            // Otherwise, ignores it
            return body;

        } else {
            throw new UnsupportedOperationException("Type of node in the intermediate tree is unknown!!");
        }

    }

    private List<Function> getAtomsFromJoinNode(InnerJoinNode node, IntermediateQuery te, Queue<RuleHead> heads,
                                                Map<QueryNode, DataAtom> subQueryProjectionAtoms,
                                                boolean isNested) {
        List<Function> body = new ArrayList<>();
        Optional<ImmutableExpression> filter = node.getOptionalFilterCondition();
        List<Function> atoms = new ArrayList<>();
        List<QueryNode> listnode =  te.getChildren(node);
        for (QueryNode childnode: listnode) {
            List<Function> atomsList = getAtomFrom(te, childnode, heads, subQueryProjectionAtoms, true);
            atoms.addAll(atomsList);
        }

        if (atoms.size() <= 1) {
            throw new IllegalArgumentException("Inconsistent IQ: an InnerJoinNode must have at least two children");
        }

        if (filter.isPresent()){
            if (isNested) {
                ImmutableExpression filter2 = filter.get();
                Function mutFilter = immutabilityTools.convertToMutableBooleanExpression(filter2);
                Function newJ = getSPARQLJoin(atoms, Optional.of(mutFilter));
                body.add(newJ);
                return body;
            }
            else {
                body.addAll(atoms);
                filter.get().flattenAND().stream()
                        .map(immutabilityTools::convertToMutableBooleanExpression)
                        .forEach(body::add);
                return body;
            }
        }else{
            Function newJ = getSPARQLJoin(atoms, Optional.empty());
            body.add(newJ);
            return body;
        }
    }

    private DistinctVariableOnlyDataAtom generateProjectionAtom(ImmutableSet<Variable> projectedVariables) {
        AtomPredicate newPredicate = atomFactory.getAtomPredicate(datalogFactory.getSubqueryPredicatePrefix()+ ++subQueryCounter,
                projectedVariables.size());
        return atomFactory.getDistinctVariableOnlyDataAtom(newPredicate, ImmutableList.copyOf(projectedVariables));
    }

    private Function getSPARQLJoin(List<Function> atoms, Optional<Function> optionalCondition) {
        int atomCount = atoms.size();
        Function rightTerm;

        switch (atomCount) {
            case 0:
            case 1:
                throw new IllegalArgumentException("A join requires at least two atoms");
            case 2:
                rightTerm = atoms.get(1);
                break;
            default:
                rightTerm = getSPARQLJoin(atoms.subList(1, atomCount), Optional.empty());
                break;
        }

        return optionalCondition.isPresent()
                ? datalogFactory.getSPARQLJoin(atoms.get(0), rightTerm, optionalCondition.get())
                : datalogFactory.getSPARQLJoin(atoms.get(0), rightTerm);
    }

}