/*
 * Copyright (c) 2015, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */

package org.truffleruby.core.cast;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import org.truffleruby.Layouts;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.dispatch.CallDispatchHeadNode;

@NodeChild(value = "child", type = RubyNode.class)
public abstract class ToStrNode extends RubyNode {

    @Child private CallDispatchHeadNode toStrNode;

    public abstract DynamicObject executeToStr(VirtualFrame frame, Object object);

    public static ToStrNode create() {
        return ToStrNodeGen.create(null);
    }

    @Specialization(guards = "isRubyString(string)")
    public DynamicObject coerceRubyString(DynamicObject string) {
        return string;
    }

    @Specialization(guards = "!isRubyString(object)")
    public DynamicObject coerceObject(VirtualFrame frame, Object object,
            @Cached("create()") BranchProfile errorProfile) {
        final Object coerced;
        try {
            coerced = getToStrNode().call(frame, object, "to_str");
        } catch (RaiseException e) {
            errorProfile.enter();
            if (Layouts.BASIC_OBJECT.getLogicalClass(e.getException()) == coreLibrary().getNoMethodErrorClass()) {
                throw new RaiseException(coreExceptions().typeErrorNoImplicitConversion(object, "String", this));
            } else {
                throw e;
            }
        }

        if (RubyGuards.isRubyString(coerced)) {
            return (DynamicObject) coerced;
        } else {
            errorProfile.enter();
            throw new RaiseException(coreExceptions().typeErrorBadCoercion(object, "String", "to_str", coerced, this));
        }
    }

    private CallDispatchHeadNode getToStrNode() {
        if (toStrNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            toStrNode = insert(CallDispatchHeadNode.create());
        }
        return toStrNode;
    }

}
