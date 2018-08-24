package it.unibz.inf.ontop.spec.mapping.transformer.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import it.unibz.inf.ontop.datalog.CQIE;
import it.unibz.inf.ontop.datalog.DatalogFactory;
import it.unibz.inf.ontop.datalog.DatalogProgram;
import it.unibz.inf.ontop.datalog.DatalogProgram2QueryConverter;
import it.unibz.inf.ontop.dbschema.DBMetadata;
import it.unibz.inf.ontop.injection.SpecificationFactory;
import it.unibz.inf.ontop.iq.IQTree;
import it.unibz.inf.ontop.iq.IntermediateQuery;
import it.unibz.inf.ontop.iq.exception.EmptyQueryException;
import it.unibz.inf.ontop.iq.node.ConstructionNode;
import it.unibz.inf.ontop.iq.optimizer.BindingLiftOptimizer;
import it.unibz.inf.ontop.iq.optimizer.JoinLikeOptimizer;
import it.unibz.inf.ontop.iq.optimizer.ProjectionShrinkingOptimizer;
import it.unibz.inf.ontop.iq.optimizer.PushUpBooleanExpressionOptimizer;
import it.unibz.inf.ontop.iq.optimizer.impl.PushUpBooleanExpressionOptimizerImpl;
import it.unibz.inf.ontop.model.atom.AtomPredicate;
import it.unibz.inf.ontop.model.term.*;
import it.unibz.inf.ontop.model.term.functionsymbol.ExpressionOperation;
import it.unibz.inf.ontop.model.term.impl.ImmutabilityTools;
import it.unibz.inf.ontop.reformulation.RuleUnfolder;
import it.unibz.inf.ontop.spec.mapping.Mapping;
import it.unibz.inf.ontop.spec.mapping.transformer.StaticRuleMappingSaturator;
import it.unibz.inf.ontop.temporal.model.*;
import it.unibz.inf.ontop.temporal.model.tree.CustomTreeTraverser;
import it.unibz.inf.ontop.utils.ImmutableCollectors;

import java.util.*;

public class StaticRuleMappingSaturatorImpl implements StaticRuleMappingSaturator {

    private final TermFactory termFactory;
    private final DatalogFactory datalogFactory;
    private final DatalogProgram2QueryConverter datalogConverter;
    private final SpecificationFactory specificationFactory;
    private final RuleUnfolder ruleUnfolder;
    private final BindingLiftOptimizer bindingLiftOptimizer;
    private final ImmutabilityTools immutabilityTools;
    private PushUpBooleanExpressionOptimizer pushUpBooleanExpressionOptimizer;
    private ProjectionShrinkingOptimizer projectionShrinkingOptimizer;
    private final JoinLikeOptimizer joinLikeOptimizer;


    @Inject
    private StaticRuleMappingSaturatorImpl(TermFactory termFactory, DatalogFactory datalogFactory,
                                           DatalogProgram2QueryConverter datalogConverter,
                                           SpecificationFactory specificationFactory,
                                           RuleUnfolder ruleUnfolder, BindingLiftOptimizer bindingLiftOptimizer, ImmutabilityTools immutabilityTools, JoinLikeOptimizer joinLikeOptimizer) {
        this.termFactory = termFactory;
        this.datalogFactory = datalogFactory;
        this.datalogConverter = datalogConverter;
        this.specificationFactory = specificationFactory;
        this.ruleUnfolder = ruleUnfolder;
        this.bindingLiftOptimizer = bindingLiftOptimizer;
        this.immutabilityTools = immutabilityTools;
        this.joinLikeOptimizer = joinLikeOptimizer;
        pushUpBooleanExpressionOptimizer = new PushUpBooleanExpressionOptimizerImpl(false, this.immutabilityTools);
        projectionShrinkingOptimizer = new ProjectionShrinkingOptimizer();
    }

    private DatalogProgram convertStaticMTLRulesToDatalogProgram(DatalogMTLProgram datalogMTLProgram){
        ImmutableList<DatalogMTLRule> staticRuleList = datalogMTLProgram.getRules().stream()
                .filter(rule -> rule.getHead() instanceof StaticExpression)
                .collect(ImmutableCollectors.toList());

        DatalogProgram datalogProgram = datalogFactory.getDatalogProgram();
        datalogProgram.appendRule(staticRuleList.stream()
                .map(rule -> datalogFactory.getCQIE(termFactory.getFunction(rule.getHead().getPredicate(), rule.getHead().getTerms()),
                        getAtomicExpressions(rule).stream()
                                .map(sae -> termFactory.getFunction(sae.getPredicate(), sae.getTerms())).collect(ImmutableCollectors.toList())))
        .collect(ImmutableCollectors.toList()));

        return datalogProgram;
    }

    @Override
    public Mapping saturate(Mapping mapping, DBMetadata dbMetadata, DatalogMTLProgram datalogMTLProgram) {

        DatalogProgram datalogProgram = convertStaticMTLRulesToDatalogProgram(datalogMTLProgram);
        Queue<CQIE> queue = new LinkedList<>();
        queue.addAll(datalogProgram.getRules());
        Map<AtomPredicate, IntermediateQuery> mappingMap = new HashMap<>();
        mapping.getPredicates().forEach(atomPredicate -> mappingMap.put(atomPredicate, mapping.getDefinition(atomPredicate).get()));

        while(!queue.isEmpty()) {
            CQIE rule = queue.poll();
                if (areAllMappingsExist(ImmutableMap.copyOf(mappingMap), ImmutableList.copyOf(rule.getBody()))) {
                    try {
                        DatalogProgram dProg = datalogFactory.getDatalogProgram();
                        dProg.appendRule(rule);
                        IntermediateQuery intermediateQuery = datalogConverter.convertDatalogProgram(
                                dbMetadata, dProg, ImmutableList.of(), mapping.getExecutorRegistry());
                        System.out.println(intermediateQuery.toString());
                        intermediateQuery = ruleUnfolder.unfold(intermediateQuery, ImmutableMap.copyOf(mappingMap));
                        System.out.println(intermediateQuery.toString());
                        intermediateQuery = bindingLiftOptimizer.optimize(intermediateQuery);
                        intermediateQuery = pushUpBooleanExpressionOptimizer.optimize(intermediateQuery);
                        intermediateQuery = projectionShrinkingOptimizer.optimize(intermediateQuery);
                        intermediateQuery = joinLikeOptimizer.optimize(intermediateQuery);
                        mappingMap.put(intermediateQuery.getProjectionAtom().getPredicate(), intermediateQuery);
                        System.out.println(intermediateQuery.toString());

                    } catch (EmptyQueryException e) {
                        e.printStackTrace();
                    }
                }else {
                    if (!queue.isEmpty()){
                        //TODO:Override compareTo for rule.getHead()
                        if (queue.stream().anyMatch(qe -> qe.getHead().equals(rule.getHead())))
                            queue.add(rule);
                    }
                }
        }


        return specificationFactory.createMapping(mapping.getMetadata(), ImmutableMap.copyOf(mappingMap), mapping.getExecutorRegistry());
    }

    private boolean isContainedInTheTree(IQTree newTree, IQTree iqTree){

        boolean flag = false;
        if (!newTree.equals(iqTree)){
            for (IQTree subTree : iqTree.getChildren()){
                flag = flag || isContainedInTheTree(newTree, subTree);
            }
        }else return true;

        return flag;
    }

    private ImmutableList<StaticAtomicExpression> getAtomicExpressions(DatalogMTLRule rule) {

        if (CustomTreeTraverser.using(DatalogMTLExpression::getChildNodes).postOrderTraversal(rule.getBody())
                .allMatch(dMTLExp -> dMTLExp instanceof StaticExpression)) {
            return CustomTreeTraverser.using(DatalogMTLExpression::getChildNodes).postOrderTraversal(rule.getBody())
                    .filter(dMTLexp -> dMTLexp instanceof StaticAtomicExpression)
                    .transform(dMTLexp -> (StaticAtomicExpression) dMTLexp)
                    .toList();
        }
        return null;
    }

    private boolean areAllMappingsExist(ImmutableMap<AtomPredicate, IntermediateQuery> mappingMap, ImmutableList<Function> bodyList){

        for (Function f : bodyList){
            if (!mappingMap.containsKey(f.getFunctionSymbol()))
                return false;
        }

        return true;
    }

//    private boolean areAllMappingsExist(ImmutableMap<AtomPredicate, IntermediateQuery> mappingMap, ImmutableList<StaticAtomicExpression> atomicExpressionsList){
//
//        if (atomicExpressionsList.stream().filter(ae-> !(ae instanceof ComparisonExpression))
//                .allMatch(ae -> mappingMap.containsKey(ae.getPredicate())))
//            return true;
//
//        return false;
//    }

    private ImmutableMap<Variable, Term> retrieveMapForVariablesOccuringInTheHead(DatalogMTLRule rule, Mapping mapping){
        Map<Variable, Term> varMap = new HashMap<>();
        ImmutableList<StaticAtomicExpression> atomicExpressionsList = getAtomicExpressions(rule);
        for(Term term : rule.getHead().getImmutableTerms()){
            if(term instanceof Variable){
                for(AtomicExpression ae :atomicExpressionsList){
                    int varIdxInBody = 0;
                    for(Term t : ae.getImmutableTerms()){
                        if (t instanceof Variable) {
                            //TODO:Override compareTo for Variable
                            if(((Variable) t).equals(term)){
                                if(mapping.getPredicates().contains(ae.getPredicate())){
                                    int varIdxInSub = 0;
                                    Optional<IntermediateQuery> iq = mapping.getDefinition(ae.getPredicate());
                                    for(ImmutableTerm subTerm : ((ConstructionNode)iq.get().getRootNode()).getSubstitution().getImmutableMap().values()){
                                        if(varIdxInBody == varIdxInSub){
                                            if(varMap.containsKey((Variable) t)){
                                                if (!varMap.get(t).equals(subTerm)){
                                                    //TODO:throw exception
                                                }
                                            }
                                            else {
                                                varMap.put((Variable) t, subTerm);
                                            }
                                        }
                                        varIdxInSub++;
                                    }
                                } else{
                                    //TODO:throw exception;
                                }
                            }
                        }
                        varIdxInBody++;
                    }
                }
            }
        }
        return ImmutableMap.copyOf(varMap);
    }

    private ImmutableExpression comparisonExpToFilterCondition(ComparisonExpression comparisonExpression){
        String operator = comparisonExpression.getPredicate().getName();
        ExpressionOperation expressionOperation = null;
        if(operator == ExpressionOperation.LT.getName())
            expressionOperation = ExpressionOperation.LT;
        else if(operator == ExpressionOperation.GT.getName())
            expressionOperation = ExpressionOperation.GT;
        else if(operator == ExpressionOperation.EQ.getName())
            expressionOperation = ExpressionOperation.EQ;
        else if(operator == ExpressionOperation.NEQ.getName())
            expressionOperation = ExpressionOperation.NEQ;

        return termFactory.getImmutableExpression(expressionOperation,comparisonExpression.getLeftOperand(), comparisonExpression.getRightOperand());
    }

}