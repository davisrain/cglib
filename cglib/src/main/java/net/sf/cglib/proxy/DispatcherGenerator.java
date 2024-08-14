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
package net.sf.cglib.proxy;

import java.util.*;
import net.sf.cglib.core.*;
import org.objectweb.asm.Type;

class DispatcherGenerator implements CallbackGenerator {
    public static final DispatcherGenerator INSTANCE =
      new DispatcherGenerator(false);
    public static final DispatcherGenerator PROXY_REF_INSTANCE =
      new DispatcherGenerator(true);

    private static final Type DISPATCHER =
      TypeUtils.parseType("net.sf.cglib.proxy.Dispatcher");
    private static final Type PROXY_REF_DISPATCHER =
      TypeUtils.parseType("net.sf.cglib.proxy.ProxyRefDispatcher");
    private static final Signature LOAD_OBJECT =
      TypeUtils.parseSignature("Object loadObject()");
    private static final Signature PROXY_REF_LOAD_OBJECT =
      TypeUtils.parseSignature("Object loadObject(Object)");

    private boolean proxyRef;

    private DispatcherGenerator(boolean proxyRef) {
        this.proxyRef = proxyRef;
    }

    public void generate(ClassEmitter ce, Context context, List methods) {
        // 遍历MethodInfo集合
        for (Iterator it = methods.iterator(); it.hasNext();) {
            MethodInfo method = (MethodInfo)it.next();
            // 如果method不是被protected修饰的，才执行以下逻辑，
            // 换言之，Dispatcher这种类型的Callback只适用于非protected方法
            if (!TypeUtils.isProtected(method.getModifiers())) {
                // 根据MethodInfo在类中声明一个方法
                CodeEmitter e = context.beginMethod(ce, method);
                // 将Method对应的Callback加载到操作数栈顶
                context.emitCallback(e, context.getIndex(method));
                // 如果proxyRef为true
                if (proxyRef) {
                    // 将this引用加载到栈顶作为方法参数
                    e.load_this();
                    // 调用ProxyRefDispatcher的loadObject方法，将this传入
                    e.invoke_interface(PROXY_REF_DISPATCHER, PROXY_REF_LOAD_OBJECT);
                }
                // 如果proxyRef为false
                else {
                    // 直接调用Dispatcher的loadObject方法
                    e.invoke_interface(DISPATCHER, LOAD_OBJECT);
                }
                // 上一步会获取实际要执行对应方法的对象，即分派对象
                // 检查分派对象是否可以转型为方法的声明类
                e.checkcast(method.getClassInfo().getType());
                // 将方法参数加载到操作数栈顶
                e.load_args();
                // 然后根据MethodInfo调用对应的方法
                e.invoke(method);
                // 插入return相关字节码，返回
                e.return_value();
                e.end_method();
            }
        }
        /*

         */
    }

    public void generateStatic(CodeEmitter e, Context context, List methods) { }
}
