/*
// $Id$
// This software is subject to the terms of the Common Public License
// Agreement, available at the following URL:
// http://www.opensource.org/licenses/cpl.html.
// Copyright (C) 2006-2006 Julian Hyde
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.olap.fun;

import mondrian.olap.FunDef;
import mondrian.olap.Evaluator;
import mondrian.olap.Dimension;
import mondrian.calc.Calc;
import mondrian.calc.ExpCompiler;
import mondrian.calc.ListCalc;
import mondrian.calc.impl.ValueCalc;
import mondrian.calc.impl.AbstractCalc;
import mondrian.mdx.ResolvedFunCall;

import java.util.List;

/**
 * Definition of the <code>Var</code> MDX builtin function
 * (and its synonym <code>Variance</code>).
 *
 * @author jhyde
 * @version $Id$
 * @since Mar 23, 2006
 */
class VarFunDef extends AbstractAggregateFunDef {
    static final Resolver VarResolver = new ReflectiveMultiResolver(
            "Var",
            "Var(<Set>[, <Numeric Expression>])",
            "Returns the variance of a numeric expression evaluated over a set (unbiased).",
            new String[]{"fnx", "fnxn"},
            VarFunDef.class);

    static final Resolver VarianceResolver = new ReflectiveMultiResolver(
            "Variance", "Variance(<Set>[, <Numeric Expression>])",
            "Alias for Var.",
            new String[]{"fnx", "fnxn"},
            VarFunDef.class);

    public VarFunDef(FunDef dummyFunDef) {
        super(dummyFunDef);
    }

    public Calc compileCall(ResolvedFunCall call, ExpCompiler compiler) {
        final ListCalc listCalc =
                compiler.compileList(call.getArg(0));
        final Calc calc = call.getArgCount() > 1 ?
                compiler.compileScalar(call.getArg(1), true) :
                new ValueCalc(call);
        return new AbstractCalc(call) {
            public Object evaluate(Evaluator evaluator) {
                List memberList = listCalc.evaluateList(evaluator);
                return var(evaluator.push(), memberList, calc, false);
            }

            public Calc[] getCalcs() {
                return new Calc[] {listCalc, calc};
            }

            public boolean dependsOn(Dimension dimension) {
                return anyDependsButFirst(getCalcs(), dimension);
            }
        };
    }
}

// End VarFunDef.java