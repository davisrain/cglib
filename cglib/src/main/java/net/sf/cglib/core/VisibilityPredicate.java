/*
 * Copyright 2003,2004 The Apache Software Foundation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.cglib.core;

import java.lang.reflect.*;
import org.objectweb.asm.Type;

public class VisibilityPredicate implements Predicate {
    private boolean protectedOk;
    private String pkg;
    private boolean samePackageOk;

    public VisibilityPredicate(Class source, boolean protectedOk) {
        // protected修饰的是否可见
        this.protectedOk = protectedOk;
        // same package is not ok for the bootstrap loaded classes.  In all other cases we are 
        // generating classes in the same classloader
        // 同一个包下的是否可见
        this.samePackageOk = source.getClassLoader() != null;
        // 获取包名
        pkg = TypeUtils.getPackageName(Type.getType(source));
    }

    public boolean evaluate(Object arg) {
        Member member = (Member)arg;
        int mod = member.getModifiers();
        // 判断访问修饰符，如果是private的，返回false，表示不可见
        if (Modifier.isPrivate(mod)) {
            return false;
        }
        // 如果是public的，可见
        else if (Modifier.isPublic(mod)) {
            return true;
        }
        // 如果是protected的，并且protectedOk是true，表示可见
        else if (Modifier.isProtected(mod) && protectedOk) {
            // protected is fine if 'protectedOk' is true (for subclasses)
            return true;
        }
        // 如果是package的，并且samePackageOk是true，并且member的声明类的包名和pkg一致，表示可见
        else {
            // protected/package private if the member is in the same package as the source class 
            // and we are generating into the same classloader.
            return samePackageOk 
                && pkg.equals(TypeUtils.getPackageName(Type.getType(member.getDeclaringClass())));
        }
    }
}

