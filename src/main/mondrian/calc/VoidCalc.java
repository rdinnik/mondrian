/*
// $Id$
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 2006-2007 Julian Hyde
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.calc;

import mondrian.olap.Evaluator;

/**
 * Expression which has a void result.
 *
 * <p>Since it doesn't return anything, any useful implementation of this
 * class will do its work by causing side-effects.
 *
 * @author jhyde
 * @version $Id$
 * @since Sep 29, 2005
 */
public interface VoidCalc extends Calc {
    void evaluateVoid(Evaluator evaluator);
}

// End VoidCalc.java
