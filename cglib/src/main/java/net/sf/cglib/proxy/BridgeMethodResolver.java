/*
 * Copyright 2011 The Apache Software Foundation
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

package net.sf.cglib.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.sf.cglib.core.Constants;
import net.sf.cglib.core.Signature;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Uses bytecode reflection to figure out the targets of all bridge methods that use invokespecial
 * and invokeinterface, so that we can later rewrite them to use invokevirtual.
 *
 * <p>For interface bridges, using invokesuper will fail since the method being bridged to is in a
 * superinterface, not a superclass. Starting in Java 8, javac emits default bridge methods in
 * interfaces, which use invokeinterface to bridge to the target method.
 *
 * @author sberlin@gmail.com (Sam Berlin)
 */
class BridgeMethodResolver {

    private final Map/* <Class, Set<Signature> */declToBridge;
    private final ClassLoader classLoader;

    public BridgeMethodResolver(Map declToBridge, ClassLoader classLoader) {
        this.declToBridge = declToBridge;
        this.classLoader = classLoader;
    }

    /**
     * Finds all bridge methods that are being called with invokespecial &
     * returns them.
     */
    public Map/*<Signature, Signature>*/resolveAll() {
        Map resolved = new HashMap();
        // 遍历持有的 class类型 映射 class中所包含的桥接方法签名set 的map
        for (Iterator entryIter = declToBridge.entrySet().iterator(); entryIter.hasNext(); ) {
            Map.Entry entry = (Map.Entry) entryIter.next();
            // 获取对应的class对象
            Class owner = (Class) entry.getKey();
            // 获取class中包含的桥接方法的set
            Set bridges = (Set) entry.getValue();
            try {
                // 使用classloader加载类的二进制
                InputStream is = classLoader.getResourceAsStream(owner.getName().replace('.', '/') + ".class");
                if (is == null) {
                    return resolved;
                }
                try {
                    // 包装成一个ClassReader，然后使用BridgedFinder这个ClassVisitor来访问它。
                    // 将bridges和resolved这个map传入visitor中
                    new ClassReader(is)
                            .accept(new BridgedFinder(bridges, resolved),
                                    ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                } finally {
                    is.close();
                }
            } catch (IOException ignored) {}
        }
        return resolved;
    }

    private static class BridgedFinder extends ClassVisitor {
        private Map/*<Signature, Signature>*/ resolved;
        private Set/*<Signature>*/ eligibleMethods;
        
        private Signature currentMethod = null;

        BridgedFinder(Set eligibleMethods, Map resolved) {
            super(Constants.ASM_API);
            this.resolved = resolved;
            this.eligibleMethods = eligibleMethods;
        }

        public void visit(int version, int access, String name,
                String signature, String superName, String[] interfaces) {
        }

        public MethodVisitor visitMethod(int access, String name, String desc,
                String signature, String[] exceptions) {
            // 当其调用visitMethod的时候
            // 根据方法名和描述符创建一个方法签名
            Signature sig = new Signature(name, desc);
            // 然后尝试从桥接方法的set中删除这个签名，如果删除成功，说明当前方法是桥接方法
            if (eligibleMethods.remove(sig)) {
                // 将签名赋值给currentMethod
                currentMethod = sig;
                // 创建一个MethodVisitor的匿名类返回
                return new MethodVisitor(Constants.ASM_API) {
                    public void visitMethodInsn(
                            int opcode, String owner, String name, String desc, boolean itf) {
                        // 如果字节码是invokespecial 或者 itf为true且字节码是invokeinterface
                        // 并且currentMethod不为null
                        if ((opcode == Opcodes.INVOKESPECIAL
                                        || (itf && opcode == Opcodes.INVOKEINTERFACE))
                                && currentMethod != null) {
                            // 将要调用的方法名和描述符组成一个方法签名
                            Signature target = new Signature(name, desc);
                            // If the target signature is the same as the current,
                            // we shouldn't change our bridge becaues invokespecial
                            // is the only way to make progress (otherwise we'll
                            // get infinite recursion).  This would typically
                            // only happen when a bridge method is created to widen
                            // the visibility of a superclass' method.
                            // 如果要调用的目标方法的签名和当前方法的签名不一致，将签名放入到resolved这个map中。
                            // 签名一致的情况只会发生在重写一个桥接方法用于扩展它原方法的可见性的时候
                            if (!target.equals(currentMethod)) {
                                resolved.put(currentMethod, target);
                            }
                            // 并且将当前的方法签名置为null
                            currentMethod = null;
                        }
                    }
                };
            } else {
                return null;
            }
        }
    }

}
