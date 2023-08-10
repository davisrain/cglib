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

import net.sf.cglib.core.*;
import java.util.*;
import org.objectweb.asm.Type;

class InvocationHandlerGenerator
implements CallbackGenerator
{
    public static final InvocationHandlerGenerator INSTANCE = new InvocationHandlerGenerator();

    private static final Type INVOCATION_HANDLER =
      TypeUtils.parseType("net.sf.cglib.proxy.InvocationHandler");
    private static final Type UNDECLARED_THROWABLE_EXCEPTION =
      TypeUtils.parseType("net.sf.cglib.proxy.UndeclaredThrowableException");
    private static final Type METHOD =
      TypeUtils.parseType("java.lang.reflect.Method");
    private static final Signature INVOKE =
      TypeUtils.parseSignature("Object invoke(Object, java.lang.reflect.Method, Object[])");

    public void generate(ClassEmitter ce, Context context, List methods) {
        // 遍历持有的MethodInfo集合
        for (Iterator it = methods.iterator(); it.hasNext();) {
            MethodInfo method = (MethodInfo)it.next();
            // 获取到实现方法的签名
            Signature impl = context.getImplSignature(method);
            // 在class中声明private static final Method CGLIB$ + {originalMethodName} + $ + {index};字段
            ce.declare_field(Constants.PRIVATE_FINAL_STATIC, impl.getName(), METHOD, null);

            // 根据methodInfo声明一个方法
            CodeEmitter e = context.beginMethod(ce, method);
            // 创建一个block代码块
            Block handler = e.begin_block();
            // 将method对应的Callback加载到操作数栈顶
            context.emitCallback(e, context.getIndex(method));
            // 加载this引用到栈顶
            e.load_this();
            // 获取CGLIB$ + {originalMethodName} + $ + {index}字段所指向的Method对象到栈顶
            e.getfield(impl.getName());
            // 创建参数数组
            e.create_arg_array();
            // 然后调用InvocationHandler的invoke方法
            e.invoke_interface(INVOCATION_HANDLER, INVOKE);
            // 将返回值拆箱
            e.unbox(method.getSignature().getReturnType());
            // 然后返回
            e.return_value();
            // 调用block的end方法，在这个位置添加一个end标签，表示代码块结束
            handler.end();
            // 如果出现异常，将其包装成UndeclaredThrowableException抛出
            EmitUtils.wrap_undeclared_throwable(e, handler, method.getExceptionTypes(), UNDECLARED_THROWABLE_EXCEPTION);
            e.end_method();
        }
    }

    public void generateStatic(CodeEmitter e, Context context, List methods) {
        // 遍历持有的methodInfo集合
        for (Iterator it = methods.iterator(); it.hasNext();) {
            MethodInfo method = (MethodInfo)it.next();
            // 根据methodInfo加载对应的Method反射对象到操作数栈顶
            EmitUtils.load_method(e, method);
            // 然后将栈顶的元素通过putstatic 放入CGLIB$ + {originalMethodName} + $ + {index}字段中
            e.putfield(context.getImplSignature(method).getName());
        }
    }
}
