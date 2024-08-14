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
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;

class LazyLoaderGenerator implements CallbackGenerator {
    public static final LazyLoaderGenerator INSTANCE = new LazyLoaderGenerator();

    private static final Signature LOAD_OBJECT = 
      TypeUtils.parseSignature("Object loadObject()");
    private static final Type LAZY_LOADER =
      TypeUtils.parseType("net.sf.cglib.proxy.LazyLoader");

    public void generate(ClassEmitter ce, Context context, List methods) {
        Set indexes = new HashSet();
        for (Iterator it = methods.iterator(); it.hasNext();) {
            MethodInfo method = (MethodInfo)it.next();
            // 如果是protected修饰的方法，忽略
            if (TypeUtils.isProtected(method.getModifiers())) {
                // ignore protected methods
            } else {
                // 获取method对应的callback的index
                int index = context.getIndex(method);
                indexes.add(new Integer(index));
                // 在类中声明一个相同签名的方法
                CodeEmitter e = context.beginMethod(ce, method);
                // 将代理对象自身加载到栈顶
                e.load_this();
                // 复制栈顶元素
                e.dup();
                // 通过invokevirtual调用自身签名为CGLIB$LOADER_PRIVATE_{index} ()Ljava/lang/Object;的方法
                e.invoke_virtual_this(loadMethod(index));
                // 将上一步方法的返回结果强转为 方法的声明类
                e.checkcast(method.getClassInfo().getType());
                // 加载方法传入的参数到栈顶
                e.load_args();
                // 使用invoke相关字节码调用方法
                e.invoke(method);
                e.return_value();
                e.end_method();
            }
        }

        // 遍历所有LazyLoader类型的callback的index集合
        for (Iterator it = indexes.iterator(); it.hasNext();) {
            int index = ((Integer)it.next()).intValue();

            // 在代理类中声明private Object CGLIB$LAZY_LOADER_{index}的属性
            String delegate = "CGLIB$LAZY_LOADER_" + index;
            ce.declare_field(Constants.ACC_PRIVATE, delegate, Constants.TYPE_OBJECT, null);

            // 然后声明private synchronized final Object CGLIB$LOADER_PRIVATE_{index}的方法
            CodeEmitter e = ce.begin_method(Constants.ACC_PRIVATE |
                                            Constants.ACC_SYNCHRONIZED |
                                            Constants.ACC_FINAL,
                                            loadMethod(index),
                                            null);
            // 加载自身这个代理对象到栈顶
            e.load_this();
            // 获取CGLIB$LAZY_LOADER_{index}属性
            e.getfield(delegate);
            // 复制栈顶元素
            e.dup();
            Label end = e.make_label();
            // 如果栈顶元素不为null的话，直接跳到end标签
            e.ifnonnull(end);
            // 如果为null，弹出栈顶为null的引用
            e.pop();
            // 加载this到栈顶
            e.load_this();
            // 加载到对应index的callback对象到栈顶
            context.emitCallback(e, index);
            // 调用LazyLoader这个类型的callback的loadObject方法
            e.invoke_interface(LAZY_LOADER, LOAD_OBJECT);
            // 复制栈顶元素到栈顶第二个元素后面，复制前：this lazyLoader
            // 复制后 lazyLoader this lazyLoader
            e.dup_x1();
            // 将lazyLoader放入this的CGLIB$LAZY_LOADER_{index}属性
            e.putfield(delegate);
            e.mark(end);
            // 然后返回lazyLoader
            e.return_value();
            e.end_method();
            
        }

        // 上述逻辑在代理类中生成的代码就是：
        /*
            private Object CGLIB$LAZY_LOADER_{index};

            private synchronized final Object CGLIB$LOADER_PRIVATE_{index} () {
                if (this.CGLIB$LAZY_LOADER_{index} == null) {
                    this.CGLIB$LAZY_LOADER_{index} = this.CGLIB$CALLBACK_{index}.loadObject();
                }
                return this.CGLIB$LAZY_LOADER_{index};
            }

            被代理的方法的逻辑就是：
            return ((methodDeclaringClass) CGLIB$LOADER_PRIVATE_{index}()).{methodName}({methodArgs});

            该callback和Dispatcher类型的callback的区别就是，Dispatcher不存在懒加载，生成字节码的时候就已经调用了Dispatcher.loadObject()，
            然后分派给了返回的对象来执行，而LazyLoader是在第一次执行代理方法的时候才调用LazyLoader的loadObject方法。
         */
    }

    private Signature loadMethod(int index) {
        return new Signature("CGLIB$LOAD_PRIVATE_" + index,
                             Constants.TYPE_OBJECT,
                             Constants.TYPES_EMPTY);
    }

    public void generateStatic(CodeEmitter e, Context context, List methods) { }
}
