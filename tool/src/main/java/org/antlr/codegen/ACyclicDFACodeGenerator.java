/*
 * [The "BSD license"]
 *  Copyright (c) 2010 Terence Parr
 *  All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions
 *  are met:
 *  1. Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *      derived from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 *  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 *  OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 *  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 *  INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 *  NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 *  DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 *  THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 *  THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.antlr.codegen;

import java.util.ArrayList;
import org.antlr.analysis.*;
import org.antlr.misc.Utils;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;

import java.util.List;

public class ACyclicDFACodeGenerator {
	protected CodeGenerator parentGenerator;

	public ACyclicDFACodeGenerator(CodeGenerator parent) {
		this.parentGenerator = parent;
	}

	public ST genFixedLookaheadDecision(STGroup templates,
													DFA dfa)
	{
		return walkFixedDFAGeneratingStateMachine(templates, dfa, dfa.startState, 1);
	}

	protected ST walkFixedDFAGeneratingStateMachine(
			STGroup templates,
			DFA dfa,
			DFAState s,
			int k)
	{
		//System.out.println("walk "+s.stateNumber+" in dfa for decision "+dfa.decisionNumber);
		if ( s.isAcceptState() ) {
			ST dfaST = templates.getInstanceOf("dfaAcceptState");
			dfaST.add("alt", Utils.integer(s.getUniquelyPredictedAlt()));
			return dfaST;
		}

		// the default templates for generating a state and its edges
		// can be an if-then-else structure or a switch
		String dfaStateName = "dfaState";
		String dfaLoopbackStateName = "dfaLoopbackState";
		String dfaOptionalBlockStateName = "dfaOptionalBlockState";
		String dfaEdgeName = "dfaEdge";
		if ( parentGenerator.canGenerateSwitch(s) ) {
			dfaStateName = "dfaStateSwitch";
			dfaLoopbackStateName = "dfaLoopbackStateSwitch";
			dfaOptionalBlockStateName = "dfaOptionalBlockStateSwitch";
			dfaEdgeName = "dfaEdgeSwitch";
		}

		ST dfaST = templates.getInstanceOf(dfaStateName);
		if ( dfa.getNFADecisionStartState().decisionStateType==NFAState.LOOPBACK ) {
			dfaST = templates.getInstanceOf(dfaLoopbackStateName);
		}
		else if ( dfa.getNFADecisionStartState().decisionStateType==NFAState.OPTIONAL_BLOCK_START ) {
			dfaST = templates.getInstanceOf(dfaOptionalBlockStateName);
		}
		dfaST.add("k", Utils.integer(k));
		dfaST.add("stateNumber", Utils.integer(s.stateNumber));
		dfaST.add("semPredState",
						   Boolean.valueOf(s.isResolvedWithPredicates()));
		/*
		String description = dfa.getNFADecisionStartState().getDescription();
		description = parentGenerator.target.getTargetStringLiteralFromString(description);
		//System.out.println("DFA: "+description+" associated with AST "+dfa.getNFADecisionStartState());
		if ( description!=null ) {
			dfaST.add("description", description);
		}
		*/
		int EOTPredicts = NFA.INVALID_ALT_NUMBER;
		DFAState EOTTarget = null;
		//System.out.println("DFA state "+s.stateNumber);
		for (int i = 0; i < s.getNumberOfTransitions(); i++) {
			Transition edge = (Transition) s.transition(i);
			//System.out.println("edge "+s.stateNumber+"-"+edge.label.toString()+"->"+edge.target.stateNumber);
			if ( edge.label.getAtom()==Label.EOT ) {
				// don't generate a real edge for EOT; track alt EOT predicts
				// generate that prediction in the else clause as default case
				EOTTarget = (DFAState)edge.target;
				EOTPredicts = EOTTarget.getUniquelyPredictedAlt();
				/*
				System.out.println("DFA s"+s.stateNumber+" EOT goes to s"+
								   edge.target.stateNumber+" predicates alt "+
								   EOTPredicts);
				*/
				continue;
			}
			ST edgeST = templates.getInstanceOf(dfaEdgeName);
			// If the template wants all the label values delineated, do that
			if ( edgeST.impl.formalArguments.get("labels")!=null ) {
				List<Integer> labels = edge.label.getSet().toList();
				List<String> targetLabels = new ArrayList<String>(labels.size());
				for (int j = 0; j < labels.size(); j++) {
					Integer vI = (Integer) labels.get(j);
					String label =
						parentGenerator.getTokenTypeAsTargetLabel(vI.intValue());
					targetLabels.add(label); // rewrite List element to be name
				}
				edgeST.add("labels", targetLabels);
			}
			else { // else create an expression to evaluate (the general case)
				edgeST.add("labelExpr",
									parentGenerator.genLabelExpr(templates,edge,k));
			}

			// stick in any gated predicates for any edge if not already a pred
			if ( !edge.label.isSemanticPredicate() ) {
				DFAState target = (DFAState)edge.target;
				SemanticContext preds =
					target.getGatedPredicatesInNFAConfigurations();
				if ( preds!=null ) {
					//System.out.println("preds="+target.getGatedPredicatesInNFAConfigurations());
					ST predST = preds.genExpr(parentGenerator,
														  parentGenerator.getTemplates(),
														  dfa);
					edgeST.add("predicates", predST);
				}
			}

			ST targetST =
				walkFixedDFAGeneratingStateMachine(templates,
												   dfa,
												   (DFAState)edge.target,
												   k+1);
			edgeST.add("targetState", targetST);
			dfaST.add("edges", edgeST);
			/*
			System.out.println("back to DFA "+
							   dfa.decisionNumber+"."+s.stateNumber);
							   */
		}

		// HANDLE EOT EDGE
		if ( EOTPredicts!=NFA.INVALID_ALT_NUMBER ) {
			// EOT unique predicts an alt
			dfaST.add("eotPredictsAlt", Utils.integer(EOTPredicts));
		}
		else if ( EOTTarget!=null && EOTTarget.getNumberOfTransitions()>0 ) {
			// EOT state has transitions so must split on predicates.
			// Generate predicate else-if clauses and then generate
			// NoViableAlt exception as else clause.
			// Note: these predicates emanate from the EOT target state
			// rather than the current DFAState s so the error message
			// might be slightly misleading if you are looking at the
			// state number.  Predicates emanating from EOT targets are
			// hoisted up to the state that has the EOT edge.
			for (int i = 0; i < EOTTarget.getNumberOfTransitions(); i++) {
				Transition predEdge = (Transition)EOTTarget.transition(i);
				ST edgeST = templates.getInstanceOf(dfaEdgeName);
				edgeST.add("labelExpr",
									parentGenerator.genSemanticPredicateExpr(templates,predEdge));
				// the target must be an accept state
				//System.out.println("EOT edge");
				ST targetST =
					walkFixedDFAGeneratingStateMachine(templates,
													   dfa,
													   (DFAState)predEdge.target,
													   k+1);
				edgeST.add("targetState", targetST);
				dfaST.add("edges", edgeST);
			}
		}
		return dfaST;
	}
}

